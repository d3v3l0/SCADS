package edu.berkeley.cs.scads.storage
package transactions

import _root_.edu.berkeley.cs.avro.marker.AvroRecord
import scala.math.{min, max}
import collection.mutable.{ArrayBuffer, ArraySeq}

case class MDCCBallotRange(var startRound: Long, var endRound: Long, var vote: Int, var server: SCADSService, var fast: Boolean) extends AvroRecord {
  /**
   * Returns the first round as a ballot
   */
  def ballot() = MDCCBallot(startRound, vote, server, fast)
}

case class MDCCMetadata(var currentVersion: MDCCBallot, var ballots: Seq[MDCCBallotRange], var confirmedVersion : Boolean, var confirmedBallot : Boolean, var statistics : Option[Statistics] = None) extends AvroRecord {
  def validate() =  {
    MDCCBallotRangeHelper.validate(currentVersion, ballots)
  }

  def getAvgAccessRate(alpha: Double = AccessStatsConstants.emaAlpha) = {
    statistics match {
      case None => 0.0
//      case Some(s) => s.getEMA(alpha)
      case Some(s) => s.getMean()
    }
  }
}



/* Transaction KVStore Metadata */
case class MDCCBallot(var round: Long, var vote: Int, var server: SCADSService, var fast: Boolean) extends AvroRecord {

  def <= (that: MDCCBallot): Boolean = compare(that) <= 0

  def >= (that: MDCCBallot): Boolean = compare(that) >= 0

  def <  (that: MDCCBallot): Boolean = compare(that) <  0

  def >  (that: MDCCBallot): Boolean = compare(that) > 0

  def equals (that: MDCCBallot): Boolean = compare(that) == 0

  /**
   * Classic ballots always win over fast ballots. Ensures that every round ends on a fast round.
   */
  def compare(other : MDCCBallot) : Int = {
    if(this.round < other.round)
      return -1
    else if (this.round > other.round)
      return 1
    else if (this.fast && !other.fast)
      return -1
    else if (!this.fast && other.fast)
      return 1
    else if(this.vote < other.vote)
      return -1
    else if (this.vote > other.vote)
      return 1
    else
      return this.server.toString.compare(other.server.toString())
  }

  def isMaster()(implicit r: SCADSService) : Boolean= {
    server == r
  }

}


object MDCCBallotRangeHelper {
  def validate(ranges: Seq[MDCCBallotRange]): Boolean = {
    assert(ranges.size > 0)
    var curRange = ranges.head
    var restRange = ranges.tail
    assert(!curRange.fast || curRange.endRound - curRange.startRound == 0, "MDCCBallotRange Validation failed. Fast is only possible for one round " + ranges)
    assert(curRange.startRound <= curRange.endRound, "MDCCBallotRange Validation failed " + ranges)
    while (!restRange.isEmpty) {
      assert(restRange.head.startRound <= restRange.head.endRound, "MDCCBallotRange Validation failed " + ranges)
      assert(curRange.endRound < restRange.head.startRound, "MDCCBallotRange Validation failed " + ranges)
      curRange = restRange.head
      restRange = restRange.tail
    }
    return true
  }

  def validate(currentVersion : MDCCBallot, ranges: Seq[MDCCBallotRange]) : Boolean = {
    MDCCBallotRangeHelper.validate(ranges)
    getBallot(ranges, currentVersion.round).map( ballot => assert(currentVersion.compare(ballot) <= 0))
    return true
  }

  def isFast(ranges: Seq[MDCCBallotRange], round: Long): Boolean = {
    if (ranges.isEmpty) {
      return false
    } else if (ranges.head.startRound <= round && round <= ranges.head.endRound) {
      return ranges.head.fast
    }
    isFast(ranges.tail, round)
  }

  def topBallot(ranges: Seq[MDCCBallotRange]): MDCCBallot = {
    var top = ranges.head
    MDCCBallot(top.startRound, top.vote, top.server, top.fast)
  }

  def getRange(ranges: Seq[MDCCBallotRange], round: Long): Option[MDCCBallotRange] = {
    if (ranges.isEmpty) {
      return None
    } else if (ranges.head.startRound <= round && round <= ranges.head.endRound) {
      return ranges.head
    }
    getRange(ranges.tail, round)
  }

