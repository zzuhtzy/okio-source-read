/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okio;

import java.io.EOFException;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Inflater;

/**
 * A source that uses <a href="http://www.ietf.org/rfc/rfc1952.txt">GZIP</a> to
 * decompress data read from another source.
 * 解压缩冲其他源中读入的GZIP数据进行解压缩
 */
public final class GzipSource implements Source {
  private static final byte FHCRC = 1;
  private static final byte FEXTRA = 2;
  private static final byte FNAME = 3;
  private static final byte FCOMMENT = 4;

  private static final byte SECTION_HEADER = 0;
  private static final byte SECTION_BODY = 1;
  private static final byte SECTION_TRAILER = 2;
  private static final byte SECTION_DONE = 3;

  /** The current section. Always progresses forward. */
  // 当前的部分，SECTION_HEADER -> SECTION_BODY -> SECTION_TRAILER -> SECTION_DONE
  private int section = SECTION_HEADER;

  /**
   * Our source should yield a GZIP header (which we consume directly), followed
   * by deflated bytes (which we consume via an InflaterSource), followed by a
   * GZIP trailer (which we also consume directly).
   * 我们的源去除GZIP的头，接着是数据，我们通过InflaterSource消耗掉header，也直接去除尾。
   */
  private final BufferedSource source;

  /** The inflater used to decompress the deflated body. */
  // TODO
  private final Inflater inflater;

  /**
   * The inflater source takes care of moving data between compressed source and
   * decompressed sink buffers.
   * 这个对象小心的在压缩的源和解压的输出间移动数据
   */
  private final InflaterSource inflaterSource;

  /** Checksum used to check both the GZIP header and decompressed body. */
  // 检查GZIP头，解压数据
  private final CRC32 crc = new CRC32();

  // 创建GZipSource对象
  public GzipSource(Source source) {
    if (source == null) throw new IllegalArgumentException("source == null");
    this.inflater = new Inflater(true);
    this.source = Okio.buffer(source);
    this.inflaterSource = new InflaterSource(this.source, inflater);
  }

  @Override public long read(Buffer sink, long byteCount) throws IOException {
    if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
    if (byteCount == 0) return 0;

    // If we haven't consumed the header, we must consume it before anything else.
    // 如果还没有去除header，我们必须要将其去除掉
    if (section == SECTION_HEADER) {
      consumeHeader();
      section = SECTION_BODY;
    }

    // Attempt to read at least a byte of the body. If we do, we're done.
    // 尝试至少读入1byte
    if (section == SECTION_BODY) {
      long offset = sink.size;
      long result = inflaterSource.read(sink, byteCount);
      if (result != -1) {
        updateCrc(sink, offset, result);
        return result;
      }
      section = SECTION_TRAILER;
    }

    // The body is exhausted; time to read the trailer. We always consume the
    // trailer before returning a -1 exhausted result; that way if you read to
    // the end of a GzipSource you guarantee that the CRC has been checked.
    if (section == SECTION_TRAILER) {
      consumeTrailer();
      section = SECTION_DONE;

      // Gzip streams self-terminate: they return -1 before their underlying
      // source returns -1. Here we attempt to force the underlying stream to
      // return -1 which may trigger it to release its resources. If it doesn't
      // return -1, then our Gzip data finished prematurely!
      if (!source.exhausted()) {
        throw new IOException("gzip finished without exhausting source");
      }
    }

    return -1;
  }

