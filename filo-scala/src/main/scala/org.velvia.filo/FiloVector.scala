package org.velvia.filo

import java.nio.{ByteBuffer, ByteOrder}
import java.sql.Timestamp
import org.joda.time.DateTime
import scala.collection.Traversable
import scala.language.postfixOps
import scalaxy.loops._

import org.velvia.filo.codecs.EmptyFiloVector
import org.velvia.filo.vector._

/**
 * The main entry point for parsing a Filo binary vector, returning a FiloVector with which
 * to iterate over and read the data vector.
 */
object FiloVector {
  import WireFormat._

  /**
   * Parses a Filo-format ByteBuffer into a FiloVector.  Automatically detects what type of encoding
   * is used underneath.
   *
   * @param buf the ByteBuffer with the columnar chunk at the current position.  After apply returns, the
   *            position will be restored to its original value, but it may change in the meantime.
   */
  def apply[A](buf: ByteBuffer, emptyLen: Int = 0)(implicit cm: VectorReader[A]): FiloVector[A] = {
    if (buf == null) return new EmptyFiloVector[A](emptyLen)
    val origPos = buf.position
    buf.order(ByteOrder.LITTLE_ENDIAN)
    val headerBytes = buf.getInt()
    val vector = majorVectorType(headerBytes) match {
      case VECTORTYPE_EMPTY =>
        new EmptyFiloVector[A](emptyVectorLen(headerBytes))
      case other =>
        cm.makeVector(buf, headerBytes)
    }
    buf.position(origPos)
    vector
  }

  type VectorMaker = PartialFunction[Class[_], (ByteBuffer, Int) => FiloVector[_]]

  import VectorReader._

  val defaultVectorMaker: VectorMaker = {
    case Classes.Boolean => ((b: ByteBuffer, len: Int) => FiloVector[Boolean](b, len))
    case Classes.String  => ((b: ByteBuffer, len: Int) => FiloVector[String](b, len))
    case Classes.Int     => ((b: ByteBuffer, len: Int) => FiloVector[Int](b, len))
    case Classes.Long    => ((b: ByteBuffer, len: Int) => FiloVector[Long](b, len))
    case Classes.Double  => ((b: ByteBuffer, len: Int) => FiloVector[Double](b, len))
    case Classes.Float   => ((b: ByteBuffer, len: Int) => FiloVector[Float](b, len))
    case Classes.DateTime => ((b: ByteBuffer, len: Int) => FiloVector[DateTime](b, len))
    case Classes.SqlTimestamp => ((b: ByteBuffer, len: Int) => FiloVector[Timestamp](b, len))
  }

  /**
   * Creates a FiloVector using a dynamically supplied class type and a pluggable VectorMaker.
   */
  def make(buf: ByteBuffer,
           clazz: Class[_],
           emptyLen: Int = 0,
           vectorMaker: VectorMaker = defaultVectorMaker): FiloVector[_] =
    vectorMaker(clazz)(buf, emptyLen)

  /**
   * Creates multiple FiloVectors from raw ByteBuffers and an array of their classes
   */
  def makeVectors(chunks: Array[ByteBuffer],
                  classes: Array[Class[_]],
                  emptyLen: Int = 0,
                  vectorMaker: VectorMaker = defaultVectorMaker): Array[FiloVector[_]] = {
    require(chunks.size == classes.size, "chunks must be same length as classes")
    val aray = new Array[FiloVector[_]](chunks.size)
    for { i <- 0 until chunks.size optimized } {
      aray(i) = make(chunks(i), classes(i), emptyLen, vectorMaker)
    }
    aray
  }
}

/**
 * A FiloVector gives collection API semantics around the binary Filo format vector,
 * as well as extremely fast read APIs, all with minimal or zero deserialization, and
 * able to be completely off-heap.
 *
 * Fastest ways to access a FiloVector in order, taking into account NA's:
 * 1. while loop, call apply and isAvailable directly
 * 2. use Traversable semantics
 *
 * Fastest ways to access FiloVector randomly:
 * 1. Call isAvailable and apply at desired index
 */
trait FiloVector[@specialized(Int, Double, Long, Float, Boolean) A] extends Traversable[A] {
  // Returns true if the element at position index is available, false if NA
  def isAvailable(index: Int): Boolean

  // Calls fn for each available element in the column.  Will call 0 times if column is empty.
  // NOTE: super slow for primitives because no matter what we do, this will not specialize
  // the A => B and becomes Object => Object.  :/
  def foreach[B](fn: A => B): Unit

  /**
   * Returns the element at a given index.  If the element is not available, the value returned
   * is undefined.  This is a very low level function intended for speed, not safety.
   * @param index the index in the column to pull from.  No bounds checking is done.
   */
  def apply(index: Int): A

  /**
   * Same as apply(), but returns Any, forcing to be an object.
   * Returns null if item not available.
   * Used mostly for APIs like Spark that require a boxed output. This will be slow.
   */
  def boxed(index: Int): Any =
    if (isAvailable(index)) { apply(index).asInstanceOf[Any] }
    else                    { null }

  /**
   * Returns the number of elements in the column.
   */
  def length: Int

  /**
   * A "safe" but slower get-element-at-position method.  It is slower because it does
   * bounds checking and has to call isAvailable() every time.
   * @param index the index in the column to get
   * @return Some(a) if index is within bounds and element is not missing
   */
  def get(index: Int): Option[A] =
    if (index >= 0 && index < length && isAvailable(index)) { Some(apply(index)) }
    else                                                    { None }

  /**
   * Returns an Iterator[Option[A]] over the Filo bytebuffer.  This basically calls
   * get() at each index, so it returns Some(A) when the value is defined and None
   * if it is NA.
   * NOTE: This is a very slow API, due to the need to wrap items in Option, as well as
   * the natural slowness of get().
   * TODO: make this faster.  Don't use the get() API.
   */
  def optionIterator(): Iterator[Option[A]] =
    for { index <- (0 until length).toIterator } yield { get(index) }
}

// TODO: separate this out into traits for AllZeroes vs mixed ones for speed, no need to do
// if sc.naMask.maskType lookup every time
// TODO: wrap bitMask with FastBufferReader for speed
trait NaMaskAvailable {
  val naMask: NaMask
  lazy val maskLen = naMask.bitMaskLength()
  lazy val maskReader = FastBufferReader(naMask.bitMaskAsByteBuffer)
  lazy val maskType = naMask.maskType

  final def isAvailable(index: Int): Boolean = {
    if (maskType == MaskType.AllZeroes) {
      true
    } else if (maskType == MaskType.AllOnes) {
      false
    } else {
      // NOTE: length of bitMask may be less than (length / 64) longwords.
      val maskIndex = index >> 6
      val maskVal = if (maskIndex < maskLen) maskReader.readLong(maskIndex) else 0L
      (maskVal & (1L << (index & 63))) == 0
    }
  }
}
