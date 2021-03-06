package updown.app

import updown.data._
import updown.data.io._
import updown.lex._

import java.io._
import java.util.zip._

import opennlp.maxent._
import opennlp.maxent.io._
import opennlp.model._

import upenn.junto.app._
import upenn.junto.config._

import org.clapper.argot._

import scala.collection.JavaConversions._

/**
 *
 * This object performs the Modified Adsorption label propagation algorithm on a graph containing
 * user nodes, unigram and bigram nodes (including hashtags), emoticon nodes, maxent seeds, MPQA seeds,
 * and emoticon seeds.
 *
 * @author Mike Speriosu
 */
object JuntoClassifier {

  val DEFAULT_MU1 = .005
  val DEFAULT_ITERATIONS = 100
  val DEFAULT_EDGE_SEED_SET = "nfmoe"

  val TOP_N = 20

  // for weighting MPQA seeds
  val BIG = 0.9
  val BIG_COMP = .1
  val SMALL = 0.8
  val SMALL_COMP = .2

  val TWEET_ = "tweet_"
  val USER_ = "user_"
  val NGRAM_ = "ngram_"
  val POS = "POS"
  val NEG = "NEG"
  val NEU = "NEU"

  val posEmoticons = """:) :D =D =) :] =] :-) :-D :-] ;) ;D ;] ;-) ;-D ;-]""".split(" ")
  val negEmoticons = """:( =( :[ =[ :-( :-[ :’( :’[ D:""".split(" ")

  val nodeRE = """^(.+_)(.+)$""".r

  var refCorpusNgramProbs: ProbabilityLexicon /*scala.collection.mutable.HashMap[String, Double]*/ = null
  var thisCorpusNgramProbs: scala.collection.mutable.HashMap[String, Double] = null

  var wordCount = 0

  import ArgotConverters._

  val parser = new ArgotParser("updown run updown.app.JuntoClassifier", preUsage = Some("Updown"))

  val goldInputFile = parser.option[String](List("g", "gold"), "gold", "gold labeled input")
  val modelInputFile = parser.option[String](List("m", "model"), "model", "model input")
  val mpqaInputFile = parser.option[String](List("p", "mpqa"), "mpqa", "MPQA sentiment lexicon input file")
  val followerGraphFile = parser.option[String](List("f", "follower-graph"), "follower-graph", "twitter follower graph input file")
  val refCorpusProbsFile = parser.option[String](List("r", "reference-corpus-probabilities"), "ref-corp-probs", "reference corpus probabilities input file")

  val edgeSeedSetOption = parser.option[String](List("e", "edge-seed-set-selection"), "edge-seed-set-selection", "edge/seed set selection")
  val targetsInputFile = parser.option[String](List("t", "targets"), "targets", "targets")
  val topNOutputFile = parser.option[String](List("z", "top-n-file"), "top-n-file", "top-n-file")

  val mu1 = parser.option[Double](List("u", "mu1"), "mu1", "seed injection probability")
  val iterations = parser.option[Int](List("n", "iterations"), "iterations", "number of iterations")

