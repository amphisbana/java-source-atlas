package io.github.javasourceatlas.jdk.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 使用确定性线程协作观察 synchronized、Object.wait/notify 与线程状态的调试入口。
 */
public final class SynchronizedMonitorDebugLab {

    private static final long WAIT_SECONDS = 5L;

    /**
     * 工具类不需要创建实例。
     */
    private SynchronizedMonitorDebugLab() {
    }

    /**
     * 按固定顺序执行全部 monitor 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws InterruptedException 等待线程协作时被中断
     */
    public static void main(String[] args) throws InterruptedException {
        printHeader("synchronized 可重入");
        observeReentrantEntry();

        printHeader("BLOCKED 与 WAITING 是两种等待");
        observeBlockedAndWaiting();

        printHeader("wait 完整释放并恢复重入层数");
        observeWaitReleaseAndReacquire();

        printHeader("notify 不会释放 monitor");
        observeNotifyDoesNotReleaseMonitor();

        printHeader("中断 wait 的收口语义");
        observeWaitInterruption();
    }

    /**
     * 在同一线程中连续两次进入同一个 monitor，证明 synchronized 允许重入。
     */
    static void observeReentrantEntry() {
        Object monitor = new Object();
        int[] depth = {0};

        synchronized (monitor) {
            depth[0] += 1;
            synchronized (monitor) {
                depth[0] += 1;
                System.out.printf("第二次进入同一 monitor，业务深度=%d，holdsLock=%s%n",
                        depth[0], Thread.holdsLock(monitor));
            }
        }
    }

