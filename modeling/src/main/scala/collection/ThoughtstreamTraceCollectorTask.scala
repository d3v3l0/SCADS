package edu.berkeley.cs
package scads
package piql
package modeling

import deploylib.mesos._
import deploylib.ec2._
import comm._
import storage._
import piql._
import perf._
import avro.marker._
import avro.runtime._

import net.lag.logging.Logger
import java.io.File
import java.net._

import scala.collection.JavaConversions._
import scala.collection.mutable._

case class ThoughtstreamTraceCollectorTask(
  var params: RunParams
) extends AvroTask with AvroRecord {
  var beginningOfCurrentWindow = 0.toLong
  
  def run(): Unit = {
    val logger = net.lag.logging.Logger()

    /* set up cluster */
    logger.info("setting up cluster...")
    val clusterRoot = ZooKeeperNode(params.clusterParams.clusterAddress)
    val cluster = new ExperimentalScadsCluster(clusterRoot)
    cluster.blockUntilReady(params.clusterParams.numStorageNodes)

    // set up subscription to notifications
    val coordination = clusterRoot.getOrCreate("coordination")
    val clientId = coordination.registerAndAwait("traceCollectorStart", params.numTraceCollectors)

    /* create executor that records trace to fileSink */
    logger.info("creating executor...")
    val fileSink = new FileTraceSink(new File("/mnt/piqltrace.avro"))
    //implicit val executor = new ParallelExecutor with TracingExecutor {
    implicit val executor = new LocalUserExecutor with TracingExecutor {
      val sink = fileSink
    }

    /* Register a listener that will record all messages sent/recv to fileSink */
    logger.info("registering listener...")
    val messageTracer = new MessagePassingTracer(fileSink)
    MessageHandler.registerListener(messageTracer)

    // TODO:  make this work for either generic or scadr
    val queryRunner = new ScadrQuerySpecRunner(params)
    queryRunner.setupNamespacesAndCreateQuery(cluster)  // creates thoughtstream query

    // initialize window
    beginningOfCurrentWindow = System.nanoTime
            
    // set up thoughtstream-specific run params
    val numSubscriptionsPerUserList = List(100,300,500)
    val numPerPageList = List(10,30,50)
    //val numSubscriptionsPerUserList = List(100)
    //val numPerPageList = List(10)
    
    // warmup to avoid JITing effects
    // TODO:  move this to a function
    logger.info("beginning warmup...")
    fileSink.recordEvent(WarmupEvent(params.warmupLengthInMinutes, true))
    var queryCounter = 1
    
    while (withinWarmup) try {
      fileSink.recordEvent(QueryEvent(params.queryType + queryCounter, true))

      queryRunner.callThoughtstream(numSubscriptionsPerUserList.head, numPerPageList.head)

      fileSink.recordEvent(QueryEvent(params.queryType + queryCounter, false))
      Thread.sleep(params.sleepDurationInMs)
      queryCounter += 1
    } catch {
      case e => logger.error(e, "Query Execution Failed")
    }
    fileSink.recordEvent(WarmupEvent(params.warmupLengthInMinutes, false))


    // wait for each trace collector to start up
    coordination.registerAndAwait("doneWithWarmup", params.numTraceCollectors)
    Thread.sleep(5000)  // just to make sure all warmup queries are finished
  
        
    /* Run some queries */
    logger.info("beginning run...")
    
    numSubscriptionsPerUserList.foreach(numSubs => {
      fileSink.recordEvent(ChangeNamedCardinalityEvent("numSubscriptions", numSubs))
      logger.info("numSubscriptions = " + numSubs)

      numPerPageList.foreach(numPerPage => {
        logger.info("numPerPage = " + numPerPage)
        fileSink.recordEvent(ChangeNamedCardinalityEvent("numPerPage", numPerPage))
        
        (1 to params.numQueriesPerCardinality).foreach(i => {
	  try {
            fileSink.recordEvent(QueryEvent(params.queryType + i + "-" + numSubs + "-" + numPerPage, true))
            queryRunner.callThoughtstream(numSubs, numPerPage)
            fileSink.recordEvent(QueryEvent(params.queryType + i + "-" + numSubs + "-" + numPerPage, false))
	  } catch {
	    case e => logger.error(e, "Query execution failed")
	  }
          Thread.sleep(params.sleepDurationInMs)
        })
      })
    })
    
    //Flush trace messages to the file
    logger.info("flushing messages to file...")
    fileSink.flush()

    // Upload file to S3
    logger.info("uploading data...")
    TraceS3Cache.uploadFile("/mnt/piqltrace.avro", params.binPrefix, "client" + clientId)
    
    // Publish to SNSClient
    val currentTimeString = System.currentTimeMillis().toString
    
    coordination.registerAndAwait("doneWithRun", params.numTraceCollectors)
    if (clientId == 0)
      ExperimentNotification.completions.publish("experiment completed:" + params.binPrefix, "client " + clientId + "\n" + params.toString)
    
    logger.info("Finished with trace collection.")
  }
  
  def convertMinutesToNanoseconds(minutes: Int): Long = {
    minutes.toLong * 60.toLong * 1000000000.toLong
  }

  def withinWarmup: Boolean = {
    val currentTime = System.nanoTime
    currentTime < beginningOfCurrentWindow + convertMinutesToNanoseconds(params.warmupLengthInMinutes)
  }
}