  def main(args: Array[String]) {
    try {
      parser.parse(args)
    }
    catch {
      case e: ArgotUsageException => println(e.message); sys.exit(0)
    }

    if (modelInputFile.value == None) {
      println("You must specify a model input file via -m.")
      sys.exit(0)
    }
    if (goldInputFile.value == None) {
      println("You must specify a gold labeled input file via -g.")
      sys.exit(0)
    }
    if (mpqaInputFile.value == None) {
      println("You must specify an MPQA sentiment lexicon file via -p.")
      sys.exit(0)
    }
    if (followerGraphFile.value == None) {
      println("You must specify a follower graph file via -f.")
      sys.exit(0)
    }

    val edgeSeedSet = edgeSeedSetOption.value.getOrElse(DEFAULT_EDGE_SEED_SET)

    val goldLabeledTweets: List[GoldLabeledTweet] = TweetFeatureReader(goldInputFile.value.get)

    if (refCorpusProbsFile.value != None) {
      refCorpusNgramProbs = loadRefCorpusNgramProbs(refCorpusProbsFile.value.get)
      thisCorpusNgramProbs = computeNgramProbs(goldLabeledTweets)
    }

    val lexicon = MPQALexicon(mpqaInputFile.value.get)

    val graph = createGraph(goldLabeledTweets, followerGraphFile.value.get, modelInputFile.value.get, lexicon, edgeSeedSet)

    JuntoRunner(graph, mu1.value.getOrElse(DEFAULT_MU1), .01, .01, iterations.value.getOrElse(DEFAULT_ITERATIONS), false)

    val tweetIdsToPredictedLabels = new scala.collection.mutable.HashMap[String, SentimentLabel.Type]

    val ngramsToPositivity = new scala.collection.mutable.HashMap[String, Double]
    val ngramsToNegativity = new scala.collection.mutable.HashMap[String, Double]
    val ngramsToNeutrality = new scala.collection.mutable.HashMap[String, Double]
    for ((id, vertex) <- graph._vertices) {
      val nodeRE(nodeType, nodeName) = id
      if (nodeType == TWEET_) {
        val predictions = vertex.GetEstimatedLabelScores
        val posProb = predictions.get(POS)
        val negProb = predictions.get(NEG)
        val neuProb = predictions.get(NEU)

        tweetIdsToPredictedLabels(nodeName) =
          if (posProb >= negProb && posProb >= neuProb)
            SentimentLabel.Positive
          else if (negProb >= posProb && negProb >= neuProb)
            SentimentLabel.Negative
          else
            SentimentLabel.Neutral
      }
      else if (topNOutputFile.value != None && nodeType == NGRAM_ && !lexicon.contains(nodeName)
        && getNgramWeight(nodeName) >= 1.0 && thisCorpusNgramProbs(nodeName) * wordCount >= 5.0) {
        val predictions = vertex.GetEstimatedLabelScores
        val posProb = predictions.get(POS)
        val negProb = predictions.get(NEG)
        val neuProb = predictions.get(NEU)

        ngramsToPositivity.put(nodeName, posProb)
        ngramsToNegativity.put(nodeName, negProb)
        ngramsToNeutrality.put(nodeName, neuProb)

      }
    }

    System.err.println("predicted nPos:%d nNeg:%d nNeu:%d".format(
    tweetIdsToPredictedLabels.count(i=>i._2==SentimentLabel.Positive),
    tweetIdsToPredictedLabels.count(i=>i._2==SentimentLabel.Negative),
    tweetIdsToPredictedLabels.count(i=>i._2==SentimentLabel.Neutral)
    ))
    val systemLabeledTweets =
      for (GoldLabeledTweet(id, userid, features, goldLabel) <- goldLabeledTweets) yield {
        SystemLabeledTweet(id, userid, features, goldLabel,
          if (tweetIdsToPredictedLabels.containsKey(id)) {
            tweetIdsToPredictedLabels(id)
          } else {
            null
          })
      }

    PerTweetEvaluator(systemLabeledTweets)
    PerUserEvaluator(systemLabeledTweets)
    if (targetsInputFile.value != None) {
      val targets =
        (for (line <- scala.io.Source.fromFile(targetsInputFile.value.get, "UTF-8").getLines) yield {
          val arr = line.split("\\|")
          (arr(0)->arr(1))
        }).toMap
      PerTargetEvaluator(systemLabeledTweets, targets)
    }

    if (topNOutputFile.value != None) {
      val tnout = new BufferedWriter(new FileWriter(topNOutputFile.value.get))
      //val topNPos = ngramsToPositivity.toList/*.filterNot(p => lexicon.contains(p._1))*/.sortWith((x, y) => x._2 >= y._2).slice(0, TOP_N)
      //val topNNeg = ngramsToPositivity.toList.sortWith((x, y) => x._2 <= y._2).slice(0, TOP_N)//ngramsToNegativity.toList/*.filterNot(p => lexicon.contains(p._1))*/.sortWith((x, y) => x._2 >= y._2).slice(0, TOP_N)

      val ngramsToRatios = ngramsToPositivity.toList.map(p => (p._1, p._2 / ngramsToNegativity(p._1)))

      //topNPos.foreach(p => tnout.write(p._1+" "+p._2+"\n"))
      //tnout.write("\n\n\n")
      //topNNeg.foreach(p => tnout.write(p._1+" "+p._2+"\n"))
      val mostPos = ngramsToRatios.sortWith((x, y) => x._2 >= y._2).slice(0, TOP_N)
      mostPos.foreach(p => tnout.write(p._1 + "\t" + p._2 + "\n"))
      mostPos.foreach(p => tnout.write(p._1 + ", "))
      tnout.write("\n\n\n\n")
      val mostNeg = ngramsToRatios.sortWith((x, y) => x._2 <= y._2).slice(0, TOP_N)
      mostNeg.foreach(p => tnout.write(p._1 + "\t" + p._2 + "\n"))
      mostNeg.foreach(p => tnout.write(p._1 + ", "))
      tnout.write("\n")

      tnout.close
    }
  }

