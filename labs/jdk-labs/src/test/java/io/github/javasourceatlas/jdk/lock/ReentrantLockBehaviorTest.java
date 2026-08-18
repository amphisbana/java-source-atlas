package io.github.javasourceatlas.jdk.lock;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 ReentrantLock、Condition 与 AQS 共享模式教学案例依赖的公开可观察行为。
 */
class ReentrantLockBehaviorTest {

    /**
     * 验证同一线程可以重入，并且必须按次数释放。
     */
    @Test
    void shouldTrackReentrantHoldCount() {
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        try {
            lock.lock();
            try {
                assertEquals(2, lock.getHoldCount());
            } finally {
                lock.unlock();
            }
            assertEquals(1, lock.getHoldCount());
            assertTrue(lock.isHeldByCurrentThread());
        } finally {
            lock.unlock();
        }
        assertFalse(lock.isLocked());
    }

    /**
     * 验证未持有锁的线程不能执行 unlock。
     */
    @Test
    void shouldRejectUnlockByNonOwner() {
        ReentrantLock lock = new ReentrantLock();

        assertThrows(IllegalMonitorStateException.class, lock::unlock);
    }

    /**
     * 验证 lockInterruptibly 可以取消已经进入同步队列的获取。
     *
     * @throws InterruptedException 等待工作线程时被中断
     */
    @Test
    void shouldInterruptQueuedAcquire() throws InterruptedException {
        assertTrue(ReentrantLockDebugLab.observeInterruptibleAcquire());
    }

    /**
     * 验证 Condition.await 会完整释放并恢复两层重入状态。
     *
     * @throws InterruptedException 等待条件线程时被中断
     */
    @Test
    void shouldRestoreTwoReentrantHoldsAfterSignal() throws InterruptedException {
        ReentrantLockDebugLab.ConditionObservation observation =
                ReentrantLockDebugLab.observeConditionSignal();

        assertEquals(2, observation.getHoldCountBeforeAwait());
        assertEquals(2, observation.getHoldCountAfterAwait());
        assertTrue(observation.isReadyObserved());
    }

    /**
     * 验证公平锁实验确实让两个线程先后入队，并按队列顺序获得锁。
     *
     * @throws InterruptedException 等待排队线程时被中断
     */
    @Test
    void shouldAcquireFairLockInQueuedOrder() throws InterruptedException {
        ReentrantLockDebugLab.FairLockObservation observation =
                ReentrantLockDebugLab.observeFairQueuedAcquire();

        assertTrue(observation.isFair());
        assertTrue(observation.isFirstQueued());
        assertTrue(observation.isSecondQueued());
        assertEquals("first -> second", observation.getAcquisitionOrder());
    }

    /**
     * 验证 CountDownLatch 在计数尚未归零时不放行，归零后传播给全部共享等待者。
     *
     * @throws InterruptedException 等待共享等待线程时被中断
     */
    @Test
    void shouldOpenAllLatchWaitersOnlyAtZero() throws InterruptedException {
        ReentrantLockDebugLab.CountDownLatchObservation observation =
                ReentrantLockDebugLab.observeCountDownLatchPropagation();

        assertEquals(1L, observation.getCountAfterFirst());
        assertEquals(0, observation.getPassedAfterFirst());
        assertEquals(3, observation.getPassedAfterOpen());
    }

    /**
     * 验证一次释放的两个 Semaphore 许可能够传播给两个共享等待者。
     *
     * @throws InterruptedException 等待共享等待线程时被中断
     */
    @Test
    void shouldPropagateReleasedSemaphorePermits() throws InterruptedException {
        ReentrantLockDebugLab.SemaphoreObservation observation =
                ReentrantLockDebugLab.observeSemaphorePropagation();

        assertTrue(observation.hasQueuedBeforeRelease());
        assertEquals(2, observation.getPassed());
        assertEquals(0, observation.getAvailablePermits());
    }
}
