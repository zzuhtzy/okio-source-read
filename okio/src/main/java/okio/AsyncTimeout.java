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
import java.io.InterruptedIOException;

import static okio.Util.checkOffsetAndCount;

/**
 * This timeout uses a background thread to take action exactly when the timeout
 * occurs. Use this to implement timeouts where they aren't supported natively,
 * such as to sockets that are blocked on writing.
 * 这个超时使用一个后台进程，当超时发生时可以被这个类感知到。当请求原声不支持超时时，使用这个类。
 * 例如写操作不成功时。
 *
 * <p>Subclasses should override {@link #timedOut} to take action when a timeout
 * occurs. This method will be invoked by the shared watchdog thread so it
 * should not do any long-running operations. Otherwise we risk starving other
 * timeouts from being triggered.
 * 超时时，子类应该重写timedOut。这个方法将被看门狗进程调用，因此不应该执行任何耗时操作。否则
 * 其他进程可能“挨饿”
 *
 * <p>Use {@link #sink} and {@link #source} to apply this timeout to a stream.
 * The returned value will apply the timeout to each operation on the wrapped
 * stream.
 * sink和source在流操作中使用超时。返回值应用这个超时在每一个超时包裹流中。
 *
 * <p>Callers should call {@link #enter} before doing work that is subject to
 * timeouts, and {@link #exit} afterwards. The return value of {@link #exit}
 * indicates whether a timeout was triggered. Note that the call to {@link
 * #timedOut} is asynchronous, and may be called after {@link #exit}.
 * 调用者应该在在做工作前调用before，之后调用afterwards。exit返回是否超时。
 * 注意到这里的调用是异步的，因此可能在调用exit后调用timedOut
 */
public class AsyncTimeout extends Timeout {
  /**
   * Don't write more than 64 KiB of data at a time, give or take a segment.
   * Otherwise slow connections may suffer timeouts even when they're making
   * (slow) progress. Without this, writing a single 1 MiB buffer may never
   * succeed on a sufficiently slow connection.
   * 不超过64KiB，此外连接缓慢可能超时。在一个缓慢的连接中，可能写1M数据永远不能成功。
   */
  private static final int TIMEOUT_WRITE_SIZE = 64 * 1024;

  /**
   * The watchdog thread processes a linked list of pending timeouts, sorted in
   * the order to be triggered. This class synchronizes on AsyncTimeout.class.
   * This lock guards the queue.
   *
   * <p>Head's 'next' points to the first element of the linked list. The first
   * element is the next node to time out, or null if the queue is empty. The
   * head is null until the watchdog thread is started.
   * 看门狗进程处理一个等待超时的链表，排队等待触发。同步锁。head的next指向链表第一个节点。
   * 第一个节点是next node，或者为null，在看门狗进程开始前，head都是null。
   */
  private static AsyncTimeout head;

  /** True if this node is currently in the queue. */
  // 这个节点在队列中，true
  private boolean inQueue;

  /** The next node in the linked list. */
  // 下一个节点
  private AsyncTimeout next;

  /** If scheduled, this is the time that the watchdog should time this out. */
  // 看门狗进程应该调用超时的时间。结束时间点
  private long timeoutAt;

  // 开始前，放入队列
  public final void enter() {
    if (inQueue) throw new IllegalStateException("Unbalanced enter/exit");
    long timeoutNanos = timeoutNanos();
    boolean hasDeadline = hasDeadline();
    if (timeoutNanos == 0 && !hasDeadline) {
      // 没有timeout也没有deadline
      return; // No timeout and no deadline? Don't bother with the queue.
    }
    inQueue = true;
    scheduleTimeout(this, timeoutNanos, hasDeadline);
  }