  // 去除Header
  // 10字节的头，包含幻数、版本号以及时间戳
  // 可选的扩展头，如原文件名
  // 文件体，包括DEFLATE压缩的数据
  // 8字节的尾注，包括CRC-32校验和以及未压缩的原始数据长度
  // https://zh.wikipedia.org/wiki/Gzip
  private void consumeHeader() throws IOException {
    // Read the 10-byte header. We peek at the flags byte first so we know if we
    // need to CRC the entire header. Then we read the magic ID1ID2 sequence.
    // We can skip everything else in the first 10 bytes.
    // +---+---+---+---+---+---+---+---+---+---+
    // |ID1|ID2|CM |FLG|     MTIME     |XFL|OS | (more-->)
    // +---+---+---+---+---+---+---+---+---+---+
    // 10字节的头，包含幻数、版本号以及时间戳
    source.require(10);
    byte flags = source.buffer().getByte(3);
    boolean fhcrc = ((flags >> FHCRC) & 1) == 1;
    if (fhcrc) updateCrc(source.buffer(), 0, 10);

    short id1id2 = source.readShort();
    checkEqual("ID1ID2", (short) 0x1f8b, id1id2);
    source.skip(8);

    // Skip optional extra fields.
    // +---+---+=================================+
    // | XLEN  |...XLEN bytes of "extra field"...| (more-->)
    // +---+---+=================================+
    // 跳过额外可选字段
    if (((flags >> FEXTRA) & 1) == 1) {
      source.require(2);
      if (fhcrc) updateCrc(source.buffer(), 0, 2);
      int xlen = source.buffer().readShortLe();
      source.require(xlen);
      if (fhcrc) updateCrc(source.buffer(), 0, xlen);
      source.skip(xlen);
    }

    // Skip an optional 0-terminated name.
    // +=========================================+
    // |...original file name, zero-terminated...| (more-->)
    // +=========================================+
    if (((flags >> FNAME) & 1) == 1) {
      long index = source.indexOf((byte) 0);
      if (index == -1) throw new EOFException();
      if (fhcrc) updateCrc(source.buffer(), 0, index + 1);
      source.skip(index + 1);
    }

    // Skip an optional 0-terminated comment.
    // +===================================+
    // |...file comment, zero-terminated...| (more-->)
    // +===================================+
    if (((flags >> FCOMMENT) & 1) == 1) {
      long index = source.indexOf((byte) 0);
      if (index == -1) throw new EOFException();
      if (fhcrc) updateCrc(source.buffer(), 0, index + 1);
      source.skip(index + 1);
    }

    // Confirm the optional header CRC.
    // +---+---+
    // | CRC16 |
    // +---+---+
    if (fhcrc) {
      checkEqual("FHCRC", source.readShortLe(), (short) crc.getValue());
      crc.reset();
    }
  }

  // 消耗文件尾
  private void consumeTrailer() throws IOException {
    // Read the eight-byte trailer. Confirm the body's CRC and size.
    // +---+---+---+---+---+---+---+---+
    // |     CRC32     |     ISIZE     |
    // +---+---+---+---+---+---+---+---+
    checkEqual("CRC", source.readIntLe(), (int) crc.getValue());
    checkEqual("ISIZE", source.readIntLe(), (int) inflater.getBytesWritten());
  }

  @Override public Timeout timeout() {
    return source.timeout();
  }

  @Override public void close() throws IOException {
    inflaterSource.close();
  }

  /** Updates the CRC with the given bytes. */
  // TODO CRC相关
  private void updateCrc(Buffer buffer, long offset, long byteCount) {
    // Skip segments that we aren't checksumming.
    Segment s = buffer.head;
    for (; offset >= (s.limit - s.pos); s = s.next) {
      offset -= (s.limit - s.pos);
    }

    // Checksum one segment at a time.
    // 校验和
    for (; byteCount > 0; s = s.next) {
      int pos = (int) (s.pos + offset);
      int toUpdate = (int) Math.min(s.limit - pos, byteCount);
      crc.update(s.data, pos, toUpdate);
      byteCount -= toUpdate;
      offset = 0;
    }
  }

  // 检查expected是否等于actual
  private void checkEqual(String name, int expected, int actual) throws IOException {
    if (actual != expected) {
      throw new IOException(String.format(
          "%s: actual 0x%08x != expected 0x%08x", name, actual, expected));
    }
  }
}