  def isDefined(ranges: Seq[MDCCBallotRange], round: Long):Boolean =  {
    if (ranges.isEmpty) {
      return false
    } else if (ranges.head.startRound <= round && round <= ranges.head.endRound) {
      return true
    }
    isDefined(ranges.tail, round)
  }

  def getBallot(ranges: Seq[MDCCBallotRange], round: Long): Option[MDCCBallot] = {
    if (ranges.isEmpty) {
      return None
    } else if (ranges.head.startRound <= round && round <= ranges.head.endRound) {
      return MDCCBallot(round, ranges.head.vote, ranges.head.server, ranges.head.fast)
    }
    getBallot(ranges.tail, round)
  }

  def replace(ranges: Seq[MDCCBallotRange], newRange: MDCCBallotRange): Seq[MDCCBallotRange] = {
    val nRange = replace(ranges, newRange, false)
    newRange.vote += 1
    nRange
  }

  /**
   * Insert the newRange into ranges and increases the vote count
   */
  private def replace(ranges: Seq[MDCCBallotRange], newRange: MDCCBallotRange, inserted: Boolean): Seq[MDCCBallotRange] = {
    if (ranges.isEmpty) {
      if (inserted) {
        return Nil
      } else {
        return newRange :: Nil
      }
    }
    val nStart = newRange.startRound
    val nEnd = newRange.endRound
    val head = ranges.head

    if (head.endRound < nStart) {
      return head +: replace(ranges.tail, newRange, inserted)
    }
    newRange.vote = max(newRange.vote, head.vote)
    if (!inserted) {
      if (nStart <= head.startRound) {
        return newRange +: replace(ranges, newRange, true)
      } else {
        return MDCCBallotRange(head.startRound, nStart - 1, head.vote, head.server, head.fast) +: newRange +: replace(ranges, newRange, true)
      }
    }
    if (nEnd < head.endRound) {
      return MDCCBallotRange(nEnd + 1, head.endRound, head.vote, head.server, head.fast) +: ranges.tail
    } else {
      if (head.endRound == nEnd) {
        return ranges.tail
      } else {
        return replace(ranges.tail, newRange, true)
      }
    }
  }

  def adjustRound(ranges: Seq[MDCCBallotRange], curRound: Long): Seq[MDCCBallotRange] = {
    assert(ranges.size > 0)
    if (ranges.head.endRound < curRound) {
      adjustRound(ranges.tail, curRound)
    } else if (ranges.head.startRound < curRound) {
      MDCCBallotRange(max(ranges.head.startRound, curRound), ranges.head.endRound, ranges.head.vote, ranges.head.server, ranges.head.fast) +: ranges.tail
    } else {
      ranges
    }
  }

  /**
   * Returns the range to propose and the new sequence of ranges
   */
  def getOwnership(ranges: Seq[MDCCBallotRange], startRound: Long, endRound: Long, fast: Boolean, r: SCADSService): Seq[MDCCBallotRange] = {
    val newRange = MDCCBallotRange(startRound, endRound, 0, r, fast)
    replace(ranges, newRange)
  }

  def combine(ballot : MDCCBallot, ranges : Seq[MDCCBallotRange]): Seq[MDCCBallotRange] = {
    //TODO: Quite expensive -> Maybe rewrite
    val newRange = MDCCBallotRange(ballot.round, ballot.round, ballot.vote, ballot.server, ballot.fast)
    combine(ranges, newRange :: Nil, ballot.round)
  }

  def combine(lRange : Seq[MDCCBallotRange], rRange : Seq[MDCCBallotRange], firstRound : Long): Seq[MDCCBallotRange] = {
    var left = lRange
    var right = rRange
    val result = ArrayBuffer[MDCCBallotRange]()
    var curRange: MDCCBallotRange = null
    var curRound: Long = firstRound
    var nextRound: Long = 0

    while (!(left.isEmpty && right.isEmpty)) {
      if (!left.isEmpty && left.head.endRound < curRound) {
        left = left.tail
      } else if (!right.isEmpty && right.head.endRound < curRound) {
        right = right.tail
      } else {
        val dominant =
          if (left.isEmpty) {
            nextRound = right.head.endRound + 1
            right.head
          } else if (right.isEmpty) {
            nextRound = left.head.endRound + 1
            left.head
          } else if (curRound < left.head.startRound) {
            //Right is dominant
            nextRound = min(left.head.startRound, right.head.endRound + 1)
            right.head
          } else if (curRound < right.head.startRound) {
            //Left is dominant
            nextRound = min(right.head.startRound, left.head.endRound + 1)
            left.head
          } else {
            nextRound = min(left.head.endRound + 1, right.head.endRound + 1)
            if (compareRanges(left.head, right.head) < 0) {
              right.head
            } else {
              left.head
            }
          }
        if (curRange == null) {
          curRange = dominant.copy()
          curRange.startRound = curRound
          curRound = nextRound
        } else {
          if (compareRanges(curRange, dominant) != 0) {
            val copy = curRange.copy()
            copy.endRound = curRound - 1
            result += copy
            curRange = dominant.copy()
            curRange.startRound = curRound
          }
          curRound = nextRound
        }
      }
    }
    curRange.endRound = curRound - 1
    result += curRange
    result.head.startRound = max(firstRound, result.head.startRound)
    result
  }

