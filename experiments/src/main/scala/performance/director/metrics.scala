package scads.director

import java.util.Date

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

import radlab.metricservice._

import org.apache.thrift._
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;

object PerformanceMetrics {
	def load(metricReader:MetricReader, server:String, reqType:String):PerformanceMetrics = {
		// FIX: handling of the dates
		val (date0, workload) = metricReader.getSingleMetric(server, "workload", reqType)
		val (date1, latencyMean) = metricReader.getSingleMetric(server, "latency_mean", reqType)
		val (date2, latency90p) = metricReader.getSingleMetric(server, "latency_90p", reqType)
		val (date3, latency99p) = metricReader.getSingleMetric(server, "latency_99p", reqType)
		new PerformanceMetrics(date0,metricReader.interval.toInt,workload,latencyMean,latency90p,latency99p)
	}
	
	def estimateFromSamples(samples:List[Double], time:Date, aggregationInterval:Int):PerformanceMetrics = {
		val workload = computeWorkload(samples)/aggregationInterval
		val latencyMean = computeMean(samples)
		val latency90p = computeQuantile(samples,0.9)
		val latency99p = computeQuantile(samples,0.99)
		PerformanceMetrics(time, aggregationInterval, workload, latencyMean, latency90p, latency99p)
	}
	
	private def computeWorkload( data:List[Double] ): Double = if (data==Nil||data.size==0) Double.NaN else data.length
	private def computeMean( data:List[Double] ): Double = if (data==Nil) Double.NaN else data.reduceLeft(_+_)/data.length
    private def computeQuantile( data:List[Double], q:Double): Double = if (data==Nil) Double.NaN else data.sort(_<_)( Math.floor(data.length*q).toInt )
}
case class PerformanceMetrics(
	val time: Date,
	val aggregationInterval: Int,  // in seconds
	val workload: Double,
	val latencyMean: Double,
	val latency90p: Double,
	val latency99p: Double
) {
	override def toString():String = time+" w="+"%.2f".format(workload)+" lMean="+"%.2f".format(latencyMean)+" l90p="+"%.2f".format(latency90p)+" l99p="+"%.2f".format(latency99p)
	def toShortLatencyString():String = "%.0f".format(latencyMean)+"/"+"%.0f".format(latency90p)+"/"+"%.0f".format(latency99p)
	
	def createMetricUpdates(server:String, requestType:String):List[MetricUpdate] = {
		var metrics = new scala.collection.mutable.ListBuffer[MetricUpdate]()
		metrics += new MetricUpdate(time.getTime,new MetricDescription("scads",s2jMap(Map("server"->server,"request_type"->requestType,"stat"->"workload"))),workload.toString)
		metrics += new MetricUpdate(time.getTime,new MetricDescription("scads",s2jMap(Map("server"->server,"request_type"->requestType,"stat"->"latency_mean"))),latencyMean.toString)
		metrics += new MetricUpdate(time.getTime,new MetricDescription("scads",s2jMap(Map("server"->server,"request_type"->requestType,"stat"->"latency_90p"))),latency90p.toString)
		metrics += new MetricUpdate(time.getTime,new MetricDescription("scads",s2jMap(Map("server"->server,"request_type"->requestType,"stat"->"latency_99p"))),latency99p.toString)
		metrics.toList
	}
	
	private def s2jMap[K,V](map:Map[K,V]): java.util.HashMap[K,V] = {	
		var jm = new java.util.HashMap[K,V]()
		map.foreach( t => jm.put(t._1,t._2) )
		jm
	}	
}

case class MetricReader(
	val host: String,
	val db: String,
	val interval: Double,
	val report_prob: Double
) {
	val port = 6000
	val user = "root"
	val pass = ""
	
	var connection = Director.connectToDatabase
	initDatabase
	
	def initDatabase() {
        // create database if it doesn't exist and select it
        try {
            val statement = connection.createStatement
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS " + db)
            statement.executeUpdate("USE " + db)
       	} catch { case ex: SQLException => ex.printStackTrace() }
    }

	def getWorkload(host:String):Double = {
		if (connection == null) connection = Director.connectToDatabase
		val workloadSQL = "select time,value from scads,scads_metrics where scads_metrics.server=\""+host+"\" and request_type=\"ALL\" and stat=\"workload\" and scads.metric_id=scads_metrics.id order by time desc limit 10"
		var value = Double.NaN
        val statement = connection.createStatement
		try {
			val result = statement.executeQuery(workloadSQL)
			val set = result.first // set cursor to first row
			if (set) value = (result.getLong("value")/interval/report_prob).toDouble
       	} catch { case ex: SQLException => println("Couldn't get workload"); ex.printStackTrace() }
		finally {statement.close}
		value
	}
	
	def getSingleMetric(host:String, metric:String, reqType:String):(java.util.Date,Double) = {
		if (connection == null) connection = Director.connectToDatabase
		val workloadSQL = "select time,value from scads,scads_metrics where scads_metrics.server=\""+host+"\" and request_type=\""+reqType+"\" and stat=\""+metric+"\" and scads.metric_id=scads_metrics.id order by time desc limit 1"
		var time:java.util.Date = null
		var value = Double.NaN
        val statement = connection.createStatement
		try {
			val result = statement.executeQuery(workloadSQL)
			val set = result.first // set cursor to first row
			if (set) {
				time = new java.util.Date(result.getLong("time"))
				value = if (metric=="workload") (result.getString("value").toDouble/interval/report_prob) else result.getString("value").toDouble
			}
       	} catch { case ex: SQLException => Director.logger.warn("SQL exception in metric reader",ex)}
		finally {statement.close}
		(time,value)
	}
	
	def getAllServers():List[String] = {
		if (connection == null) connection = Director.connectToDatabase
		val workloadSQL = "select distinct server from scads_metrics"
		var servers = new scala.collection.mutable.ListBuffer[String]()
        val statement = connection.createStatement
		try {
			val result = statement.executeQuery(workloadSQL)
			while (result.next) servers += result.getString("server")
       	} catch { case ex: SQLException => Director.logger.warn("SQL exception in metric reader",ex)}
		finally {statement.close}
		servers.toList
	}

}


case class ThriftMetricDBConnection (
	val host: String,
	val port: Int
) {
	val metricService = connectToMetricService(host,port)
	
	def connectToMetricService(host:String, port:Int): MetricServiceAPI.Client = {
		System.err.println("using MetricService at "+host+":"+port)
	
		var metricService: MetricServiceAPI.Client = null
		
        while (metricService==null) {
            try {
                val metricServiceTransport = new TSocket(host,port);
                val metricServiceProtocol = new TBinaryProtocol(metricServiceTransport);

                metricService = new MetricServiceAPI.Client(metricServiceProtocol);
                metricServiceTransport.open();
                System.err.println("connected to MetricService")

            } catch {
            	case e:Exception => {
	                e.printStackTrace
	                println("can't connect to the MetricService, waiting 60 seconds");
	                try {
	                    Thread.sleep(60 * 1000);
	                } catch {
	                    case e1:Exception => e1.printStackTrace()
	                }
                }
            }
        }
        metricService
	}	
	
}