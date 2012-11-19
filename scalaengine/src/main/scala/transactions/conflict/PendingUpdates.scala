package edu.berkeley.cs.scads.storage
package transactions
package conflict

import actors.threadpool.ThreadPoolExecutor.AbortPolicy
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import scala.collection.mutable.ListBuffer
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap

import java.io._
import org.apache.avro.Schema
import edu.berkeley.cs.avro.marker._
import edu.berkeley.cs.scads.util.Logger
import _root_.transactions.protocol.MDCCRoutingTable

// TODO: Make thread-safe.  It might already be, by using TxDB

object Status extends Enumeration {
  type Status = Value
  val Commit, Abort, Unknown, Accept, Reject = Value
}

trait DBRecords {
  val db: TxDB[Array[Byte], Array[Byte]]
  val factory: TxDBFactory
}

trait PendingUpdates extends DBRecords {
  //TODO: Gene all the operations should be ex

  // Even on abort, store the xid and always abort it afterwards --> Check
  // NoOp property
  // Is only allowed to accept, if the operation will be successful, even
  // if all outstanding Cmd might be NullOps
  // If accepted, returns all the cstructs for all the keys.  Otherwise, None
  // is returned.
  // If dbTxn is non null, it is used for all db operations, and the commit is
  // NOT performed at the end.  Otherwise, a new transaction is started and
  // committed.
  def acceptOptionTxn(xid: ScadsXid, updates: Seq[RecordUpdate], dbTxn: TransactionData = null, isFast: Boolean = false, forceNonPending: Boolean = false) : (Boolean, Seq[(Array[Byte], CStruct)])

  def acceptOption(xid: ScadsXid, update: RecordUpdate, isFast: Boolean = false, forceNonPending: Boolean = false)(implicit dbTxn: TransactionData): (Boolean, Array[Byte], CStruct)


  /**
   * The transaction was successful (we will never decide otherwise)
   */
  // DO NOT hold db locks while calling this.
  def commit(xid: ScadsXid): Boolean

  /**
   * The transaction was learned as aborted (we will never decide otherwise)
   */
  // DO NOT hold db locks while calling this.
  def abort(xid: ScadsXid): Boolean

  /**
   * Writes the new truth. Should only return false if something is messed up with the db
   */
  def overwrite(key: Array[Byte], safeValue: CStruct, meta: Option[MDCCMetadata], committedXids: Seq[ScadsXid], abortedXids: Seq[ScadsXid], isFast: Boolean = false)(implicit dbTxn: TransactionData): Boolean

  def overwriteTxn(key: Array[Byte], safeValue: CStruct, meta: Option[MDCCMetadata], committedXids: Seq[ScadsXid], abortedXids: Seq[ScadsXid], dbTxn: TransactionData = null, isFast: Boolean = false): Boolean

  def getDecision(xid: ScadsXid, key: Array[Byte]): Option[Boolean]

  // Must call this after acceptOption, in order to add updates to xid.
  // DO NOT hold db locks while calling this.
  def txStatusAccept(xid: ScadsXid, updates: Seq[RecordUpdate], success: Boolean)
  // Atomically updates the status, while getting the list of updates for xid.
  // DO NOT hold db locks while calling this.
  def updateAndGetTxStatus(xid: ScadsXid, status: Status.Status): TxStatusEntry

  // DO NOT hold db locks while calling this.
  def getCStruct(key: Array[Byte]): CStruct

  def addEarlyCommit(xid: ScadsXid, status: Boolean)

  def startup() = {}

  def shutdown() = {}

  def setICs(ics: FieldICList)

  def getConflictResolver : ConflictResolver

  val routingTable: MDCCRoutingTable
}

case class UpdateEntry(var update: RecordUpdate,
                       // 0: free, 1: updating, 2: done
                       var lockStatus: Int) extends Serializable with AvroRecord {
}
// Status of a transaction.  Stores all the updates in the transaction.
// status is the toString() of the enum.
case class TxStatusEntry(var status: String,
                         var updates: ArrayBuffer[UpdateEntry]) extends Serializable with AvroRecord {
  // Is ArrayBuffer append thread safe?
  def appendUpdate(update: RecordUpdate) = {
    updates.synchronized {
      updates.append(UpdateEntry(update, 0))
    }
  }
  def getUpdates() = {
    updates.synchronized {
      updates.toList
    }
  }
}

case class PendingStateInfo(var state: Array[Byte],
                            var xids: List[List[ScadsXid]]) extends Serializable with AvroRecord
