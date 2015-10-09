package org.velvia.filo

import org.scalatest.FunSpec
import org.scalatest.Matchers

class DictEncodingTest extends FunSpec with Matchers {
  import BuilderEncoder.DictionaryEncoding
  import ColumnParser._

  it("should encode and decode back an empty Seq") {
    val buf = ColumnBuilder(Seq[String]()).toFiloBuffer(DictionaryEncoding)
    val binarySeq = ColumnParser.parse[String](buf)

    binarySeq.length should equal (0)
  }

  it("should encode and decode back a Seq[String]") {
    val orig = Seq("apple", "banana")
    val buf = ColumnBuilder(orig).toFiloBuffer(DictionaryEncoding)
    val binarySeq = ColumnParser.parse[String](buf)

    binarySeq.length should equal (orig.length)
    binarySeq.toSeq should equal (orig)
  }

  it("should encode and decode back a Seq[Option[String]]") {
    val orig = Seq(Some("apple"), None, Some("banana"))
    val buf = ColumnBuilder.fromOptions(orig).toFiloBuffer(DictionaryEncoding)
    val binarySeq = ColumnParser.parse[String](buf)

    binarySeq.length should equal (orig.length)
    binarySeq.toSeq should equal (Seq("apple", "banana"))
    binarySeq.optionIterator.toSeq should equal (orig)
  }

  it("should encode and decode back a sequence starting with NAs") {
    val orig = Seq(None, None, None, Some("apple"), Some("banana"))
    val buf = ColumnBuilder.fromOptions(orig).toFiloBuffer(DictionaryEncoding)
    val binarySeq = ColumnParser.parse[String](buf)

    binarySeq.length should equal (orig.length)
    binarySeq.toSeq should equal (Seq("apple", "banana"))
    binarySeq.optionIterator.toSeq should equal (orig)
  }

  // Negative byte values might not get converted to ints properly, leading
  // to an ArrayOutOfBoundsException.
  it("should ensure proper conversion when there are 128-255 unique strings") {
    val orig = (0 to 130).map(_.toString).toSeq
    val buf = ColumnBuilder(orig).toFiloBuffer(DictionaryEncoding)
    val binarySeq = ColumnParser.parse[String](buf)

    binarySeq.length should equal (orig.length)
    binarySeq.toSeq should equal (orig)
  }
}