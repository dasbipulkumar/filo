// automatically generated, do not modify

package org.velvia.filo.vector;

import java.nio.*;
import java.lang.*;
import java.util.*;
import com.google.flatbuffers.*;

@SuppressWarnings("unused")
public final class DataInfo extends Struct {
  public DataInfo __init(int _i, ByteBuffer _bb) { bb_pos = _i; bb = _bb; return this; }

  public int nbits() { return bb.get(bb_pos + 0) & 0xFF; }
  public boolean signed() { return 0!=bb.get(bb_pos + 1); }

  public static int createDataInfo(FlatBufferBuilder builder, int nbits, boolean signed) {
    builder.prep(1, 2);
    builder.putBoolean(signed);
    builder.putByte((byte)(nbits & 0xFF));
    return builder.offset();
  }
};