case class PendingCommandsInfo(var base: Option[Array[Byte]],
                               var commands: ArrayBuffer[CStructCommand],
                               var states: ArrayBuffer[PendingStateInfo]) extends Serializable with AvroRecord {

  def getCommand(xid: ScadsXid): Option[CStructCommand] = {
    commands.find(x => x.xid == xid)
  }

  def appendCommand(command: CStructCommand) = {
    if (false) {
      val logger = Logger(classOf[PendingCommandsInfo])
      logger.debug("cstruct append: %s commands: %s", command, this)
    }
    commands.append(command)
  }

  def removeCommand(xid: ScadsXid) = {
    val index = commands.indexWhere(x => x.xid == xid)
    if (index >= 0) {
      commands.remove(index)
      // Copied from abortCommand()
      // Update the states and the xid lists to reflect this abort.
      states = states.filter(x => x.xids.exists(y => !y.contains(xid))).map(x => {
        x.xids = x.xids.filterNot(y => y.contains(xid))
        x})
    }
  }

  def replaceCommand(command: CStructCommand) = {
    // TODO: Linear search for xid.  Store hash for performance?
    val index = commands.indexWhere(x => x.xid == command.xid)
    if (index == -1) {
      // This commmand was not pending.
      val logger = Logger(classOf[PendingCommandsInfo])
      logger.debug("cstruct replace3: %s commands: %s", command, this)

      appendCommand(command)
    } else if (commands(index).pending) {
      // Only update the existing command if it was pending.

      if (false) {
        val logger = Logger(classOf[PendingCommandsInfo])
        logger.debug("cstruct replace: %s commands: %s", command, this)
      }

      commands.update(index, command)
    } else {
      val logger = Logger(classOf[PendingCommandsInfo])
      logger.debug("cstruct replace2: %s commands: %s", command, this)
    }
  }

  // Correct flags for the command should already be set.
  def commitCommand(command: CStructCommand, logicalUpdater: LogicalRecordUpdater) = {
    // TODO: Linear search for xid.  Store hash for performance?
    val index = commands.indexWhere(x => x.xid == command.xid)
    if (index == -1) {
      // This commmand was not pending.
      commands.append(command)
      // Update all the states to include this command.  Only the states
      // have to change.
      val deltaBytes = command.command match {
        case LogicalUpdate(_, delta) => MDCCRecordUtil.fromBytes(delta).value
        case _ => None
      }

      if (deltaBytes.isDefined) {
        states = states.map(x => {
          x.state = logicalUpdater.applyDeltaBytes(Option(x.state), deltaBytes)
          x})
      }
    } else {
      // TODO(gpang): move this to the end of the list for correct ordering?

      if (false) {
        val logger = Logger(classOf[PendingCommandsInfo])
        logger.debug("cstruct commit: %s commands: %s", command, this)
      }

      commands.update(index, command)
      // Update all the states and the xid lists.
      val deltaBytes = command.command match {
        case LogicalUpdate(_, delta) => MDCCRecordUtil.fromBytes(delta).value
        case _ => None
      }

      if (deltaBytes.isDefined) {
        if (states.size == 1) {
          states = new ArrayBuffer[PendingStateInfo]
        } else if (states.size > 1) {
          states = states.filter(x => x.xids.exists(y => !y.contains(command.xid))).map(x => {
            x.xids = x.xids.filterNot(y => y.contains(command.xid))
            x.state = logicalUpdater.applyDeltaBytes(Option(x.state), deltaBytes)
            x})
        }
      }
    }
  }

  // Correct flags for the command should already be set.
  def abortCommand(command: CStructCommand) = {
    // TODO: Linear search for xid.  Store hash for performance?
    val index = commands.indexWhere(x => x.xid == command.xid)
    if (index != -1) {
      // Update existing command to abort.

      if (false) {
        val logger = Logger(classOf[PendingCommandsInfo])
        logger.debug("cstruct abort: %s commands: %s", command, this)
      }

      commands.update(index, command)

      // Update the states and the xid lists to reflect this abort.
      states = states.filter(x => x.xids.exists(y => !y.contains(command.xid))).map(x => {
        x.xids = x.xids.filterNot(y => y.contains(command.xid))
        x})
    } else {
      // This command was not previously pending.  Append the aborted status.
      commands.append(command)
    }
  }

  def updateStates(newStates: Seq[PendingStateInfo]) = {
    states.clear
    states ++= newStates
  }
}

class AccessStats() {
  var buckets = new ListBuffer[AccessStatsBucket]()
  // create a bucket in constructor.
  buckets.prepend(AccessStatsBucket(System.currentTimeMillis / AccessStatsConstants.bucketSize, 1))

  // add an access to the statistics.
  def addAccess() = {
    val millis = System.currentTimeMillis
    val bucket = millis / AccessStatsConstants.bucketSize
    buckets.headOption match {
      case None =>
        // no buckets, so add the first.
        buckets.prepend(AccessStatsBucket(bucket, 1))
      case Some(b) =>
        if (b.bucket == bucket) {
          // update the current bucket.
          b.num = b.num + 1
        } else {
          // this update does not belong to current bucket. prepend a new one.
          buckets.prepend(AccessStatsBucket(bucket, 1))
          if (buckets.length > AccessStatsConstants.numBuckets) {
            buckets = buckets.take(AccessStatsConstants.numBuckets)
          }
        }
    }
  }

  def getMetadataStatistics = Statistics(buckets)
}

