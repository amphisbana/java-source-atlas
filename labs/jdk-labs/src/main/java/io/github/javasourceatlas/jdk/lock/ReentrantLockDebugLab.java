package io.github.javasourceatlas.jdk.lock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 用公开 API 稳定触发 ReentrantLock、AQS 独占/共享模式和 Condition 核心分支的调试入口。
 */
public final class ReentrantLockDebugLab {

    private static final long WAIT_SECONDS = 5;

    /**
     * 工具类不需要创建实例。
     */
    private ReentrantLockDebugLab() {
    }

    /**
     * 按固定顺序运行全部锁、条件队列与共享同步器调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws InterruptedException 等待工作线程时被中断
     */
    public static void main(String[] args) throws InterruptedException {
        printHeader("可重入状态");
        observeReentrancy();

        printHeader("可中断获取");
        observeInterruptibleAcquire();

        printHeader("Condition 保存并恢复两层重入");
        observeConditionSignal();

        printHeader("公平锁排队竞争");
        observeFairQueuedAcquire();

        printHeader("CountDownLatch 共享传播");
        observeCountDownLatchPropagation();

        printHeader("Semaphore 共享许可传播");
        observeSemaphorePropagation();
    }

    /**
     * 同一线程连续获取两次锁，观察持有次数逐层增加和释放。
     */
    static void observeReentrancy() {
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        try {
            lock.lock();
            try {
                System.out.printf("重入后 holdCount=%d，当前线程持有=%s%n",
                        lock.getHoldCount(), lock.isHeldByCurrentThread());
            } finally {
                lock.unlock();
            }
            System.out.printf("释放一层后 holdCount=%d，仍持有=%s%n",
                    lock.getHoldCount(), lock.isHeldByCurrentThread());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 让工作线程在 lockInterruptibly 中排队，并通过中断取消获取。
     *
     * @return 工作线程是否在排队获取阶段响应了中断
     * @throws InterruptedException 等待工作线程时被中断
     */
    static boolean observeInterruptibleAcquire() throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();
        CountDownLatch attempting = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        lock.lock();
        Thread worker = new Thread(() -> {
            attempting.countDown();
            try {
                lock.lockInterruptibly();
                try {
                    failure.compareAndSet(null, new AssertionError("工作线程不应在中断前获得锁"));
                } finally {
                    lock.unlock();
                }
            } catch (InterruptedException exception) {
                interrupted.set(true);
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                finished.countDown();
            }
        }, "atlas-lock-waiter");

        try {
            worker.start();
            awaitGate(attempting, "等待线程未按时启动");
            waitUntilQueued(lock, worker, "线程未按时进入同步队列");
            worker.interrupt();
            awaitGate(finished, "等待线程未按时响应中断");
            throwIfFailed(failure.get(), "可中断获取实验失败");
            System.out.printf("排队获取已被中断=%s%n", interrupted.get());
            return interrupted.get();
        } finally {
            // 先释放主线程持有的锁，避免异常路径中工作线程无法退出同步队列。
            lock.unlock();
            interruptAndJoin("可中断获取实验", worker);
        }
    }

    /**
     * 让等待线程重入两次后进入条件队列，再验证 await 完整释放并恢复 state=2。
     *
     * @return await 前后持有次数及业务谓词观察结果
     * @throws InterruptedException 等待条件线程时被中断
     */
    static ConditionObservation observeConditionSignal() throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean ready = new AtomicBoolean();
        AtomicBoolean readyObserved = new AtomicBoolean();
        AtomicInteger holdCountBeforeAwait = new AtomicInteger();
        AtomicInteger holdCountAfterAwait = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread waiter = new Thread(() -> {
            lock.lock();
            lock.lock();
            try {
                holdCountBeforeAwait.set(lock.getHoldCount());
                waiting.countDown();
                while (!ready.get()) {
                    condition.await();
                }
                holdCountAfterAwait.set(lock.getHoldCount());
                readyObserved.set(ready.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failure.compareAndSet(null, exception);
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                // await 无论正常、中断还是超时返回，都会先恢复原重入层数；这里逐层释放干净。
                while (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
                finished.countDown();
            }
        }, "atlas-condition-waiter");

        try {
            waiter.start();
            awaitGate(waiting, "条件线程未按时启动");

            // 主线程取得锁，证明等待线程已经在 fullyRelease 中把 state 从 2 完整释放到 0。
            lock.lock();
            try {
                ready.set(true);
                condition.signal();
            } finally {
                lock.unlock();
            }

            awaitGate(finished, "条件线程未按时完成");
            throwIfFailed(failure.get(), "Condition 实验失败");
            ConditionObservation observation = new ConditionObservation(
                    holdCountBeforeAwait.get(), holdCountAfterAwait.get(), readyObserved.get());
            System.out.printf("await 前 holdCount=%d，返回后 holdCount=%d，ready=%s%n",
                    observation.getHoldCountBeforeAwait(), observation.getHoldCountAfterAwait(),
                    observation.isReadyObserved());
            return observation;
        } finally {
            // 失败路径既设置谓词又中断线程，避免等待者永久停留在 Condition 队列。
            ready.set(true);
            waiter.interrupt();
            interruptAndJoin("Condition 实验", waiter);
        }
    }

    /**
     * 让两个线程按确定顺序进入公平锁同步队列，再验证先排队者先获得锁。
     *
     * @return 两个线程的排队状态与实际获取顺序
     * @throws InterruptedException 等待排队线程时被中断
     */
    static FairLockObservation observeFairQueuedAcquire() throws InterruptedException {
        ReentrantLock fairLock = new ReentrantLock(true);
        CountDownLatch firstAttempting = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<String> acquisitionOrder = new ArrayList<>();

        Thread first = new Thread(() -> {
            firstAttempting.countDown();
            fairLock.lock();
            try {
                acquisitionOrder.add("first");
                firstAcquired.countDown();
                awaitWorkerGate(releaseFirst, failure);
            } finally {
                fairLock.unlock();
                finished.countDown();
            }
        }, "atlas-fair-first");

        Thread second = new Thread(() -> {
            secondAttempting.countDown();
            fairLock.lock();
            try {
                acquisitionOrder.add("second");
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                fairLock.unlock();
                finished.countDown();
            }
        }, "atlas-fair-second");

        boolean ownerLocked = false;
        boolean firstQueued = false;
        boolean secondQueued = false;
        try {
            fairLock.lock();
            ownerLocked = true;

            first.start();
            awaitGate(firstAttempting, "公平锁第一个线程未按时启动");
            waitUntilQueued(fairLock, first, "公平锁第一个线程未按时入队");
            firstQueued = true;

            second.start();
            awaitGate(secondAttempting, "公平锁第二个线程未按时启动");
            waitUntilQueued(fairLock, second, "公平锁第二个线程未按时入队");
            secondQueued = true;

            // 两个线程已按 first -> second 排入同一同步队列；释放后会进入 FairSync.tryAcquire。
            fairLock.unlock();
            ownerLocked = false;

            awaitGate(firstAcquired, "公平锁第一个排队线程未先获得锁");
            releaseFirst.countDown();
            awaitGate(finished, "公平锁排队线程未按时完成");
            throwIfFailed(failure.get(), "公平锁排队实验失败");

            FairLockObservation observation = new FairLockObservation(
                    fairLock.isFair(), firstQueued, secondQueued, String.join(" -> ", acquisitionOrder));
            System.out.printf("公平模式=%s，firstQueued=%s，secondQueued=%s，获取顺序=%s%n",
                    observation.isFair(), observation.isFirstQueued(), observation.isSecondQueued(),
                    observation.getAcquisitionOrder());
            return observation;
        } finally {
            releaseFirst.countDown();
            if (ownerLocked) {
                fairLock.unlock();
            }
            interruptAndJoin("公平锁排队实验", first, second);
        }
    }

    /**
     * 让三个等待者共享同一个 CountDownLatch，验证只有 state 降到 0 才传播唤醒。
     *
     * @return 第一次 countDown 后和最终打开后的观察结果
     * @throws InterruptedException 等待工作线程时被中断
     */
    static CountDownLatchObservation observeCountDownLatchPropagation() throws InterruptedException {
        int waiterCount = 3;
        CountDownLatch subject = new CountDownLatch(2);
        CountDownLatch started = new CountDownLatch(waiterCount);
        CountDownLatch finished = new CountDownLatch(waiterCount);
        AtomicInteger passed = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] waiters = new Thread[waiterCount];

        for (int index = 0; index < waiterCount; index++) {
            waiters[index] = createLatchWaiter(index + 1, subject, started, finished, passed, failure);
            waiters[index].start();
        }

        try {
            awaitGate(started, "CountDownLatch 等待线程未全部启动");
            for (Thread waiter : waiters) {
                waitUntilBlocked(waiter, "CountDownLatch 等待线程未进入共享等待");
            }

            subject.countDown();
            long countAfterFirst = subject.getCount();
            int passedAfterFirst = passed.get();

            subject.countDown();
            awaitGate(finished, "CountDownLatch 没有传播唤醒全部等待者");
            throwIfFailed(failure.get(), "CountDownLatch 共享传播实验失败");

            CountDownLatchObservation observation = new CountDownLatchObservation(
                    countAfterFirst, passedAfterFirst, passed.get());
            System.out.printf("第一次 countDown 后 count=%d、通过=%d；归零后通过=%d%n",
                    observation.getCountAfterFirst(), observation.getPassedAfterFirst(),
                    observation.getPassedAfterOpen());
            return observation;
        } finally {
            while (subject.getCount() > 0) {
                subject.countDown();
            }
            interruptAndJoin("CountDownLatch 共享传播实验", waiters);
        }
    }

    /**
     * 一次释放两个 Semaphore 许可，观察共享获取成功后继续传播给后继节点。
     *
     * @return 释放前是否存在排队线程、最终通过数与剩余许可数
     * @throws InterruptedException 等待工作线程时被中断
     */
    static SemaphoreObservation observeSemaphorePropagation() throws InterruptedException {
        int waiterCount = 2;
        Semaphore semaphore = new Semaphore(0, true);
        CountDownLatch started = new CountDownLatch(waiterCount);
        CountDownLatch finished = new CountDownLatch(waiterCount);
        AtomicInteger passed = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] waiters = new Thread[waiterCount];

        for (int index = 0; index < waiterCount; index++) {
            waiters[index] = createSemaphoreWaiter(index + 1, semaphore, started, finished, passed, failure);
            waiters[index].start();
        }

        try {
            awaitGate(started, "Semaphore 等待线程未全部启动");
            for (Thread waiter : waiters) {
                waitUntilBlocked(waiter, "Semaphore 等待线程未进入共享等待");
            }
            boolean queuedBeforeRelease = semaphore.hasQueuedThreads();

            semaphore.release(waiterCount);
            awaitGate(finished, "Semaphore 许可没有传播给全部等待者");
            throwIfFailed(failure.get(), "Semaphore 共享传播实验失败");

            SemaphoreObservation observation = new SemaphoreObservation(
                    queuedBeforeRelease, passed.get(), semaphore.availablePermits());
            System.out.printf("释放前存在排队线程=%s，通过=%d，剩余许可=%d%n",
                    observation.hasQueuedBeforeRelease(), observation.getPassed(),
                    observation.getAvailablePermits());
            return observation;
        } finally {
            // 异常路径补足许可并中断线程，确保尚未获得许可的等待者也能退出。
            semaphore.release(waiterCount);
            interruptAndJoin("Semaphore 共享传播实验", waiters);
        }
    }

