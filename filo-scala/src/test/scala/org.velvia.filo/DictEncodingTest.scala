package org.velvia.filo

import org.scalatest.FunSpec
import org.scalatest.Matchers

class DictEncodingTest extends FunSpec with Matchers {
  import BuilderEncoder.DictionaryEncoding
  import ColumnParser._

  it("should encode and decode back a Seq[String]") {
    val orig = Seq("apple", "banana")
    val buf = BuilderEncoder.seqToBuffer(orig, DictionaryEncoding)
    val binarySeq = ColumnParser.parse[String](buf)

    binarySeq.length should equal (orig.length)
    binarySeq.toSeq should equal (orig)
  }

  it("should encode and decode back a Seq[Option[String]]") {
    val orig = Seq(Some("apple"), None, Some("banana"))
    val buf = BuilderEncoder.seqOptionToBuffer(orig, DictionaryEncoding)
    val binarySeq = ColumnParser.parse[String](buf)

    binarySeq.length should equal (orig.length)
    binarySeq.toSeq should equal (Seq("apple", "banana"))
    binarySeq.optionIterator.toSeq should equal (orig)
  }
}