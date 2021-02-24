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
  override def apply(offer: Protos.Offer): Boolean = {
    offer.getAttributesList.iterator.asScala.exists { attribute =>
      val attributeValue = attribute.getText.getValue()
      val attributeSplit = attributeValue.split("?")
      var compareResult = ( attribute.getText.getValue() == value )
      if ( attributeSplit.size == 2 ) {
        val splitValue = attributeSplit(0)
        val splitOperator = attributeSplit(1)
        val compared = versionComp( splitValue, value)
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
          // Invalid Operator, default to direct value compare
        }
      }
      attribute.getName() == attributeName &&
      attribute.getType == Protos.Value.Type.TEXT &&
      compareResult
    }
  }

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

  override def description: String = s"attribute ${attributeName}'s string value is exactly '${value}'"
}
