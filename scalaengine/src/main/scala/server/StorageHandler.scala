package edu.berkeley.cs.scads.storage

import net.lag.logging.Logger
import com.sleepycat.je
import je.{Cursor,Database, DatabaseConfig, DatabaseException, DatabaseEntry, Environment, LockMode, OperationStatus, Durability}

import edu.berkeley.cs.scads.comm._

import org.apache.avro.Schema
import edu.berkeley.cs.avro.runtime._

import org.apache.zookeeper.CreateMode

import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.{ Arrays => JArrays }

import scala.collection.JavaConversions._
import scala.collection.mutable.{ Set => MSet, HashSet, HashMap }

import org.apache.zookeeper.KeeperException.NodeExistsException
import transactions._
import transactions.conflict._
import transactions.mdcc._
import _root_.transactions.protocol.MDCCRoutingTable

object StorageHandler {
  val idGen = new AtomicLong
}

/**
 * Basic implementation of a storage handler using BDB as a backend.
 */
class StorageHandler(env: Environment, val root: ZooKeeperProxy#ZooKeeperNode, val name:Option[String] = None) 
  extends ServiceHandler[StorageMessage] {

  @volatile private var serverNode: ZooKeeperProxy#ZooKeeperNode = _

  val counterId = StorageHandler.idGen.getAndIncrement()

  def registry = StorageRegistry

  override def toString = 
    "<CounterID: %d, EnvDir: %s, Handle: %s>".format(counterId, env.getHome.getCanonicalPath, remoteHandle)

  /** Logger must be lazy so we can reference in startup() */
  protected lazy val logger = Logger()
  implicit def toOption[A](a: A): Option[A] = Option(a)

  /** Hashmap of currently open partition handler, indexed by partitionId.
   * Must also be lazy so we can reference in startup() */
  protected lazy val partitions = new ConcurrentHashMap[String, PartitionHandler]

  /**
   * Factory for NamespaceContext
   */
  object NamespaceContext {
    def apply(schema: Schema): NamespaceContext = {
      val comparator = new AvroComparator { val keySchema = schema }
      NamespaceContext(comparator, new HashSet[(String, PartitionHandler)])
    }
  }

  /**
   * Contains metadata for namespace
   */
  case class NamespaceContext(comparator: AvroComparator, partitions: MSet[(String, PartitionHandler)])

  /** 
   * Map of namespaces to currently open partitions for that namespace.
   * The values of this map (mutable sets) must be synchronized on before reading/writing 
   */
  protected lazy val namespaces = new ConcurrentHashMap[String, NamespaceContext]

  /* Register a shutdown hook for proper cleanup */
  class SDRunner(sh: ServiceHandler[_]) extends Thread {
    override def run(): Unit = {
      sh.stop
    }
  }
  java.lang.Runtime.getRuntime().addShutdownHook(new SDRunner(this))

  /** Points to the DB that can recreate partitions on restart.
   * Must also be lazy so we can reference in startup() */
  private lazy val partitionDb =
    makeDatabase("partitiondb", None, None)

    /**
     * Workload statistics thread periodically clears the stats from all partitions
     */
    private val period = 20 // seconds
    private val intervalsToSave = 3
    protected lazy val statsThread = new Thread(new StatsManager(period), "workload statistic clearing thread")
    statsThread.setDaemon(true)
    statsThread.start()
    
    class StatsManager(periodInSeconds:Int) extends Runnable {
      @volatile var running = true
      def run():Unit =
	while (running) {
	  Thread.sleep(periodInSeconds*1000)
	  logger.debug("starting stats clearing")
	  partitions.foreach(_._2.resetWorkloadStats)
	  logger.debug("finished stats clearing")
	}
      def stop() = running = false
    }

  // Stores latency info from everyone.
  // client -> (dc -> latency info list)
  private val latencyStats = new HashMap[String, scala.collection.Map[String, LatencyInfoList]]()

  // Stores latency info from everyone.
  // client -> (dc -> latency delay)
  private val latencyDelayStats = new HashMap[String, scala.collection.Map[String, LatencyDelayInfo]]()

  private def makeDatabase(databaseName: String, keySchema: Schema, txn: Option[je.Transaction]): Database =
    makeDatabase(databaseName, Some(new AvroBdbComparator(keySchema)), txn)

  private def makeDatabase(databaseName: String, keySchema: String, txn: Option[je.Transaction]): Database =
    makeDatabase(databaseName, Some(new AvroBdbComparator(keySchema)), txn)

  private def makeDatabase(databaseName: String, comparator: Option[Comparator[Array[Byte]]], txn: Option[je.Transaction]): Database = {
    val dbConfig = new DatabaseConfig
    dbConfig.setAllowCreate(true)
    comparator.foreach(comp => dbConfig.setBtreeComparator(comp))
    dbConfig.setTransactional(true)
    env.openDatabase(txn.orNull, databaseName, dbConfig)
  }

  private def keySchemaFor(namespace: String) = {
    val nsRoot = getNamespaceRoot(namespace)
    val keySchema = new String(nsRoot("keySchema").data)
    new Schema.Parser().parse(keySchema)
  }

  private def schemasAndValueClassFor(namespace: String) = {
    val nsRoot = getNamespaceRoot(namespace)
    val keySchema = new String(nsRoot("keySchema").data)
    val valueSchema = new String(nsRoot("valueSchema").data)
    val valueClass = new String(nsRoot("valueClass").data)
    (new Schema.Parser().parse(keySchema),new Schema.Parser().parse(valueSchema),valueClass)
  }

  private def makeBDBPendingUpdates(database: Database, namespace: String) = {
    val schemasvc = schemasAndValueClassFor(namespace)
    val nsRoot = getNamespaceRoot(namespace)
    val routingTable = new MDCCRoutingTable(nsRoot)

    val pu = new PendingUpdatesController(
      new BDBTxDB[Array[Byte], Array[Byte]](
        database,
        new ByteArrayKeySerializer[Array[Byte]],
        new ByteArrayValueSerializer[Array[Byte]]),
      new BDBTxDBFactory(database.getEnvironment),
      schemasvc._1, schemasvc._2, routingTable)

    nsRoot.get("valueICs").foreach(icBytes => {
      val reader = new AvroSpecificReaderWriter[FieldICList](None)
      pu.setICs(reader.deserialize(icBytes.data))
    })
    pu
  }

  /** 
   * Preconditions:
   *   (1) namespace is a valid namespace in zookeeper
   *   (2) keySchema is already set in the namespace/keySchema file
   *       in ZooKeeper
   *   (3) valueSchema is already set in the namespace/valueSchema file
   *       in ZooKeeper
   */
  private def makeBdbPartitionHandler(
      database: Database, namespace: String, partitionIdLock: ZooKeeperProxy#ZooKeeperNode,
      startKey: Option[Array[Byte]], endKey: Option[Array[Byte]], trxMgrType : String) = {
    val schemasvc = schemasAndValueClassFor(namespace)
    val storageMgr = new BdbStorageManager(database, partitionIdLock, startKey, endKey, getNamespaceRoot(namespace), schemasvc._1, schemasvc._2)
    val handler = new PartitionHandler(storageMgr)
    handler.trxManager =  makeTrxMgr(namespace, partitionIdLock,
      new BDBTxDB[Array[Byte], Array[Byte]](
        database,
        new ByteArrayKeySerializer[Array[Byte]],
        new ByteArrayValueSerializer[Array[Byte]]),
      trxMgrType,
      handler,
      storageMgr,
      makeBDBPendingUpdates(database, namespace)
    )
    handler
  }


  private def makeInMemPartitionHandler
    (namespace:String,partitionIdLock:ZooKeeperProxy#ZooKeeperNode,
     startKey:Option[Array[Byte]], endKey:Option[Array[Byte]], trxMgrType : String) = {
    val schemasvc = schemasAndValueClassFor(namespace)
    val storageMgr = new InMemStorageManager(partitionIdLock, startKey, endKey, getNamespaceRoot(namespace),schemasvc._1, schemasvc._2, schemasvc._3)
    val handler = new PartitionHandler(storageMgr)
    handler.trxManager = makeTrxMgr(namespace, partitionIdLock, null, trxMgrType, null, storageMgr, null)  //TODO create TxDB and factory
    handler
  }

  private def makeTrxMgr(namespace:String,
                         partitionIdLock:ZooKeeperProxy#ZooKeeperNode,
                         db: TxDB[Array[Byte], Array[Byte]],
                         trxMgrType : String,
                         handler : PartitionHandler,
                         storageMgr : StorageManager,
                         pu: PendingUpdates) : TrxManager = {
      //TODO The protocol should be a singleton per namespace not partitonHandler
    val schemasvc = schemasAndValueClassFor(namespace)
    val nsRoot = getNamespaceRoot(namespace)
    trxMgrType match {
      case "2PC" =>
        val partition = PartitionService(handler.remoteHandle, partitionIdLock.name, StorageService(remoteHandle))
        val defaultMeta = MDCCMetaDefault.getOrCreateDefault(nsRoot, partition, true)
        new Protocol2PCManager(pu, storageMgr, PartitionService(handler.remoteHandle, partitionIdLock.name, StorageService(remoteHandle)))
      case "MDCC" => {
        assert(db != null)
        ProtocolMDCCServer.createMDCCProtocol(
          namespace,
          nsRoot,
          db,
          PartitionService(handler.remoteHandle, partitionIdLock.name, StorageService(remoteHandle)),
          schemasvc._1,
          pu,
          true)
      }
      case _ =>
        val partition = PartitionService(handler.remoteHandle, partitionIdLock.name, StorageService(remoteHandle))
        val defaultMeta = MDCCMetaDefault.getOrCreateDefault(nsRoot, partition)
        null
    }
  }

  /** Iterator scans the entire cursor and does not close it */
  private implicit def cursorToIterator(cursor: Cursor): Iterator[(DatabaseEntry, DatabaseEntry)]
    = new Iterator[(DatabaseEntry, DatabaseEntry)] {
      private var cur = getNext()
      private def getNext() = {
        val tuple = (new DatabaseEntry, new DatabaseEntry)
        if (cursor.getNext(tuple._1, tuple._2, null) == OperationStatus.SUCCESS)
          Some(tuple)
        else
          None
      }
      override def hasNext = cur.isDefined
      override def next() = cur match {
        case Some(tup) =>
          cur = getNext()
          tup
        case None =>
          throw new IllegalStateException("No more elements")
      }
    }

  /**
   * Performs the following startup tasks:
   * * Register with zookeeper as an available server
   * * Reopen any partitions.
   */
  protected def startup(): Unit = {
    /* Register with the zookeper as an available server */
    val availServs = root.getOrCreate("availableServers")
    logger.debug("Created StorageHandler" + name.getOrElse(remoteHandle.toString))
    serverNode = availServs.createChild(name.getOrElse(remoteHandle.toString), StorageService(remoteHandle).toBytes, if (!name.isEmpty) CreateMode.EPHEMERAL else CreateMode.EPHEMERAL_SEQUENTIAL)

    /* Reopen partitions */
    val cursor = partitionDb.openCursor(null, null)
    cursor.map { case (key, value) =>
      (new String(key.getData), (classOf[CreatePartitionRequest].newInstance).parse(value.getData))
    } foreach { case (partitionId, request) =>

      logger.info("Recreating partition %s from request %s".format(partitionId, request))

      /* Grab partition root from ZooKeeper */
      val partitionsDir = getNamespaceRoot(request.namespace).apply("partitions")

      /* Create the lock file, assuming that it does not already exist (since
       * the lock files are created ephemerally, so if this node dies, all the
       * lock files should die accordingly) */
      val partitionIdLock =
        try {
          partitionsDir.createChild(partitionId)
        } catch {
          case e: NodeExistsException =>
            /* The lock file has not been removed yet. Assume for now that
             * this lock file belonged to this partition to begin with, and we
             * had a race condition where the ephemeral nodes were not removed
             * in time that the node started back up. Therefore, delete the
             * existing lock file and recreate it */
            logger.critical("Clobbering lock file! Namespace: %s, PartitionID: %s".format(request.namespace, partitionId))
            partitionsDir.deleteChild(partitionId)
            partitionsDir.createChild(partitionId)
        }
      assert(partitionIdLock.name == partitionId, "Lock file was not created with the same name on restore")

      // no need to grab locks below, because startup runs w/o any invocations
      // to process (so no races can occur)

      /* Make partition handler */
      val db      = makeDatabase(request.namespace, keySchemaFor(request.namespace), None)
      val handler = makeBdbPartitionHandler(db, request.namespace, partitionIdLock, request.startKey, request.endKey, request.trxProtocol)  //TODO we need to store the trx protocol type
			//val handler = makePartitionHandlerWithAC(db, acdb, request.namespace, partitionIdLock, request.startKey, request.endKey)

      /* Add to our list of open partitions */
      partitions.put(partitionId, handler)

      val ctx = getOrCreateContextForNamespace(request.namespace) 
      ctx.partitions += ((partitionId, handler))

    }
    cursor.close()
    
  }

  /**
   * WARNING: you must synchronize on the context for a namespace before
   * performaning any mutating operations (or to have the correct memory
   * read semantics)
   *
   * TODO: in the current implementation, once a namespace lock is created, it
   * remains for the duration of the JVM process (and is thus not eligible for
   * GC). this makes implementation easier (since we don't have to worry about
   * locks changing over time), but wastes memory
   */
  private def getOrCreateContextForNamespace(namespace: String) = {
    val test = namespaces.get(namespace)
    if (test ne null)
      test
    else {
      val ctx = NamespaceContext(keySchemaFor(namespace))
      Option(namespaces.putIfAbsent(namespace, ctx)) getOrElse ctx
    }
  }

  @inline private def getContextForNamespace(namespace: String) = 
    Option(namespaces.get(namespace))

  @inline private def removeNamespaceContext(namespace: String) = 
    Option(namespaces.remove(namespace))

  /**
   * Performs the following shutdown tasks:
   *   Shutdown all active partitions
   *   Closes the bdb environment
   */
  protected def shutdown(): Unit = {
    try serverNode.delete() catch {
      case e => logger.warning("failed to delete server node from zookeeper")
    }
    partitions.values.foreach(_.stop)
    partitions.clear()
    namespaces.clear()
    partitionDb.close()
    env.close()
  }

  private def getNamespaceRoot(namespace: String): ZooKeeperProxy#ZooKeeperNode =
    root("namespaces")
      .get(namespace)
      .getOrElse(throw new RuntimeException("Attempted to open namespace that doesn't exist in zookeeper: " + namespace))

  protected def process(src: Option[RemoteServiceProxy[StorageMessage]], msg: StorageMessage): Unit = {
    def reply(msg: StorageMessage) = src.foreach(_ ! msg)

    msg match {
      case createRequest @ CreatePartitionRequest(namespace, partitionType, startKey, endKey, trxProtocol) => {
        logger.info("[%s] CreatePartitionRequest for namespace %s, [%s, %s)", this, namespace, JArrays.toString(startKey.orNull), JArrays.toString(endKey.orNull))

        /* Grab root to namespace from ZooKeeper */
        val nsRoot = getNamespaceRoot(namespace)

        /* Grab a lock on the partitionId. 
         * TODO: Handle sequence wrap-around */
        val partitionIdLock = nsRoot("partitions").createChild(namespace, mode = CreateMode.EPHEMERAL_SEQUENTIAL)
        val partitionId = partitionIdLock.name

        //logger.info("Active partitions after insertion in ZooKeeper: %s".format(nsRoot("partitions").children.mkString(",")))

        logger.info("%d active partitions after insertion in ZooKeeper".format(nsRoot("partitions").children.size))

        val ctx = getOrCreateContextForNamespace(namespace) 

        /* For now, the creation of DBs under a namespace are executed
         * serially. It is assumed that a single node will not run multiple
         * storage handlers sharing the same namespaces (which allows us to
         * lock the namespace in memory. */
        // TODO: cleanup if fails
        val handler = ctx.synchronized {
          val handler =
            partitionType match {
              case "inmemory" => {
                /* Start a new transaction to atomically add an entry into the partition DB */
                val txn = env.beginTransaction(null, null)
                partitionDb.put(txn, new DatabaseEntry(partitionId.getBytes), new DatabaseEntry(createRequest.toBytes))
                txn.commit()
                makeInMemPartitionHandler(namespace,partitionIdLock, startKey, endKey, trxProtocol)
              }
              case "bdb" => {
                /* Start a new transaction to atomically make both the namespace DB,
                 * and add an entry into the partition DB */
                val txn = env.beginTransaction(null, null)

                /* Open the namespace DB */
                val newDb = makeDatabase(namespace, keySchemaFor(namespace), Some(txn))
                
	        /* Open a DB for access control info */
	        //val acDb = makeDatabase(namespace+"_ac", keySchemaFor(namespace), Some(txn))
                
                /* Log to partition DB for recreation */
                partitionDb.put(txn, new DatabaseEntry(partitionId.getBytes), new DatabaseEntry(createRequest.toBytes))
                
                /* for now, let errors propogate up to the exception handler */
                txn.commit()
                
                /* Make partition handler from request */
                makeBdbPartitionHandler(newDb, namespace, partitionIdLock, startKey, endKey, trxProtocol)
	        //val handler = makePartitionHandlerWithAC(newDb, acDb, namespace, partitionIdLock, startKey, endKey)
              }
              case _ => throw new RuntimeException("Invalid partition type specified in create partition request: "+partitionType)
            }
          /* Add to our list of open partitions */
          val test = partitions.put(partitionId, handler)
          assert(test eq null, "Partition ID was not unique: %s".format(partitionId))
          
          /* On success, add this partitionId to the ctx set */
          val succ = ctx.partitions.add((partitionId, handler))
          assert(succ, "Handler not successfully added to partitions")
          
          logger.info("[%s] %d active partitions after insertion on this StorageHandler".format(this, ctx.partitions.size))

          handler
        }

        logger.info("Partition %s in namespace %s created".format(partitionId, namespace))
        reply(CreatePartitionResponse(PartitionService(handler.remoteHandle, partitionId, StorageService(remoteHandle))))
      }
      case DeletePartitionRequest(partitionId) => {
        logger.info("Deleting partition " + partitionId)

        /* Get the handler and shut it down */
        val handler = Option(partitions.remove(partitionId)) getOrElse {reply(InvalidPartition(partitionId)); return}
        handler.stopListening

        handler.manager match {
          case bdbManager:BdbStorageManager => {
            val dbName = bdbManager.db.getDatabaseName /* dbName is namespace */
            val dbEnv  = bdbManager.db.getEnvironment

            //logger.info("Unregistering handler from MessageHandler for partition %s in namespace %s".format(partitionId, dbName))

            logger.info("[%s] Deleting partition [%s, %s) for namespace %s", this, JArrays.toString(bdbManager.startKey.orNull), JArrays.toString(bdbManager.endKey.orNull), dbName)

            val ctx = getContextForNamespace(dbName) getOrElse {
              /**
               * Race condition - a request to delete a namespace is going on
               * right now- this error can actually be safely ignored since this
               * partition will be deleted (in response to the namespace deletion
               * request)
               */
              reply(RequestRejected("Partition will be removed by a delete namespace request currently in progress", msg)); return
            }

            ctx.synchronized {
              val succ = ctx.partitions.remove((partitionId, handler))
              assert(succ, "Handler not successfully removed from partitions")

              /* Delete from partitionDB, and (possibly) delete the database in a
               * single transaction */
              val txn = env.beginTransaction(null, null)

              /* Remove from bdb map */
              partitionDb.delete(txn, new DatabaseEntry(partitionId.getBytes))

              if (ctx.partitions.isEmpty) {
                /* Remove database from environment- this removes all the data
                 * associated with the database */
                logger.info("[%s] Deleting database %s for namespace %s".format(this, dbName, dbName))
                bdbManager.db.close() /* Close underlying DB */
                dbEnv.removeDatabase(txn, dbName)
              } else {
                logger.info("[%s] Garbage collecting inaccessible keys, since %d partitions in namespace %s remain", this, ctx.partitions.size, dbName)

                implicit def orderedByteArrayView(thiz: Array[Byte]) = new Ordered[Array[Byte]] {
                  def compare(that: Array[Byte]) = ctx.comparator.compare(thiz, that) 
                }

                val thisPartition = bdbManager
                var thisSlice = Slice(thisPartition.startKey, thisPartition.endKey)
                ctx.partitions.foreach { t =>
                  t._2.manager match {
                    case thatPartition:BdbStorageManager => {                   
                      val thatSlice = Slice(thatPartition.startKey, thatPartition.endKey)
                      thisSlice = thisSlice.remove(thatSlice) /* Remove (from deletion) slice which cannot be deleted */
                    }
                    case _ =>
                      logger.warning("Can't properly garbage collect when namespace mixes Bdb and other partitions: "+t._2)
                  }
                }
                thisSlice.foreach((startKey, endKey) => {
                  logger.info("++ [%s] Deleting range: [%s, %s)", this, JArrays.toString(startKey.orNull), JArrays.toString(endKey.orNull))
                  bdbManager.deleteRange(startKey, endKey, txn)
                })
              }

              txn.commit()
            }
          }
          case _ => {
            handler.stop
            reply(RequestRejected("Unknown StorageManager type.  Cannot delete partition", msg))
            return
          }
        }

        handler.stop

        reply(DeletePartitionResponse())
      }
      case GetPartitionsRequest() => {
        reply(GetPartitionsResponse(partitions.toList.map( a => PartitionService(a._2.remoteHandle,a._1, StorageService(remoteHandle)))))

      }
      case DeleteNamespaceRequest(namespace) => {

        val ctx = removeNamespaceContext(namespace).getOrElse { reply(InvalidNamespace(namespace)); return }

        logger.info("WARNING: About to delete namespace %s! All information and metadata will be lost!", namespace)

        ctx.synchronized {
          val txn = env.beginTransaction(null, null)
          ctx.partitions.foreach { case (partitionId, handler) => 
            partitions.remove(partitionId) /* remove from map */
            handler.stop /* Closes DB handle */
            logger.info("Deleting metadata for partition %s", partitionId)
            partitionDb.delete(txn, new DatabaseEntry(partitionId.getBytes)) 
          }
          env.removeDatabase(txn, namespace)
          txn.commit()
        }

        reply(DeleteNamespaceResponse())
      }
      case ShutdownStorageHandler() => System.exit(0)
      case LatencyPing(m, d) =>
        val sname = src.get.host
        latencyStats.put(sname, m)
        latencyDelayStats.put(sname, d)
        reply(LatencyPingResponse(latencyStats, latencyDelayStats))
      case _ => reply(RequestRejected("StorageHandler can't process this message type", msg))
    }
  }
}