    /**
     * 创建一个等待 CountDownLatch 打开的工作线程。
     *
     * @param number   线程编号
     * @param subject  被观察的共享闩锁
     * @param started  启动通知
     * @param finished 完成通知
     * @param passed   已通过等待的线程数
     * @param failure  工作线程失败记录
     * @return 尚未启动的工作线程
     */
    private static Thread createLatchWaiter(int number, CountDownLatch subject, CountDownLatch started,
                                            CountDownLatch finished, AtomicInteger passed,
                                            AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            started.countDown();
            try {
                subject.await();
                passed.incrementAndGet();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failure.compareAndSet(null, exception);
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                finished.countDown();
            }
        }, "atlas-latch-waiter-" + number);
    }

    /**
     * 创建一个等待 Semaphore 许可的工作线程。
     *
     * @param number    线程编号
     * @param semaphore 被观察的共享信号量
     * @param started   启动通知
     * @param finished  完成通知
     * @param passed    已取得许可的线程数
     * @param failure   工作线程失败记录
     * @return 尚未启动的工作线程
     */
    private static Thread createSemaphoreWaiter(int number, Semaphore semaphore, CountDownLatch started,
                                                CountDownLatch finished, AtomicInteger passed,
                                                AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            started.countDown();
            try {
                semaphore.acquire();
                passed.incrementAndGet();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failure.compareAndSet(null, exception);
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                finished.countDown();
            }
        }, "atlas-semaphore-waiter-" + number);
    }

    /**
     * 在线程内部等待闸门，并把中断记录为实验失败。
     *
     * @param gate    被等待的闸门
     * @param failure 工作线程失败记录
     */
    private static void awaitWorkerGate(CountDownLatch gate, AtomicReference<Throwable> failure) {
        try {
            gate.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failure.compareAndSet(null, exception);
        }
    }

    /**
     * 在有限时间内等待闸门归零。
     *
     * @param gate         被等待的闸门
     * @param timeoutError 超时错误信息
     * @throws InterruptedException 当前线程等待时被中断
     */
    private static void awaitGate(CountDownLatch gate, String timeoutError) throws InterruptedException {
        if (!gate.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException(timeoutError);
        }
    }

    /**
     * 在有限时间内等待指定线程真正进入锁的同步队列。
     *
     * @param lock         被竞争的锁
     * @param thread       等待入队的线程
     * @param timeoutError 超时错误信息
     */
    private static void waitUntilQueued(ReentrantLock lock, Thread thread, String timeoutError) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!lock.hasQueuedThread(thread)) {
            if (!thread.isAlive() || System.nanoTime() >= deadline) {
                throw new IllegalStateException(timeoutError);
            }
            Thread.yield();
        }
    }

    /**
     * 等待线程进入 WAITING、TIMED_WAITING 或 BLOCKED，证明它已到达同步等待点。
     *
     * @param thread       被观察的线程
     * @param timeoutError 超时错误信息
     */
    private static void waitUntilBlocked(Thread thread, String timeoutError) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (true) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING
                    || state == Thread.State.TIMED_WAITING
                    || state == Thread.State.BLOCKED) {
                return;
            }
            if (!thread.isAlive() || System.nanoTime() >= deadline) {
                throw new IllegalStateException(timeoutError + "，当前状态=" + state);
            }
            Thread.yield();
        }
    }

    /**
     * 把工作线程异常提升到主线程，避免实验只打印错误后继续假通过。
     *
     * @param failure 工作线程捕获的异常
     * @param message 主线程错误信息
     */
    private static void throwIfFailed(Throwable failure, String message) {
        if (failure != null) {
            throw new IllegalStateException(message, failure);
        }
    }

    /**
     * 中断并限时回收全部工作线程；即使清理期间当前线程被中断，也会继续处理其他线程。
     *
     * @param context 场景名称
     * @param threads 需要回收的线程
     */
    private static void interruptAndJoin(String context, Thread... threads) {
        for (Thread thread : threads) {
            if (thread != null && thread.isAlive()) {
                thread.interrupt();
            }
        }

        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        for (Thread thread : threads) {
            if (thread == null) {
                continue;
            }
            while (thread.isAlive()) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }
                try {
                    long waitMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                    thread.join(waitMillis);
                } catch (InterruptedException exception) {
                    interrupted = true;
                    thread.interrupt();
                }
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        for (Thread thread : threads) {
            if (thread != null && thread.isAlive()) {
                throw new IllegalStateException(context + "未能回收线程：" + thread.getName());
            }
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

    /**
     * 保存 Condition 完整释放和恢复重入状态的观察值。
     */
    static final class ConditionObservation {
        private final int holdCountBeforeAwait;
        private final int holdCountAfterAwait;
        private final boolean readyObserved;

        /**
         * 创建 Condition 观察结果。
         *
         * @param holdCountBeforeAwait await 前持有次数
         * @param holdCountAfterAwait  await 返回后持有次数
         * @param readyObserved        是否在恢复锁后看到了业务谓词
         */
        private ConditionObservation(int holdCountBeforeAwait, int holdCountAfterAwait,
                                     boolean readyObserved) {
            this.holdCountBeforeAwait = holdCountBeforeAwait;
            this.holdCountAfterAwait = holdCountAfterAwait;
            this.readyObserved = readyObserved;
        }

        /**
         * 返回 await 前的重入层数。
         *
         * @return await 前的 holdCount
         */
        int getHoldCountBeforeAwait() {
            return holdCountBeforeAwait;
        }

        /**
         * 返回 await 正常返回后的重入层数。
         *
         * @return await 返回后的 holdCount
         */
        int getHoldCountAfterAwait() {
            return holdCountAfterAwait;
        }

        /**
         * 返回等待线程是否看到了已满足的业务谓词。
         *
         * @return 已观察到 ready 时返回 true
         */
        boolean isReadyObserved() {
            return readyObserved;
        }
    }

    /**
     * 保存公平锁真实排队实验的观察值。
     */
    static final class FairLockObservation {
        private final boolean fair;
        private final boolean firstQueued;
        private final boolean secondQueued;
        private final String acquisitionOrder;

        /**
         * 创建公平锁排队观察结果。
         *
         * @param fair             锁是否为公平模式
         * @param firstQueued      第一个线程是否确认入队
         * @param secondQueued     第二个线程是否确认入队
         * @param acquisitionOrder 实际获得锁的顺序
         */
        private FairLockObservation(boolean fair, boolean firstQueued, boolean secondQueued,
                                    String acquisitionOrder) {
            this.fair = fair;
            this.firstQueued = firstQueued;
            this.secondQueued = secondQueued;
            this.acquisitionOrder = acquisitionOrder;
        }

        /**
         * 返回锁是否为公平模式。
         *
         * @return 公平模式返回 true
         */
        boolean isFair() {
            return fair;
        }

        /**
         * 返回第一个线程是否已确认进入同步队列。
         *
         * @return 已入队返回 true
         */
        boolean isFirstQueued() {
            return firstQueued;
        }

        /**
         * 返回第二个线程是否已确认进入同步队列。
         *
         * @return 已入队返回 true
         */
        boolean isSecondQueued() {
            return secondQueued;
        }

        /**
         * 返回线程实际获得锁的顺序。
         *
         * @return 以箭头连接的线程顺序
         */
        String getAcquisitionOrder() {
            return acquisitionOrder;
        }
    }

    /**
     * 保存 CountDownLatch 共享传播实验的观察值。
     */
    static final class CountDownLatchObservation {
        private final long countAfterFirst;
        private final int passedAfterFirst;
        private final int passedAfterOpen;

        /**
         * 创建 CountDownLatch 观察结果。
         *
         * @param countAfterFirst  第一次 countDown 后的计数
         * @param passedAfterFirst 第一次 countDown 后通过的线程数
         * @param passedAfterOpen  计数归零后通过的线程数
         */
        private CountDownLatchObservation(long countAfterFirst, int passedAfterFirst,
                                          int passedAfterOpen) {
            this.countAfterFirst = countAfterFirst;
            this.passedAfterFirst = passedAfterFirst;
            this.passedAfterOpen = passedAfterOpen;
        }

        /**
         * 返回第一次 countDown 后的计数。
         *
         * @return 剩余计数
         */
        long getCountAfterFirst() {
            return countAfterFirst;
        }

        /**
         * 返回第一次 countDown 后已通过的线程数。
         *
         * @return 已通过线程数
         */
        int getPassedAfterFirst() {
            return passedAfterFirst;
        }

        /**
         * 返回计数归零后已通过的线程数。
         *
         * @return 已通过线程总数
         */
        int getPassedAfterOpen() {
            return passedAfterOpen;
        }
    }

    /**
     * 保存 Semaphore 共享许可传播实验的观察值。
     */
    static final class SemaphoreObservation {
        private final boolean queuedBeforeRelease;
        private final int passed;
        private final int availablePermits;

        /**
         * 创建 Semaphore 观察结果。
         *
         * @param queuedBeforeRelease 释放许可前是否存在排队线程
         * @param passed              取得许可的线程数
         * @param availablePermits    实验完成后的剩余许可
         */
        private SemaphoreObservation(boolean queuedBeforeRelease, int passed, int availablePermits) {
            this.queuedBeforeRelease = queuedBeforeRelease;
            this.passed = passed;
            this.availablePermits = availablePermits;
        }

        /**
         * 返回释放许可前是否有线程排队。
         *
         * @return 存在排队线程时返回 true
         */
        boolean hasQueuedBeforeRelease() {
            return queuedBeforeRelease;
        }

        /**
         * 返回取得许可并通过的线程数。
         *
         * @return 已通过线程数
         */
        int getPassed() {
            return passed;
        }

        /**
         * 返回实验结束时剩余许可数。
         *
         * @return 剩余许可数
         */
        int getAvailablePermits() {
            return availablePermits;
        }
    }
}
