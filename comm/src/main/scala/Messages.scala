package edu.berkeley.cs.scads.comm

import com.googlecode.avro.marker.AvroRecord


sealed trait MessageBody
case class Record(var key: Array[Byte], var value: Array[Byte]) extends AvroRecord with MessageBody
case class RecordSet(var records: List[Record]) extends AvroRecord with MessageBody

case class ProcessingException(var cause: String, var stacktrace: String) extends AvroRecord with MessageBody
case class TestAndSetFailure(var key: Array[Byte], var currentValue: Option[Array[Byte]]) extends AvroRecord with MessageBody

case class KeyPartition(var minKey: Option[Array[Byte]], var maxKey: Option[Array[Byte]]) extends AvroRecord with MessageBody
case class PartitionPolicy(var partitions: List[KeyPartition]) extends AvroRecord with MessageBody

case class KeyRange(var minKey: Option[Array[Byte]], var maxKey: Option[Array[Byte]], var limit: Option[Int], var offset: Option[Int], var backwards: Boolean) extends AvroRecord with MessageBody
case class GetRequest(var namespace: String, var key: Option[Array[Byte]]) extends AvroRecord with MessageBody
case class GetRangeRequest(var namespace: String, var range: KeyRange) extends AvroRecord with MessageBody
case class GetPrefixRequest(var namespace: String, var start: Array[Byte], var limit: Option[Int], var ascending: Boolean, var fields: Int) extends AvroRecord with MessageBody
case class CountRangeRequest(var namespace: String, var range: KeyRange) extends AvroRecord with MessageBody
case class RemoveRangeRequest(var namespace: String, var range: KeyRange) extends AvroRecord with MessageBody
case class PutRequest(var namespace: String, var key: Array[Byte], var value: Option[Array[Byte]]) extends AvroRecord with MessageBody
case class TestSetRequest(var namespace: String, var key: Array[Byte], var value: Array[Byte], var expectedValue: Option[Array[Byte]], var prefixMatch: Boolean) extends AvroRecord with MessageBody
case class ConfigureRequest(var namespace: String, var partition: String) extends AvroRecord with MessageBody
case class CopyRangesRequest(var namespace: String, var destinationHost: String, var destinationPort: String, var rateLimit: Int, var ranges: List[KeyRange]) extends AvroRecord with MessageBody
case class CopyStartRequest(var namespace: String, var ranges: List[KeyRange]) extends AvroRecord with MessageBody
case class TransferStartReply(var recvActorId: Long) extends AvroRecord with MessageBody
case class TransferFinished(var sendActorId: Long) extends AvroRecord with MessageBody
case class SyncRangeRequest(var namespace: String, var method: Int, var destinationHost: String, var destinationPort: String, var range: KeyRange) extends AvroRecord with MessageBody
case class SyncStartRequest(var namespace: String, var recvIterId: Long, var range: KeyRange) extends AvroRecord with MessageBody
case class TransferStarted(var sendActorId: Long) extends AvroRecord with MessageBody
case class TransferSucceded(var sendActorId: Long, var recordsSent: Long, var milliseconds: Long) extends AvroRecord with MessageBody
case class TransferFailed(var sendActorId: Long, var reason: String) extends AvroRecord with MessageBody
case class BulkData(var seqNum: Int, var sendActorId: Long, var records: RecordSet) extends AvroRecord with MessageBody
case class BulkDataAck(var seqNum: Int, var sendActorId: Long) extends AvroRecord with MessageBody
case class RecvIterClose(var sendActorId: Long) extends AvroRecord with MessageBody
case class FlatMapRequest(var namespace: String, var keyType: String, var valueType: String, var codename: String, var closure: Array[Byte]) extends AvroRecord with MessageBody
case class FlatMapResponse(var records: List[Array[Byte]]) extends AvroRecord with MessageBody
case class FilterRequest(var namespace: String, var keyType: String, var valueType: String, var codename: String, var code: Array[Byte]) extends AvroRecord with MessageBody
case class FoldRequest(var namespace: String, var keyType: String, var valueType: String, var initValueOne: Array[Byte], var initValueTwo: Array[Byte], var direction: Int, var codename: String, var code: Array[Byte]) extends AvroRecord with MessageBody
case class FoldRequest2L(var namespace: String, var keyType: String, var valueType: String, var initValueOne: Array[Byte], var initValueTwo: Array[Byte], var direction: Int, var codename: String, var code: Array[Byte]) extends AvroRecord with MessageBody
case class Fold2Reply(var reply: Array[Byte]) extends AvroRecord with MessageBody

sealed trait ActorId
case class ActorNumber(var num: Long) extends AvroRecord with ActorId
case class ActorName(var name: String) extends AvroRecord with ActorId

case class Message(var src: Option[ActorId], dest: ActorId, id: Option[Long], body: MessageBody)