class PendingUpdatesController(override val db: TxDB[Array[Byte], Array[Byte]],
                               override val factory: TxDBFactory,
                               val keySchema: Schema,
                               val valueSchema: Schema,
                               val routingTable: MDCCRoutingTable) extends PendingUpdates {

  protected val logger = Logger(classOf[PendingUpdatesController])

  // Transaction state info. Maps txid -> txstatus/decision.
  private val txStatus = new ConcurrentHashMap[ScadsXid, TxStatusEntry](100000, 0.75f, 100)
  // CStructs per key.
  private val pendingCStructs = new ConcurrentHashMap[List[Byte], PendingCommandsInfo](100000, 0.75f, 100)

  private val committedXidMap = new ConcurrentHashMap[(ScadsXid, Int), Boolean](1000000, 0.75f, 1000)

  // access statistics.
  private val rowStats = new ConcurrentHashMap[List[Byte], AccessStats](100000, 0.75f, 100)

  // Detects conflicts for new updates.
  private var newUpdateResolver = new NewUpdateResolver(keySchema, valueSchema, valueICs, committedXidMap)

  private val logicalRecordUpdater = new LogicalRecordUpdater(valueSchema)

  private var valueICs: FieldICList = null

  private var conflictResolver: ConflictResolver = null

  private val avroUtil = new IndexedRecordUtil(valueSchema)

  def setICs(ics: FieldICList) = {
    valueICs = ics
    newUpdateResolver = new NewUpdateResolver(keySchema, valueSchema, valueICs, committedXidMap)
    conflictResolver = new ConflictResolver(valueSchema, valueICs)
    println("ics: " + valueICs)
  }

  def updateRowStats(key: Array[Byte]) = {
    val prev = rowStats.putIfAbsent(key.toList, new AccessStats())
    if (prev != null) {
      // already existed.
      prev.addAccess()
    }
  }

  def addStatsToMetadata(key: Array[Byte], meta: MDCCMetadata) = {
    val stats = rowStats.get(key.toList) match {
      case null =>
        None
      case s =>
        Some(s.getMetadataStatistics)
    }
    meta.statistics = stats
    meta
  }

  // If accept was successful, returns the cstruct.  Otherwise, returns None.
  override def acceptOption(xid: ScadsXid, update: RecordUpdate, isFast: Boolean = false, forceNonPending: Boolean = false)(implicit dbTxn : TransactionData): (Boolean, Array[Byte], CStruct) = {
    val result = acceptOptionTxn(xid, update :: Nil, dbTxn, isFast, forceNonPending)
    (result._1, result._2.head._1, result._2.head._2)
  }

  // Returns a tuple (success, list of (key, cstruct) pairs)
  // TODO: Handle duplicate accept messages?
  override def acceptOptionTxn(xid: ScadsXid, updates: Seq[RecordUpdate], dbTxn: TransactionData = null, isFast: Boolean = false, forceNonPending: Boolean = false): (Boolean, Seq[(Array[Byte], CStruct)]) = {
    var success = true

    val startT = System.nanoTime / 1000000

    val txn = dbTxn match {
      case null => db.txStart()
      case x => x
    }

    val endT2 = System.nanoTime / 1000000
    var cstructs: Seq[(Array[Byte], CStruct)] = Nil
    try {
      cstructs = updates.map(r => {

        val endT3 = System.nanoTime / 1000000

        val ignore = if (committedXidMap.containsKey((xid, Arrays.hashCode(r.key)))) {
          // If this transaction for this record already committed or aborted
          // earlier, do not process this option again.
          true
        } else {
          false
        }
        val endT4 = System.nanoTime / 1000000

        if (success) {

          if (false) {
            // TODO: not sure if this is necessary yet, so not enabled yet.
            val defaultRecMeta = r match {
              case LogicalUpdate(_, d) => MDCCRecordUtil.fromBytes(d).metadata
              case ValueUpdate(_, _, d) => MDCCRecordUtil.fromBytes(d).metadata
              case VersionUpdate(_, d) => MDCCRecordUtil.fromBytes(d).metadata
            }
            val endT5 = System.nanoTime / 1000000
            val newRec = MDCCRecordUtil.toBytes(MDCCRecord(None, defaultRecMeta))
            val endT6 = System.nanoTime / 1000000

            // Put a default record for the record.  Not in a transaction.
            // with null txn, times out.
            // TODO(gpang): need some sort of locking for the record???
            db.putNoOverwrite(txn, r.key, newRec)
            val endT7 = System.nanoTime / 1000000

            val storedMDCCRec = Some(db.getOrPut(txn, r.key, newRec)).map(MDCCRecordUtil.fromBytes(_))
          }
          val endT5 = System.nanoTime / 1000000
          val endT6 = System.nanoTime / 1000000
          val endT7 = System.nanoTime / 1000000

          val storedMDCCRec: Option[MDCCRecord] =
            db.get(txn, r.key).map(MDCCRecordUtil.fromBytes(_))
          val storedRecValue: Option[Array[Byte]] =
            storedMDCCRec match {
              case Some(v) => v.value
              case None => None
            }
          val endT8 = System.nanoTime / 1000000

          val defaultInfo =
            PendingCommandsInfo(storedRecValue,
                                new ArrayBuffer[CStructCommand],
                                new ArrayBuffer[PendingStateInfo])
          var commandsInfo = pendingCStructs.putIfAbsent(r.key.toList, defaultInfo)
          if (commandsInfo == null) commandsInfo = defaultInfo

          val endT9 = System.nanoTime / 1000000
          if (!ignore) {
            val numServers = routingTable.serversForKey(r.key).size

            val endT10 = System.nanoTime / 1000000

/*
            // Add the updates to the pending list, if compatible.
            // This worked well for fast and micro-benchmark.
            if (commandsInfo.getCommand(xid).isDefined) {
              // Already in the cstruct.
              logger.debug("Update is already in cstruct %s key:%s xid: %s %s %s %s", Thread.currentThread.getName, (new mdcc.ByteArrayWrapper(r.key)).hashCode(), xid, commandsInfo, storedMDCCRec, r)
            } else if (newUpdateResolver.isCompatible(xid, commandsInfo, storedMDCCRec, commandsInfo.base, r, numServers, isFast)) {

*/

/*
            // Add the updates to the pending list, if compatible.
            // This worked well for classic rounds with physical, Megastore.
            if (commandsInfo.getCommand(xid).isDefined) {
              // Already in the cstruct.
              // Try removing the old command to make a new decision.
              commandsInfo.removeCommand(xid)
              logger.debug("Update is already in cstruct. remove it and try check. %s key:%s xid: %s %s %s %s", Thread.currentThread.getName, (new mdcc.ByteArrayWrapper(r.key)).hashCode(), xid, commandsInfo, storedMDCCRec, r)
            }

            if (newUpdateResolver.isCompatible(xid, commandsInfo, storedMDCCRec, commandsInfo.base, r, numServers, isFast)) {

*/

            // Add the updates to the pending list, if compatible.
            // This worked well for fast and micro-benchmark.
            if (commandsInfo.getCommand(xid).isDefined) {
              // Already in the cstruct.
              logger.debug("Update is already in cstruct %s key:%s xid: %s %s %s %s", Thread.currentThread.getName, (new mdcc.ByteArrayWrapper(r.key)).hashCode(), xid, commandsInfo, storedMDCCRec, r)
            } else if (newUpdateResolver.isCompatible(xid, commandsInfo, storedMDCCRec, commandsInfo.base, r, numServers, isFast)) {

              logger.debug("Update is compatible %s %s key:%s xid: %s %s %s %s", db.getName, Thread.currentThread.getName, (new mdcc.ByteArrayWrapper(r.key)).hashCode(), xid, commandsInfo, storedMDCCRec, r)

              commandsInfo.appendCommand(CStructCommand(xid, r, true, true))
            } else {
              logger.debug("Update is not compatible %s %s key:%s xid: %s %s %s %s forceNonPending: %s", db.getName, Thread.currentThread.getName, (new mdcc.ByteArrayWrapper(r.key)).hashCode(), xid, commandsInfo, storedMDCCRec, r, forceNonPending)
              success = false
            }

            val endT = System.nanoTime / 1000000
            // TODO: debugging
            if (endT - startT >= 35) {
              logger.debug("slow accept: xid: %s key:%s [%s, %s, %s, %s, %s, %s, %s, %s, %s, %s] acceptTime: %s", xid, (new mdcc.ByteArrayWrapper(r.key)).hashCode(), (endT2 - startT), (endT3 - endT2), (endT4 - endT3), (endT5 - endT4), (endT6 - endT5), (endT7 - endT6), (endT8 - endT7), (endT9 - endT8), (endT10 - endT9), (endT - endT10), (endT - startT))
            }

          }

//          logger.debug("ACCEPT xid: %s %s key:%s ignore: %s storedRecValue: %s commands: %s", xid, Thread.currentThread.getName, (new mdcc.ByteArrayWrapper(r.key)).hashCode(), ignore, storedRecValue, commandsInfo)

          updateRowStats(r.key)

          (r.key, CStruct(commandsInfo.base, commandsInfo.commands))
        } else {
          updateRowStats(r.key)
          (null, null)
        }
      })
    } catch {
      case e: Exception => {
        logger.debug("acceptOptionTxn Exception %s xid: %s %s", Thread.currentThread.getName, xid, e)
        e.printStackTrace()
        success = false
      }
    }
    if (success) {
      if (dbTxn == null) {
        db.txCommit(txn)
      }
    } else {
      cstructs = updates.map(r => {
        val ignore = if (committedXidMap.containsKey((xid, Arrays.hashCode(r.key)))) {
          // If this transaction for this record already committed or aborted
          // earlier, do not process this option again.
          true
        } else {
          false
        }

        val storedMDCCRec: Option[MDCCRecord] =
          db.get(txn, r.key).map(MDCCRecordUtil.fromBytes(_))
        val storedRecValue: Option[Array[Byte]] =
          storedMDCCRec match {
            case Some(v) => v.value
            case None => None
          }
        val defaultInfo =
          PendingCommandsInfo(storedRecValue,
                              new ArrayBuffer[CStructCommand],
                              new ArrayBuffer[PendingStateInfo])
        var commandsInfo = pendingCStructs.putIfAbsent(r.key.toList, defaultInfo)
        if (commandsInfo == null) commandsInfo = defaultInfo

        if (!ignore) {
//          commandsInfo.replaceCommand(CStructCommand(xid, r, true, false))
          // If forceNonPending is set, the new command already not pending.
          commandsInfo.replaceCommand(CStructCommand(xid, r, !forceNonPending, false))
        }

//        logger.debug("ACCEPT abort xid: %s %s key:%s ignore: %s storedRecValue: %s commands: %s", xid, Thread.currentThread.getName, (new mdcc.ByteArrayWrapper(r.key)).hashCode(), ignore, storedRecValue, commandsInfo)

        (r.key, CStruct(commandsInfo.base, commandsInfo.commands))
      })

      if (dbTxn == null) {
//        db.txAbort(txn)
        // This can be commit, since we don't write uncommitted records.
        db.txCommit(txn)
      }
    }

    (success, cstructs)
  }

  // DO NOT hold db locks while calling this.
  override def txStatusAccept(xid: ScadsXid, updates: Seq[RecordUpdate], success: Boolean) = {
    val entryStatus = success match {
      case true => Status.Accept.toString
      case false => Status.Reject.toString
    }

    // Merge the updates to the state of tx, in a transaction.
    val startT = System.nanoTime / 1000000

    val default = TxStatusEntry(entryStatus, ArrayBuffer[UpdateEntry]())
    var txInfo = txStatus.putIfAbsent(xid, default)
    val endT2 = System.nanoTime / 1000000
    if (txInfo == null) {
      txInfo = default
    }

    val existingKeys = txInfo.getUpdates.map(_.update.key)
    val endT3 = System.nanoTime / 1000000
    val newUpdates = updates.filter(a => !existingKeys.exists(b => Arrays.equals(a.key, b)))
    newUpdates.foreach(txInfo.appendUpdate(_))
    val endT4 = System.nanoTime / 1000000

    var newAction = ""
    if (!newUpdates.isEmpty) {
      // Only have to redo the commit/abort if we added a NEW update.
      val status = Status.withName(txInfo.status)
      if (status == Status.Commit) {
        // It was already decided that this transaction should commit.
        // Run commit() again to commit this record update.
        logger.debug("compensating commit %s xid: %s", Thread.currentThread.getName, xid)
        commit(xid)
        newAction = "C"
      } else if (status == Status.Abort) {
        // It was already decided that this transaction should abort.
        // Run abort() again to abort this record update.
        logger.debug("compensating abort %s xid: %s", Thread.currentThread.getName, xid)
        abort(xid)
        newAction = "A"
      }
    }
    val endT5 = System.nanoTime / 1000000

    val endT = System.nanoTime / 1000000
    // TODO: debugging
    if (endT - startT > 35) {
      logger.debug("slow txStatusAccept: " + db.getName + " " + Thread.currentThread.getName + " xid: " + xid + " newUpdates: " + newUpdates + " [" + (endT2 - startT) + ", " + (endT3 - endT2) + ", " + (endT4 - endT3) + ", " + (endT5 - endT4) + newAction + "]" + " totaltime2: " + (endT - startT))
    }
  }

  // DO NOT hold db locks while calling this.
  def updateAndGetTxStatus(xid: ScadsXid, status: Status.Status): TxStatusEntry = {
    // Atomically set the tx status to Commit, and get the list of updates.
    val startT = System.nanoTime / 1000000

    val default = TxStatusEntry(status.toString, ArrayBuffer[UpdateEntry]())
    var txInfo = txStatus.putIfAbsent(xid, default)
    if (txInfo == null) txInfo = default
    txInfo.status = status.toString

    val endT = System.nanoTime / 1000000
//    logger.debug("updateAndGetTxStatus: " + Thread.currentThread.getName + " xid: " + xid + " status: " + status + " totalTime: " + (endT - startT))
    txInfo
  }

  def addEarlyCommit(xid: ScadsXid, status: Boolean) = {
    val startT = System.nanoTime / 1000000
    if (!committedXidMap.containsKey((xid, 0))) {
      committedXidMap.put((xid, 0), status)
//      val endT = System.nanoTime / 1000000
//      logger.debug("added early committed map %s xid: %s %s earlyCommit: %s", Thread.currentThread.getName, xid, status, (endT - startT))
    } else {
//      val endT = System.nanoTime / 1000000
//      logger.debug("skipped early committed map %s xid: %s %s earlyCommit: %s", Thread.currentThread.getName, xid, status, (endT - startT))
    }
  }

  // DO NOT hold db locks while calling this.
  override def commit(xid: ScadsXid) : Boolean = {
    // TODO: Handle out of order commits to same records.

    logger.debug("COMMIT start: xid: %s %s", xid, Thread.currentThread.getName)

    if (!committedXidMap.containsKey((xid, 0))) {
      committedXidMap.put((xid, 0), true)
      logger.debug("%s added committed map xid: %s true", Thread.currentThread.getName, xid)
    }

    // Atomically set the tx status to Commit, and get the list of updates.
    val txInfo = updateAndGetTxStatus(xid, Status.Commit)

    // Sort the updates by key.
    val txRecords = txInfo.getUpdates.sortWith((a, b) => ArrayLT.arrayLT(a.update.key, b.update.key))

    logger.debug("COMMIT updates %s xid: %s recs: %s", Thread.currentThread.getName, xid, txRecords.map(r => "(key:" + (new mdcc.ByteArrayWrapper(r.update.key)).hashCode() + ", " + r.update + ")").mkString(", "))

    var success = true

    txRecords.foreach(ue => {
      val r = ue.update
      if (ue.lockStatus == 2) {
        // Already applied.
        logger.debug("COMMIT already applied: xid: %s %s key:%s", xid, Thread.currentThread.getName, (new mdcc.ByteArrayWrapper(r.key)).hashCode())
      } else {
        ue.lockStatus = 1
        val txn = db.txStart()

        try {
          val dbVal = db.get(txn, r.key)
          val storedMDCCRec: Option[MDCCRecord] =
            dbVal.map(MDCCRecordUtil.fromBytes(_))
          val storedRecValue: Option[Array[Byte]] =
            storedMDCCRec match {
              case Some(v) => v.value
              case None => None
            }

          logger.debug("COMMIT read rec: xid: %s %s key:%s dbVal: %s storedRecValue: %s", xid, Thread.currentThread.getName, (new mdcc.ByteArrayWrapper(r.key)).hashCode(), dbVal, storedRecValue)

          val defaultInfo =
            PendingCommandsInfo(storedRecValue,
                                new ArrayBuffer[CStructCommand],
                                new ArrayBuffer[PendingStateInfo])
          var commandsInfo = pendingCStructs.putIfAbsent(r.key.toList, defaultInfo)
          if (commandsInfo == null) commandsInfo = defaultInfo

          val applyUpdate = commandsInfo.getCommand(xid) match {
            case None =>
              // Update is not in the pending list, so that means the option was
              // never received.  Do not apply update, and just stay out of date.
              logger.debug("COMMIT notpending xid: %s %s key:%s commands: %s", xid, Thread.currentThread.getName, (new mdcc.ByteArrayWrapper(r.key)).hashCode(), commandsInfo)
              false
            case Some(c) =>
              // Only apply the update if the command is still pending.
              // TODO(gpang): also look at the commit status?
              c.pending
          }

          logger.debug("COMMIT applyUpdate: %s comm: %s xid: %s %s key:%s dbVal: %s storedRecValue: %s commands: %s", applyUpdate, commandsInfo.getCommand(xid), xid, Thread.currentThread.getName, (new mdcc.ByteArrayWrapper(r.key)).hashCode(), dbVal, storedRecValue, commandsInfo)

          if (applyUpdate) {
            var skippedPut = false
            r match {
              case LogicalUpdate(key, delta) => {
                dbVal match {
                  case None => {
                    val deltaRec = MDCCRecordUtil.fromBytes(delta)
                    // logger.debug("COMMIT logical1: " + xid + " " + Thread.currentThread.getName + " key:" + (new mdcc.ByteArrayWrapper(key)).hashCode() + " writing delta: " + avroUtil.fromBytes(deltaRec.value.get))
                    db.put(txn, key, delta)
                  }
                  case Some(recBytes) => {
                    val deltaRec = MDCCRecordUtil.fromBytes(delta)
                    val dbRec = MDCCRecordUtil.fromBytes(recBytes)
                    val newBytes = logicalRecordUpdater.applyDeltaBytes(dbRec.value, deltaRec.value)

                    // logger.debug("COMMIT logical2: " + xid + " " + Thread.currentThread.getName + " key:" + (new mdcc.ByteArrayWrapper(key)).hashCode() + " dbRec.metadata: " + dbRec.metadata)

                    val newRec = MDCCRecordUtil.toBytes(MDCCRecord(Some(newBytes), addStatsToMetadata(key, dbRec.metadata)))
                    db.put(txn, key, newRec)
                  }
                }
              }
              case ValueUpdate(key, oldValue, newValue) => {
                dbVal match {
                  case None => {
                    val newRec = MDCCRecordUtil.fromBytes(newValue)
                    logger.debug("COMMIT insert: " + xid + " " + Thread.currentThread.getName + " key:" + (new mdcc.ByteArrayWrapper(key)).hashCode() + " newRec.value: " + newRec.value)
                    db.put(txn, key, newValue)
                  }
                  case Some(recBytes) => {
                    // Do not overwrite the metadata in the db.
                    val dbRec = MDCCRecordUtil.fromBytes(recBytes)
                    val dbRecVal = dbRec.value
                    val oldRecVal = if (oldValue.isEmpty) {
                      oldValue
                    } else {
                      MDCCRecordUtil.fromBytes(oldValue.get).value
                    }
                    if ((dbRecVal.isEmpty && !oldRecVal.isEmpty) ||
                        (!dbRecVal.isEmpty && oldRecVal.isEmpty) ||
                        (!dbRecVal.isEmpty && !oldRecVal.isEmpty &&
                         !Arrays.equals(oldRecVal.get, dbRecVal.get))) {
                           // The old and db values do not match.
                           logger.debug("COMMIT skippedPut: " + xid + " " + Thread.currentThread.getName + " key:" + (new mdcc.ByteArrayWrapper(key)).hashCode() + " oldRecVal: " + oldRecVal + " dbRecVal: " + dbRecVal)
                           skippedPut = true
                         } else {
                           val newRec = MDCCRecordUtil.fromBytes(newValue)
                           val newDbRec = MDCCRecordUtil.toBytes(MDCCRecord(newRec.value, addStatsToMetadata(key, dbRec.metadata)))
                           logger.debug("COMMIT replace: " + xid + " " + Thread.currentThread.getName + " key:" + (new mdcc.ByteArrayWrapper(key)).hashCode())
                           db.put(txn, key, newDbRec)
                         }
                  }
                }
              }
              case VersionUpdate(key, newValue) => {
                db.put(txn, key, newValue)
              }
            }

            // Commit the updates in the pending list.
            if (!skippedPut) {
              commandsInfo.commitCommand(CStructCommand(xid, r, false, true), logicalRecordUpdater)
              ue.lockStatus = 2
              logger.debug("COMMIT commitCommand %s xid: %s key:%s commandsinfo: %s, applyUpdate: %s", Thread.currentThread.getName, xid, (new mdcc.ByteArrayWrapper(r.key)).hashCode(), commandsInfo, applyUpdate)
            }

          } else { // applyUpdate
//            logger.debug("COMMIT no_apply: xid: %s %s key:%s val: %s", xid, Thread.currentThread.getName, (new mdcc.ByteArrayWrapper(r.key)).hashCode(), dbVal)
          }

          logger.debug("COMMIT wrote command %s xid: %s key:%s commandsinfo: %s, applyUpdate: %s", Thread.currentThread.getName, xid, (new mdcc.ByteArrayWrapper(r.key)).hashCode(), commandsInfo, applyUpdate)

          db.txCommit(txn)
        } catch {
          case e: Exception => {
            logger.debug("commitTxnException xid: %s %s", xid, e)
            e.printStackTrace()
          }
          db.txAbort(txn)
          success = false
        }
      } // if (ru.lockStatus == 2) {} else

    }) // txRecords.foreach

    txRecords.map(r => Arrays.hashCode(r.update.key)).foreach(h => committedXidMap.put((xid, h), true))

    success
  }

  // DO NOT hold db locks while calling this.
  override def abort(xid: ScadsXid) : Boolean = {

    if (!committedXidMap.containsKey((xid, 0))) {
      committedXidMap.put((xid, 0), false)
      logger.debug("added committed map xid: %s false", xid)
    }

    // Atomically set the tx status to Commit, and get the list of updates.
    val txInfo = updateAndGetTxStatus(xid, Status.Abort)

    // Sort the updates by key.
//    val txRecords = txInfo.updates.sortWith((a, b) => ArrayLT.arrayLT(a.key, b.key))
    val txRecords = txInfo.getUpdates.sortWith((a, b) => ArrayLT.arrayLT(a.update.key, b.update.key))

    txRecords.foreach(ue => {
      val r = ue.update

      // txn for writing stats on abort.
      val txn = db.txStart()

      try {
        // Remove the updates in the pending list.

        val defaultInfo =
          PendingCommandsInfo(None,
                              new ArrayBuffer[CStructCommand],
                              new ArrayBuffer[PendingStateInfo])
        var commandsInfo = pendingCStructs.putIfAbsent(r.key.toList, defaultInfo)
        if (commandsInfo == null) commandsInfo = defaultInfo

        val applyAbort = commandsInfo.getCommand(xid) match {
          case None =>
            // Update is not in the pending list, so that means the option was
            // never received.  Do not abort update, and just stay out of date.
            false
          case Some(c) =>
            // Only apply the abort if the command is still pending.
            c.pending
        }
        if (applyAbort) {
          commandsInfo.abortCommand(CStructCommand(xid, r, false, false))
        }

        // Try writing stats for aborts.
        val dbVal = db.get(txn, r.key)
        val storedMDCCRec: Option[MDCCRecord] =
          dbVal.map(MDCCRecordUtil.fromBytes(_))
        if (storedMDCCRec.isDefined) {
          val r1 = storedMDCCRec.get
          val newDbRec = MDCCRecordUtil.toBytes(MDCCRecord(r1.value, addStatsToMetadata(r.key, r1.metadata)))
          db.put(txn, r.key, newDbRec)
        }
        db.txCommit(txn)

      } catch {
        case e: Exception => {
          logger.debug("abortTxnException xid: %s %s", xid, e)
          e.printStackTrace()
        }
        // txn for writing stats on abort.
        db.txAbort(txn)
      }

    }) // txRecords.foreach

    txRecords.map(r => Arrays.hashCode(r.update.key)).foreach(h => committedXidMap.put((xid, h), false))
    true
  }

  // TODO(kraska): This probably doesn't belong here, since the conflict
  //               resolver is never used within PendingUpdates.  A
  //               ConflictResolver should just be created elsewhere.
  def getConflictResolver : ConflictResolver = conflictResolver

  def overwrite(key: Array[Byte], safeValue: CStruct, meta: Option[MDCCMetadata], committedXids: Seq[ScadsXid], abortedXids: Seq[ScadsXid], isFast: Boolean = false)(implicit dbTxn: TransactionData) : Boolean = {
    overwriteTxn(key, safeValue, meta, committedXids, abortedXids, dbTxn, isFast)
}

  def overwriteTxn(key: Array[Byte], safeValue: CStruct, meta: Option[MDCCMetadata], committedXids: Seq[ScadsXid], abortedXids: Seq[ScadsXid], dbTxn: TransactionData = null, isFast: Boolean = false): Boolean = {
    var success = true
    val txn = dbTxn match {
      case null => db.txStart()
      case x => x
    }

//    logger.debug("Overwrite " + Thread.currentThread.getName + " key:" + (new mdcc.ByteArrayWrapper(key)).hashCode() + " safeValue: " + safeValue)

    // Apply all nonpending commands to the base of the cstruct.
    // TODO: This will apply all nonpending, committed updates, even if there
    //       are pending updates inter-mixed.  Not sure if that is correct...
    if (safeValue.value.isEmpty) {
      logger.error("overwrite empty: updates: %s", safeValue.commands)
    }
    val nonPendingCommits = safeValue.commands.filter(x => !x.pending && x.commit)
    val newDBrec = if (nonPendingCommits.length == 0) {
      // Nothing to apply.
      safeValue.value
    } else {
      ApplyUpdates.applyUpdatesToBase(
        logicalRecordUpdater, safeValue.value,
        nonPendingCommits)
    }

    val storedMDCCRec: Option[MDCCRecord] =
      db.get(txn, key).map(MDCCRecordUtil.fromBytes(_))
    val newMDCCRec = storedMDCCRec match {
      // TODO: I don't know if it is possible to not have a db record but have
      //       a cstruct.
      case None => //throw new RuntimeException("When overwriting, db record should already exist. " + Thread.currentThread.getName + " key:" + (new mdcc.ByteArrayWrapper(key)).hashCode() + " safeValue: " + safeValue + " committedXids: " + committedXids + " abortedXids: " + abortedXids)
        logger.error("When overwriting, db record should already exist??? " + Thread.currentThread.getName + " key:" + (new mdcc.ByteArrayWrapper(key)).hashCode() + " safeValue: " + safeValue + " committedXids: " + committedXids + " abortedXids: " + abortedXids)
        MDCCRecord(newDBrec, addStatsToMetadata(key, meta.get))

      case Some(r) => MDCCRecord(newDBrec, addStatsToMetadata(key, meta.getOrElse(r.metadata)))
    }

//    logger.debug("Overwrite " + Thread.currentThread.getName + " key:" + (new mdcc.ByteArrayWrapper(key)).hashCode())
    // Write the record to the database.
    db.put(txn, key, MDCCRecordUtil.toBytes(newMDCCRec))

    // Update the stored cstruct.
    val commandsInfo = PendingCommandsInfo(safeValue.value,
                                           new ArrayBuffer[CStructCommand],
                                           new ArrayBuffer[PendingStateInfo])

    // Update the pending states.
    // Assumption: The pending updates are all logical, or there is a single
    //             physical update.  Also, the command should already be
    //             compatible.
    safeValue.commands.foreach(c => {
      if (c.pending && c.commit) {
        if (!newUpdateResolver.isCompatible(c.xid, commandsInfo, newMDCCRec, safeValue.value, c.command)) {
          c.command match {
            case ValueUpdate(key1, oldValue1, newValue1) => {
              logger.error("overwrite incompatible: committedXids: %s, abortedXids: %s, safeValue: %s, command: %s, in committed map: %s", committedXids, abortedXids, safeValue, c.command, committedXidMap.containsKey((c.xid, Arrays.hashCode(key1))))
              // No pending commands.
              // Record found in db, compare with the old version
              val oldRec = MDCCRecordUtil.fromBytes(oldValue1.get)
              (oldRec.value, newDBrec) match {
                case (Some(a), Some(b)) =>
                  logger.error("overwrite db: array compare: %s, safe compare: %s", Arrays.equals(a, b), Arrays.equals(a, safeValue.value.get))
//                  logger.error("old: " + avroUtil.fromBytes(a))
//                  logger.error("db: " + avroUtil.fromBytes(b))
//                  logger.error("safe: " + avroUtil.fromBytes(safeValue.value.get))
                case (a, b) =>
                  logger.error("overwrite diff: a: %s, b: %s", a, b)
              }
            }
            case _ =>
          }
          throw new RuntimeException("All of the overwriting commands should be compatible. key:" + (new mdcc.ByteArrayWrapper(key)).hashCode() + " safeValue: " + safeValue)
        }
      }
      commandsInfo.appendCommand(c)
    })

    if (committedXids.size > 0) {
      committedXids.foreach(x => committedXidMap.put((x, Arrays.hashCode(key)), true))
    }
    if (abortedXids.size > 0) {
      abortedXids.foreach(x => committedXidMap.put((x, Arrays.hashCode(key)), false))
    }

    // Store the new cstruct info.
    pendingCStructs.put(key.toList, commandsInfo)

    logger.debug("Overwrite done " + Thread.currentThread.getName + " key:" + (new mdcc.ByteArrayWrapper(key)).hashCode() + " safeValue: " + safeValue + " newDBrec: " + newDBrec + " commands: " + commandsInfo)

    if (dbTxn == null) {
      db.txCommit(txn)
    }

    // TODO: Should the return value just be a boolean, or the cstruct, or
    //       something else?
    success
  }

  override def getDecision(xid: ScadsXid, key: Array[Byte]) = {
    Option(committedXidMap.get((xid, Arrays.hashCode(key))))
  }

  override def getCStruct(key: Array[Byte]) = {
    val storedMDCCRec: Option[MDCCRecord] =
      db.get(null, key).map(MDCCRecordUtil.fromBytes(_))
    val storedRecValue: Option[Array[Byte]] =
      storedMDCCRec match {
        case Some(v) => v.value
        case None => None
      }

    pendingCStructs.get(key.toList) match {
      case null => CStruct(storedRecValue, new ArrayBuffer[CStructCommand])
      case c => CStruct(c.base, c.commands)
    }
  }

  override def shutdown() = {
  }
}