    /**
     * 同时构造一个在 WaitSet 等待条件的线程和一个在入口竞争 monitor 的线程。
     *
     * @throws InterruptedException 等待线程协作时被中断
     */
    static void observeBlockedAndWaiting() throws InterruptedException {
        Object waitMonitor = new Object();
        MonitorCondition waitCondition = new MonitorCondition();
        CountDownLatch beforeWait = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            synchronized (waitMonitor) {
                beforeWait.countDown();
                while (!waitCondition.open) {
                    waitOnMonitor(waitMonitor, "WAITING 观察线程被中断");
                }
            }
        }, "monitor-waiter");

        Object blockedMonitor = new Object();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        Thread owner = new Thread(() -> {
            synchronized (blockedMonitor) {
                ownerEntered.countDown();
                awaitGate(releaseOwner, "monitor owner 未获准退出");
            }
        }, "monitor-owner");
        Thread contender = new Thread(() -> {
            contenderStarted.countDown();
            synchronized (blockedMonitor) {
                // 取得锁本身就是本场景需要观察的终点，不需要额外业务动作。
            }
        }, "monitor-contender");

        waiter.start();
        awaitGate(beforeWait, "waiter 未进入 synchronized");
        waitForState(waiter, Thread.State.WAITING);

        owner.start();
        awaitGate(ownerEntered, "owner 未取得 monitor");
        contender.start();
        awaitGate(contenderStarted, "contender 未开始竞争 monitor");
        waitForState(contender, Thread.State.BLOCKED);

        System.out.printf("waiter=%s（条件队列），contender=%s（入口竞争）%n",
                waiter.getState(), contender.getState());

        synchronized (waitMonitor) {
            waitCondition.open = true;
            waitMonitor.notifyAll();
        }
        releaseOwner.countDown();
        joinThread(waiter);
        joinThread(owner);
        joinThread(contender);
    }

    /**
     * 以两层重入调用 wait，验证等待时完整释放、返回时恢复原重入层数。
     *
     * @throws InterruptedException 等待线程协作时被中断
     */
    static void observeWaitReleaseAndReacquire() throws InterruptedException {
        Object monitor = new Object();
        MonitorCondition condition = new MonitorCondition();
        CountDownLatch beforeWait = new CountDownLatch(1);
        CountDownLatch innerExited = new CountDownLatch(1);
        CountDownLatch releaseOuter = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        CountDownLatch contenderAcquired = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            synchronized (monitor) {
                synchronized (monitor) {
                    beforeWait.countDown();
                    while (!condition.open) {
                        waitOnMonitor(monitor, "重入 wait 线程被中断");
                    }
                }
                innerExited.countDown();
                awaitGate(releaseOuter, "外层 synchronized 未获准退出");
            }
        }, "monitor-reentrant-waiter");
        Thread contender = new Thread(() -> {
            contenderStarted.countDown();
            synchronized (monitor) {
                contenderAcquired.countDown();
            }
        }, "monitor-after-wait-contender");

        waiter.start();
        awaitGate(beforeWait, "重入线程未准备 wait");
        waitForState(waiter, Thread.State.WAITING);

        // main 能在 waiter 等待期间进入同一 monitor，证明 wait 释放了全部重入层数。
        synchronized (monitor) {
            condition.open = true;
            monitor.notifyAll();
        }
        awaitGate(innerExited, "waiter 未在 notify 后重新取得 monitor");

        contender.start();
        awaitGate(contenderStarted, "竞争线程未开始执行");
        waitForState(contender, Thread.State.BLOCKED);
        System.out.printf("wait 返回并退出内层后 contender=%s，说明外层重入仍由 waiter 持有%n",
                contender.getState());

        releaseOuter.countDown();
        awaitGate(contenderAcquired, "外层 monitor 释放后 contender 仍未取得锁");
        joinThread(waiter);
        joinThread(contender);
    }

    /**
     * 让通知线程在 notify 后继续持锁，证明被通知线程不能立即从 wait 之后继续执行。
     *
     * @throws InterruptedException 等待线程协作时被中断
     */
    static void observeNotifyDoesNotReleaseMonitor() throws InterruptedException {
        Object monitor = new Object();
        MonitorCondition condition = new MonitorCondition();
        CountDownLatch beforeWait = new CountDownLatch(1);
        CountDownLatch notifyCalled = new CountDownLatch(1);
        CountDownLatch releaseNotifier = new CountDownLatch(1);
        CountDownLatch waiterResumed = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            synchronized (monitor) {
                beforeWait.countDown();
                while (!condition.open) {
                    waitOnMonitor(monitor, "notify 观察线程被中断");
                }
                waiterResumed.countDown();
            }
        }, "monitor-notified-waiter");
        Thread notifier = new Thread(() -> {
            synchronized (monitor) {
                condition.open = true;
                monitor.notify();
                notifyCalled.countDown();
                awaitGate(releaseNotifier, "notifier 未获准释放 monitor");
            }
        }, "monitor-notifier");

        waiter.start();
        awaitGate(beforeWait, "被通知线程未准备 wait");
        waitForState(waiter, Thread.State.WAITING);
        notifier.start();
        awaitGate(notifyCalled, "notifier 未调用 notify");
        waitForState(waiter, Thread.State.BLOCKED);

        System.out.printf("notify 已调用但 notifier 仍持锁：waiter=%s，是否已继续=%s%n",
                waiter.getState(), waiterResumed.getCount() == 0L);
        releaseNotifier.countDown();
        awaitGate(waiterResumed, "notifier 退出后 waiter 仍未恢复");
        joinThread(waiter);
        joinThread(notifier);
    }

    /**
     * 中断正在 wait 的线程，观察 InterruptedException 与中断标记清除语义。
     *
     * @throws InterruptedException 等待目标线程结束时被中断
     */
    static void observeWaitInterruption() throws InterruptedException {
        Object monitor = new Object();
        CountDownLatch beforeWait = new CountDownLatch(1);
        AtomicBoolean exceptionCaught = new AtomicBoolean();
        AtomicBoolean flagAfterCatch = new AtomicBoolean(true);
        Thread waiter = new Thread(() -> {
            synchronized (monitor) {
                beforeWait.countDown();
                try {
                    monitor.wait();
                } catch (InterruptedException exception) {
                    exceptionCaught.set(true);
                    flagAfterCatch.set(Thread.currentThread().isInterrupted());
                }
            }
        }, "monitor-interrupted-waiter");

        waiter.start();
        awaitGate(beforeWait, "中断实验线程未准备 wait");
        waitForState(waiter, Thread.State.WAITING);
        waiter.interrupt();
        joinThread(waiter);

        System.out.printf("捕获 InterruptedException=%s，catch 中中断标记=%s%n",
                exceptionCaught.get(), flagAfterCatch.get());
    }

    /**
     * 在已经持有 monitor 的调用方中执行条件等待，并把中断转成明确失败。
     *
     * @param monitor      当前线程已经持有的 monitor
     * @param errorMessage 中断时的场景说明
     */
    private static void waitOnMonitor(Object monitor, String errorMessage) {
        try {
            monitor.wait();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(errorMessage, exception);
        }
    }

    /**
     * 在限定时间内等待闸门，避免实验因线程协作异常永久挂起。
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
            throw new IllegalStateException("等待 monitor 实验闸门时被中断", exception);
        }
    }

    /**
     * 等待线程进入指定公开状态，避免使用固定 sleep 猜测调度时机。
     *
     * @param thread        目标线程
     * @param expectedState 预期状态
     * @throws InterruptedException 轮询期间被中断
     */
    private static void waitForState(Thread thread, Thread.State expectedState) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (thread.getState() != expectedState && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        if (thread.getState() != expectedState) {
            throw new IllegalStateException(
                    "线程 " + thread.getName() + " 未进入 " + expectedState + "，当前状态=" + thread.getState());
        }
    }

    /**
     * 有界等待线程结束，超时后中断并报告失败。
     *
     * @param thread 需要回收的线程
     * @throws InterruptedException 等待线程时被中断
     */
    private static void joinThread(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        if (thread.isAlive()) {
            thread.interrupt();
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
        if (thread.isAlive()) {
            throw new IllegalStateException("monitor 调试线程未在预期时间内结束：" + thread.getName());
        }
    }

    /**
     * 输出实验分段标题。
     *
     * @param title 分段标题
     */
    private static void printHeader(String title) {
        System.out.printf("%n=== %s ===%n", title);
    }

    /**
     * 受 monitor 保护的等待条件，所有读写都发生在 synchronized 内。
     */
    private static final class MonitorCondition {
        private boolean open;
    }
}
