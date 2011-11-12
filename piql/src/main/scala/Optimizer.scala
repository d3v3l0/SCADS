package edu.berkeley.cs
package scads
package piql
package opt

import plans._
import storage.client.index._

import org.apache.avro.Schema
import org.apache.avro.Schema.Field
import org.apache.avro.util.Utf8
import scala.collection.JavaConversions._
import net.lag.logging.Logger

case class ImplementationLimitation(desc: String) extends Exception

object Optimizer {
  val logger = Logger()
  val defaultFetchSize = 10

  case class OptimizedSubPlan(physicalPlan: QueryPlan, schema: TupleSchema)

  def apply(logicalPlan: LogicalPlan): OptimizedSubPlan = {
    logger.info("Optimizing subplan: %s", logicalPlan)

    logicalPlan match {
      case IndexRange(equalityPreds, None, None, r: Relation) if ((equalityPreds.size == r.keySchema.getFields.size) &&
        isPrefix(equalityPreds.map(_.attribute.fieldName), r)) => {
        val tupleSchema = r :: Nil
        OptimizedSubPlan(
          IndexLookup(r, makeKeyGenerator(r, tupleSchema, equalityPreds)),
          tupleSchema)
      }
      case IndexRange(equalityPreds, Some(TupleLimit(count, dataStop)), None, r: Relation) => {
        if (isPrefix(equalityPreds.map(_.attribute.fieldName), r)) {
          logger.info("Using primary index for predicates: %s", equalityPreds)
          val tupleSchema = r :: Nil
          val idxScanPlan = IndexScan(r, makeKeyGenerator(r, tupleSchema, equalityPreds), count, true)
          val fullPlan = dataStop match {
            case true => idxScanPlan
            case false => LocalStopAfter(count, idxScanPlan)
          }
          OptimizedSubPlan(fullPlan, tupleSchema)
        } else {
          logger.info("Using secondary index for predicates: %s", equalityPreds)

          val idx = Index(r.ns.getOrCreateIndex(equalityPreds.map(p => AttributeIndex(p.attribute.fieldName))))
          val tupleSchema: TupleSchema = idx :: r :: Nil
          val idxScanPlan = IndexScan(idx, makeKeyGenerator(idx, tupleSchema, equalityPreds), count, true)
          val derefedPlan = derefPlan(r, idxScanPlan)

          val fullPlan = dataStop match {
            case true => derefedPlan
            case false => LocalStopAfter(count, derefedPlan)
          }
          OptimizedSubPlan(fullPlan, tupleSchema)
        }
      }
      case IndexRange(equalityPreds, bound, Some(Ordering(attrs, asc)), r: Relation) => {
        val limitHint = bound.map(_.count).getOrElse {
          logger.warning("UnboundedPlan %s: %s", r, logicalPlan)
          FixedLimit(defaultFetchSize)
        }
        val isDataStop = bound.map(_.isDataStop).getOrElse(true)
        val prefixAttrs = equalityPreds.map(_.attribute.fieldName) ++ attrs.map(_.fieldName)
        val (idxScanPlan, tupleSchema) =
          if (isPrefix(prefixAttrs, r)) {
            val tupleSchema: TupleSchema = r :: Nil
            (IndexScan(r, makeKeyGenerator(r, tupleSchema, equalityPreds), limitHint, asc), tupleSchema)
          }
          else {
            logger.debug("Creating index for attributes: %s", prefixAttrs)
            val idx = Index(r.ns.getOrCreateIndex(prefixAttrs.map(p => AttributeIndex(p))))
            val tupleSchema = idx :: r :: Nil
            (derefPlan(r,
              IndexScan(idx,
                makeKeyGenerator(idx, tupleSchema, equalityPreds),
                limitHint,
                asc)),
              tupleSchema)
          }

        val fullPlan = isDataStop match {
          case true => idxScanPlan
          case false => LocalStopAfter(limitHint, idxScanPlan)
        }
        OptimizedSubPlan(fullPlan, tupleSchema)
      }
      case IndexRange(equalityPreds, None, None, Join(child, r: Relation))
        if (equalityPreds.size == r.keySchema.getFields.size) &&
          isPrefix(equalityPreds.map(_.attribute.fieldName), r) => {
        val optChild = apply(child)
        val tupleSchema = optChild.schema :+ r
        OptimizedSubPlan(
          IndexLookupJoin(r, makeKeyGenerator(r, tupleSchema, equalityPreds), optChild.physicalPlan),
          tupleSchema)
      }
      case IndexRange(equalityPreds, Some(TupleLimit(count, dataStop)), Some(Ordering(attrs, asc)), Join(child, r: Relation)) => {
        val prefixAttrs = equalityPreds.map(_.attribute.fieldName) ++ attrs.map(_.fieldName)
        val optChild = apply(child)

        val (joinPlan, tupleSchema) =
          if (isPrefix(prefixAttrs, r)) {
            val tupleSchema = optChild.schema :+ r
            logger.debug("Using index special orders for %s", attrs)

            (IndexMergeJoin(r,
              makeKeyGenerator(r, tupleSchema, equalityPreds),
              attrs,
              count,
              asc,
              optChild.physicalPlan),
              tupleSchema)
          } else {
            val idx = Index(r.ns.getOrCreateIndex(prefixAttrs.map(p => AttributeIndex(p))))
            val tupleSchema = idx :: r :: Nil

            val idxJoinPlan = IndexScanJoin(idx,
              makeKeyGenerator(idx, tupleSchema, equalityPreds),
              count,
              asc,
              optChild.physicalPlan)
            (derefPlan(r, idxJoinPlan), tupleSchema)
          }

        val fullPlan = dataStop match {
          case true => joinPlan
          case false => LocalStopAfter(count, joinPlan)
        }

        OptimizedSubPlan(fullPlan, tupleSchema)
      }
      case Selection(pred, child) => {
        val optChild = apply(child)
        val boundPred = pred
        optChild.copy(physicalPlan = LocalSelection(boundPred, optChild.physicalPlan))
      }
    }
  }