  def createGraph(tweets: List[GoldLabeledTweet], followerGraphFile: String, modelInputFile: String, lexicon: MPQALexicon, edgeSeedSet: String) = {
    val edges = (if (edgeSeedSet.contains("n")) getTweetNgramEdges(tweets) else Nil) :::
      (if (edgeSeedSet.contains("f")) (getFollowerEdges(followerGraphFile) ::: getUserTweetEdges(tweets)) else Nil)
    val seeds = (if (edgeSeedSet.contains("m")) getMaxentSeeds(tweets, modelInputFile) else Nil) :::
      (if (edgeSeedSet.contains("o")) getMPQASeeds(lexicon) else Nil) :::
      (if (edgeSeedSet.contains("e")) getEmoticonSeeds else Nil)
    GraphBuilder(edges, seeds)
  }

  def getTweetNgramEdges(tweets: List[GoldLabeledTweet]): List[Edge] = {
    (for (tweet <- tweets) yield {
      for (ngram <- tweet.features) yield {
        val weight = getNgramWeight(ngram)
        //println(TWEET_ + tweet.id + "   " + NGRAM_ + ngram + "   " + weight)
        if (weight > 0.0) {
          //if(ngram == "mccain") println("mccain: " + weight)
          Some(new Edge(TWEET_ + tweet.id, NGRAM_ + ngram, weight))
        }
        else
          None
      }
    }).flatten.flatten
  }

  def getUserTweetEdges(tweets: List[GoldLabeledTweet]): List[Edge] = {
    for (tweet <- tweets) yield {
      //println(USER_ + tweet.userid + "   " + TWEET_ + tweet.id)
      new Edge(USER_ + tweet.userid, TWEET_ + tweet.id, 1.0)
    }
  }

  def getFollowerEdges(followerGraphFile: String): List[Edge] = {
    (for (line <- scala.io.Source.fromFile(followerGraphFile, "utf-8").getLines) yield {
      val tokens = line.split("\t")
      if (tokens.length < 2 || tokens(0).length == 0 || tokens(1).length == 0)
        None
      else {
        //println(USER_ + tokens(0) + "   " + USER_ + tokens(1))
        Some(new Edge(USER_ + tokens(0), USER_ + tokens(1), 1.0))
      }
    }).flatten.toList
  }

  def getMaxentSeeds(tweets: List[GoldLabeledTweet], modelInputFile: String): List[Label] = {
    val dataInputStream = new DataInputStream(new FileInputStream(modelInputFile));
    val reader = new BinaryGISModelReader(dataInputStream)
    val model = reader.getModel

    val labels = model.getDataStructures()(2).asInstanceOf[Array[String]]
    val posIndex = labels.indexOf("1")
    val negIndex = labels.indexOf("-1")
    val neuIndex = labels.indexOf("0")

    (for (tweet <- tweets) yield {
      val result = model.eval(tweet.features.toArray)

      val posProb = if (posIndex >= 0) result(posIndex) else 0.0
      val negProb = if (negIndex >= 0) result(negIndex) else 0.0
      val neuProb = if (neuIndex >= 0) result(neuIndex) else 0.0

      //println(TWEET_ + tweet.id + "   " + POS + "   " + posProb)
      //println(TWEET_ + tweet.id + "   " + NEG + "   " + negProb)
      new Label(TWEET_ + tweet.id, POS, posProb) :: new Label(TWEET_ + tweet.id, NEG, negProb) :: new Label(TWEET_ + tweet.id, NEU, neuProb) :: Nil
    }).flatten
  }

  def getMPQASeeds(lexicon: MPQALexicon): List[Label] = {
    (for (word <- lexicon.keySet.toList) yield {
      val entry = lexicon(word)
      val posWeight =
        if (entry.isStrong && entry.isPositive) BIG
        else if (entry.isWeak && entry.isPositive) SMALL
        else if (entry.isStrong && entry.isNegative) BIG_COMP
        else /*if(entry.isWeak && entry.isNegative)*/ SMALL_COMP

      val negWeight =
        if (entry.isStrong && entry.isPositive) BIG_COMP
        else if (entry.isWeak && entry.isPositive) SMALL_COMP
        else if (entry.isStrong && entry.isNegative) BIG
        else /*if(entry.isWeak && entry.isNegative)*/ SMALL

      val neuWeight = 0.5 //Matt has little to no inkling of what is appropriate here.


      new Label(NGRAM_ + word, POS, posWeight) :: new Label(NGRAM_ + word, NEG, negWeight) :: new Label(NGRAM_ + word, NEU, neuWeight) :: Nil
    }).flatten
  }

