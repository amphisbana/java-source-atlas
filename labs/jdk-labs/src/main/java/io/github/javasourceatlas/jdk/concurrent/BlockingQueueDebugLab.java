package io.github.javasourceatlas.jdk.concurrent;

import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * 用公开 API 稳定触发 BlockingQueue 容量等待、边界通知和直接交接的调试入口。
 */
public final class BlockingQueueDebugLab {

    private static final long WAIT_SECONDS = 5;
    private static final long OBSERVE_MILLIS = 100;

    /**
     * 工具类不需要创建实例。
     */
    private BlockingQueueDebugLab() {
    }

    /**
     * 按固定顺序运行全部 BlockingQueue 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws Exception 等待并发交接失败或被中断
     */
    public static void main(String[] args) throws Exception {
        printHeader("四组方法的失败语义");
        observeMethodFamilies();

        printHeader("ArrayBlockingQueue 满队列等待");
        observeArrayQueuePutWait();

        printHeader("LinkedBlockingQueue 容量边界通知");
        observeLinkedQueueBoundarySignals();

        printHeader("SynchronousQueue 直接交接");
        observeSynchronousHandoff();

        printHeader("SynchronousQueue 立即失败");
        observeSynchronousImmediateFailure();
    }

    /**
     * 使用容量为一的队列对照异常、特殊返回值和定时等待三类失败结果。
     *
     * @throws InterruptedException 等待定时 poll 时被中断
     */
    static void observeMethodFamilies() throws InterruptedException {
        ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        boolean firstOffer = queue.offer("seed");
        boolean fullOffer = queue.offer("extra");
        boolean addRejected = false;
        try {
            queue.add("extra");
        } catch (IllegalStateException exception) {
            addRejected = true;
        }

        String removed = queue.poll();
        String emptyPoll = queue.poll();
        boolean removeRejected = false;
        try {
            queue.remove();
        } catch (NoSuchElementException exception) {
            removeRejected = true;
        }
        String timedPoll = queue.poll(OBSERVE_MILLIS, TimeUnit.MILLISECONDS);

        System.out.printf(
                "首次 offer=%s，满队列 offer=%s，满队列 add 抛异常=%s%n",
                firstOffer, fullOffer, addRejected);
        System.out.printf(
                "poll 取出=%s，空 poll=%s，空 remove 抛异常=%s，定时 poll=%s%n",
                removed, emptyPoll, removeRejected, timedPoll);
    }

