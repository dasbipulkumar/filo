package org.velvia.filo

import java.nio.ByteBuffer

/**
 * A generic trait for reading typed values out of a row of data.
 * Used for both reading out of Filo vectors as well as for RowToVectorBuilder,
 * which means it can be used to compose heterogeneous Filo vectors together.
 */
trait RowReader {
  def notNull(columnNo: Int): Boolean
  def getBoolean(columnNo: Int): Boolean
  def getInt(columnNo: Int): Int
  def getLong(columnNo: Int): Long
  def getDouble(columnNo: Int): Double
  def getFloat(columnNo: Int): Float
  def getString(columnNo: Int): String
}

/**
 * An example of a RowReader that can read from Scala tuples containing Option[_]
 */
case class TupleRowReader(tuple: Product) extends RowReader {
  def notNull(columnNo: Int): Boolean =
    tuple.productElement(columnNo).asInstanceOf[Option[Any]].nonEmpty

  def getBoolean(columnNo: Int): Boolean = tuple.productElement(columnNo) match {
    case Some(x: Boolean) => x
  }

  def getInt(columnNo: Int): Int = tuple.productElement(columnNo) match {
    case Some(x: Int) => x
  }

  def getLong(columnNo: Int): Long = tuple.productElement(columnNo) match {
    case Some(x: Long) => x
  }

  def getDouble(columnNo: Int): Double = tuple.productElement(columnNo) match {
    case Some(x: Double) => x
  }

  def getFloat(columnNo: Int): Float = tuple.productElement(columnNo) match {
    case Some(x: Float) => x
  }

  def getString(columnNo: Int): String = tuple.productElement(columnNo) match {
    case Some(x: String) => x
  }
}

/**
 * A RowReader for working with OpenCSV or anything else that emits string[]
 */
case class ArrayStringRowReader(strings: Array[String]) extends RowReader {
  //scalastyle:off
  def notNull(columnNo: Int): Boolean = strings(columnNo) != null && strings(columnNo) != ""
  //scalastyle:on
  def getBoolean(columnNo: Int): Boolean = strings(columnNo).toBoolean
  def getInt(columnNo: Int): Int = strings(columnNo).toInt
  def getLong(columnNo: Int): Long = strings(columnNo).toLong
  def getDouble(columnNo: Int): Double = strings(columnNo).toDouble
  def getFloat(columnNo: Int): Float = strings(columnNo).toFloat
  def getString(columnNo: Int): String = strings(columnNo)
}

object RowReader {
  // Type class for extracting a field of a specific type
  trait TypedFieldExtractor[F] {
    def getField(reader: RowReader, columnNo: Int): F
  }

  implicit object BooleanFieldExtractor extends TypedFieldExtractor[Boolean] {
    final def getField(reader: RowReader, columnNo: Int): Boolean = reader.getBoolean(columnNo)
  }

  implicit object LongFieldExtractor extends TypedFieldExtractor[Long] {
    final def getField(reader: RowReader, columnNo: Int): Long = reader.getLong(columnNo)
  }

  implicit object IntFieldExtractor extends TypedFieldExtractor[Int] {
    final def getField(reader: RowReader, columnNo: Int): Int = reader.getInt(columnNo)
  }

  implicit object DoubleFieldExtractor extends TypedFieldExtractor[Double] {
    final def getField(reader: RowReader, columnNo: Int): Double = reader.getDouble(columnNo)
  }

  implicit object FloatFieldExtractor extends TypedFieldExtractor[Float] {
    final def getField(reader: RowReader, columnNo: Int): Float = reader.getFloat(columnNo)
  }

  implicit object StringFieldExtractor extends TypedFieldExtractor[String] {
    final def getField(reader: RowReader, columnNo: Int): String = reader.getString(columnNo)
  }
}

/**
 * A RowReader designed for iteration over rows of multiple Filo vectors, ideally all
 * with the same length.
 * An Iterator[RowReader] sets the rowNo and returns this RowReader, and
 * the application is responsible for calling the right method to extract each value.
 * For example, a Spark Row can inherit from RowReader.
 * Thus, this is not appropriate for Seq[RowReader].
 */
abstract class FiloRowReader extends RowReader {
  def parsers: Array[FiloVector[_]]
  var rowNo: Int = -1

  final def notNull(columnNo: Int): Boolean = parsers(columnNo).isAvailable(rowNo)
  final def getBoolean(columnNo: Int): Boolean = parsers(columnNo).asInstanceOf[FiloVector[Boolean]](rowNo)
  final def getInt(columnNo: Int): Int = parsers(columnNo).asInstanceOf[FiloVector[Int]](rowNo)
  final def getLong(columnNo: Int): Long = parsers(columnNo).asInstanceOf[FiloVector[Long]](rowNo)
  final def getDouble(columnNo: Int): Double = parsers(columnNo).asInstanceOf[FiloVector[Double]](rowNo)
  final def getFloat(columnNo: Int): Float = parsers(columnNo).asInstanceOf[FiloVector[Float]](rowNo)
  final def getString(columnNo: Int): String = parsers(columnNo).asInstanceOf[FiloVector[String]](rowNo)
  final def getAny(columnNo: Int): Any = parsers(columnNo).boxed(rowNo)
}

/**
 * Just a concrete implementation.
 */
class FastFiloRowReader(chunks: Array[ByteBuffer], classes: Array[Class[_]]) extends FiloRowReader {
  import VectorReader._

  require(chunks.size == classes.size, "chunks must be same length as classes")

  val parsers: Array[FiloVector[_]] = chunks.zip(classes).map {
    case (chunk, Classes.Boolean) => FiloVector[Boolean](chunk)
    case (chunk, Classes.String) => FiloVector[String](chunk)
    case (chunk, Classes.Int) => FiloVector[Int](chunk)
    case (chunk, Classes.Long) => FiloVector[Long](chunk)
    case (chunk, Classes.Double) => FiloVector[Double](chunk)
    case (chunk, Classes.Float) => FiloVector[Float](chunk)
  }
}
