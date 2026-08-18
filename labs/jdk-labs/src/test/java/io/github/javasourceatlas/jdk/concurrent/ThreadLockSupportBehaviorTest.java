package io.github.javasourceatlas.jdk.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Thread 与 LockSupport 专题依赖的公开可观察行为。
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ThreadLockSupportBehaviorTest {

    /**
     * 验证直接调用 run 不会启动线程，而 start 会在新线程执行且只能成功一次。
     *
     * @throws InterruptedException 等待工作线程结束时被中断
     */
    @Test
    void shouldSeparateDirectRunFromStart() throws InterruptedException {
        ThreadLockSupportDebugLab.StartObservation observation =
                ThreadLockSupportDebugLab.observeRunAndStart();

        assertEquals(Thread.State.NEW, observation.getStateBeforeStart());
        assertEquals(Thread.State.NEW, observation.getStateAfterDirectRun());
        assertEquals(Thread.State.TERMINATED, observation.getStateAfterJoin());
        assertEquals(observation.getCallerThreadName(), observation.getDirectThreadName());
        assertNotEquals(observation.getCallerThreadName(), observation.getStartedThreadName());
        assertEquals(2, observation.getInvocations());
        assertTrue(observation.isSecondStartRejected());
    }

    /**
     * 验证六种 Thread.State 都能由对应的线程动作稳定观察到。
     *
     * @throws InterruptedException 等待状态切换时被中断
     */
    @Test
    void shouldObserveAllSixThreadStates() throws InterruptedException {
        ThreadLockSupportDebugLab.StateObservation observation =
                ThreadLockSupportDebugLab.observeThreadStates();

        assertEquals(Thread.State.NEW, observation.getNewState());
        assertEquals(Thread.State.RUNNABLE, observation.getRunnableState());
        assertEquals(Thread.State.BLOCKED, observation.getBlockedState());
        assertEquals(Thread.State.WAITING, observation.getWaitingState());
        assertEquals(Thread.State.TIMED_WAITING, observation.getTimedWaitingState());
        assertEquals(Thread.State.TERMINATED, observation.getTerminatedState());
        assertTrue(observation.isWaitingBlockerVisible());
    }

    /**
     * 验证 unpark 可以先于 park，且连续两次 unpark 仍只保留一个许可。
     *
     * @throws InterruptedException 等待许可线程时被中断
     */
    @Test
    void shouldKeepOnlyOnePermit() throws InterruptedException {
        ThreadLockSupportDebugLab.PermitObservation observation =
                ThreadLockSupportDebugLab.observeOneBitPermit();

        assertTrue(observation.isFirstParkReturned());
        assertTrue(observation.isSecondParkWaiting());
        assertTrue(observation.isBlockerVisible());
        assertTrue(observation.isCompletedAfterFreshUnpark());
        assertTrue(observation.getSpuriousReturns() >= 0);
    }

    /**
     * 验证中断会让 park 返回但保留标记，Thread.interrupted 才读取并清除当前线程标记。
     *
     * @throws InterruptedException 等待中断线程时被中断
     */
    @Test
    void shouldReturnFromParkAndPreserveInterruptStatus() throws InterruptedException {
        ThreadLockSupportDebugLab.InterruptObservation observation =
                ThreadLockSupportDebugLab.observeInterruptAndBlocker();

        assertTrue(observation.isBlockerVisibleWhileParked());
        assertTrue(observation.isParkReturned());
        assertTrue(observation.isInterruptedAfterPark());
        assertTrue(observation.isInterruptedResult());
        assertFalse(observation.isFlagAfterClear());
        assertTrue(observation.isBlockerClearedAfterReturn());
    }

    /**
     * 验证 parkNanos 可以用剩余时间循环抵抗伪唤醒，并在返回后清理 blocker。
     *
     * @throws InterruptedException 等待定时线程时被中断
     */
    @Test
    void shouldCompleteTimedParkWithDeadlineLoop() throws InterruptedException {
        ThreadLockSupportDebugLab.TimedParkObservation observation =
                ThreadLockSupportDebugLab.observeTimedPark();

        assertTrue(observation.getParkCalls() >= 1);
        assertTrue(observation.getElapsedNanos() >= observation.getRequestedNanos());
        assertFalse(observation.isInterrupted());
        assertTrue(observation.isBlockerCleared());
    }
}