    /**
     * 让生产者在已满的 ArrayBlockingQueue 上执行 put，再由消费者释放一个槽位。
     *
     * @throws InterruptedException 等待生产者和队列操作时被中断
     */
    static void observeArrayQueuePutWait() throws InterruptedException {
        ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        queue.put("seed");
        CountDownLatch producerStarted = new CountDownLatch(1);
        CountDownLatch producerFinished = new CountDownLatch(1);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            producerStarted.countDown();
            try {
                queue.put("next");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                producerFailure.set(exception);
            } catch (Throwable failure) {
                producerFailure.set(failure);
            } finally {
                producerFinished.countDown();
            }
        }, "blocking-array-producer");

        producer.start();
        try {
            awaitGate(producerStarted, "ArrayBlockingQueue 生产者未按时启动");
            waitUntilBlocked(producer, "ArrayBlockingQueue 生产者未进入 notFull 等待");
            // 队列保持满时 put 不可能成功；有限观察窗只用于在控制台确认线程仍未返回。
            boolean completedWhileFull = producerFinished.await(
                    OBSERVE_MILLIS, TimeUnit.MILLISECONDS);
            String first = queue.take();
            awaitGate(producerFinished, "释放数组队列空间后生产者仍未完成");
            String second = queue.take();

            System.out.printf(
                    "满队列时 put 已完成=%s，take 依次得到=%s、%s%n",
                    completedWhileFull, first, second);
        } finally {
            interruptAndJoin(producer);
        }
        throwIfFailed(producerFailure.get(), "ArrayBlockingQueue 生产者执行失败");
    }

    /**
     * 依次触发 LinkedBlockingQueue 的空转非空、满转非满两条跨锁通知边界。
     *
     * @throws Exception 等待消费者、生产者或 Future 结果失败
     */
    static void observeLinkedQueueBoundarySignals() throws Exception {
        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch consumerReady = new CountDownLatch(1);
        CountDownLatch startConsumer = new CountDownLatch(1);
        CountDownLatch consumerCallingPoll = new CountDownLatch(1);
        AtomicReference<Thread> consumerThread = new AtomicReference<>();
        CountDownLatch producerReady = new CountDownLatch(1);
        CountDownLatch startProducer = new CountDownLatch(1);
        CountDownLatch producerCallingPut = new CountDownLatch(1);
        AtomicReference<Thread> producerThread = new AtomicReference<>();
        CountDownLatch producerFinished = new CountDownLatch(1);
        try {
            Future<String> firstTaken = executor.submit(() -> {
                consumerThread.set(Thread.currentThread());
                consumerReady.countDown();
                awaitGate(startConsumer, "链表队列消费者未获准开始");
                consumerCallingPoll.countDown();
                return queue.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            });
            awaitGate(consumerReady, "链表队列消费者未按时就绪");
            startConsumer.countDown();
            awaitGate(consumerCallingPoll, "链表队列消费者未进入 poll 调用");
            waitUntilBlocked(consumerThread.get(), "链表队列消费者未进入 notEmpty 等待");

            // 这次 0 → 1 更新会执行 signalNotEmpty，并取得 takeLock 通知消费端。
            queue.put("first");
            String first = firstTaken.get(WAIT_SECONDS, TimeUnit.SECONDS);

            queue.put("second");
            queue.put("third");
            Future<?> fourthPut = executor.submit(() -> {
                producerThread.set(Thread.currentThread());
                producerReady.countDown();
                awaitGate(startProducer, "链表队列生产者未获准开始");
                producerCallingPut.countDown();
                try {
                    queue.put("fourth");
                } finally {
                    producerFinished.countDown();
                }
                return null;
            });
            awaitGate(producerReady, "链表队列生产者未按时就绪");
            startProducer.countDown();
            awaitGate(producerCallingPut, "链表队列生产者未进入 put 调用");
            waitUntilBlocked(producerThread.get(), "链表队列生产者未进入 notFull 等待");
            boolean completedWhileFull = producerFinished.await(
                    OBSERVE_MILLIS, TimeUnit.MILLISECONDS);

            // 这次 capacity → capacity-1 更新会执行 signalNotFull，随后生产者重新竞争 putLock。
            String second = queue.take();
            fourthPut.get(WAIT_SECONDS, TimeUnit.SECONDS);
            String third = queue.take();
            String fourth = queue.take();

            System.out.printf(
                    "空转非空交接=%s，满时 put 已完成=%s，后续顺序=%s、%s、%s%n",
                    first, completedWhileFull, second, third, fourth);
        } finally {
            // 任一前置步骤失败时都打开闸门并中断阻塞操作，避免实验遗留线程。
            startConsumer.countDown();
            startProducer.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 分别使用非公平和公平构造模式完成一次 SynchronousQueue 定时交接。
     *
     * @throws Exception 等待直接交接失败或被中断
     */
    static void observeSynchronousHandoff() throws Exception {
        runSynchronousHandoff(false);
        runSynchronousHandoff(true);
    }

    /**
     * 验证没有互补等待者时 SynchronousQueue 的立即方法返回失败特殊值。
     */
    static void observeSynchronousImmediateFailure() {
        SynchronousQueue<String> queue = new SynchronousQueue<>();
        boolean offered = queue.offer("orphan");
        String polled = queue.poll();

        System.out.printf(
                "offer=%s，poll=%s，size=%d，remainingCapacity=%d%n",
                offered, polled, queue.size(), queue.remainingCapacity());
    }

    /**
     * 在指定构造模式下让一个消费者与一个生产者完成零容量交接。
     *
     * @param fair 是否使用公平 FIFO 等待策略
     * @throws Exception 等待消费者或生产者失败
     */
    private static void runSynchronousHandoff(boolean fair) throws Exception {
        SynchronousQueue<String> queue = new SynchronousQueue<>(fair);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch consumerReady = new CountDownLatch(1);
        CountDownLatch startConsumer = new CountDownLatch(1);
        CountDownLatch consumerCallingPoll = new CountDownLatch(1);
        AtomicReference<Thread> consumerThread = new AtomicReference<>();
        try {
            Future<String> received = executor.submit(() -> {
                consumerThread.set(Thread.currentThread());
                consumerReady.countDown();
                awaitGate(startConsumer, "直接交接消费者未获准开始");
                consumerCallingPoll.countDown();
                return queue.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            });
            awaitGate(consumerReady, "直接交接消费者未按时就绪");
            startConsumer.countDown();
            awaitGate(consumerCallingPoll, "直接交接消费者未进入 poll 调用");
            waitUntilBlocked(consumerThread.get(), "直接交接消费者未发布 REQUEST 等待节点");

            boolean handedOff = queue.offer("handoff", WAIT_SECONDS, TimeUnit.SECONDS);
            String value = received.get(WAIT_SECONDS, TimeUnit.SECONDS);
            System.out.printf(
                    "构造模式 fair=%s，交接成功=%s，消费值=%s，size=%d，remainingCapacity=%d%n",
                    fair, handedOff, value, queue.size(), queue.remainingCapacity());
        } finally {
            startConsumer.countDown();
            shutdownNowAndAwait(executor);
        }
    }

    /**
     * 在限定时间内等待闸门打开，避免实验因线程协作异常永久挂起。
     *
     * @param gate         需要等待的闸门
     * @param timeoutError 超时错误信息
     */
    private static void awaitGate(CountDownLatch gate, String timeoutError) {
        try {
            if (!gate.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(timeoutError);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待实验闸门时被中断", exception);
        }
    }

    /**
     * 在统一截止时间内等待线程进入队列阻塞状态，避免对端操作抢在等待节点发布之前发生。
     *
     * @param thread 预期进入等待的工作线程
     * @param timeoutError 超时时报告的场景说明
     */
    private static void waitUntilBlocked(Thread thread, String timeoutError) {
        if (thread == null) {
            throw new IllegalStateException(timeoutError + "：工作线程引用为空");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() - deadline < 0) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            if (!thread.isAlive()) {
                throw new IllegalStateException(timeoutError + "：线程已提前结束");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new IllegalStateException(timeoutError);
    }

    /**
     * 中断并回收独立实验线程，确保异常路径不会留下阻塞的 put。
     *
     * @param thread 需要回收的线程
     * @throws InterruptedException 等待线程结束时被中断
     */
    private static void interruptAndJoin(Thread thread) throws InterruptedException {
        if (thread.isAlive()) {
            thread.interrupt();
        }
        thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        if (thread.isAlive()) {
            throw new IllegalStateException("实验线程未在预期时间内终止");
        }
    }

    /**
     * 立即关闭执行器并等待工作线程退出。
     *
     * @param executor 需要关闭的执行器
     * @throws InterruptedException 等待执行器终止时被中断
     */
    private static void shutdownNowAndAwait(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("BlockingQueue 实验执行器未按时终止");
        }
    }

    /**
     * 把工作线程异常传播给主实验线程，避免只打印部分结果后静默成功。
     *
     * @param failure 工作线程捕获的异常
     * @param message 传播异常时使用的说明
     */
    private static void throwIfFailed(Throwable failure, String message) {
        if (failure != null) {
            throw new IllegalStateException(message, failure);
        }
    }

    /**
     * 打印场景标题，使控制台输出与断点实验步骤保持一致。
     *
     * @param title 场景名称
     */
    private static void printHeader(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }
}
