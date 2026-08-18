package io.github.javasourceatlas.jdk.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 synchronized、wait/notify 与线程状态的公开行为边界。
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class SynchronizedMonitorBehaviorTest {

    private static final long WAIT_SECONDS = 5L;

    /**
     * 验证同一线程可以重复进入同一个 monitor，并按进入次数逐层退出。
     */
    @Test
    void shouldReenterSameMonitor() {
        Object monitor = new Object();
        AtomicInteger entries = new AtomicInteger();

        synchronized (monitor) {
            entries.incrementAndGet();
            synchronized (monitor) {
                entries.incrementAndGet();
                assertTrue(Thread.holdsLock(monitor));
            }
            assertTrue(Thread.holdsLock(monitor));
        }

        assertEquals(2, entries.get());
        assertFalse(Thread.holdsLock(monitor));
    }

    /**
     * 验证条件等待使用 WAITING，入口锁竞争使用 BLOCKED。
     *
     * @throws Exception 等待线程协作失败或被中断
     */
    @Test
    void shouldDistinguishWaitingFromBlocked() throws Exception {
        Object waitMonitor = new Object();
        AtomicBoolean open = new AtomicBoolean();
        CountDownLatch beforeWait = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            synchronized (waitMonitor) {
                beforeWait.countDown();
                while (!open.get()) {
                    waitOnMonitor(waitMonitor);
                }
            }
        });

        Object blockedMonitor = new Object();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        Thread owner = new Thread(() -> {
            synchronized (blockedMonitor) {
                ownerEntered.countDown();
                awaitGate(releaseOwner);
            }
        });
        Thread contender = new Thread(() -> {
            contenderStarted.countDown();
            synchronized (blockedMonitor) {
                // 取得 monitor 后即可结束。
            }
        });

        waiter.start();
        assertTrue(beforeWait.await(WAIT_SECONDS, TimeUnit.SECONDS));
        waitForState(waiter, Thread.State.WAITING);
        owner.start();
        assertTrue(ownerEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
        contender.start();
        assertTrue(contenderStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        waitForState(contender, Thread.State.BLOCKED);

        assertEquals(Thread.State.WAITING, waiter.getState());
        assertEquals(Thread.State.BLOCKED, contender.getState());

        synchronized (waitMonitor) {
            open.set(true);
            waitMonitor.notifyAll();
        }
        releaseOwner.countDown();
        joinThread(waiter);
        joinThread(owner);
        joinThread(contender);
    }

    /**
     * 验证两层重入中的 wait 会完整释放 monitor，返回时又恢复原重入层数。
     *
     * @throws Exception 等待线程协作失败或被中断
     */
    @Test
    void shouldReleaseAndRestoreFullReentrantDepthAroundWait() throws Exception {
        Object monitor = new Object();
        AtomicBoolean open = new AtomicBoolean();
        CountDownLatch beforeWait = new CountDownLatch(1);
        CountDownLatch innerExited = new CountDownLatch(1);
        CountDownLatch releaseOuter = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        CountDownLatch contenderAcquired = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            synchronized (monitor) {
                synchronized (monitor) {
                    beforeWait.countDown();
                    while (!open.get()) {
                        waitOnMonitor(monitor);
                    }
                }
                innerExited.countDown();
                awaitGate(releaseOuter);
            }
        });
        Thread contender = new Thread(() -> {
            contenderStarted.countDown();
            synchronized (monitor) {
                contenderAcquired.countDown();
            }
        });

        waiter.start();
        assertTrue(beforeWait.await(WAIT_SECONDS, TimeUnit.SECONDS));
        waitForState(waiter, Thread.State.WAITING);
        synchronized (monitor) {
            open.set(true);
            monitor.notifyAll();
        }
        assertTrue(innerExited.await(WAIT_SECONDS, TimeUnit.SECONDS));

        contender.start();
        assertTrue(contenderStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        waitForState(contender, Thread.State.BLOCKED);
        assertEquals(1L, contenderAcquired.getCount());

        releaseOuter.countDown();
        assertTrue(contenderAcquired.await(WAIT_SECONDS, TimeUnit.SECONDS));
        joinThread(waiter);
        joinThread(contender);
    }

    /**
     * 验证 notify 只改变等待资格，通知线程退出 synchronized 前 waiter 不能继续。
     *
     * @throws Exception 等待线程协作失败或被中断
     */
    @Test
    void shouldKeepMonitorAfterNotifyUntilSynchronizedExit() throws Exception {
        Object monitor = new Object();
        AtomicBoolean open = new AtomicBoolean();
        CountDownLatch beforeWait = new CountDownLatch(1);
        CountDownLatch notifyCalled = new CountDownLatch(1);
        CountDownLatch releaseNotifier = new CountDownLatch(1);
        CountDownLatch waiterResumed = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            synchronized (monitor) {
                beforeWait.countDown();
                while (!open.get()) {
                    waitOnMonitor(monitor);
                }
                waiterResumed.countDown();
            }
        });
        Thread notifier = new Thread(() -> {
            synchronized (monitor) {
                open.set(true);
                monitor.notify();
                notifyCalled.countDown();
                awaitGate(releaseNotifier);
            }
        });

        waiter.start();
        assertTrue(beforeWait.await(WAIT_SECONDS, TimeUnit.SECONDS));
        waitForState(waiter, Thread.State.WAITING);
        notifier.start();
        assertTrue(notifyCalled.await(WAIT_SECONDS, TimeUnit.SECONDS));
        waitForState(waiter, Thread.State.BLOCKED);

        assertEquals(1L, waiterResumed.getCount());
        releaseNotifier.countDown();
        assertTrue(waiterResumed.await(WAIT_SECONDS, TimeUnit.SECONDS));
        joinThread(waiter);
        joinThread(notifier);
    }

    /**
     * 验证中断 Object.wait 会抛出 InterruptedException，并在抛出前清除中断标记。
     *
     * @throws Exception 等待线程协作失败或被中断
     */
    @Test
    void shouldClearInterruptStatusWhenWaitThrows() throws Exception {
        Object monitor = new Object();
        CountDownLatch beforeWait = new CountDownLatch(1);
        AtomicBoolean caught = new AtomicBoolean();
        AtomicBoolean flagAfterCatch = new AtomicBoolean(true);
        Thread waiter = new Thread(() -> {
            synchronized (monitor) {
                beforeWait.countDown();
                try {
                    monitor.wait();
                } catch (InterruptedException exception) {
                    caught.set(true);
                    flagAfterCatch.set(Thread.currentThread().isInterrupted());
                }
            }
        });

        waiter.start();
        assertTrue(beforeWait.await(WAIT_SECONDS, TimeUnit.SECONDS));
        waitForState(waiter, Thread.State.WAITING);
        waiter.interrupt();
        joinThread(waiter);

        assertTrue(caught.get());
        assertFalse(flagAfterCatch.get());
    }

    /**
     * 在已经持有 monitor 的线程中执行 wait，并把中断转成测试失败。
     *
     * @param monitor 当前线程持有的 monitor
     */
    private static void waitOnMonitor(Object monitor) {
        try {
            monitor.wait();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("monitor 测试线程意外被中断", exception);
        }
    }

    /**
     * 在限定时间内等待闸门，失败时终止当前测试任务。
     *
     * @param gate 需要等待的闸门
     */
    private static void awaitGate(CountDownLatch gate) {
        try {
            if (!gate.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("monitor 测试闸门未在预期时间内打开");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 monitor 测试闸门时被中断", exception);
        }
    }

    /**
     * 等待线程进入指定状态，避免用固定 sleep 推测调度结果。
     *
     * @param thread        目标线程
     * @param expectedState 预期状态
     * @throws InterruptedException 轮询状态时被中断
     */
    private static void waitForState(Thread thread, Thread.State expectedState) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (thread.getState() != expectedState && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertEquals(expectedState, thread.getState());
    }

    /**
     * 有界等待线程结束，避免失败场景拖住测试进程。
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
        assertFalse(thread.isAlive(), "monitor 测试线程未在预期时间内结束");
    }
}
