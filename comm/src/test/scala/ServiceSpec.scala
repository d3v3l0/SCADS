package edu.berkeley.cs
package scads.comm
package test

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers

import avro.runtime._
import avro.marker.{AvroUnion, AvroRecord}

sealed trait TestMessages extends AvroUnion
case class TestMsg1(var f1: Int) extends TestMessages with AvroRecord
case class TestMsg2(var f2: Int) extends TestMessages with AvroRecord



@RunWith(classOf[JUnitRunner])
class RemoteServiceSpec extends Spec with ShouldMatchers {
  implicit object TestRegistry extends ServiceRegistry[TestMessages]
  object TestRegistry2 extends ServiceRegistry[TestMessages]


  object TestService extends ServiceHandler[TestMessages] {
    val logger = net.lag.logging.Logger()
    val registry = TestRegistry
    protected def process(src: Option[RemoteServiceProxy[TestMessages]], msg: TestMessages) = {
      msg match {
        case TestMsg1(x) => src.foreach(_ ! TestMsg2(x))
        case _ => throw new RuntimeException("Unexp msg")
      }
    }
    protected def startup() = null
    protected def shutdown() = null
  }

  object ForwardTarget extends ServiceHandler[TestMessages] {
    val logger = net.lag.logging.Logger()
    val registry = TestRegistry2
    val sync = new concurrent.SyncVar[Boolean]

    protected def process(src: Option[RemoteServiceProxy[TestMessages]], msg: TestMessages) = {
      msg match {
        case TestMsg2(4) => {
          logger.debug("Forwarded message received")
          sync.set(true)
        }
        case _ => throw new RuntimeException("Unexp msg")
      }
    }
    protected def startup() = null
    protected def shutdown() = null
  }

  describe("RemoteActors") {
    it("should send message asynchronously") {
      val mailbox = new MessageFuture[TestMessages](TestService.remoteHandle, TestMsg1(1))
      implicit val sender = mailbox.remoteService
      TestService.remoteHandle ! TestMsg1(1)
      mailbox.get(1000) should equal(Some(TestMsg2(1)))
    }

    it("should send messages synchronously") {
      (TestService.remoteHandle !? TestMsg1(2)) should equal(TestMsg2(2))
    }

    it("should send messages and return a future") {
      (TestService.remoteHandle !! TestMsg1(3)).get(1000) should equal(Some(TestMsg2(3)))
    }

    it("should forward messages") {
      TestService.remoteHandle.forward(TestMsg1(4), ForwardTarget.remoteHandle)
      ForwardTarget.sync.get(1000) should equal(Some(true))
    }

    it("should send message across handlers") {
      val networkService = new RemoteService[TestMessages](TestService.remoteHandle.remoteNode, TestService.remoteHandle.id)
      networkService.registry = TestRegistry2
      (networkService !? TestMsg1(4)) should equal(TestMsg2(4))
    }
  }
}
