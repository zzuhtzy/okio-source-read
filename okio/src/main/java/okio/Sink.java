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

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;

/**
 * Receives a stream of bytes. Use this interface to write data wherever it's
 * needed: to the network, storage, or a buffer in memory. Sinks may be layered
 * to transform received data, such as to compress, encrypt, throttle, or add
 * protocol framing.
 * 接收一个byte流，向需要的地方写数据。可以对数据进行压缩加密等操作。可以添加其他协议。
 * 
 * <p>Most application code shouldn't operate on a sink directly, but rather on a
 * {@link BufferedSink} which is both more efficient and more convenient. Use
 * {@link Okio#buffer(Sink)} to wrap any sink with a buffer.
 * 一般情况下我们不直接使用该sink，而是使用BufferedSink，更有效也更方便，使用Okio.buffer对
 * 其进行包装。
 *
 * <p>Sinks are easy to test: just use a {@link Buffer} in your tests, and
 * read from it to confirm it received the data that was expected.
 * 测试起来很简单，在test中使用Buffer，从其中读数据看收到的数据是不是期望看到的。
 *
 * <h3>Comparison with OutputStream</h3>
 * This interface is functionally equivalent to {@link java.io.OutputStream}.
 * 这个接口和OutputStream功能相等。
 *
 * <p>{@code OutputStream} requires multiple layers when emitted data is
 * heterogeneous: a {@code DataOutputStream} for primitive values, a {@code
 * BufferedOutputStream} for buffering, and {@code OutputStreamWriter} for
 * charset encoding. This class uses {@code BufferedSink} for all of the above.
 * BufferedSink可以统一解决DataOutputStream、BufferedOutputStream和outputStreamWriter的问题。
 *
 * <p>Sink is also easier to layer: there is no {@linkplain
 * java.io.OutputStream#write(int) single-byte write} method that is awkward to
 * implement efficiently.
 *
 * <h3>Interop with OutputStream</h3>
 * Use {@link Okio#sink} to adapt an {@code OutputStream} to a sink. Use {@link
 * BufferedSink#outputStream} to adapt a sink to an {@code OutputStream}.
 * 与OutputStream具有互操作性。Okio.sink适配OutputStream到sink，使用BufferedSink.outputStream
 * 适配sink到outputStream
 */
public interface Sink extends Closeable, Flushable {
  /** Removes {@code byteCount} bytes from {@code source} and  them to this. */
  // 从source移除byteCount位追加到this
  void write(Buffer source, long byteCount) throws IOException;

  /** Pushes all buffered bytes to their final destination. */
  // 推动所有数据到目标地址
  @Override void flush() throws IOException;

  /** Returns the timeout for this sink. */
  // 超时
  Timeout timeout();

  /**
   * Pushes all buffered bytes to their final destination and releases the
   * resources held by this sink. It is an error to write a closed sink. It is
   * safe to close a sink more than once.
   * 数据推送给目标，释放sink的资源。
   */
  @Override void close() throws IOException;
}