  def getEmoticonSeeds(): List[Label] = {
    (for (emo <- posEmoticons) yield {
      new Label(NGRAM_ + emo, POS, BIG) ::
        new Label(NGRAM_ + emo, NEG, BIG_COMP) :: Nil
    }).toList.flatten :::
      (for (emo <- negEmoticons) yield {
        new Label(NGRAM_ + emo, NEG, BIG) ::
          new Label(NGRAM_ + emo, POS, BIG_COMP) :: Nil
      }).toList.flatten :::
      (for (emo <- negEmoticons) yield {
        new Label(NGRAM_ + emo, NEG, BIG) ::
          new Label(NGRAM_ + emo, POS, BIG_COMP) :: Nil
      }).toList.flatten
  }

  def loadRefCorpusNgramProbs(filename: String): ProbabilityLexicon /*scala.collection.mutable.HashMap[String, Double]*/ = {
    val gis = new GZIPInputStream(new FileInputStream(filename))
    val ois = new ObjectInputStream(gis)
    val refProbs = ois.readObject()

    refProbs match {
      //case refProbsHM: scala.collection.mutable.HashMap[String, Double] => refProbsHM
      case refProbLex: ProbabilityLexicon => refProbLex
      case _ => throw new ClassCastException
    }
  }

  def computeNgramProbs(tweets: List[GoldLabeledTweet]): scala.collection.mutable.HashMap[String, Double] = {
    val probs = new scala.collection.mutable.HashMap[String, Double] {
      override def default(s: String) = 0.0
    }
    /*var */ wordCount = 0
    for (tweet <- tweets) {
      for (feature <- tweet.features) {
        probs.put(feature, probs(feature) + 1.0)
        wordCount += 1
      }
    }

    probs.foreach(p => probs.put(p._1, p._2 / wordCount))

    probs
  }

  def getNgramWeight(ngram: String): Double = {
    if (refCorpusNgramProbs == null || thisCorpusNgramProbs == null)
      return 1.0
    else {
      val numerator = thisCorpusNgramProbs(ngram)
      val denominator = refCorpusNgramProbs.getNgramProb(ngram)
      //println(ngram+" "+denominator)

      if (denominator == 0.0) //ngram not found in reference corpus; assume NOT relevant to this corpus
        return 0.0
      else if (numerator > denominator) {
        //println(ngram + "   this: " + numerator + "   ref: " + denominator + "   weight: " + math.log(numerator / denominator))
        return math.log(numerator / denominator)
      }
      else
        return 0.0
    }
  }
}


/**
 *
 * This object performs supervised transductive label propagation. One set of tweets is given as the training set,
 * and its gold labels are used as seeds. The graph includes tweet nodes, ngram nodes, and follower edges for both
 * the training set and a test set (whose gold labels are not included) which is evaluated on.
 *
 * @author Mike Speriosu
 */

object TransductiveJuntoClassifier {

  import JuntoClassifier._

  import ArgotConverters._

  val parser = new ArgotParser("updown run updown.app.TransductiveJuntoClassifier", preUsage = Some("Updown"))

  val goldInputFile = parser.option[String](List("g", "gold"), "gold", "gold training labeled input")
  val testInputFile = parser.option[String](List("v", "test"), "test", "gold test labeled input")
  //val modelInputFile = parser.option[String](List("m", "model"), "model", "model input")
  //val mpqaInputFile = parser.option[String](List("p", "mpqa"), "mpqa", "MPQA sentiment lexicon input file")
  val followerGraphFile = parser.option[String](List("f", "follower-graph"), "follower-graph", "twitter follower graph input file (TRAIN)")
  val followerGraphFileTest = parser.option[String](List("h", "follower-graph-test"), "follower-graph-test", "twitter follower graph input (TEST)")
  val refCorpusProbsFile = parser.option[String](List("r", "reference-corpus-probabilities"), "ref-corp-probs", "reference corpus probabilities input file")

  val edgeSeedSetOption = parser.option[String](List("e", "edge-seed-set-selection"), "edge-seed-set-selection", "edge/seed set selection")
  val targetsInputFile = parser.option[String](List("t", "targets"), "targets", "targets (TRAIN)")
  val targetsInputFileTest = parser.option[String](List("u", "targets-test"), "targets", "targets (TEST)")

