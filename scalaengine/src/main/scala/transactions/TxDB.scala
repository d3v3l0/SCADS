package edu.berkeley.cs.scads.storage.transactions

import edu.berkeley.cs.avro.marker._

import edu.berkeley.cs.scads.comm._
import edu.berkeley.cs.scads.util._

import collection.mutable.ArrayBuffer

import org.apache.avro._
import generic._
import io._
import specific._

import java.util.Comparator

import com.sleepycat.je.{Cursor, Database, DatabaseConfig, CursorConfig,
                         DatabaseException, DatabaseEntry, Environment,
                         LockMode, OperationStatus, Durability, Transaction}

import java.util.concurrent.TimeUnit

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.Lock
import scala.collection.mutable.ListBuffer

import java.nio._

import edu.berkeley.cs.scads.storage._

// Transactional db interface.
// The underlying store can be a hashmap (for testing) or a bdb instance.
sealed trait TxDB[K <: AnyRef, V <: AnyRef] {
  // Put a (key, value) pair in the db and will overwrite values.  Returns true
  // if the value was stored successfully.
  def put(tx: TransactionData, key: K, value: V): Boolean

  // Like put(), but only stores the value if the key does NOT exist already.
  // Returns true if the new value was stored successfully.
  def putNoOverwrite(tx: TransactionData, key: K, value: V): Boolean

  // Removes the value for the key.
  def delete(tx: TransactionData, key: K): Boolean

  // Returns the value for the key.
  def get(tx: TransactionData, key: K): Option[V]

  // Returns the value for the key.  If it does not exist, tries to put the
  // default value, without overwriting.  Returns the value in the db.
  def getOrPut(tx: TransactionData, key: K, default: V): V = {
    get(tx, key) match {
      case None => {
        val noOverwrite = putNoOverwrite(tx, key, default)
        // The get() should be defined, because of the putNoOverwrite.
        get(tx, key).get
      }
      case Some(s) => s
    }
  }

  // Starts a transaction in the db.  Returns an object which must be passed
  // into other methods to be part of the transaction.
  def txStart(): TransactionData

  // Commit the transaction.
  def txCommit(tx: TransactionData)

  // Abort the transaction.  
  def txAbort(tx: TransactionData)

  def startup() = {}

  def shutdown() = {}

  def getName = "empty"

  def keyToBytes(k: K): Array[Byte] = keySerializer.keyToBytes(k)
  def keyFromBytes(b: Array[Byte]): K = keySerializer.keyFromBytes(b)
  def valueToBytes(v: V): Array[Byte] = valueSerializer.valueToBytes(v)
  def valueFromBytes(b: Array[Byte]): V = valueSerializer.valueFromBytes(b)

  private var keySerializer = new KeySerializer[K]
  private var valueSerializer = new ValueSerializer[V]

  def setKeySerializer(s: KeySerializer[K]) {
    keySerializer = s
  }
  def setValueSerializer(s: ValueSerializer[V]) {
    valueSerializer = s
  }
}

// Transaction data for the transactionactional dbs
sealed trait TransactionData
case class BDBTransactionData(tx: Transaction) extends TransactionData
case class MapTransactionData(tx: ListBuffer[(Any, Any)]) extends TransactionData

// Factory for creating new transactional dbs.
sealed trait TxDBFactory {
  def getNewDB[K <: AnyRef, V <: AnyRef](
    name: String,
    keySer: KeySerializer[K] = new KeySerializer[K],
    valueSer: ValueSerializer[V] = new ValueSerializer[V]): TxDB[K, V]
}

// A factory for creating BDB transactional dbs.
class BDBTxDBFactory(env: Environment) extends TxDBFactory {
  override def getNewDB[K <: AnyRef, V <: AnyRef](
    name: String,
    keySer: KeySerializer[K] = new KeySerializer[K],
    valueSer: ValueSerializer[V] = new ValueSerializer[V]) = {

    val dbConfig = new DatabaseConfig
    dbConfig.setAllowCreate(true)
    dbConfig.setTransactional(true)
    val db = env.openDatabase(null, name, dbConfig)
    val result = new BDBTxDB[K, V](db)
    result.setKeySerializer(keySer)
    result.setValueSerializer(valueSer)
    result
  }
}

// A factory for creating hashmap transactional dbs.
class MapTxDBFactory extends TxDBFactory {
  override def getNewDB[K <: AnyRef, V <: AnyRef](
    name: String,
    keySer: KeySerializer[K] = new KeySerializer[K],
    valueSer: ValueSerializer[V] = new ValueSerializer[V]) = {

    val result = new MapTxDB[K, V](new ByteArrayHashMap[K, V], name)
    result.setKeySerializer(keySer)
    result.setValueSerializer(valueSer)
    result
  }
}

