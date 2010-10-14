package edu.berkeley.cs.scads.piql

import edu.berkeley.cs.scads.storage._
import edu.berkeley.cs.avro.marker._

import org.apache.avro.util._


case class ItemKey(var I_ID : String) extends AvroRecord
case class ItemValue(
        var I_TITLE : String,
        var A_ID : Int,
        var I_PUB_DATE : Long,
        var I_PUBLISHER : String,
        var I_SUBJECT : String,
        var I_DESC : String,
        var I_RELATED1 : Int,
        var I_RELATED2 : Int,
        var I_RELATED3 : Int,
        var I_RELATED4 : Int,
        var I_RELATED5 : Int,
        var I_THUMBNAIL : Array[Byte],
        var I_IMAGE : Array[Byte],
        var I_SRP : Float,
        var I_COST : Float,
        var I_AVAIL : Long,
        var I_STOCK : Int,
        var ISBN : String,
        var I_PAGE : Int,
        var I_BACKING : String,
        var I_DIMENSION : String
        ) extends AvroRecord

case class ItemSubjectDateTitleIndexKey(
        var I_SUBJECT : String,
        var I_PUB_DATE : Long,
        var I_TITLE : String
        ) extends AvroRecord

case class CountryKey(var CO_ID : String) extends AvroRecord
case class CountryValue(
        var CO_NAME : String,
        var CO_EXCHANGE : Float,
        var CO_CURRENCY : String
        )   extends AvroRecord

case class AuthorKey(var A_ID : String) extends AvroRecord
case class AuthorValue(
        var A_FNAME : String,
        var A_LNAME : String,
        var A_MNAME : String,
        var A_DOB : Long,
        var A_BIO : String
        )   extends AvroRecord

case class AuthorFNameIndexKey(var A_FName : String, var A_ID : Int)  extends AvroRecord      //Additional
case class AuthorLNameIndexKey(var A_LName : String, var A_ID : Int)  extends AvroRecord      //Additional

//Different PK
case class CustomerKey(var C_UNAME : String) extends AvroRecord
case class CustomerValue(
        var C_PASSWD : String,
        var C_FNAME : String,
        var C_LNAME : String,
        var C_ADDR_ID : Int,
        var C_PHONE : String,
        var C_EMAIL : String,
        var C_SINCE : Long,
        var C_LAST_VISIT : Long,
        var C_LOGIN : Long,
        var C_EXPIRATION : Long,
        var C_DISCOUNT : Float,
        var C_BALANCE : Double,
        var C_YTD_PMT : Double,
        var C_BIRTHDATE : Long,
        var C_DATA : String
        ) extends AvroRecord

case class CustomerNameKey(var C_FNAME : String) extends AvroRecord


case class OrdersKey(var O_ID : String) extends AvroRecord
case class OrdersValue(
        var O_C_ID : String,
        var O_DATE_Time : Long, //Change: Stores date and time
        var O_SUB_TOTAL : Double,
        var O_TAX : Double,
        var O_TOTAL : Double,
        var O_SHIP_TYPE : String,
        var O_SHIP_DATE : Long,
        var O_BILL_ADDR_ID : String,
        var O_SHIP_ADDR_ID : String,
        var O_STATUS : String
        )   extends AvroRecord

case class CustomerOrderIndex(var C_UNAME : String, var O_DATE : Long) extends AvroRecord


case class OrderLineKey(var OL_O_ID : String, var OL_ID : String) extends AvroRecord
case class OrderLineValue(
        var OL_I_ID : Int,
        var OL_QTY : Int,
        var OL_DISCOUNT : Float,
        var OL_COMMENT : String
        ) extends AvroRecord

case class CcXactsKey(var CX_O_ID : String) extends AvroRecord
case class CcXactsValue(
        var CX_TYPE : String,
        var CX_NUM : Int,
        var CX_NAME : String,
        var CX_EXPIRY : Long,
        var CX_AUTH_ID : String,
        var CX_XACT_AMT : Double,
        var CX_XACT_DATE : Long,
        var CX_CO_ID : Int
        ) extends AvroRecord

case class AddressKey(var ADDR_ID : String) extends AvroRecord
case class AddressValue(
        var ADDR_STREET1 : String,
        var ADDR_STREET2 : String,
        var ADDR_CITY : String,
        var ADDR_STATE : String,
        var ADDR_ZIP : String,
        var ADDR_CO_ID : Int
        ) extends AvroRecord