class NewUpdateResolver(val keySchema: Schema, val valueSchema: Schema,
                        val ics: FieldICList,
                        val committedXidMap: ConcurrentHashMap[(ScadsXid, Int), Boolean]) {
  val avroUtil = new IndexedRecordUtil(valueSchema)
  val logicalRecordUpdater = new LogicalRecordUpdater(valueSchema)
  val icChecker = new ICChecker(valueSchema)

  protected val logger = Logger(classOf[NewUpdateResolver])

  // Returns true if the value of the record encoded in v1, is equal to the
  // value in v2.
  private def compareRecords(v1: Option[Array[Byte]], v2: Option[MDCCRecord]): Boolean = {
    (v1, v2) match {
      case (Some(old), Some(dbRec)) => {

        // Record found in db, compare with the old version
        val oldRec = MDCCRecordUtil.fromBytes(old)

        // Compare the value of the record.
        (oldRec.value, dbRec.value) match {
          case (Some(a), Some(b)) => Arrays.equals(a, b)
          case (None, None) => true
          case (_, _) => {
            logger.debug("failed compare1 %s, (%s %s)", Thread.currentThread.getName, oldRec.value, dbRec.value)
            false
          }
        }
      }
      case (None, None) => true
      case (None, Some(MDCCRecord(None, _))) => true
      case (_, _) => {
        logger.debug("failed compare2 %s, (%s %s)", Thread.currentThread.getName, v1, v2)
        false
      }
    }
  }

  // dbValue is the committed value in the db.
  // safeBaseValue is the base value of the cstruct, which may not have all
  // committed updates applied.
  def isCompatible(xid: ScadsXid,
                   commandsInfo: PendingCommandsInfo,
                   dbValue: Option[MDCCRecord],
                   safeBaseValue: Option[Array[Byte]],
                   newUpdate: RecordUpdate,
                   numServers: Int = 1,
                   isFast: Boolean = false): Boolean = {
    newUpdate match {
      case LogicalUpdate(key, delta) => {
        // TODO: what about deleted/non-existent records???
        if (!dbValue.isDefined) {
          throw new RuntimeException("base record should exist for logical updates. xid: " + xid + " , key:" + (new mdcc.ByteArrayWrapper(key)).hashCode())
        } else {
//          logger.debug("base record exists. xid: " + xid + " , key:" + (new mdcc.ByteArrayWrapper(key)).hashCode())
        }
        val safeBase = safeBaseValue match {
          case None => dbValue.get.value
          case Some(b) => safeBaseValue
        }

        if (!safeBase.isDefined) {
          val alreadyCommitted = commandsInfo.commands.map(x => {
            if (!x.pending) {
              // Not pending.
              (x, false, x.commit)
            } else if (committedXidMap.containsKey((x.xid, 0))) {
              // (command, pending, commit status)
              (x, false, committedXidMap.get((x.xid, 0)))
            } else {
              (x, true, false)
            }
          })
          logger.error("base record should exist for logical updates. xid: %s key:%s committed: %s", xid, (new mdcc.ByteArrayWrapper(key)).hashCode(), alreadyCommitted)
        }

        val deltaRec = MDCCRecordUtil.fromBytes(delta)

        var oldStates = new HashMap[List[Byte], List[List[ScadsXid]]]()
        oldStates ++= commandsInfo.states.map(s => (s.state.toList, s.xids))
        var newStates = new HashMap[List[Byte], List[List[ScadsXid]]]()

        // Apply to base record first
        val newStateBytes = logicalRecordUpdater.applyDeltaBytes(dbValue.get.value, deltaRec.value)
        val newState = newStateBytes.toList
        val newXidList = oldStates.getOrElse(newState, List[List[ScadsXid]]()) ++ List(List(xid))

//        logger.debug(" " + Thread.currentThread.getName + " base: " + avroUtil.fromBytes(dbValue.get.value.get) + " delta: " + avroUtil.fromBytes(deltaRec.value.get) + " newState: " + avroUtil.fromBytes(newState.toArray))
        var valid = newStates.put(newState, newXidList) match {
          case None => icChecker.check(avroUtil.fromBytes(newState.toArray), ics, safeBase, dbValue.get.value, numServers, isFast)
          case Some(_) => true
        }

        if (!valid) {
          newStates.remove(newState)
          commandsInfo.updateStates(newStates.toList.map(x => PendingStateInfo(x._1.toArray, x._2)))
//          logger.debug(" " + Thread.currentThread.getName + " isCompatible1: " + false + " newState: " + avroUtil.fromBytes(newState.toArray))
          false
        } else {

          // TODO: what if current old state is NOT currently in new states?
          commandsInfo.states.foreach(s => {
            if (valid) {
              val newState = logicalRecordUpdater.applyDeltaBytes(Option(s.state), deltaRec.value).toList
              val baseXidList = oldStates.get(s.state.toList).get.map(_ ++ List(xid))
              val newXidList = oldStates.getOrElse(newState, List[List[ScadsXid]]()) ++ baseXidList
              valid = newStates.put(newState, newXidList) match {
                case None => icChecker.check(avroUtil.fromBytes(newState.toArray), ics, safeBase, Option(s.state), numServers, isFast)
                case Some(_) => true
              }
              if (!valid) {
                newStates.remove(newState)
              }
            }
          })

          commandsInfo.updateStates(newStates.toList.map(x => PendingStateInfo(x._1.toArray, x._2)))
          valid
        }
      }
      case ValueUpdate(key, oldValue, newValue) => {
        // Value updates conflict with all pending updates
        val pendingComms = commandsInfo.commands.filter(_.pending)

        val startT = System.nanoTime / 1000000

        // TODO: purely for debugging.
        // TODO: this is pretty slow.
//        val vv = MDCCRecordUtil.fromBytes(newValue).value
//        if (!vv.isDefined) {
//          logger.debug("None value %s xid: %s update: %s", Thread.currentThread.getName, xid, newUpdate)
//        }

        val endT1 = System.nanoTime / 1000000

        // Check to see if some of them have already committed.
        val alreadyCommitted = commandsInfo.commands.map(x => {
          if (!x.pending) {
            // Not pending.
            (x, false, x.commit)
          } else if (committedXidMap.containsKey((x.xid, 0))) {
            // (command, pending, commit status)
            commandsInfo.replaceCommand(CStructCommand(x.xid, x.command, false, committedXidMap.get((x.xid, 0))))
            logger.debug("REPLACE xid: %s %s key:%s commands: %s", x.xid, Thread.currentThread.getName, (new mdcc.ByteArrayWrapper(key)).hashCode(), commandsInfo)
            (x, false, committedXidMap.get((x.xid, 0)))
          } else {
            (x, true, false)
          }
        })

        val endT2 = System.nanoTime / 1000000

        // Pending after looking for early commits.
        val newPending = alreadyCommitted.filter(_._2)

        // The value to compare with "oldValue"
        var dbCompare = dbValue

        val endT3 = System.nanoTime / 1000000

        if (pendingComms.size > 0 && newPending.size == 0) {
          // No pending commands after considering early commits.

          // Find last committed ValueUpdate.
          dbCompare = alreadyCommitted.filter(_._3).lastOption match {
            case None => {
              logger.debug("detected noop %s xid: %s commands: %s", Thread.currentThread.getName, xid, alreadyCommitted)
              dbValue
            }
            case Some(x) => {
              x._1.command match {
                case ValueUpdate(k2, oldVal2, newVal2) => {
                  val v = MDCCRecordUtil.fromBytes(newVal2)
                  logger.debug("detected early value %s xid: %s newXid: %s newVal: %s", Thread.currentThread.getName, xid, x._1.xid, v.value)
                  Some(v)
                }
                case _ => {
                  logger.debug("detected early nonphysical %s xid: %s commands: %s", Thread.currentThread.getName, xid, alreadyCommitted)
                  dbValue
                }
              }
            }
          }
          logger.debug("detected early commit %s xid: %s pendingComms: %s commands: %s pendingCommsSize: %d newAcceptance: %s", Thread.currentThread.getName, xid, pendingComms, alreadyCommitted, pendingComms.size, compareRecords(oldValue, dbCompare))
        }

        val endT4 = System.nanoTime / 1000000

        // TODO: this is pretty slow. it was if (!dbValue.isDefined) {
        if (!dbCompare.isDefined && alreadyCommitted.size > 0) {
          // DB rec does not exist.
          // Find last committed ValueUpdate.
          logger.debug("empty dbval1. %s xid: %s alreadyCommitted: %s", Thread.currentThread.getName, xid, alreadyCommitted)
          dbCompare = alreadyCommitted.filter(_._3).lastOption match {
            case None => {
              dbValue
            }
            case Some(x) => {
              x._1.command match {
                case ValueUpdate(k2, oldVal2, newVal2) => {
                  logger.debug("empty dbval2. %s xid: %s new command: %s %s", Thread.currentThread.getName, xid, x._1.xid, x._1.command)
                  Some(MDCCRecordUtil.fromBytes(newVal2))
                }
                case _ => {
                  dbValue
                }
              }
            }
          }
        }

        val endT5 = System.nanoTime / 1000000

        if (newPending.size == 0) {
          // No pending commands.
          val ret = compareRecords(oldValue, dbCompare)

          val endT6 = System.nanoTime / 1000000

          if (endT6 - startT > 100) {
            logger.debug("slow compatibility %s xid: %s [%s, %s, %s, %s, %s, %s] %s", Thread.currentThread.getName, xid, (endT1 - startT), (endT2 - endT1), (endT3 - endT2), (endT4 - endT3), (endT5 - endT4), (endT6 - endT5), (endT6 - startT))
          }

          if (!ret) {
            logger.debug("failed compatibility1 %s xid: %s pendingComms: %s commands: %s newPending: %s", Thread.currentThread.getName, xid, pendingComms, alreadyCommitted, newPending)
          }
          ret
        } else {
          // There exists a pending command.  Value update is not compatible.
          // TODO: in a fast round, multiple physical updates are sometimes
          //       compatible.
          logger.debug("failed compatibility2 %s xid: %s pendingComms: %s commands: %s newPending: %s", Thread.currentThread.getName, xid, pendingComms, alreadyCommitted, newPending)
          false
        }
      }
      case VersionUpdate(key, newValue) => {
        // Version updates conflict with all pending updates
        if (commandsInfo.commands.indexWhere(x => x.pending) == -1) {
          // No pending commands.
          val newRec = MDCCRecordUtil.fromBytes(newValue)
          dbValue match {
            case Some(v) =>
              (newRec.metadata.currentVersion.round == v.metadata.currentVersion.round  + 1)
            case None => true
          }
        } else {
          // There exists a pending command.  Version update is not compatible.
          // TODO: in a fast round, multiple physical updates are sometimes
          //       compatible.
          false
        }
      }
    }
  } // isCompatible
}
