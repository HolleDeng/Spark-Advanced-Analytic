/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.datascience

import java.io.{FileOutputStream, PrintStream}
import java.util.{Properties, HashMap}

import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.linalg.{Vectors, Vector}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.LongWritable
import org.apache.hadoop.io.Text

import scala.collection.JavaConversions._
import scala.collection.Map
import scala.collection.mutable.ArrayBuffer

import edu.stanford.nlp.pipeline.{Annotation, StanfordCoreNLP}
import edu.stanford.nlp.ling.CoreAnnotations.{TokensAnnotation, SentencesAnnotation, LemmaAnnotation}

import edu.umd.cloud9.collection.wikipedia.language.EnglishWikipediaPage
import edu.umd.cloud9.collection.wikipedia.WikipediaPage

case class Page(title: String, contents: String)

object ParseWikipedia {

  private def addTermCount(map: HashMap[String, Int], term: String, count: Int) {
    map.put(term, (map.getOrElse(term, 0) + count))
  }

  /**
   * Returns a term-document matrix where each element is the TF-IDF of the row's document and
   * the column's term.
   */
  def termDocumentMatrix(docs: RDD[Seq[String]], stopWords: Set[String], numTerms: Int,
      sc: SparkContext): (RDD[Vector], Map[Int, String]) = {
    val docTermFreqs = docs.map(terms => {
      val termFreqsInDoc = terms.foldLeft(new HashMap[String, Int]()) {
        (map, term) => {
          addTermCount(map, term, 1)
          map
        }
      }
      termFreqsInDoc
    })
    docTermFreqs.cache()
    val docFreqs = documentFrequencies(docTermFreqs, numTerms)
    println("Number of terms: " + docFreqs.size)
    saveDocFreqs("docfreqs.tsv", docFreqs)

    val numDocs = docTermFreqs.count().toInt

    val idfs = inverseDocumentFrequencies(docFreqs, numDocs)

    // maps terms to their indices in the vector
    val termIndices = new HashMap[String, Int]()
    for ((term, index) <- idfs.keySet().zipWithIndex) {
      termIndices += (term -> index)
    }

    val bIdfs = sc.broadcast(idfs).value
    val bTermIndices = sc.broadcast(termIndices).value

    val vecs = docTermFreqs.map(docTermFreqs => {
      val docTotalTerms = docTermFreqs.values().sum
      // TODO: this could be more performant?
      val termScores = docTermFreqs.filter{
        case (term, freq) => bTermIndices.containsKey(term)
      }.map{
        case (term, freq) => (bTermIndices(term), bIdfs(term) * docTermFreqs(term) / docTotalTerms)
      }.toSeq
      Vectors.sparse(bTermIndices.size, termScores)
    })
    (vecs, termIndices.map(_.swap))
  }
/*
  def documentFrequencies(docTermFreqs: RDD[HashMap[String, Int]]): HashMap[String, Int] = {
    docTermFreqs.aggregate(new HashMap[String, Int]())(
      (dfs, tfs) => {
        tfs.keySet.foreach{ term =>
          addTermCount(dfs, term, 1)
        }
        dfs
      },
      (dfs1, dfs2) => {
        for ((term, count) <- dfs2) {
          addTermCount(dfs1, term, count)
        }
        dfs1
      }
    )
  }
  */

  def documentFrequencies(docTermFreqs: RDD[HashMap[String, Int]], numTerms: Int)
      : Array[(String, Int)] = {
    val docFreqs = docTermFreqs.flatMap(_.keySet).map((_, 1)).reduceByKey(_ + _, 15)
    val ordering = new Ordering[(String, Int)] {
      def compare(x: (String, Int), y: (String, Int)): Int = x._2 - y._2
    }
    docFreqs.top(numTerms)(ordering)
  }

  def trimLeastFrequent(freqs: Map[String, Int], numToKeep: Int): Map[String, Int] = {
    freqs.toArray.sortBy(_._2).take(math.min(numToKeep, freqs.size)).toMap
  }

  def inverseDocumentFrequencies(docFreqs: Array[(String, Int)], numDocs: Int):
      HashMap[String, Double] = {
    val idfs = new HashMap[String, Double]()
    for ((term, count) <- docFreqs) {
      idfs.put(term, math.log10(numDocs.toDouble / count))
    }
    idfs
  }

  def readFile(path: String, sc: SparkContext): RDD[String] = {
    val conf = new Configuration()
    conf.set(XmlInputFormat.START_TAG_KEY, "<page>")
    conf.set(XmlInputFormat.END_TAG_KEY, "</page>")
    val rawXmls = sc.newAPIHadoopFile(path, classOf[XmlInputFormat], classOf[LongWritable],
      classOf[Text], conf)
    rawXmls.map(p => p._2.toString)
  }

  def wikiXmlToPlainText(pageXml: String): String = {
    val page = new EnglishWikipediaPage()
    WikipediaPage.readPage(page, pageXml)
    if (page.isEmpty || !page.isArticle || page.isDisambiguation || page.isRedirect) ""
    else page.getContent
  }

  def createPipeline(): StanfordCoreNLP = {
    val props = new Properties()
    props.put("annotators", "tokenize, ssplit, pos, lemma")
    new StanfordCoreNLP(props)
  }

  def plainTextToLemmas(text: String, stopWords: Set[String], pipeline: StanfordCoreNLP)
      : Seq[String] = {
    val doc = new Annotation(text)
    pipeline.annotate(doc)
    val lemmas = new ArrayBuffer[String]()
    val sentences = doc.get(classOf[SentencesAnnotation])
    for (sentence <- sentences) {
      for (token <- sentence.get(classOf[TokensAnnotation])) {
        val lemma = token.get(classOf[LemmaAnnotation])
        if (lemma.length > 2 && !stopWords.contains(lemma) && isOnlyLetters(lemma)) {
          lemmas += lemma.toLowerCase
        }
      }
    }
    lemmas
  }

  def isOnlyLetters(str: String): Boolean = {
    var i = 0
    while (i < str.length) {
      if (!Character.isLetter(str.charAt(i))) {
        return false
      }
      i += 1
    }
    true
  }

  def loadStopWords(path: String) = scala.io.Source.fromFile(path).getLines.toSet

  def saveDocFreqs(path: String, docFreqs: Array[(String, Int)]) {
    val ps = new PrintStream(new FileOutputStream(path))
    for ((doc, freq) <- docFreqs) {
      ps.println(s"$doc\t$freq")
    }
    ps.close()
  }
}
