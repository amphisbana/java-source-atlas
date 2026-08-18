package io.github.javasourceatlas.jdk.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConcurrentLinkedQueue 公开契约的行为测试。
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class ConcurrentLinkedQueueBehaviorTest {

    private static final int WAIT_SECONDS = 5;

    /**
     * 验证静默期 FIFO、空队列返回值和 null 禁止规则。
     */
    @Test
    void shouldKeepFifoAndRejectNull() {
        ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<String>();

        assertTrue(queue.offer("A"));
        assertTrue(queue.offer("B"));
        assertTrue(queue.offer("C"));
        assertEquals("A", queue.poll());
        assertEquals("B", queue.poll());
        assertEquals("C", queue.poll());
        assertNull(queue.poll());
        assertThrows(NullPointerException.class, () -> queue.offer(null));
    }

    /**
     * 验证两个并发生产者的元素都被保留，并保持各自程序顺序。
     *
     * @throws Exception 生产任务失败或超过截止时间
     */
    @Test
    void shouldPreserveEachProducersOrder() throws Exception {
        final int itemsPerProducer = 100;
        ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<String>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<?> first = executor.submit(() -> offerSeries(queue, "P1", itemsPerProducer, ready, start));
            Future<?> second = executor.submit(() -> offerSeries(queue, "P2", itemsPerProducer, ready, start));
            assertTrue(ready.await(WAIT_SECONDS, TimeUnit.SECONDS));
            start.countDown();
            first.get(WAIT_SECONDS, TimeUnit.SECONDS);
            second.get(WAIT_SECONDS, TimeUnit.SECONDS);

            int nextP1 = 0;
            int nextP2 = 0;
            String value;
            while ((value = queue.poll()) != null) {
                if (value.startsWith("P1-")) {
                    assertEquals("P1-" + nextP1++, value);
                } else {
                    assertEquals("P2-" + nextP2++, value);
                }
            }
            assertEquals(itemsPerProducer, nextP1);
            assertEquals(itemsPerProducer, nextP2);
        } finally {
            start.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证多个消费者不会把同一个预装元素成功返回两次。
     *
     * @throws Exception 消费任务失败或超过截止时间
     */
    @Test
    void shouldReturnEachElementToOnlyOneConsumer() throws Exception {
        final int elementCount = 500;
        ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<Integer>();
        for (int i = 0; i < elementCount; i++) {
            queue.offer(i);
        }

        Set<Integer> observed = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<Future<?>>();

        try {
            for (int i = 0; i < 4; i++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    Integer value;
                    // 预装完成后不再生产，null 表示没有尚未被任何线程认领的节点。
                    while ((value = queue.poll()) != null) {
                        assertTrue(observed.add(value), "同一元素被重复返回：" + value);
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }

            assertEquals(elementCount, observed.size());
            assertTrue(queue.isEmpty());
        } finally {
            start.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 验证弱一致迭代不会快速失败，并且创建时已有元素各返回一次。
     */
    @Test
    void shouldIterateWeaklyWithoutDuplicateExistingElements() {
        ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<String>();
        queue.addAll(Arrays.asList("A", "B", "C"));
        Iterator<String> iterator = queue.iterator();
        queue.offer("D");

        List<String> observed = new ArrayList<String>();
        while (iterator.hasNext()) {
            observed.add(iterator.next());
        }

        assertTrue(observed.containsAll(Arrays.asList("A", "B", "C")));
        assertEquals(observed.size(), new HashSet<String>(observed).size());
    }

    /**
     * 验证迭代器 remove 删除最后返回元素，而不会破坏后续 FIFO。
     */
    @Test
    void shouldRemoveLastReturnedIteratorElement() {
        ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<String>();
        queue.addAll(Arrays.asList("A", "B", "C"));
        Iterator<String> iterator = queue.iterator();

        assertEquals("A", iterator.next());
        iterator.remove();

        assertFalse(queue.contains("A"));
        assertEquals("B", queue.poll());
        assertEquals("C", queue.poll());
        assertNull(queue.poll());
    }

    /**
     * 验证入队前普通字段写入对成功取得该对象的消费者可见。
     *
     * @throws Exception 消费者没有在截止时间内取得消息
     */
    @Test
    void shouldPublishPriorActionsToConsumer() throws Exception {
        ConcurrentLinkedQueue<Message> queue = new ConcurrentLinkedQueue<Message>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch consumerReady = new CountDownLatch(1);

        try {
            Future<String> consumed = executor.submit(() -> {
                consumerReady.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
                Message message;
                while ((message = queue.poll()) == null) {
                    if (System.nanoTime() >= deadline) {
                        throw new IllegalStateException("等待消息超时");
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                return message.payload;
            });

            assertTrue(consumerReady.await(WAIT_SECONDS, TimeUnit.SECONDS));
            Message message = new Message();
            message.payload = "visible";
            queue.offer(message);

            assertEquals("visible", consumed.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 按固定前缀和递增序号写入队列。
     *
     * @param queue 共享队列
     * @param producer 生产者前缀
     * @param count 写入数量
     * @param ready 就绪闸门
     * @param start 开始闸门
     */
    private static void offerSeries(ConcurrentLinkedQueue<String> queue,
                                    String producer,
                                    int count,
                                    CountDownLatch ready,
                                    CountDownLatch start) {
        ready.countDown();
        await(start);
        for (int i = 0; i < count; i++) {
            queue.offer(producer + "-" + i);
        }
    }

    /**
     * 在固定截止时间内等待闸门，并把中断转换为任务失败。
     *
     * @param latch 要等待的闸门
     */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待闸门超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待闸门时被中断", e);
        }
    }

    /**
     * 停止线程池并确认没有测试线程遗留。
     *
     * @param executor 要清理的线程池
     * @throws InterruptedException 当前线程在等待终止时被中断
     */
    private static void shutdownNowAndAwait(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    /**
     * 使用普通字段承载入队前写入的数据。
     */
    private static final class Message {
        private String payload;
    }
}