  // 按照先后顺序插入node
  private static synchronized void scheduleTimeout(
      AsyncTimeout node, long timeoutNanos, boolean hasDeadline) {
    // Start the watchdog thread and create the head node when the first timeout is scheduled.
    // 第一次，创建看门狗和创建head节点
    if (head == null) {
      head = new AsyncTimeout();
      new Watchdog().start();
    }

    long now = System.nanoTime();
    if (timeoutNanos != 0 && hasDeadline) {
      // Compute the earliest event; either timeout or deadline. Because nanoTime can wrap around,
      // Math.min() is undefined for absolute values, but meaningful for relative ones.
      // 结束时间点
      node.timeoutAt = now + Math.min(timeoutNanos, node.deadlineNanoTime() - now);
    } else if (timeoutNanos != 0) {
      node.timeoutAt = now + timeoutNanos;
    } else if (hasDeadline) {
      node.timeoutAt = node.deadlineNanoTime();
    } else {
      throw new AssertionError();
    }

    // Insert the node in sorted order.
    long remainingNanos = node.remainingNanos(now);
    for (AsyncTimeout prev = head; true; prev = prev.next) {
      if (prev.next == null || remainingNanos < prev.next.remainingNanos(now)) {
        // 插入
        node.next = prev.next;
        prev.next = node;
        if (prev == head) {
          // 唤醒看门狗
          AsyncTimeout.class.notify(); // Wake up the watchdog when inserting at the front.
        }
        break;
      }
    }
  }

  /** Returns true if the timeout occurred. */
  // 超时发生，return true
  public final boolean exit() {
    if (!inQueue) return false;
    inQueue = false;
    return cancelScheduledTimeout(this);
  }

  /** Returns true if the timeout occurred. */
  // 超时发生return true
  private static synchronized boolean cancelScheduledTimeout(AsyncTimeout node) {
    // Remove the node from the linked list.
    // 移除节点
    for (AsyncTimeout prev = head; prev != null; prev = prev.next) {
      if (prev.next == node) {
        prev.next = node.next;
        node.next = null;
        return false;
      }
    }

    // The node wasn't found in the linked list: it must have timed out!
    // 没有找到这个node，一定时已经超时了
    return true;
  }

  /**
   * Returns the amount of time left until the time out. This will be negative
   * if the timeout has elapsed and the timeout should occur immediately.
   * 返回剩余时间，如果时间已经消耗完返回负数
   */
  private long remainingNanos(long now) {
    return timeoutAt - now;
  }

  /**
   * Invoked by the watchdog thread when the time between calls to {@link
   * #enter()} and {@link #exit()} has exceeded the timeout.
   */
  protected void timedOut() {
  }

  /**
   * Returns a new sink that delegates to {@code sink}, using this to implement
   * timeouts. This works best if {@link #timedOut} is overridden to interrupt
   * {@code sink}'s current operation.
   * TODO 返回新的sink，使用这个来实现timeout。timeOut被重写来中断sink现在的操作。
   */
  public final Sink sink(final Sink sink) {
    return new Sink() {
      // 写，包含超时
      @Override public void write(Buffer source, long byteCount) throws IOException {
        checkOffsetAndCount(source.size, 0, byteCount);

        while (byteCount > 0L) {
          // Count how many bytes to write. This loop guarantees we split on a segment boundary.
          // 有多长的数据需要被重写
          long toWrite = 0L;
          for (Segment s = source.head; toWrite < TIMEOUT_WRITE_SIZE; s = s.next) {
            int segmentSize = source.head.limit - source.head.pos;
            toWrite += segmentSize;
            if (toWrite >= byteCount) {
              toWrite = byteCount;
              break;
            }
          }

          // Emit one write. Only this section is subject to the timeout.
          boolean throwOnTimeout = false;
          enter();
          try {
            sink.write(source, toWrite);
            byteCount -= toWrite;
            throwOnTimeout = true;
          } catch (IOException e) {
            throw exit(e);
          } finally {
            exit(throwOnTimeout);
          }
        }
      }

      @Override public void flush() throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          sink.flush();
          throwOnTimeout = true;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public void close() throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          sink.close();
          throwOnTimeout = true;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public Timeout timeout() {
        return AsyncTimeout.this;
      }

      @Override public String toString() {
        return "AsyncTimeout.sink(" + sink + ")";
      }
    };
  }

