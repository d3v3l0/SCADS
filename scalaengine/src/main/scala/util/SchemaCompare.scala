package edu.berkeley.cs.scads.util

import org.apache.avro.Schema
import collection.JavaConversions._

object SchemaCompare {
  def typesEqual(s1:Schema,s2:Schema):Boolean = {
    if (s1.getType != s2.getType)
      false
    else {
      s1.getType match {
        case Schema.Type.RECORD => {
          s1.getFields.zip(s2.getFields) foreach(fields => {
            if (!typesEqual(fields._1.schema,fields._2.schema))
              return false
          })
          true
        }
        case Schema.Type.UNION => {
          s1.getTypes.zip(s2.getTypes) foreach(types => {
            if (!typesEqual(types._1,types._2))
              return false
          })
          true
        }
        case Schema.Type.ENUM => {
          s1.getEnumSymbols.zip(s2.getEnumSymbols) foreach(symbols => {
            if (!symbols._1.equals(symbols._2))
              return false
          })
          true
        }
        case Schema.Type.ARRAY => 
          typesEqual(s1.getElementType,s2.getElementType)
        case Schema.Type.MAP =>
          typesEqual(s1.getValueType,s2.getValueType)
        case Schema.Type.FIXED =>
          s1.getFixedSize == s2.getFixedSize
        case _ => true
      }
    }
  }
}