  val mu1 = parser.option[Double](List("u", "mu1"), "mu1", "seed injection probability")
  val iterations = parser.option[Int](List("n", "iterations"), "iterations", "number of iterations")

  def main(args: Array[String]) = {

    try {
      parser.parse(args)
    }
    catch {
      case e: ArgotUsageException => println(e.message); sys.exit(0)
    }

    val edgeSeedSet = edgeSeedSetOption.value.getOrElse(DEFAULT_EDGE_SEED_SET)

    val trainTweets = TweetFeatureReader(goldInputFile.value.get)
    val goldLabeledTestTweets = TweetFeatureReader(testInputFile.value.get)
    val totalTweets = trainTweets ::: goldLabeledTestTweets

    if (refCorpusProbsFile.value != None) {
      refCorpusNgramProbs = loadRefCorpusNgramProbs(refCorpusProbsFile.value.get)
      thisCorpusNgramProbs = computeNgramProbs(totalTweets)
    }

    val graph = createTransductiveGraph(trainTweets, followerGraphFile.value.get, goldLabeledTestTweets, followerGraphFileTest.value.get, edgeSeedSet)

    JuntoRunner(graph, mu1.value.getOrElse(DEFAULT_MU1), .01, .01, iterations.value.getOrElse(DEFAULT_ITERATIONS), false)

    val tweetIdsToPredictedLabels = new scala.collection.mutable.HashMap[String, SentimentLabel.Type]

    for ((id, vertex) <- graph._vertices) {
      val nodeRE(nodeType, nodeName) = id
      if (nodeType == TWEET_) {
        val predictions = vertex.GetEstimatedLabelScores
        val posProb = predictions.get(POS)
        val negProb = predictions.get(NEG)
        val neuProb = predictions.get(NEU)
        if (posProb >= negProb && posProb >= neuProb)
          tweetIdsToPredictedLabels.put(nodeName, POS)
        else if (negProb >= posProb && negProb >= neuProb)
          tweetIdsToPredictedLabels.put(nodeName, NEG)
        else
          tweetIdsToPredictedLabels.put(nodeName, NEU)
      }
    }

    val systemLabeledTestTweets =
      for (GoldLabeledTweet(id, userid, features, goldLabel) <- goldLabeledTestTweets) yield {
        SystemLabeledTweet(id, userid, features, goldLabel,
          if (tweetIdsToPredictedLabels.containsKey(id)) {
            tweetIdsToPredictedLabels(id)
          } else {
            null
          })
      }

    PerTweetEvaluator.apply(systemLabeledTestTweets)
    PerUserEvaluator.evaluate(systemLabeledTestTweets)
    if (targetsInputFile.value != None) {
//      val targets = new scala.collection.mutable.HashMap[String, String]
//
//      scala.io.Source.fromFile(targetsInputFile.value.get, "utf-8").getLines
//        .foreach(p => targets.put(p.split("\t")(0).trim, p.split("\t")(1).trim))
      val targets: Map[String, String] =
        (for (line <- scala.io.Source.fromFile(targetsInputFile.value.get, "UTF-8").getLines) yield {
          val arr = line.split("\\|")
          (arr(0)->arr(1))
        }).toMap
      PerTargetEvaluator(systemLabeledTestTweets, targets)
    }
  }

  def createTransductiveGraph(trainTweets: List[GoldLabeledTweet], followerGraphFile: String, testTweets: List[GoldLabeledTweet], followerGraphFileTest: String, edgeSeedSet: String) = {
    val totalTweets = trainTweets ::: testTweets
    val edges = (if (edgeSeedSet.contains("n")) getTweetNgramEdges(totalTweets) else Nil) :::
      (if (edgeSeedSet.contains("f")) (getFollowerEdges(followerGraphFile) ::: getUserTweetEdges(totalTweets) :::
        getFollowerEdges(followerGraphFileTest))
      else Nil)
    val seeds = getGoldSeeds(trainTweets)
    GraphBuilder(edges, seeds)
  }

  def getGoldSeeds(tweets: List[GoldLabeledTweet]): List[Label] = {
    for (tweet <- tweets) yield {
      tweet match {
        case GoldLabeledTweet(id, _, _, SentimentLabel.Positive) => new Label(TWEET_ + id, POS, 1.0)
        case GoldLabeledTweet(id, _, _, SentimentLabel.Negative) => new Label(TWEET_ + id, POS, 1.0)
        case GoldLabeledTweet(id, _, _, SentimentLabel.Neutral) => new Label(TWEET_ + id, POS, 1.0)
      }
    }
  }
}