  /**
   * Returns a new source that delegates to {@code source}, using this to
   * implement timeouts. This works best if {@link #timedOut} is overridden to
   * interrupt {@code sink}'s current operation.
   */
  public final Source source(final Source source) {
    return new Source() {
      @Override public long read(Buffer sink, long byteCount) throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          long result = source.read(sink, byteCount);
          throwOnTimeout = true;
          return result;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public void close() throws IOException {
        boolean throwOnTimeout = false;
        try {
          source.close();
          throwOnTimeout = true;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public Timeout timeout() {
        return AsyncTimeout.this;
      }

      @Override public String toString() {
        return "AsyncTimeout.source(" + source + ")";
      }
    };
  }

  /**
   * Throws an IOException if {@code throwOnTimeout} is {@code true} and a
   * timeout occurred. See {@link #newTimeoutException(java.io.IOException)}
   * for the type of exception thrown.
   */
  final void exit(boolean throwOnTimeout) throws IOException {
    boolean timedOut = exit();
    if (timedOut && throwOnTimeout) throw newTimeoutException(null);
  }

  /**
   * Returns either {@code cause} or an IOException that's caused by
   * {@code cause} if a timeout occurred. See
   * {@link #newTimeoutException(java.io.IOException)} for the type of
   * exception returned.
   */
  final IOException exit(IOException cause) throws IOException {
    if (!exit()) return cause;
    return newTimeoutException(cause);
  }

  /**
   * Returns an {@link IOException} to represent a timeout. By default this method returns
   * {@link java.io.InterruptedIOException}. If {@code cause} is non-null it is set as the cause of
   * the returned exception.
   */
  protected IOException newTimeoutException(IOException cause) {
    InterruptedIOException e = new InterruptedIOException("timeout");
    if (cause != null) {
      e.initCause(cause);
    }
    return e;
  }

  // 看门狗
  private static final class Watchdog extends Thread {
    public Watchdog() {
      super("Okio Watchdog");
      // 守护进程
      setDaemon(true);
    }

    public void run() {
      while (true) {
        try {
          AsyncTimeout timedOut = awaitTimeout();

          // Didn't find a node to interrupt. Try again.
          if (timedOut == null) continue;

          // Close the timed out node.
          timedOut.timedOut();
        } catch (InterruptedException ignored) {
        }
      }
    }
  }

  /**
   * Removes and returns the node at the head of the list, waiting for it to
   * time out if necessary. Returns null if the situation changes while waiting:
   * either a newer node is inserted at the head, or the node being waited on
   * has been removed.
   * 移除返回链表头节点。如果必要，等待起超时。当等待时状态改变了，返回null：新插入头节点，
   * 或者正等待的节点被移除了
   */
  static synchronized AsyncTimeout awaitTimeout() throws InterruptedException {
    // Get the next eligible node.
    // 下一个可被移除节点
    AsyncTimeout node = head.next;

    // The queue is empty. Wait for something to be enqueued.
    // 队列空，等待
    if (node == null) {
      AsyncTimeout.class.wait();
      return null;
    }

    long waitNanos = node.remainingNanos(System.nanoTime());

    // The head of the queue hasn't timed out yet. Await that.
    // 头节点没有超时，唤醒
    if (waitNanos > 0) {
      // Waiting is made complicated by the fact that we work in nanoseconds,
      // but the API wants (millis, nanos) in two arguments.
      long waitMillis = waitNanos / 1000000L;
      waitNanos -= (waitMillis * 1000000L);
      AsyncTimeout.class.wait(waitMillis, (int) waitNanos);
      return null;
    }

    // The head of the queue has timed out. Remove it.
    // 对了头节点超时，移除
    head.next = node.next;
    node.next = null;
    return node;
  }
}