// Default serializer which just uses java streams.
sealed trait TxRecordSerializer {
  def toBytes[T <: AnyRef](v: T): Array[Byte] = {
    v match {
      case x: Array[Byte] => x
      case _ => {
        val baos = new java.io.ByteArrayOutputStream
        val oos = new java.io.ObjectOutputStream(baos)
        oos.writeObject(v)
        baos.toByteArray
      }
    }
  }
  def fromBytes[T <: AnyRef](b: Array[Byte]): T = {
    // TODO: better way to do this?  common case is slow...
    try {
      // Try converting first
      val bais = new java.io.ByteArrayInputStream(b)
      val ois = new java.io.ObjectInputStream(bais)
      ois.readObject().asInstanceOf[T]
    } catch {
      case e: Exception => { 
        // Fall back to casting it to byte array. Hopefully T is Array[Byte]?
        b.asInstanceOf[T]
      }
    }
  }
}

class KeySerializer[T <: AnyRef] extends TxRecordSerializer {
  def keyToBytes(k: T): Array[Byte] = {
    toBytes[T](k)
  }
  def keyFromBytes(b: Array[Byte]): T = {
    fromBytes[T](b)
  }
}
class ValueSerializer[T <: AnyRef] extends TxRecordSerializer {
  def valueToBytes(v: T): Array[Byte] = {
    toBytes[T](v)
  }
  def valueFromBytes(b: Array[Byte]): T = {
    fromBytes[T](b)
  }
}

// Array[Byte] versions for better performance.
// Any way to check/force T to be Array[Byte]???
class ByteArrayKeySerializer[T <: AnyRef] extends KeySerializer[T] {
  override def keyToBytes(k: T): Array[Byte] = {
    k.asInstanceOf[Array[Byte]]
  }
  override def keyFromBytes(b: Array[Byte]): T = {
    b.asInstanceOf[T]
  }
}
class ByteArrayValueSerializer[T <: AnyRef] extends ValueSerializer[T] {
  override def valueToBytes(v: T): Array[Byte] = {
    v.asInstanceOf[Array[Byte]]
  }
  override def valueFromBytes(b: Array[Byte]): T = {
    b.asInstanceOf[T]
  }
}

// Avro serializers.
class AvroKeySerializer[T <: SpecificRecord](implicit man: Manifest[T]) extends KeySerializer[T] {
  private val rw = new AvroSpecificReaderWriter[T](None)
  override def keyToBytes(k: T): Array[Byte] = {
    rw.serialize(k)
  }
  override def keyFromBytes(b: Array[Byte]): T = {
    rw.deserialize(b)
  }
}
class AvroValueSerializer[T <: SpecificRecord](implicit man: Manifest[T]) extends ValueSerializer[T] {
  private val rw = new AvroSpecificReaderWriter[T](None)
  override def valueToBytes(v: T): Array[Byte] = {
    rw.serialize(v)
  }
  override def valueFromBytes(b: Array[Byte]): T = {
    rw.deserialize(b)
  }
}

// Transactional db using BDB as the underlying store.
class BDBTxDB[K <: AnyRef, V <: AnyRef](val db: Database,
                                        var keySer: KeySerializer[K] = new KeySerializer[K],
                                        var valueSer: ValueSerializer[V] = new ValueSerializer[V]) extends TxDB[K, V] {
  setKeySerializer(keySer)
  setValueSerializer(valueSer)

  private def getTransaction(tx: TransactionData, msg: String): Transaction = {
    tx match {
      case BDBTransactionData(txn) => txn
      case null => null
      case _ => throw new RuntimeException("wrong transaction type: " + msg)
    }
  }

  override def put(tx: TransactionData, key: K, value: V): Boolean = { 
    val txn = getTransaction(tx, "BDB.put()")
    val (dbeKey, dbeValue) = (new DatabaseEntry(keyToBytes(key)),
                              new DatabaseEntry(valueToBytes(value)))
    val opStatus = db.put(txn, dbeKey, dbeValue)
    (opStatus == OperationStatus.SUCCESS)
  }
  override def putNoOverwrite(tx: TransactionData,
                              key: K, value: V): Boolean = {
    val txn = getTransaction(tx, "BDB.putNoOverWrite()")
    val (dbeKey, dbeValue) = (new DatabaseEntry(keyToBytes(key)),
                              new DatabaseEntry(valueToBytes(value)))
    val opStatus = db.putNoOverwrite(txn, dbeKey, dbeValue)
    (opStatus == OperationStatus.SUCCESS)
  }
  override def delete(tx: TransactionData, key: K): Boolean = {
    val txn = getTransaction(tx, "BDB.delete()")
    val dbeKey = new DatabaseEntry(keyToBytes(key))
    db.delete(txn, dbeKey)
    true
  }
  override def get(tx: TransactionData, key: K): Option[V] = {
    val txn = getTransaction(tx, "BDB.get()")
    val (dbeKey, dbeValue) = (new DatabaseEntry(keyToBytes(key)),
                              new DatabaseEntry)
    val opStatus = txn match {
//      case null => db.get(txn, dbeKey, dbeValue, LockMode.READ_COMMITTED)
      // try reading uncommitted, since we don't rollback writes.
      case null => db.get(txn, dbeKey, dbeValue, LockMode.READ_UNCOMMITTED)
      case _ => db.get(txn, dbeKey, dbeValue, LockMode.RMW)
    }
    if (opStatus == OperationStatus.SUCCESS) {
      // Record found in db
      Some(valueFromBytes(dbeValue.getData()))
    } else {
      None
    }
  }
  override def txStart() = {
    val tx = db.getEnvironment.beginTransaction(null, null)
    tx.setLockTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
    BDBTransactionData(tx)
  }
  override def txCommit(tx: TransactionData) { 
    val txn = getTransaction(tx, "BDB.txCommit()")
    if (txn != null) txn.commit()
  }
  override def txAbort(tx: TransactionData) {
    val txn = getTransaction(tx, "BDB.txAbort()")
    if (txn != null) txn.abort()
  }

  override def shutdown() = {
    db.close()
  }

  override def getName = db.getDatabaseName
}

