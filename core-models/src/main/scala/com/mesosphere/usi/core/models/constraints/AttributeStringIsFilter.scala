package com.mesosphere.usi.core.models.constraints

import org.apache.mesos.v1.Protos

import collection.JavaConverters._
import com.typesafe.scalalogging.StrictLogging

/**
  * An agent attribute filter which exactly compares two strings (including case)
  *
  * @param attributeName the name of the attribute to compare
  * @param value The value of the attribute.
  */
case class AttributeStringIsFilter(attributeName: String, value: String) extends AgentFilter with StrictLogging {
  // preCompiles RegEx
  val VersionStringRegEx = """\d(\.\d)*""".r
  
  override def apply(offer: Protos.Offer): Boolean = {
    offer.getAttributesList.iterator.asScala.exists { attributeOffered =>
      val attributeOfferedValue = attributeOffered.getText.getValue()
      // is offered attribute a version string e.g. 1.2.3
      val isOfferedVersionString = VersionStringRegEx.unapplySeq(attributeOfferedValue).isDefined
      // operator filter allows version strings to be compared e.g. 1.2.3?gt
      val valueSplit = value.split("\\?")
      val splitValue = valueSplit(0)
      // is plugins attribute a version string e.g. 1.2.3
      val isValueVersionString = VersionStringRegEx.unapplySeq(splitValue).isDefined

      var compareResult = ( attributeOfferedValue == value )
      // Only use operator logic if:
      //    not already matched
      //    offered values is a version string
      //    filter value can be split by ?
      //    split filter value is a version string
      if ( !compareResult && isOfferedVersionString && valueSplit.size == 2 && isValueVersionString ) {
        val splitOperator = valueSplit(1)
        val compared = versionComp( splitValue, attributeOfferedValue)
        splitOperator match {
          case "lt" => {
            compareResult = ( compared == -1 )
          }
          case "le" =>  {
            compareResult = ( compared != 1 )
          }
          case "eq" =>  {
            compareResult = ( compared == 0 )
          }
          case "ne" =>  {
            compareResult = ( compared != 0 )
          }
          case "ge" =>  {
            compareResult = ( compared != -1 )
          }
          case "gt" =>  {
            compareResult = ( compared == 1 )
          }
          case unknownOperator =>  {
            logger.debug(
              s"Invalid attribute operator '${unknownOperator}'. Defaulting to 'eq'"
            )
            compareResult = ( compared == 0 )
          }
        }
      }
      attributeOffered.getName() == attributeName &&
          attributeOffered.getType == Protos.Value.Type.TEXT &&
          compareResult
    }
  }

  // Compare version strings:
  //    ( a <  b )  => -1
  //    ( a == b )  =>  0
  //    ( a >  b )  =>  1
  def versionComp(a: String, b: String) = {
    def nums(s: String) = s.split("\\.").map(_.toInt) 
    val pairs = nums(a).zipAll(nums(b), 0, 0).toList
    def go(ps: List[(Int, Int)]): Int = ps match {
      case Nil => 0
      case (a, b) :: t => 
        if (a > b) 1 else if (a < b) -1 else go(t)
    }
    go(pairs)
  }

  override def description: String = s"Looking for ${attributeName}='${value}'"
}
