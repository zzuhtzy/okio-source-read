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

/**
 * A segment of a buffer.
 *
 * <p>Each segment in a buffer is a circularly-linked list node referencing the following and
 * preceding segments in the buffer.
 *
 * <p>Each segment in the pool is a singly-linked list node referencing the rest of segments in the
 * pool.
 *
 * <p>The underlying byte arrays of segments may be shared between buffers and byte strings. When a
 * segment's byte array is shared the segment may not be recycled, nor may its byte data be changed.
 * The lone exception is that the owner segment is allowed to append to the segment, writing data at
 * {@code limit} and beyond. There is a single owning segment for each byte array. Positions,
 * limits, prev, and next references are not shared.
 * 数据块
 */
final class Segment {
  /** The size of all segments in bytes. */
  // 一个节点内数据最大长度
  static final int SIZE = 8192; // 8 * 1024

  /** Segments will be shared when doing so avoids {@code arraycopy()} of this many bytes. */
  static final int SHARE_MINIMUM = 1024;

  final byte[] data;

  /** The next byte of application data byte to read in this segment. */
  // 读
  int pos;

  /** The first byte of available data ready to be written to. */
  // 写
  int limit;

  /** True if other segments or byte strings use the same byte array. */
  // 两个节点共用一个byte array时true
  boolean shared;

  /** True if this segment owns the byte array and can append to it, extending {@code limit}. */
  // 没有与其他节点共享，可以向数组后追加数据
  boolean owner;

  /** Next segment in a linked or circularly-linked list. */
  // 下一个
  Segment next;

  /** Previous segment in a circularly-linked list. */
  // 前一个
  Segment prev;

  Segment() {
    this.data = new byte[SIZE];
    this.owner = true;
    this.shared = false;
  }

  Segment(Segment shareFrom) {
    this(shareFrom.data, shareFrom.pos, shareFrom.limit);
    shareFrom.shared = true;
  }

  Segment(byte[] data, int pos, int limit) {
    this.data = data;
    this.pos = pos;
    this.limit = limit;
    this.owner = false;
    this.shared = true;
  }

  /**
   * Removes this segment of a circularly-linked list and returns its successor.
   * Returns null if the list is now empty.
   * 将自己从链表中删除
   * before
   *      A------------>B------------->C
   *      pro---------->this---------->next
   * end
   *      A------------>C     this
   * return C
   */
  public Segment pop() {
    Segment result = next != this ? next : null;
    prev.next = next;
    next.prev = prev;
    next = null;
    prev = null;
    return result;
  }

  /**
   * Appends {@code segment} after this segment in the circularly-linked list.
   * Returns the pushed segment.
   * 向本节点后添加一个新节点
   * before
   *      A------------->B------------->C          new segment
   *      pro----------->this---------->next       segment
   * end
   *      A------------->B------------->segment------------>C
   * return segment
   */
  public Segment push(Segment segment) {
    segment.prev = this;
    segment.next = next;
    next.prev = segment;
    next = segment;
    return segment;
  }

  /**
   * Splits this head of a circularly-linked list into two segments. The first
   * segment contains the data in {@code [pos..pos+byteCount)}. The second
   * segment contains the data in {@code [pos+byteCount..limit)}. This can be
   * useful when moving partial segments from one buffer to another.
   *
   * <p>Returns the new head of the circularly-linked list.
   * 切割Segment，返回第一个Segment的头
   */
  public Segment split(int byteCount) {
    if (byteCount <= 0 || byteCount > limit - pos) throw new IllegalArgumentException();
    Segment prefix;

    // We have two competing performance goals:
    //  - Avoid copying data. We accomplish this by sharing segments.
    //  - Avoid short shared segments. These are bad for performance because they are readonly and
    //    may lead to long chains of short segments.
    // To balance these goals we only share segments when the copy will be large.
    if (byteCount >= SHARE_MINIMUM) {
      // 与其他节点共用一个byte数组
      prefix = new Segment(this);
    } else {
      // 从poll中获取
      prefix = SegmentPool.take();
      System.arraycopy(data, pos, prefix.data, 0, byteCount);
    }

    prefix.limit = prefix.pos + byteCount;
    pos += byteCount;
    prev.push(prefix);
    return prefix;
  }

  /**
   * Call this when the tail and its predecessor may both be less than half
   * full. This will copy data so that segments can be recycled.
   * 将本节点数据整理放到前一节点中(如果前一节点有足够空间容纳本节点未被消费数据)，回收本节点
   */
  public void compact() {
    if (prev == this) throw new IllegalStateException();
    if (!prev.owner) return; // Cannot compact: prev isn't writable.
    // 本节点有用数据长度
    int byteCount = limit - pos;
    // 前一节点空闲长度
    int availableByteCount = SIZE - prev.limit + (prev.shared ? 0 : prev.pos);
    // 如果前一节点放不下本节点数据，返回
    if (byteCount > availableByteCount) return; // Cannot compact: not enough writable space.
    // 本节点数据放到前一节点中
    writeTo(prev, byteCount);
    // 本节从链表中删除
    pop();
    // 回收本节点
    SegmentPool.recycle(this);
  }

  /** Moves {@code byteCount} bytes from this segment to {@code sink}. */
  // 从本节点，读byteCount长度数据放到sink中
  public void writeTo(Segment sink, int byteCount) {
    if (!sink.owner) throw new IllegalArgumentException();
    if (sink.limit + byteCount > SIZE) {
      // 如果目标输出节点末端剩余长度不够，即继续往可写位置向后写数据会超出数组界限
      // We can't fit byteCount bytes at the sink's current position. Shift sink first.
      if (sink.shared) throw new IllegalArgumentException();
      // 如果本数组内有效数据加上byteCount长度数据超出数组最大长度，即目标data装不下原数据和将要复
      // 进来数据的总和，抛出异常
      if (sink.limit + byteCount - sink.pos > SIZE) throw new IllegalArgumentException();
      // 将原来的数据复制到数组开头
      System.arraycopy(sink.data, sink.pos, sink.data, 0, sink.limit - sink.pos);
      // 调整指针
      sink.limit -= sink.pos;
      sink.pos = 0;
    }

    // 从本节点的pos位置开始，向sink节点limit(可写)位置写byteCount长度数据
    // 将本节点byteCount长度数据复制到目标节点
    System.arraycopy(data, pos, sink.data, sink.limit, byteCount);
    // 输出节点写指针向后移
    sink.limit += byteCount;
    // 本节点读指针向后移
    pos += byteCount;
  }
}
