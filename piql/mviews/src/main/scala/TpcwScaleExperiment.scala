package edu.berkeley.cs
package scads
package piql
package mviews

import avro.runtime._
import deploylib.RemoteMachine
import org.apache.avro.file.CodecFactory
import java.io.File

import deploylib.mesos._
import tpcw._
import tpcw.scale._
import perf._
import comm._
import storage._
import piql.debug.DebugExecutor
import avro.marker.{AvroPair, AvroRecord}
import edu.berkeley.cs.scads.perf.ReplicatedExperimentTask
import java.util.concurrent.TimeUnit
import net.lag.logging.Logger
import org.apache.avro.generic.GenericContainer


case class RefreshResult(var expId: String,
                        var function: String) extends AvroPair {
  var times: Seq[Long] = Nil
}

case class TpcwViewRefreshTask(var experimentAddress: String,
                               var clusterAddress: String,
                               var resultClusterAddress: String,
                               var expId: String) extends AvroRecord with ExperimentTask {


  def run() = {
    val clusterRoot = ZooKeeperNode(clusterAddress)
    val coordination = clusterRoot.getOrCreate("coordination/clients")

    val resultCluster = new ScadsCluster(ZooKeeperNode(resultClusterAddress))
    val results = resultCluster.getNamespace[RefreshResult]("updateResults")
    logger.info("Waiting for experiment to start.")
    clusterRoot.awaitChild("clusterReady")
    logger.info("Cluster ready... entering refresh loop")

    val cluster = new ScadsCluster(clusterRoot)
    val client = new TpcwClient(cluster, new ParallelExecutor)

    val ocTimes = new scala.collection.mutable.ArrayBuffer[Long]()
    val rcTimes = new scala.collection.mutable.ArrayBuffer[Long]()

    logger.info("Waiting for clients to start")
    coordination.awaitChild("runViewRefresh")

    while(coordination.get("expRunning").isDefined) {
      logger.info("Updating OrderCounts")
      val ocStartTime = System.currentTimeMillis()
      retry(5) { client.updateOrderCount() }
      val ocEndTime = System.currentTimeMillis()
      logger.info("OrderCount Update complete in %d", ocEndTime - ocStartTime)
      ocTimes += (ocEndTime - ocStartTime)

      logger.info("Updating RelatedCounts")
      val rcStartTime = System.currentTimeMillis()
      retry(5) { client.updateRelatedCounts() }
      val rcEndTime = System.currentTimeMillis()
      logger.info("Related Counts updated in %d", rcEndTime - rcStartTime)
      rcTimes += (rcEndTime - rcStartTime)

      coordination.getOrCreate("viewsReady")
      val nextEpoch = client.calculateEpochs().drop(1).head
      while(System.currentTimeMillis < nextEpoch) {
        val sleepTime = System.currentTimeMillis - nextEpoch
        if(sleepTime > 0) Thread.sleep(sleepTime)
      }
    }

    logger.info("Recording Results")
    val ocResult = RefreshResult(expId, "orderCount")
    ocResult.times = ocTimes
    val rcResult = RefreshResult(expId, "relatedCount")
    rcResult.times = rcTimes
    Seq(ocResult, rcResult).foreach(results ++= List(_))
  }
}

case class WorkloadStat(
  var host: String,
  var id: ServiceId,
  var ns: String,
  var time: Long) extends AvroPair {
  var stat: GetWorkloadStatsResponse = null
}

object TpcwScaleExperiment {
  val logger = Logger()
  lazy val resultClusterAddress = ZooKeeperNode("zk://ec2-54-245-53-1.us-west-2.compute.amazonaws.com/home/ekl/results2")
  lazy val resultsCluster = new ScadsCluster(resultClusterAddress)
  lazy val scaleResults =  resultsCluster.getNamespace[Result]("tpcwScaleResults")
  lazy val updateResults = resultsCluster.getNamespace[RefreshResult]("updateResults")
  lazy val workloadStats = resultsCluster.getNamespace[WorkloadStat]("workloadStats")

  def statsPerPartition = {
    workloadStats.iterateOverRange(None,None).toSeq
      .groupBy(s => (s.host, s.id))
      .map {
        case (node, recs) =>
          (node, recs.size, recs.map(_.time).min, recs.map(_.time).max)
    }
  }

