package io.github.javasourceatlas.jdk.concurrent;

import java.util.ArrayList;
import java.util.Arrays;
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

/**
 * ConcurrentLinkedQueue 的公开行为与源码断点实验。
 */
public final class ConcurrentLinkedQueueDebugLab {

    private static final int WAIT_SECONDS = 5;

    /**
     * 工具类不需要实例化。
     */
    private ConcurrentLinkedQueueDebugLab() {
    }

    /**
     * 依次运行 FIFO、并发生产消费、弱一致遍历和安全发布实验。
     *
     * @param args 命令行参数，本实验不使用
     * @throws Exception 任一并发实验未在截止时间内完成
     */
    public static void main(String[] args) throws Exception {
        observeFifoAndNullBoundary();
        observeConcurrentProducers();
        observeConcurrentConsumers();
        observeWeakIterator();
        observePublication();
    }

    /**
     * 观察静默期 FIFO、空队列特殊值和 null 拒绝语义。
     */
    public static void observeFifoAndNullBoundary() {
        ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<String>();
        queue.offer("A");
        queue.offer("B");
        queue.offer("C");

        List<String> drained = Arrays.asList(queue.poll(), queue.poll(), queue.poll());
        require(drained.equals(Arrays.asList("A", "B", "C")), "静默期必须保持 FIFO");
        require(queue.poll() == null, "空队列 poll 必须返回 null");

        try {
            queue.offer(null);
            throw new IllegalStateException("ConcurrentLinkedQueue 不应接受 null");
        } catch (NullPointerException expected) {
            System.out.println("null 插入被拒绝：" + expected.getClass().getSimpleName());
        }
        System.out.println("FIFO 排空结果：" + drained);
    }

    /**
     * 让两个生产者同时入队，并验证各自程序顺序在最终 FIFO 中没有倒置。
     *
     * @throws Exception 线程未按时就绪或任务执行失败
     */
    public static void observeConcurrentProducers() throws Exception {
        final int itemsPerProducer = 12;
        ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<String>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<?> first = executor.submit(() -> offerSeries(queue, "P1", itemsPerProducer, ready, start));
            Future<?> second = executor.submit(() -> offerSeries(queue, "P2", itemsPerProducer, ready, start));
            require(ready.await(WAIT_SECONDS, TimeUnit.SECONDS), "两个生产者未按时就绪");
            start.countDown();
            first.get(WAIT_SECONDS, TimeUnit.SECONDS);
            second.get(WAIT_SECONDS, TimeUnit.SECONDS);

            int nextP1 = 0;
            int nextP2 = 0;
            String value;
            List<String> interleaving = new ArrayList<String>();
            while ((value = queue.poll()) != null) {
                interleaving.add(value);
                if (value.startsWith("P1-")) {
                    require(value.equals("P1-" + nextP1++), "P1 的局部顺序发生倒置");
                } else {
                    require(value.equals("P2-" + nextP2++), "P2 的局部顺序发生倒置");
                }
            }
            require(nextP1 == itemsPerProducer && nextP2 == itemsPerProducer, "并发 offer 出现元素丢失");
            System.out.println("两个生产者的合法交错：" + interleaving);
        } finally {
            start.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 让多个消费者竞争预装元素，并验证每个元素只被成功取走一次。
     *
     * @throws Exception 消费任务失败或超过截止时间
     */
    public static void observeConcurrentConsumers() throws Exception {
        final int elementCount = 48;
        final int consumerCount = 4;
        ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<Integer>();
        for (int i = 0; i < elementCount; i++) {
            queue.offer(i);
        }

        Set<Integer> observed = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(consumerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<Future<?>>();

        try {
            for (int i = 0; i < consumerCount; i++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    Integer value;
                    // 没有并发生产者，因此某线程观察到 null 时队列已没有尚未认领的节点。
                    while ((value = queue.poll()) != null) {
                        if (!observed.add(value)) {
                            throw new IllegalStateException("元素被重复返回：" + value);
                        }
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }
            require(observed.size() == elementCount, "并发 poll 出现丢失或重复");
            require(queue.isEmpty(), "消费完成后队列应为空");
            System.out.println("并发消费者唯一取得元素数：" + observed.size());
        } finally {
            start.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 观察迭代器在创建后队列继续增长时不会快速失败。
     */
    public static void observeWeakIterator() {
        ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<String>();
        queue.addAll(Arrays.asList("A", "B", "C"));
        Iterator<String> iterator = queue.iterator();
        queue.offer("D");

        List<String> observed = new ArrayList<String>();
        while (iterator.hasNext()) {
            observed.add(iterator.next());
        }

        require(observed.containsAll(Arrays.asList("A", "B", "C")), "创建时已有元素必须各被观察一次");
        require(observed.size() == new java.util.HashSet<String>(observed).size(), "同一迭代器不应重复返回元素");
        System.out.println("弱一致迭代结果（D 是否出现不作为依赖）：" + observed);
    }

    /**
     * 观察普通消息字段在 offer 成功后对 poll 到该消息的线程可见。
     *
     * @throws Exception 消费者没有在截止时间内取得消息
     */
    public static void observePublication() throws Exception {
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
                        throw new IllegalStateException("消费者等待消息超时");
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                return message.payload;
            });

            require(consumerReady.await(WAIT_SECONDS, TimeUnit.SECONDS), "消费者未按时开始");
            Message message = new Message();
            message.payload = "published-before-offer";
            queue.offer(message);

            require("published-before-offer".equals(consumed.get(WAIT_SECONDS, TimeUnit.SECONDS)),
                    "消费者没有看到入队前写入的消息字段");
            System.out.println("安全发布字段：" + message.payload);
        } finally {
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 按生产者前缀顺序写入一组元素。
     *
     * @param queue 共享无锁队列
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
     * 等待闸门，并把中断转换为明确的实验失败且恢复中断标记。
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
     * 强制停止线程池并确认工作线程退出。
     *
     * @param executor 要清理的线程池
     * @throws InterruptedException 当前线程在等待终止时被中断
     */
    private static void shutdownNowAndAwait(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        require(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "线程池未按时终止");
    }

    /**
     * 检查实验不变量，不满足时立即失败。
     *
     * @param condition 要验证的条件
     * @param message 失败说明
     */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * 用普通字段验证队列建立的安全发布边界。
     */
    private static final class Message {
        private String payload;
    }
}
