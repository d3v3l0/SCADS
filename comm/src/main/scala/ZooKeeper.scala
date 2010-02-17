package edu.berkeley.cs.scads.comm

import org.apache.zookeeper.server.ZooKeeperServerMain
import org.apache.log4j.Logger


object ZooKeep {
	val logger = Logger.getLogger("scads.test")

	def start(port: Int): ZooKeeperProxy = start("zookeeper", port)
  def start(path: String, port: Int): ZooKeeperProxy = {
    val thread = new Thread() {
      override def run() = ZooKeeperServerMain.main(Array[String](port.toString, path))
    }
    thread.start()

		var connected = false
		while(!connected) {
			try {
				val s = new java.net.Socket("localhost", 2181)
				connected = true
			}
			catch {
				case ce: java.net.ConnectException => {
					logger.info("Connection to zookeeper failed, waiting")
				}
			}
			Thread.sleep(5000)
		}

    new ZooKeeperProxy("localhost:" + port)
  }
}