  def partitionsPerServer = {
    workloadStats.iterateOverRange(None,None).toSeq
      .map(s => (s.host, s.id))
      .distinct
      .groupBy(_._1)
      .map {
      case (host, ids) => (host, ids.size)
    }
  }

  def workloadByServer = {
    workloadStats.iterateOverRange(None,None).toSeq
      .filter(_.time <= 1351726381068L)
      .filter(_.time >= 1351724814792L)
      .groupBy(s => s.host)
      .map {
      case (host, recs) =>
        val stats = recs.map(_.stat)
          (host,
              stats.map(_.getCount).sum,
              stats.map(_.getRangeCount).sum,
              stats.map(_.putCount).sum,
              stats.map(_.bulkCount).sum,
              stats.size)
    }
  }

  def workloadByServerNamespace = {
    workloadStats.iterateOverRange(None,None).toSeq
      .groupBy(s => (s.host, s.ns))
      .map {
      case ((host,ns), recs) =>
        val stats = recs.map(_.stat)
          (host,
            ns,
              stats.map(_.getCount).sum,
              stats.map(_.getRangeCount).sum,
              stats.map(_.putCount).sum,
              stats.map(_.bulkCount).sum,
              stats.size)
    }
  }

  /*
   * Returns list of high skew operations, weighted by skewed load induced
   * Seq[Tuple2[namespace, op_desc, host_min, host_max, skew, estimated_skew_load]]
   */
  def findHighSkewOps(iter: Seq[WorkloadStat]) = {
    val deduped: Seq[WorkloadStat] = iter.toSet.toSeq
    val width = deduped.groupBy(t => t.time).map(_._2.length).max
    val complete = deduped.groupBy(t => t.time).filter(_._2.length == width).flatMap(_._2)
    complete.groupBy(t => (t.ns, t.host)).toList.map {
      case ((ns, host), recs) =>
        val hostStats = recs.flatMap(s => s.stat.countKeys.zip(s.stat.countValues))
        (ns, host) -> hostStats.groupBy(_._1).map {
          case (k,v) => (k, v.map(_._2).sum)
        }
    }.groupBy(_._1._1).map {
      case (ns, hostRecs) =>
        val tracedOps = hostRecs.flatMap(_._2.keys).toSet
        ns -> tracedOps.flatMap{op =>
          val distribution = hostRecs.map(h => (h._2.getOrElse(op, 0), h._1._2))
          val (min, minh) = distribution.min
          val (max, maxh) = distribution.max
          val skew = if (max != 0) (max - min).toDouble / max else 0
          List((ns, op, min, max, minh, maxh, skew, skew * max))
        }
    }.flatMap {
      case (ns, skewed) => skewed
    }.toList.sortBy(t => - t._8)
  }

  def captureWorkload(clientRoot: String, buffer: collection.mutable.ArrayBuffer[WorkloadStat])(implicit cluster: Cluster): Unit =
    captureWorkload(new TpcwClient(new ScadsCluster(ZooKeeperNode(clientRoot)), new ParallelExecutor), buffer)

  def captureWorkload(client: TpcwClient, buffer: collection.mutable.ArrayBuffer[WorkloadStat])(implicit cluster: Cluster): Unit = {
    cluster.addInternalAddressMappings(StorageRegistry)

    val t = new Thread {
      override def run(): Unit = {
        val allNs = client.namespaces.asInstanceOf[Seq[Namespace with KeyRangeRoutable]] ++ client.namespaces.flatMap(_.listIndexes).map(_._2).asInstanceOf[Seq[Namespace with KeyRangeRoutable]]

        logger.warning("Start workload collection.")
        while(true) {
          val futures = (allNs
            .flatMap(ns => ns.serversForKeyRange(None,None).map(r => (ns.name, r)))
            .flatMap { case (name, rangeDesc) => rangeDesc.servers.map(s => (name, s !! GetWorkloadStats())) }
          )

          buffer ++= futures.map {
            case (name, f) =>
              val result = f.get(100000).getOrElse(sys.error("TIMEOUT")).asInstanceOf[GetWorkloadStatsResponse]
              var node = f.dest.asInstanceOf[PartitionService]
              val s = WorkloadStat(node.host, node.id, name, result.statsSince)
              s.stat = result
              s
          }

          Thread.sleep(10000)
        }
      }
    }

    t.start()
  }