  protected def derefPlan(r: Relation, idxPlan: RemotePlan): QueryPlan = {
    val keyFields = r.keySchema.getFields
    val idxFields = idxPlan.namespace.schema.getFields
    val keyGenerator = keyFields.map(kf => AttributeValue(0, idxFields.indexWhere(_.name equals kf.name)))
    IndexLookupJoin(r, keyGenerator, idxPlan)
  }

  /**
   * Returns true only if the given equality predicates can be satisfied by a prefix scan
   * over the given namespace
   */
  protected def isPrefix(attrNames: Seq[String], ns: Relation): Boolean = {
    val primaryKeyAttrs = ns.keySchema.getFields.take(attrNames.size).map(_.name)
    attrNames.map(primaryKeyAttrs.contains(_)).reduceLeft(_ && _)
  }



  /**
   * Given a namespace and a set of attribute equality predicates return
   * at the keyGenerator
   */
  protected def makeKeyGenerator(ns: TupleProvider, schema: TupleSchema, equalityPreds: Seq[AttributeEquality]): KeyGenerator = {
    ns.keySchema.getFields.take(equalityPreds.size).map(f => {
      logger.info("Looking for key generator value for field %s in %s", f.name, equalityPreds)
      val value = equalityPreds.find(_.attribute.fieldName equals f.name).getOrElse(throw new ImplementationLimitation("Invalid prefix")).value
      value
    })
  }

  case class AttributeEquality(attribute: QualifiedAttributeValue, value: Value)

  case class Ordering(attributes: Seq[QualifiedAttributeValue], ascending: Boolean)

  case class TupleLimit(count: Limit, isDataStop: Boolean)

  /**
   * Groups sets of logical operations that can be executed as a
   * single get operations against the key value store
   */
  protected object IndexRange {
    def unapply(logicalPlan: LogicalPlan): Option[(Seq[AttributeEquality], Option[TupleLimit], Option[Ordering], LogicalPlan)] = {
      val (limit, planWithoutStop) = logicalPlan match {
        case StopAfter(count, child) => (Some(TupleLimit(count, false)), child)
        case DataStopAfter(count, child) => (Some(TupleLimit(count, true)), child)
        case otherOp => (None, otherOp)
      }

      //TODO: check to make sure these are fields in the base relation
      val (ordering, planWithoutSort) = planWithoutStop match {
        case Sort(attrs, asc, child) if (attrs.map(_.isInstanceOf[QualifiedAttributeValue]).reduceLeft(_ && _)) => {
          (Some(Ordering(attrs.asInstanceOf[Seq[QualifiedAttributeValue]], asc)), child)
        }
        case otherOp => (None, otherOp)
      }

      val (predicates, planWithoutPredicates) = planWithoutSort.gatherUntil {
        case Selection(pred, _) => pred
      }

      val basePlan = planWithoutPredicates.getOrElse {
        logger.info("IndexRange match failed.  No base plan")
        return None
      }

      val relation = basePlan match {
        case r: Relation => r
        case Join(_, r: Relation) => r
        case otherOp => {
          logger.info("IndexRange match failed.  Invalid base plan: %s", otherOp)
          return None
        }
      }

      val fields = relation.ns.schema.getFields

      val idxEqPreds = predicates.map {
        case EqualityPredicate(v: Value, a@QualifiedAttributeValue(r, f)) if r == relation =>
          AttributeEquality(a, v)
        case EqualityPredicate(a@QualifiedAttributeValue(r, f), v: Value) if r == relation =>
          AttributeEquality(a, v)
        case otherPred => {
          logger.info("IndexScan match failed.  Can't apply %s to index scan of %s.{%s}", otherPred, relation, relation.ns.keySchema.getFields.map(_.name))
          return None
        }
      }

      val getOp = (idxEqPreds, limit, ordering, basePlan)
      logger.info("Matched IndexRange%s", getOp)
      Some(getOp)
    }
  }

}