// Transactional db using a hashmap as the underlying store.
class MapTxDB[K <: AnyRef, V <: AnyRef](val map: ByteArrayHashMap[K, V], val name: String) extends TxDB[K, V] {
  private val lock = new Lock()

  private def getTransaction(tx: TransactionData, msg: String): ListBuffer[(Any, Any)] = {
    tx match {
      case MapTransactionData(txn) => txn
      case null => null
      case _ => throw new RuntimeException("wrong transaction type: " + msg)
    }
  }

  override def put(tx: TransactionData, key: K, value: V): Boolean = {
    val txn = getTransaction(tx, "Map.put()")
    val oldVal = map.put(key, value)
    if (txn != null) {
      txn.append((key, oldVal))
    }
    true
  }
  override def putNoOverwrite(tx: TransactionData, key: K, value: V): Boolean = {
    val txn = getTransaction(tx, "Map.putNoOverwrite()")
    val oldVal = map.putIfAbsent(key, value)
    if (txn != null) {
      txn.append((key, oldVal))
    }
    (oldVal == null)
  }
  override def delete(tx: TransactionData, key: K): Boolean = {
    val txn = getTransaction(tx, "Map.delete()")
    val oldVal = map.remove(key)
    if (txn != null) {
      txn.append((key, oldVal))
    }
    true
  }
  override def get(tx: TransactionData, key: K): Option[V] = {
    Option(map.get(key))
  }
  override def txStart() = {
    // For transactional map access, just lock the entire structure.
    // TODO: If locking entire map is a bottle neck, implement better
    //       transactional system?
    lock.acquire
    MapTransactionData(new ListBuffer[(Any, Any)])
  }
  override def txCommit(tx: TransactionData) {
    lock.release
    val txn = getTransaction(tx, "Map.txComit()")
    if (txn != null) {
      txn.clear
    }
  }
  override def txAbort(tx: TransactionData) {
    val txn = getTransaction(tx, "Map.txAbort()")
    if (txn != null) {
      // Undo the changes
      txn.reverse.foreach(x => x match {
        case (k: K, null) => map.remove(k)
        case (k: K, v: V) => map.put(k, v)
        case (_, _) => // this is the wrong format
      })
      txn.clear
    }
    lock.release
  }

  override def getName = name
}

// This is like ConcurrentHashMap, but automatically converts keys of type
// Array[Byte] to List[Byte], in order for hashCode() and equals() to work as
// expected.
class ByteArrayHashMap[K, V] {
  private val map = new ConcurrentHashMap[Any, V]

  // Converts Array[Byte] to List[Byte] for better equals() and hashCode().
  // Other types are unchanged.
  private def convertKey(key: K): Any = {
    key match {
      case x: Array[Byte] => x.toList
      case _ => key
    }
  }

  // ConcurrentHashMap-like method wrappers to convert the key.
  def put(key: K, value: V): V = {
    map.put(convertKey(key), value)
  }
  def putIfAbsent(key: K, value: V): V = {
    map.putIfAbsent(convertKey(key), value)
  }
  def remove(key: K): V = {
    map.remove(convertKey(key))
  }
  def get(key: K): V = {
    map.get(convertKey(key))
  }
}