  implicit def productSeqToExcel(lines: Traversable[Product]) = new {
    import java.io._
    def toExcel: Unit = {
      val file = File.createTempFile("scadsOut", ".csv")
      val writer = new FileWriter(file)

      lines.map(_.productIterator.mkString(",") + "\n").foreach(writer.write)
      writer.close

      Runtime.getRuntime.exec(Array("/usr/bin/open", file.getCanonicalPath))
    }
  }

  implicit def seqSeqToExcel(lines: Traversable[Traversable[Any]]) = new {
    import java.io._
    def toExcel: Unit = {
      val file = File.createTempFile("scadsOut", ".csv")
      val writer = new FileWriter(file)

      lines.map(_.mkString(",") + "\n").foreach(writer.write)
      writer.close

      Runtime.getRuntime.exec(Array("/usr/bin/open", file.getCanonicalPath))
    }
  }

  implicit def avroIterWriter[RecordType <: GenericContainer](iter: Iterator[RecordType]) = new {
     def toAvroFile(file: File, codec: Option[CodecFactory] = None)(implicit schema: TypedSchema[RecordType]) = {
       val outfile = AvroOutFile[RecordType](file, codec)
       iter.foreach(outfile.append)
       outfile.close
      }
    }


  def backup = scaleResults.iterateOverRange(None,None).toAvroFile(new java.io.File("scaleResults" + System.currentTimeMillis() + ".avro"))

  implicit def toOption[A](a: A) = Option(a)

  lazy val testTpcwClient =
    new piql.tpcw.TpcwClient(new piql.tpcw.TpcwLoaderTask(10,5,10,10000,2).newTestCluster, new ParallelExecutor with DebugExecutor)

  lazy val tinyTpcwClient =
    new piql.tpcw.TpcwClient(new piql.tpcw.TpcwLoaderTask(1,1,1,1000,1).newTestCluster, new ParallelExecutor with DebugExecutor)

  def resultsByAction = scaleResults
    .iterateOverRange(None,None)
    .filter(_.iteration != 1)
    .flatMap(_.times).toSeq
    .groupBy(_.name)
    .map { case (name, hists) => (name, hists.reduceLeft(_ + _).quantile(0.99)) }

  def resultHistograms(action: String) = {
    scaleResults
    .iterateOverRange(None,None)
    .filter(_.iteration != 1)
    .toSeq
    .groupBy(_.loaderConfig.numServers)
    .map { case (n, results) => {
      val hists = results.flatMap(_.times.filter(_.name == action))
      (n, hists.reduceLeft(_ + _))
    }}
    .toSeq
    .sortBy(_._1)
    .map(_._2.buckets)
    .transpose
    .foreach(row => println(row.mkString(" ")))
  }

  /* Returns rows of (numServers, action, 99th, numRequests, expId) */
  def resultRows(expIdHorizon: String, iter: Traversable[Result]=scaleResults.iterateOverRange(None,None).toSeq) = {
    iter.filter(t => t.iteration != 1 && t.expId >= expIdHorizon)
      .groupBy(r => (r.loaderConfig.numServers, r.clientConfig.expId)).toSeq
      .sortBy(_._1)
      .flatMap {
        case (size, results) =>
          results.flatMap(_.times)
            .groupBy(_.name).toSeq
            .map {
              case (wi, hists) =>
                val quantiles = hists.map(_.quantile(0.99))
                val aggResults = hists.reduceLeft(_+_)
                (size._1, wi, aggResults.quantile(0.99), aggResults.totalRequests, size._2)
          }
      }.sortBy(_._1).sortBy(_._2)
  }

  def resultTable(rows: Seq[Tuple5[Int,String,Int,Long,String]]) = {
    val lines = new scala.collection.mutable.ArrayBuffer[String]
    lines.append("numServers,action,99th,numRequests,expId")
    rows.foreach {
      case t => lines.append(t.productIterator.toList.mkString(","))
    }
    lines.mkString("\n")
  }

