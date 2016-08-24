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

import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static java.util.zip.Deflater.DEFAULT_COMPRESSION;

/**
 * A sink that uses <a href="http://www.ietf.org/rfc/rfc1952.txt">GZIP</a> to
 * compress written data to another sink.
 * 压缩已经写的数据到另外的sink
 *
 * <h3>Sync flush</h3>
 * Aggressive flushing of this stream may result in reduced compression. Each
 * call to {@link #flush} immediately compresses all currently-buffered data;
 * this early compression may be less effective than compression performed
 * without flushing.
 * 同步刷新。早期持续调用flush可能导致压缩效果不如不调用flush好。
 *
 * <p>This is equivalent to using {@link Deflater} with the sync flush option.
 * This class does not offer any partial flush mechanism. For best performance,
 * only call {@link #flush} when application behavior requires it.
 * 只在需要flush时才刷新
 */
public final class GzipSink implements Sink {
  /** Sink into which the GZIP format is written. */
  // 正在写哪种GZIP格式
  private final BufferedSink sink;

  /** The deflater used to compress the body. */
  // 检查GZIP头，压缩数据
  private final Deflater deflater;

  /**
   * The deflater sink takes care of moving data between decompressed source and
   * compressed sink buffers.
   * 小心的压缩，小心的移动未压缩数据到已经压缩的数据
   */
  private final DeflaterSink deflaterSink;

  private boolean closed;

  /** Checksum calculated for the compressed body. */
  // 对于压缩的数据计算校验和(CRC)
  private final CRC32 crc = new CRC32();

  // 初始化对象
  public GzipSink(Sink sink) {
    if (sink == null) throw new IllegalArgumentException("sink == null");
    this.deflater = new Deflater(DEFAULT_COMPRESSION, true /* No wrap */);
    this.sink = Okio.buffer(sink);
    this.deflaterSink = new DeflaterSink(this.sink, deflater);

    writeHeader();
  }

  @Override public void write(Buffer source, long byteCount) throws IOException {
    if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
    if (byteCount == 0) return;

    updateCrc(source, byteCount);
    deflaterSink.write(source, byteCount);
  }

  // 强制刷新
  @Override public void flush() throws IOException {
    deflaterSink.flush();
  }

  // 超时
  @Override public Timeout timeout() {
    return sink.timeout();
  }

  // close
  @Override public void close() throws IOException {
    if (closed) return;

    // This method delegates to the DeflaterSink for finishing the deflate process
    // but keeps responsibility for releasing the deflater's resources. This is
    // necessary because writeFooter needs to query the processed byte count which
    // only works when the deflater is still open.

    Throwable thrown = null;
    try {
      deflaterSink.finishDeflate();
      writeFooter();
    } catch (Throwable e) {
      thrown = e;
    }

    try {
      deflater.end();
    } catch (Throwable e) {
      if (thrown == null) thrown = e;
    }

    try {
      sink.close();
    } catch (Throwable e) {
      if (thrown == null) thrown = e;
    }
    closed = true;

    if (thrown != null) Util.sneakyRethrow(thrown);
  }

  /**
   * Returns the {@link Deflater}.
   * Use it to access stats, dictionary, compression level, etc.
   */
  public Deflater deflater() {
    return deflater;
  }

  // 直接写头
  private void writeHeader() {
    // Write the Gzip header directly into the buffer for the sink to avoid handling IOException.
    Buffer buffer = this.sink.buffer();
    buffer.writeShort(0x1f8b); // Two-byte Gzip ID.
    buffer.writeByte(0x08); // 8 == Deflate compression method.
    buffer.writeByte(0x00); // No flags.
    buffer.writeInt(0x00); // No modification time.
    buffer.writeByte(0x00); // No extra flags.
    buffer.writeByte(0x00); // No OS.
  }

  // 写结尾
  private void writeFooter() throws IOException {
    sink.writeIntLe((int) crc.getValue()); // CRC of original data.
    sink.writeIntLe((int) deflater.getBytesRead()); // Length of original data.
  }

  /** Updates the CRC with the given bytes. */
  // 修改CRC
  private void updateCrc(Buffer buffer, long byteCount) {
    for (Segment head = buffer.head; byteCount > 0; head = head.next) {
      int segmentLength = (int) Math.min(byteCount, head.limit - head.pos);
      crc.update(head.data, head.pos, segmentLength);
      byteCount -= segmentLength;
    }
  }
}
