package org.velvia.filo

import com.google.flatbuffers.FlatBufferBuilder
import java.nio.ByteBuffer
import org.joda.time.DateTime
import scala.collection.mutable.BitSet
import scala.reflect.ClassTag

import RowReader._

/**
 * A builder for FiloVectors.  Used to build up elements of a vector before freezing it as an
 * immutable, extremely fast for reading FiloVector.
 */
sealed trait VectorBuilderBase {
  type T

  /** Add a Not Available (null) element to the builder. */
  def addNA(): Unit

  /** Add a value of type T to the builder.  It will be marked as available. */
  def addData(value: T): Unit

  /** If value is defined, then use addData, otherwise use addNA */
  def addOption(value: Option[T]): Unit = {
    value.foreach { v => addData(v) }
    value.orElse  { addNA(); None }
  }

  implicit val extractor: TypedFieldExtractor[T]

  /** Adds an element from a RowReader */
  final def add(row: RowReader, colNo: Int): Unit = {
    if (row.notNull(colNo)) { addData(extractor.getField(row, colNo)) }
    else                    { addNA() }
  }

  /** Resets the builder state to build a new column */
  def reset(): Unit

  /** Number of elements added so far */
  def length: Int

  /** Returns true if every element added is NA, or no elements have been added */
  def isAllNA: Boolean

  implicit val builder: BuilderEncoder[T]

  /**
   * Produces a binary Filo vector as a ByteBuffer, using default encoding hints
   */
  def toFiloBuffer(): ByteBuffer = toFiloBuffer(BuilderEncoder.AutoDetect)

  /**
   * Produces a binary Filo vector as a ByteBuffer, with a specific encoding hint
   */
  def toFiloBuffer(hint: BuilderEncoder.EncodingHint): ByteBuffer = builder.encode(this, hint)
}

/**
 * A concrete implementation of VectorBuilderBase based on ArrayBuffer and BitSet for a mask
 * @param empty The empty value to insert for an NA or missing value
 */
sealed abstract class VectorBuilder[A](empty: A) extends VectorBuilderBase {
  type T = A

  // True for a row number (or bit is part of the set) if data for that row is not available
  val naMask = new BitSet
  val data = new collection.mutable.ArrayBuffer[A]

  def addNA(): Unit = {
    naMask += data.length
    data += empty
  }

  def addData(value: A): Unit = { data += value }

  def reset(): Unit = {
    naMask.clear
    data.clear
  }

  def length: Int = data.length
  def isAllNA: Boolean = Utils.isAllNA(naMask, data.length)
}

sealed abstract class TypedVectorBuilder[A](empty: A)
   (implicit val extractor: TypedFieldExtractor[A],
    implicit val builder: BuilderEncoder[A]) extends VectorBuilder(empty)

sealed abstract class MinMaxVectorBuilder[A](minValue: A,
                                             maxValue: A,
                                             val zero: A)
                                            (implicit val ordering: Ordering[A],
                                             implicit val extractor: TypedFieldExtractor[A],
                                             implicit val builder: BuilderEncoder[A])
extends VectorBuilder(zero) {
  var min: A = maxValue
  var max: A = minValue

  override def addData(value: A): Unit = {
    super.addData(value)
    if (ordering.compare(value, max) > 0) max = value
    if (ordering.compare(value, min) < 0) min = value
  }
}

object VectorBuilder {
  /**
   * Creates a VectorBuilder dynamically based on a passed in class.
   * Please add your builder here when you add a type
   */
  def apply(dataType: Class[_]): VectorBuilderBase = dataType match {
    case Classes.Boolean  => new BoolVectorBuilder
    case Classes.Int      => new IntVectorBuilder
    case Classes.Long     => new LongVectorBuilder
    case Classes.Double   => new DoubleVectorBuilder
    case Classes.Float    => new FloatVectorBuilder
    case Classes.String   => new StringVectorBuilder
    case Classes.DateTime => new DateTimeVectorBuilder
  }

  import BuilderEncoder._

  val FifteenMinMillis = 15 * org.joda.time.DateTimeConstants.MILLIS_PER_MINUTE

  /**
   * Builds a VectorBuilder automatically from a scala collection.
   * All values will be marked available.
   */
  def apply[A: ClassTag: BuilderEncoder](seq: collection.Seq[A]): VectorBuilderBase = {
    val builder = apply(implicitly[ClassTag[A]].runtimeClass).asInstanceOf[VectorBuilderBase { type T = A}]
    seq.foreach(builder.addData)
    builder
  }

  /**
   * Encodes a sequence of type Option[A] to a Filo format ByteBuffer.
   * Elements which are None will get encoded as NA bits.
   */
  def fromOptions[A: ClassTag: BuilderEncoder](seq: collection.Seq[Option[A]]): VectorBuilderBase = {
    val builder = apply(implicitly[ClassTag[A]].runtimeClass).asInstanceOf[VectorBuilderBase { type T = A}]
    seq.foreach(builder.addOption)
    builder
  }
}

class BoolVectorBuilder extends MinMaxVectorBuilder(false, true, false)
class IntVectorBuilder extends MinMaxVectorBuilder(Int.MinValue, Int.MaxValue, 0)
class LongVectorBuilder extends MinMaxVectorBuilder(Long.MinValue, Long.MaxValue, 0L)
class DoubleVectorBuilder extends MinMaxVectorBuilder(Double.MinValue, Double.MaxValue, 0.0)
class FloatVectorBuilder extends MinMaxVectorBuilder(Float.MinValue, Float.MaxValue, 0.0F)

class StringVectorBuilder extends TypedVectorBuilder("") {
  // For dictionary encoding. NOTE: this set does NOT include empty value
  val stringSet = new collection.mutable.HashSet[String]

  final def fromReader(row: RowReader, colNo: Int): String = row.getString(colNo)

  override def addData(value: String): Unit = {
    stringSet += value
    super.addData(value)
  }

  override def reset(): Unit = {
    stringSet.clear
    super.reset()
  }
}

class DateTimeVectorBuilder extends VectorBuilderBase {
  type T = DateTime
  implicit val builder = BuilderEncoder.DateTimeEncoder
  implicit val extractor = RowReader.DateTimeFieldExtractor

  val millisBuilder = new LongVectorBuilder
  val tzBuilder = new IntVectorBuilder

  def addData(value: DateTime): Unit = {
    millisBuilder.addData(value.getMillis)
    tzBuilder.addData(value.getZone.getOffset(0) / VectorBuilder.FifteenMinMillis)
  }

  def addNA(): Unit = {
    millisBuilder.addNA()
    tzBuilder.addNA()
  }

  def reset(): Unit = {
    millisBuilder.reset()
    tzBuilder.reset()
  }

  def length: Int = millisBuilder.length
  def isAllNA: Boolean = millisBuilder.isAllNA
}