  def resultScatterTable(rows: Seq[Tuple5[Int,String,Int,Long,String]]) = {
    val actions = rows.map(_._2).toSet.toList.sorted
    val meanHeader = actions.map(_ + "_avg")
    val stddevHeader = actions.map(_ + "_stddev")
    List(List("numServers") ++ meanHeader ++ stddevHeader ++ List("numRequests", "numSamples")) ++
    rows.groupBy(t => (t._1, t._5)).map{case ((n, expId), rows) => {
      val means = rows.groupBy(_._2).toList.sortBy(_._1).map{
        case (action, samples) => {
          samples.map(_._3).sum / samples.length
        }
      }
      val stddevs = rows.groupBy(_._2).toList.sortBy(_._1).map{
        case (action, samples) => {
          val mean_99th = samples.map(_._3).sum / samples.length
          val mean_99th_sq = samples.map(_._3 match {case x => math.pow(x, 2)}).sum / samples.length
          math.sqrt(mean_99th_sq - math.pow(mean_99th, 2))
        }
      }
      val samples = rows.map(_._5).toSet.size
      val count = rows.map(_._4).sum / samples
      List(n) ++ means ++ stddevs ++ List(count, samples)
    }}
  }

  def results = {
    val allResults = scaleResults.iterateOverRange(None,None).toSeq

    allResults.filter(_.iteration != 1)
      .groupBy(r => (r.loaderConfig.numServers, r.clientConfig.expId)).toSeq
      .sortBy(_._1)
      .foreach {
      case (size, results) =>
        results.flatMap(_.times)
          .groupBy(_.name).toSeq
          .sortBy(_._1)
          .foreach  {
          case (wi, hists) =>
            val aggResults = hists.reduceLeft(_+_)
          println(Seq(size._1, size._2, wi, aggResults.quantile(0.90), aggResults.quantile(0.99), aggResults.stddev, aggResults.totalRequests).mkString("\t"))
        }
    }
  }

  def reqResults = {
    val allResults = scaleResults.iterateOverRange(None,None).toSeq

    allResults.filter(_.iteration != 1)
      .groupBy(r => (r.loaderConfig.numServers, r.clientConfig.expId)).toSeq
      .sortBy(_._1)
      .foreach {
      case (size, results) =>
        (results.flatMap(_.getTimes) ++ results.flatMap(_.getRangeTimes))
          .groupBy(_.name).toSeq
          .sortBy(_._1)
          .foreach {
          case (wi, hists) =>
            val aggResults = hists.reduceLeft(_+_)
          println(Seq(size._1, size._2, wi, aggResults.quantile(0.90), aggResults.quantile(0.99), aggResults.stddev, aggResults.totalRequests).mkString("\t"))
        }
    }
  }

  def runScaleTest(numServers: Int, small: Boolean = false, executor: String = "edu.berkeley.cs.scads.piql.exec.ParallelExecutor")(implicit cluster: Cluster) = {
    val (scadsTasks, scadsCluster) = TpcwLoaderTask(numServers, numServers/2, replicationFactor=2, numEBs = 150 * numServers/2, numItems = 10000).delayedCluster

    val tpcwTaskTemplate = TpcwWorkflowTask(
          if(small) 1 else numServers/2,
          executor,
          iterations = 5,
          runLengthMin = 5,
          numThreads = 10
        )

    val tpcwTasks = tpcwTaskTemplate.delayedSchedule(scadsCluster, resultsCluster)

    val viewRefreshTask = TpcwViewRefreshTask(
      tpcwTaskTemplate.experimentAddress,
      tpcwTaskTemplate.clusterAddress,
      tpcwTaskTemplate.resultClusterAddress,
      tpcwTaskTemplate.expId
    ).toJvmTask

    cluster.serviceScheduler.scheduleExperiment(scadsTasks ++ tpcwTasks :+ viewRefreshTask)

    logger.info("Experiment started on cluster %s", scadsCluster.root.canonicalAddress)

    new ComputationFuture[TpcwClient] {
      /**
       * The computation that should run with this future. Is guaranteed to only
       * be called at most once. Timeout hint is a hint for how long the user is
       * willing to wait for this computation to compute
       */
      protected def compute(timeoutHint: Long, unit: TimeUnit) = {
        scadsCluster.root.awaitChild("clusterReady")
        new TpcwClient(scadsCluster, new ParallelExecutor)
      }

      /**
       * Signals a request to cancel the computation. Is guaranteed to only be
       * called at most once.
       */
      protected def cancelComputation {}
    }
  }
}
