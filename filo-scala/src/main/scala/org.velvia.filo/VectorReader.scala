package org.velvia.filo

import com.google.flatbuffers.Table
import java.nio.ByteBuffer

import org.velvia.filo.codecs._
import org.velvia.filo.vector._

case class UnsupportedFiloType(vectType: Int, subType: Int) extends
  Exception(s"Unsupported Filo vector type $vectType, subType $subType")

/**
 * VectorReader is a type class to help create FiloVector's from the raw Filo binary byte buffers --
 * mostly parsing the header bytes and ensuring the creation of the right FiloVector parsing class.
 *
 * NOTE: I KNOW there is LOTS of repetition here, but apply() method is the inner loop and must be
 * super fast.  Traits would slow it WAY down.  Instead maybe we can use macros.
 */
object VectorReader {
  import WireFormat._
  import TypedBufferReader._

  implicit object BoolVectorReader extends PrimitiveVectorReader[Boolean]
  implicit object IntVectorReader extends PrimitiveVectorReader[Int]
  implicit object LongVectorReader extends PrimitiveVectorReader[Long]
  implicit object DoubleVectorReader extends PrimitiveVectorReader[Double]
  implicit object FloatVectorReader extends PrimitiveVectorReader[Float]

  implicit object StringVectorReader extends VectorReader[String] {
    def makeVector(buf: ByteBuffer, headerBytes: Int): FiloVector[String] = {
      (majorVectorType(headerBytes), vectorSubType(headerBytes)) match {
        case (VECTORTYPE_SIMPLE, SUBTYPE_STRING) =>
          val ssv = SimpleStringVector.getRootAsSimpleStringVector(buf)
          new SimpleStringWrapper(ssv)

        case (VECTORTYPE_CONST, SUBTYPE_STRING) =>
          val csv = ConstStringVector.getRootAsConstStringVector(buf)
          new ConstStringWrapper(csv)

        case (VECTORTYPE_DICT, SUBTYPE_STRING) =>
          val dsv = DictStringVector.getRootAsDictStringVector(buf)
          new DictStringWrapper(dsv) {
            val intReader = TypedBufferReader[Int](reader, dsv.info.nbits, dsv.info.signed)
            final def getCode(i: Int): Int = intReader.read(i)
          }

        case (vectType, subType) => throw UnsupportedFiloType(vectType, subType)
      }
    }
  }
}

/**
 * Implemented by specific Filo column/vector types.
 */
trait VectorReader[A] {
  /**
   * Creates a FiloVector based on the remaining bytes.  Needs to decipher
   * what sort of vector it is and make the appropriate choice.
   * @param buf a ByteBuffer of the binary vector, with the position at right after
   *            the 4 header bytes... at the beginning of FlatBuffers or whatever
   * @param the four byte headerBytes
   */
  def makeVector(buf: ByteBuffer, headerBytes: Int): FiloVector[A]
}

// TODO: Move this somewhere else
class PrimitiveVectorReader[A: TypedReaderProvider] extends VectorReader[A] {
  import VectorReader._
  import WireFormat._

  def makeVector(buf: ByteBuffer, headerBytes: Int): FiloVector[A] = {
    (majorVectorType(headerBytes), vectorSubType(headerBytes)) match {
      case (VECTORTYPE_SIMPLE, SUBTYPE_PRIMITIVE) =>
        val spv = SimplePrimitiveVector.getRootAsSimplePrimitiveVector(buf)
        new SimplePrimitiveWrapper[A](spv) {
          val typedReader = TypedBufferReader[A](reader, spv.info.nbits, spv.info.signed)
          final def apply(i: Int): A = typedReader.read(i)
        }

      case (VECTORTYPE_CONST, SUBTYPE_PRIMITIVE) =>
        val spv = SimplePrimitiveVector.getRootAsSimplePrimitiveVector(buf)
        new SimplePrimitiveWrapper[A](spv) {
          val typedReader = TypedBufferReader[A](reader, spv.info.nbits, spv.info.signed)
          final def apply(i: Int): A = typedReader.read(0)
        }

      case (vectType, subType) => throw UnsupportedFiloType(vectType, subType)
    }
  }
}