  private def buildRange(ranges: Seq[MDCCBallotRange], startRound: Long, endRound: Long, r: SCADSService, fast: Boolean): MDCCBallotRange = {
    val maxVote = ranges.maxBy(_.vote).vote
    assert(maxVote < Long.MaxValue)
    new MDCCBallotRange(startRound, endRound, maxVote + 1, r, fast)
  }

    /**
   * Classic ballots always win over fast ballots. Ensures that every round ends on a fast round.
   */
  def compareRanges(lRange: MDCCBallotRange, rRange: MDCCBallotRange): Int = {
    if (lRange.fast && !rRange.fast)
      return -1
    else if (!lRange.fast && rRange.fast)
      return 1
    else if (lRange.vote < rRange.vote)
      return -1
    else if (lRange.vote > rRange.vote)
      return 1
    else
      return lRange.server.toString.compare(rRange.server.toString())
  }

 /**
   * Compares two ranges starting from a certain round
   * Returns 0 if both are equal
   * -1 if metaL is smaller
   * 1  if metaL is bigger
   * -2 if it is undefined
   */
  def compareRanges(metaL: Seq[MDCCBallotRange], metaR: Seq[MDCCBallotRange], round : Long): Int = {
    var status : Int = 0
    var iterL : Iterator[MDCCBallotRange] = metaL.toIterator
    var iterR : Iterator[MDCCBallotRange] = metaR.toIterator

    if (metaL.head.endRound < round) {
      iterL  = metaL.filter(_.startRound >= round).toIterator
    }
    if (metaR.head.endRound < round) {
      iterR  = metaR.filter(_.startRound >= round).toIterator
    }

    // first element or None.
    var l: Option[MDCCBallotRange] = None
    var r: Option[MDCCBallotRange] = None
    var init = true

    def nextL() = if (iterL.hasNext) Some(iterL.next) else None
    def nextR() = if (iterR.hasNext) Some(iterR.next) else None

    while (init || !(l.isEmpty && r.isEmpty)) {
      if (init) {
        // First time through, get both first elements.
        init = false
        l = nextL()
        r = nextR()
      }
      if (!l.isEmpty && r.isEmpty) {
        // l exists, r is None
        if (status >= 0)
          return 1
        else if (status != 1)
          return -2
      } else if (l.isEmpty && !r.isEmpty) {
        // l is None, r exists
        if (status <= 0)
          return -1
        else if (status != -1)
          return -2
      } else if (l.isEmpty && r.isEmpty) {
        return status
      }

      // Both l and r exist.
      val ll = l.get
      val rr = r.get

      // Should be some sort of intersection.
      if (min(ll.endRound, rr.endRound) < max(ll.startRound, rr.startRound)) {
        // DEBUG
        println("compareRangesAssert l: " + ll + ", r: " + rr + ", seqL: " + metaL + ", seqR: " + metaR + ", round: " + round)
      }
      assert(min(ll.endRound, rr.endRound) >= max(ll.startRound, rr.startRound))

      val cmp = compareRanges(ll, rr)
      if (status == 0) {
        status = cmp
      } else if (status != cmp) {
        return -2
      }

      if (ll.endRound == rr.endRound) {
        // Same end point. Move both pointers.
        l = nextL()
        r = nextR()
      } else if (ll.endRound < rr.endRound) {
        // l ends before r.  Move l.
        l = nextL()
      } else if (ll.endRound > rr.endRound) {
        // l ends after r.  Move r.
        r = nextR()
      }
    }

    status
  }

}
