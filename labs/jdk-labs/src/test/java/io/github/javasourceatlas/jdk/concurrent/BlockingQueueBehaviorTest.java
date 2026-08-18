package io.github.javasourceatlas.jdk.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 BlockingQueue 教学案例依赖的公开可观察行为。
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class BlockingQueueBehaviorTest {

    private static final long WAIT_SECONDS = 5;
    private static final long OBSERVE_MILLIS = 100;

    /**
     * 验证异常和特殊值方法在满、空状态下返回不同失败结果。
     */
    @Test
    void shouldExposeExceptionAndSpecialValueFamilies() {
        ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(1);

        assertTrue(queue.offer("seed"));
        assertFalse(queue.offer("extra"));
        assertThrows(IllegalStateException.class, () -> queue.add("extra"));
        assertThrows(NullPointerException.class, () -> queue.offer(null));

        assertEquals("seed", queue.poll());
        assertNull(queue.poll());
        assertNull(queue.peek());
        assertThrows(NoSuchElementException.class, queue::remove);
        assertThrows(NoSuchElementException.class, queue::element);
    }

    /**
     * 验证 ArrayBlockingQueue 满时 put 不能完成，take 释放空间后生产者才可返回。
     *
     * @throws Exception 等待生产者或队列操作失败
     */
    @Test
    void shouldResumeArrayQueuePutAfterTakeCreatesCapacity() throws Exception {
        ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        queue.put("seed");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch producerReady = new CountDownLatch(1);
        CountDownLatch startProducer = new CountDownLatch(1);
        CountDownLatch producerCallingPut = new CountDownLatch(1);
        CountDownLatch producerFinished = new CountDownLatch(1);
        AtomicReference<Thread> producerThread = new AtomicReference<>();
        try {
            Future<?> producer = executor.submit(() -> {
                producerThread.set(Thread.currentThread());
                producerReady.countDown();
                awaitGate(startProducer);
                producerCallingPut.countDown();
                try {
                    queue.put("next");
                } finally {
                    producerFinished.countDown();
                }
                return null;
            });
            assertTrue(producerReady.await(WAIT_SECONDS, TimeUnit.SECONDS));
            startProducer.countDown();
            assertTrue(producerCallingPut.await(WAIT_SECONDS, TimeUnit.SECONDS));
            waitUntilBlocked(producerThread.get(), "ArrayBlockingQueue 生产者未进入等待状态");

            // 已确认线程进入 notFull 等待，再验证队列保持满时 put 不会自行完成。
            assertFalse(producerFinished.await(OBSERVE_MILLIS, TimeUnit.MILLISECONDS));
            assertEquals("seed", queue.take());
            producer.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("next", queue.take());
        } finally {
            startProducer.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证 LinkedBlockingQueue 满转非满后会让等待中的生产者继续，并保持 FIFO 顺序。
     *
     * @throws Exception 等待生产者或队列操作失败
     */
    @Test
    void shouldResumeLinkedQueuePutAcrossFullBoundary() throws Exception {
        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(2);
        queue.put("first");
        queue.put("second");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch producerReady = new CountDownLatch(1);
        CountDownLatch startProducer = new CountDownLatch(1);
        CountDownLatch producerCallingPut = new CountDownLatch(1);
        CountDownLatch producerFinished = new CountDownLatch(1);
        AtomicReference<Thread> producerThread = new AtomicReference<>();
        try {
            Future<?> producer = executor.submit(() -> {
                producerThread.set(Thread.currentThread());
                producerReady.countDown();
                awaitGate(startProducer);
                producerCallingPut.countDown();
                try {
                    queue.put("third");
                } finally {
                    producerFinished.countDown();
                }
                return null;
            });
            assertTrue(producerReady.await(WAIT_SECONDS, TimeUnit.SECONDS));
            startProducer.countDown();
            assertTrue(producerCallingPut.await(WAIT_SECONDS, TimeUnit.SECONDS));
            waitUntilBlocked(producerThread.get(), "LinkedBlockingQueue 生产者未进入等待状态");

            assertFalse(producerFinished.await(OBSERVE_MILLIS, TimeUnit.MILLISECONDS));
            assertEquals("first", queue.take());
            producer.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("second", queue.take());
            assertEquals("third", queue.take());
            assertEquals(2, queue.remainingCapacity());
        } finally {
            startProducer.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证空 LinkedBlockingQueue 的定时消费者会收到随后插入的元素。
     *
     * @throws Exception 等待消费者或队列操作失败
     */
    @Test
    void shouldResumeLinkedQueuePollAcrossEmptyBoundary() throws Exception {
        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(2);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch consumerReady = new CountDownLatch(1);
        CountDownLatch startConsumer = new CountDownLatch(1);
        CountDownLatch consumerCallingPoll = new CountDownLatch(1);
        AtomicReference<Thread> consumerThread = new AtomicReference<>();
        try {
            Future<String> consumer = executor.submit(() -> {
                consumerThread.set(Thread.currentThread());
                consumerReady.countDown();
                awaitGate(startConsumer);
                consumerCallingPoll.countDown();
                return queue.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            });
            assertTrue(consumerReady.await(WAIT_SECONDS, TimeUnit.SECONDS));
            startConsumer.countDown();
            assertTrue(consumerCallingPoll.await(WAIT_SECONDS, TimeUnit.SECONDS));
            waitUntilBlocked(consumerThread.get(), "LinkedBlockingQueue 消费者未进入等待状态");

            queue.put("message");
            assertEquals("message", consumer.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(0, queue.size());
        } finally {
            startConsumer.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证 SynchronousQueue 没有等待消费者时立即 offer 失败，集合容量始终为零。
     */
    @Test
    void shouldExposeZeroCapacityAndImmediateFailure() {
        SynchronousQueue<String> queue = new SynchronousQueue<>();

        assertFalse(queue.offer("orphan"));
        assertNull(queue.poll());
        assertNull(queue.peek());
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertEquals(0, queue.remainingCapacity());
    }

    /**
     * 验证公平和非公平构造模式都要求 DATA 与 REQUEST 当场配对。
     *
     * @throws Exception 等待直接交接失败
     */
    @Test
    void shouldHandoffInFairAndNonfairModes() throws Exception {
        assertSynchronousHandoff(false);
        assertSynchronousHandoff(true);
    }

    /**
     * 验证阻塞中的 put 响应中断，并且不会把待插入元素写入已满队列。
     *
     * @throws Exception 等待生产者进入阻塞、响应中断或执行器终止时失败
     */
    @Test
    void shouldInterruptBlockedArrayQueuePut() throws Exception {
        ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        queue.put("seed");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch producerCallingPut = new CountDownLatch(1);
        AtomicReference<Thread> producerThread = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        try {
            Future<?> producer = executor.submit(() -> {
                producerThread.set(Thread.currentThread());
                producerCallingPut.countDown();
                try {
                    queue.put("next");
                } catch (InterruptedException exception) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(producerCallingPut.await(WAIT_SECONDS, TimeUnit.SECONDS));
            waitUntilBlocked(producerThread.get(), "待中断生产者未进入等待状态");

            producerThread.get().interrupt();
            producer.get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertTrue(interrupted.get());
            assertEquals(1, queue.size());
            assertEquals("seed", queue.take());
        } finally {
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 在指定构造模式下执行一次带截止时间的 SynchronousQueue 交接。
     *
     * @param fair 是否使用公平 FIFO 等待策略
     * @throws Exception 等待消费者或生产者失败
     */
    private static void assertSynchronousHandoff(boolean fair) throws Exception {
        SynchronousQueue<String> queue = new SynchronousQueue<>(fair);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch consumerReady = new CountDownLatch(1);
        CountDownLatch startConsumer = new CountDownLatch(1);
        CountDownLatch consumerCallingPoll = new CountDownLatch(1);
        AtomicReference<Thread> consumerThread = new AtomicReference<>();
        try {
            Future<String> consumer = executor.submit(() -> {
                consumerThread.set(Thread.currentThread());
                consumerReady.countDown();
                awaitGate(startConsumer);
                consumerCallingPoll.countDown();
                return queue.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            });
            assertTrue(consumerReady.await(WAIT_SECONDS, TimeUnit.SECONDS));
            startConsumer.countDown();
            assertTrue(consumerCallingPoll.await(WAIT_SECONDS, TimeUnit.SECONDS));
            waitUntilBlocked(consumerThread.get(), "SynchronousQueue 消费者未发布等待节点");

            assertTrue(queue.offer("handoff", WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals("handoff", consumer.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(0, queue.size());
            assertEquals(0, queue.remainingCapacity());
        } finally {
            startConsumer.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 在限定时间内等待测试闸门，超时或中断时终止当前工作任务。
     *
     * @param gate 需要等待的闸门
     */
    private static void awaitGate(CountDownLatch gate) {
        try {
            if (!gate.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("BlockingQueue 测试闸门未按时打开");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 BlockingQueue 测试闸门时被中断", exception);
        }
    }

    /**
     * 在统一截止时间内等待线程进入阻塞状态，确保否定断言建立在真实等待之后。
     *
     * @param thread 预期进入等待的线程
     * @param message 未按时进入等待时的错误信息
     */
    private static void waitUntilBlocked(Thread thread, String message) {
        assertTrue(thread != null, message + "：线程引用为空");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() - deadline < 0) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            if (!thread.isAlive()) {
                throw new AssertionError(message + "：线程已提前结束");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError(message);
    }

    /**
     * 立即停止执行器并等待工作线程退出。
     *
     * @param executor 需要关闭的执行器
     * @throws InterruptedException 等待执行器终止时被中断
     */
    private static void shutdownNowAndAwait(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS),
                "BlockingQueue 测试执行器未在预期时间内终止");
    }
}
