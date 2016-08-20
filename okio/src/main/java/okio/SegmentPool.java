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
 * A collection of unused segments, necessary to avoid GC churn and zero-fill.
 * This pool is a thread-safe static singleton.
 * Segment缓冲区管理，设置缓存池上限，对Segment提供释放和新建操作
 */
final class SegmentPool {
  /** The maximum number of bytes to pool. */
  // TODO: Is 64 KiB a good maximum size? Do we ever have that many idle segments?
  static final long MAX_SIZE = 64 * 1024; // 64 KiB.

  /** Singly-linked list of segments. */
  // 链表头
  static Segment next;

  /** Total bytes in this pool. */
  static long byteCount;

  private SegmentPool() {
  }

  // 从缓存中拿出一个
  static Segment take() {
    synchronized (SegmentPool.class) {
      if (next != null) {
        Segment result = next;
        next = result.next;
        result.next = null;
        byteCount -= Segment.SIZE;
        return result;
      }
    }
    // 池子空了，创建新的
    return new Segment(); // Pool is empty. Don't zero-fill while holding a lock.
  }

  // 回收一个节点
  static void recycle(Segment segment) {
    // 该节点有前驱和后继，不可回收
    if (segment.next != null || segment.prev != null) throw new IllegalArgumentException();
    // 有其他节点使用本节点
    if (segment.shared) return; // This segment cannot be recycled.
    synchronized (SegmentPool.class) {
      // 缓存池满了
      if (byteCount + Segment.SIZE > MAX_SIZE) return; // Pool is full.
      // 加入缓存池
      byteCount += Segment.SIZE;
      segment.next = next;
      segment.pos = segment.limit = 0;
      next = segment;
    }
  }
}
