package io.github.javasourceatlas.jdk.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * 用公开 API 稳定观察 Thread 生命周期、线程状态、LockSupport 许可、中断和 blocker 的调试入口。
 */
public final class ThreadLockSupportDebugLab {

    private static final long WAIT_SECONDS = 5;
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long TIMED_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(40);
    private static final Object PERMIT_BLOCKER = new LabBlocker("permit-slot");
    private static final Object INTERRUPT_BLOCKER = new LabBlocker("interrupt-demo");
    private static final Object STATE_BLOCKER = new LabBlocker("state-demo");
    private static final Object TIMED_BLOCKER = new LabBlocker("timed-demo");

    /**
     * 工具类不需要创建实例。
     */
    private ThreadLockSupportDebugLab() {
    }

    /**
     * 按固定顺序运行 Thread 与 LockSupport 的全部调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws Exception 等待工作线程或传播实验失败时抛出
     */
    public static void main(String[] args) throws Exception {
        printHeader("run 与 start 的执行边界");
        observeRunAndStart();

        printHeader("六种 Thread.State 的可控观察");
        observeThreadStates();

        printHeader("一位 permit 与 unpark-before-park");
        observeOneBitPermit();

        printHeader("park blocker 与中断标记");
        observeInterruptAndBlocker();

        printHeader("parkNanos 的截止时间循环");
        observeTimedPark();
    }

    /**
     * 先直接调用 run，再调用 start，证明 run 是普通方法而 start 只能成功一次。
     *
     * @return 直接调用与新线程执行的观察结果
     * @throws InterruptedException 等待新线程结束时被中断
     */
    static StartObservation observeRunAndStart() throws InterruptedException {
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<String> directThreadName = new AtomicReference<>();
        AtomicReference<String> startedThreadName = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        String callerThreadName = Thread.currentThread().getName();

        Thread worker = new Thread(() -> {
            try {
                int invocation = invocations.incrementAndGet();
                if (invocation == 1) {
                    directThreadName.set(Thread.currentThread().getName());
                } else {
                    startedThreadName.set(Thread.currentThread().getName());
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        }, "atlas-start-worker");

        Thread.State stateBeforeStart = worker.getState();
        worker.run();
        Thread.State stateAfterDirectRun = worker.getState();
        worker.start();
        joinWithin(worker, "start 创建的工作线程未按时结束");
        throwIfFailed(failure.get(), "run/start 实验失败");

        boolean secondStartRejected = false;
        try {
            worker.start();
        } catch (IllegalThreadStateException exception) {
            secondStartRejected = true;
        }

        StartObservation observation = new StartObservation(
                callerThreadName,
                directThreadName.get(),
                startedThreadName.get(),
                stateBeforeStart,
                stateAfterDirectRun,
                worker.getState(),
                invocations.get(),
                secondStartRejected);
        System.out.printf("调用线程=%s，run 执行线程=%s，start 执行线程=%s%n",
                observation.getCallerThreadName(), observation.getDirectThreadName(),
                observation.getStartedThreadName());
        System.out.printf("状态=%s -> run 后 %s -> start/join 后 %s，执行次数=%d，二次 start 被拒绝=%s%n",
                observation.getStateBeforeStart(), observation.getStateAfterDirectRun(),
                observation.getStateAfterJoin(), observation.getInvocations(),
                observation.isSecondStartRejected());
        return observation;
    }

    /**
     * 用自旋、park、monitor 竞争和定时 park 分别稳定观察六种 Thread.State。
     *
     * @return 六种状态以及 park blocker 的观察结果
     * @throws InterruptedException 等待工作线程时被中断
     */
    static StateObservation observeThreadStates() throws InterruptedException {
        AtomicBoolean keepRunnable = new AtomicBoolean(true);
        AtomicBoolean releaseWaiting = new AtomicBoolean(false);
        CountDownLatch lifecycleStarted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread lifecycleWorker = new Thread(() -> {
            try {
                lifecycleStarted.countDown();
                while (keepRunnable.get()) {
                    Thread.yield();
                }
                while (!releaseWaiting.get()) {
                    // 条件必须独立保存；park 可能因中断或伪唤醒返回。
                    LockSupport.park(STATE_BLOCKER);
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        }, "atlas-state-lifecycle");

        Thread.State newState = lifecycleWorker.getState();
        Thread.State runnableState;
        Thread.State waitingState;
        boolean waitingBlockerVisible;
        Thread.State terminatedState;
        try {
            lifecycleWorker.start();
            awaitGate(lifecycleStarted, "生命周期线程未按时启动");
            runnableState = waitForState(lifecycleWorker, Thread.State.RUNNABLE,
                    "生命周期线程未保持 RUNNABLE");

            keepRunnable.set(false);
            waitingState = waitForState(lifecycleWorker, Thread.State.WAITING,
                    "生命周期线程未进入 WAITING");
            waitingBlockerVisible = LockSupport.getBlocker(lifecycleWorker) == STATE_BLOCKER;

            releaseWaiting.set(true);
            LockSupport.unpark(lifecycleWorker);
            joinWithin(lifecycleWorker, "生命周期线程未按时结束");
            terminatedState = lifecycleWorker.getState();
            throwIfFailed(failure.get(), "生命周期状态实验失败");
        } finally {
            keepRunnable.set(false);
            releaseWaiting.set(true);
            interruptUnparkAndJoin(lifecycleWorker);
        }

        Object monitor = new Object();
        CountDownLatch monitorAttempted = new CountDownLatch(1);
        Thread blockedWorker = new Thread(() -> {
            monitorAttempted.countDown();
            synchronized (monitor) {
                // 获得 monitor 即可退出；临界区只用于制造 BLOCKED 状态。
            }
        }, "atlas-state-blocked");

        Thread.State blockedState;
        synchronized (monitor) {
            blockedWorker.start();
            awaitGate(monitorAttempted, "monitor 竞争线程未按时启动");
            blockedState = waitForState(blockedWorker, Thread.State.BLOCKED,
                    "monitor 竞争线程未进入 BLOCKED");
        }
        joinWithin(blockedWorker, "monitor 竞争线程未按时结束");

        AtomicBoolean releaseTimedWaiting = new AtomicBoolean(false);
        CountDownLatch timedStarted = new CountDownLatch(1);
        Thread timedWorker = new Thread(() -> {
            timedStarted.countDown();
            while (!releaseTimedWaiting.get()) {
                // 使用较长上限保证观察窗口，主线程取样后会立即 unpark，不等待自然超时。
                LockSupport.parkNanos(TIMED_BLOCKER, TimeUnit.SECONDS.toNanos(WAIT_SECONDS));
            }
        }, "atlas-state-timed");

        Thread.State timedWaitingState;
        try {
            timedWorker.start();
            awaitGate(timedStarted, "定时等待线程未按时启动");
            timedWaitingState = waitForState(timedWorker, Thread.State.TIMED_WAITING,
                    "定时等待线程未进入 TIMED_WAITING");
        } finally {
            releaseTimedWaiting.set(true);
            LockSupport.unpark(timedWorker);
            interruptUnparkAndJoin(timedWorker);
        }

        StateObservation observation = new StateObservation(
                newState, runnableState, blockedState, waitingState,
                timedWaitingState, terminatedState, waitingBlockerVisible);
        System.out.printf("NEW=%s，RUNNABLE=%s，BLOCKED=%s，WAITING=%s，TIMED_WAITING=%s，TERMINATED=%s%n",
                observation.getNewState(), observation.getRunnableState(), observation.getBlockedState(),
                observation.getWaitingState(), observation.getTimedWaitingState(),
                observation.getTerminatedState());
        System.out.printf("WAITING 时 blocker 可见=%s%n", observation.isWaitingBlockerVisible());
        return observation;
    }

    /**
     * 在线程已经启动但尚未 park 时连续 unpark 两次，验证许可只保留一位。
     *
     * @return 第一次立即返回、第二次实际等待和 blocker 的观察结果
     * @throws InterruptedException 等待工作线程时被中断
     */
    static PermitObservation observeOneBitPermit() throws InterruptedException {
        AtomicBoolean enterParks = new AtomicBoolean(false);
        AtomicBoolean releaseSecondPark = new AtomicBoolean(false);
        AtomicInteger spuriousReturns = new AtomicInteger();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch firstParkReturned = new CountDownLatch(1);
        CountDownLatch secondParkEntered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                workerStarted.countDown();
                while (!enterParks.get()) {
                    Thread.yield();
                }

                LockSupport.park(PERMIT_BLOCKER);
                firstParkReturned.countDown();
                secondParkEntered.countDown();

                while (!releaseSecondPark.get()) {
                    LockSupport.park(PERMIT_BLOCKER);
                    if (!releaseSecondPark.get()) {
                        // 伪唤醒合法，因此记录后继续检查业务条件，不能把一次返回当成通知。
                        spuriousReturns.incrementAndGet();
                    }
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                finished.countDown();
            }
        }, "atlas-permit-worker");

        boolean secondParkWaiting;
        boolean blockerVisible;
        try {
            worker.start();
            awaitGate(workerStarted, "许可实验线程未按时启动");

            // 目标线程此时正在运行而不是等待；两个 unpark 只把同一 permit 槽设为可用。
            LockSupport.unpark(worker);
            LockSupport.unpark(worker);
            enterParks.set(true);

            awaitGate(firstParkReturned, "unpark-before-park 的许可没有让第一次 park 返回");
            awaitGate(secondParkEntered, "许可实验线程没有进入第二次 park");
            secondParkWaiting = waitForState(worker, Thread.State.WAITING,
                    "连续两次 unpark 不应积累成两个许可") == Thread.State.WAITING;
            blockerVisible = LockSupport.getBlocker(worker) == PERMIT_BLOCKER;

            releaseSecondPark.set(true);
            LockSupport.unpark(worker);
            awaitGate(finished, "发放新许可后工作线程未按时结束");
            joinWithin(worker, "许可实验线程未按时结束");
            throwIfFailed(failure.get(), "一位 permit 实验失败");
        } finally {
            enterParks.set(true);
            releaseSecondPark.set(true);
            LockSupport.unpark(worker);
            interruptUnparkAndJoin(worker);
        }

        PermitObservation observation = new PermitObservation(
                firstParkReturned.getCount() == 0,
                secondParkWaiting,
                blockerVisible,
                finished.getCount() == 0,
                spuriousReturns.get());
        System.out.printf("第一次 park 返回=%s，第二次 park 实际等待=%s，blocker 可见=%s%n",
                observation.isFirstParkReturned(), observation.isSecondParkWaiting(),
                observation.isBlockerVisible());
        System.out.printf("补发许可后完成=%s，条件成立前伪唤醒次数=%d%n",
                observation.isCompletedAfterFreshUnpark(), observation.getSpuriousReturns());
        return observation;
    }

    /**
     * 在线程 park 后读取 blocker，再用 interrupt 唤醒并比较两个中断查询 API。
     *
     * @return blocker 可见性、park 返回和中断标记变化
     * @throws InterruptedException 等待工作线程时被中断
     */
    static InterruptObservation observeInterruptAndBlocker() throws InterruptedException {
        CountDownLatch aboutToPark = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean parkReturned = new AtomicBoolean();
        AtomicBoolean isInterruptedAfterPark = new AtomicBoolean();
        AtomicBoolean interruptedResult = new AtomicBoolean();
        AtomicBoolean flagAfterClear = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                aboutToPark.countDown();
                while (!Thread.currentThread().isInterrupted()) {
                    // 单次 park 允许伪唤醒；只有中断标记出现才结束本场景的等待循环。
                    LockSupport.park(INTERRUPT_BLOCKER);
                }
                parkReturned.set(true);
                isInterruptedAfterPark.set(Thread.currentThread().isInterrupted());
                interruptedResult.set(Thread.interrupted());
                flagAfterClear.set(Thread.currentThread().isInterrupted());
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                finished.countDown();
            }
        }, "atlas-interrupt-worker");

        boolean blockerVisible;
        try {
            worker.start();
            awaitGate(aboutToPark, "中断实验线程未按时启动");
            waitForState(worker, Thread.State.WAITING, "中断实验线程未进入 WAITING");
            blockerVisible = LockSupport.getBlocker(worker) == INTERRUPT_BLOCKER;

            worker.interrupt();
            awaitGate(finished, "中断没有让 park 线程按时返回");
            joinWithin(worker, "中断实验线程未按时结束");
            throwIfFailed(failure.get(), "中断与 blocker 实验失败");
        } finally {
            worker.interrupt();
            LockSupport.unpark(worker);
            interruptUnparkAndJoin(worker);
        }

        InterruptObservation observation = new InterruptObservation(
                blockerVisible,
                parkReturned.get(),
                isInterruptedAfterPark.get(),
                interruptedResult.get(),
                flagAfterClear.get(),
                LockSupport.getBlocker(worker) == null);
        System.out.printf("阻塞中 blocker 可见=%s，park 已返回=%s，isInterrupted=%s%n",
                observation.isBlockerVisibleWhileParked(), observation.isParkReturned(),
                observation.isInterruptedAfterPark());
        System.out.printf("Thread.interrupted() 返回=%s，调用后标记=%s，退出后 blocker 已清空=%s%n",
                observation.isInterruptedResult(), observation.isFlagAfterClear(),
                observation.isBlockerClearedAfterReturn());
        return observation;
    }

    /**
     * 用“剩余时间 + 条件循环”调用 parkNanos，允许伪唤醒但保证最终覆盖完整截止时间。
     *
     * @return 定时等待调用次数、耗时和 blocker 清理结果
     * @throws InterruptedException 等待定时线程时被中断
     */
    static TimedParkObservation observeTimedPark() throws InterruptedException {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger parkCalls = new AtomicInteger();
        AtomicLong elapsedNanos = new AtomicLong();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            long startedAt = System.nanoTime();
            long deadline = startedAt + TIMED_PARK_NANOS;
            try {
                long remaining;
                while ((remaining = deadline - System.nanoTime()) > 0L) {
                    parkCalls.incrementAndGet();
                    LockSupport.parkNanos(TIMED_BLOCKER, remaining);
                    if (Thread.interrupted()) {
                        interrupted.set(true);
                        throw new IllegalStateException("定时等待不应收到中断");
                    }
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                elapsedNanos.set(System.nanoTime() - startedAt);
                finished.countDown();
            }
        }, "atlas-timed-park-worker");

        try {
            worker.start();
            awaitGate(finished, "parkNanos 截止时间循环未按时完成");
            joinWithin(worker, "parkNanos 实验线程未按时结束");
            throwIfFailed(failure.get(), "parkNanos 实验失败");
        } finally {
            worker.interrupt();
            LockSupport.unpark(worker);
            interruptUnparkAndJoin(worker);
        }

        TimedParkObservation observation = new TimedParkObservation(
                TIMED_PARK_NANOS,
                elapsedNanos.get(),
                parkCalls.get(),
                interrupted.get(),
                LockSupport.getBlocker(worker) == null);
        System.out.printf("请求至少等待=%dms，实际截止时间循环=%dms，parkNanos 调用=%d 次%n",
                TimeUnit.NANOSECONDS.toMillis(observation.getRequestedNanos()),
                TimeUnit.NANOSECONDS.toMillis(observation.getElapsedNanos()),
                observation.getParkCalls());
        System.out.printf("收到中断=%s，退出后 blocker 已清空=%s%n",
                observation.isInterrupted(), observation.isBlockerCleared());
        return observation;
    }

    /**
     * 在限定时间内等待线程到达指定状态，避免使用 sleep 猜测调度时序。
     *
     * @param thread 待观察线程
     * @param expected 目标状态
     * @param message 超时错误信息
     * @return 实际观察到的目标状态
     * @throws InterruptedException 当前观察线程被中断
     */
    private static Thread.State waitForState(Thread thread, Thread.State expected, String message)
            throws InterruptedException {
        waitUntil(() -> thread.getState() == expected, message);
        return thread.getState();
    }

    /**
     * 在统一截止时间内轮询条件；短暂 park 只降低空转，不决定业务先后。
     *
     * @param condition 完成条件
     * @param message 超时错误信息
     * @throws InterruptedException 当前观察线程被中断
     */
    private static void waitUntil(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(message);
            }
            LockSupport.parkNanos(ThreadLockSupportDebugLab.class, POLL_NANOS);
            if (Thread.interrupted()) {
                throw new InterruptedException("等待观察条件时被中断");
            }
        }
    }

    /**
     * 有界等待闩锁，超时后使用明确错误结束实验。
     *
     * @param gate 待打开闩锁
     * @param message 超时错误信息
     * @throws InterruptedException 当前线程被中断
     */
    private static void awaitGate(CountDownLatch gate, String message) throws InterruptedException {
        if (!gate.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * 有界等待线程退出，防止失败路径永久挂住测试进程。
     *
     * @param thread 待等待线程
     * @param message 超时错误信息
     * @throws InterruptedException 当前线程被中断
     */
    private static void joinWithin(Thread thread, String message) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        if (thread.isAlive()) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * 同时发送 interrupt 和 unpark，再有界等待线程退出，覆盖两类阻塞路径。
     *
     * @param threads 待清理线程
     * @throws InterruptedException 当前线程被中断
     */
    private static void interruptUnparkAndJoin(Thread... threads) throws InterruptedException {
        for (Thread thread : threads) {
            if (thread != null && thread.isAlive()) {
                thread.interrupt();
                LockSupport.unpark(thread);
            }
        }
        for (Thread thread : threads) {
            if (thread != null && thread.isAlive()) {
                joinWithin(thread, "清理工作线程超时: " + thread.getName());
            }
        }
    }

    /**
     * 将工作线程中的失败重新抛到主线程，避免只留下未捕获异常日志。
     *
     * @param failure 工作线程失败
     * @param message 场景说明
     */
    private static void throwIfFailed(Throwable failure, String message) {
        if (failure == null) {
            return;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        throw new IllegalStateException(message, failure);
    }

    /**
     * 输出实验分隔标题，便于在控制台和断点日志中定位场景。
     *
     * @param title 场景标题
     */
    private static void printHeader(String title) {
        System.out.printf("%n=== %s ===%n", title);
    }

    /**
     * 保存 run 与 start 的执行观察值。
     */
    static final class StartObservation {
        private final String callerThreadName;
        private final String directThreadName;
        private final String startedThreadName;
        private final Thread.State stateBeforeStart;
        private final Thread.State stateAfterDirectRun;
        private final Thread.State stateAfterJoin;
        private final int invocations;
        private final boolean secondStartRejected;

        /**
         * 创建不可变的启动观察结果。
         */
        StartObservation(String callerThreadName, String directThreadName, String startedThreadName,
                         Thread.State stateBeforeStart, Thread.State stateAfterDirectRun,
                         Thread.State stateAfterJoin, int invocations, boolean secondStartRejected) {
            this.callerThreadName = callerThreadName;
            this.directThreadName = directThreadName;
            this.startedThreadName = startedThreadName;
            this.stateBeforeStart = stateBeforeStart;
            this.stateAfterDirectRun = stateAfterDirectRun;
            this.stateAfterJoin = stateAfterJoin;
            this.invocations = invocations;
            this.secondStartRejected = secondStartRejected;
        }

        /** 返回调用方线程名。 */
        String getCallerThreadName() { return callerThreadName; }

        /** 返回直接调用 run 时的执行线程名。 */
        String getDirectThreadName() { return directThreadName; }

        /** 返回 start 后的执行线程名。 */
        String getStartedThreadName() { return startedThreadName; }

        /** 返回首次 start 前的线程状态。 */
        Thread.State getStateBeforeStart() { return stateBeforeStart; }

        /** 返回直接调用 run 后的线程状态。 */
        Thread.State getStateAfterDirectRun() { return stateAfterDirectRun; }

        /** 返回 join 后的线程状态。 */
        Thread.State getStateAfterJoin() { return stateAfterJoin; }

        /** 返回 target 总执行次数。 */
        int getInvocations() { return invocations; }

        /** 返回第二次 start 是否被拒绝。 */
        boolean isSecondStartRejected() { return secondStartRejected; }
    }

    /**
     * 保存六种 Thread.State 的观察值。
     */
    static final class StateObservation {
        private final Thread.State newState;
        private final Thread.State runnableState;
        private final Thread.State blockedState;
        private final Thread.State waitingState;
        private final Thread.State timedWaitingState;
        private final Thread.State terminatedState;
        private final boolean waitingBlockerVisible;

        /**
         * 创建不可变的状态观察结果。
         */
        StateObservation(Thread.State newState, Thread.State runnableState,
                         Thread.State blockedState, Thread.State waitingState,
                         Thread.State timedWaitingState, Thread.State terminatedState,
                         boolean waitingBlockerVisible) {
            this.newState = newState;
            this.runnableState = runnableState;
            this.blockedState = blockedState;
            this.waitingState = waitingState;
            this.timedWaitingState = timedWaitingState;
            this.terminatedState = terminatedState;
            this.waitingBlockerVisible = waitingBlockerVisible;
        }

        /** 返回 NEW 观察值。 */
        Thread.State getNewState() { return newState; }

        /** 返回 RUNNABLE 观察值。 */
        Thread.State getRunnableState() { return runnableState; }

        /** 返回 BLOCKED 观察值。 */
        Thread.State getBlockedState() { return blockedState; }

        /** 返回 WAITING 观察值。 */
        Thread.State getWaitingState() { return waitingState; }

        /** 返回 TIMED_WAITING 观察值。 */
        Thread.State getTimedWaitingState() { return timedWaitingState; }

        /** 返回 TERMINATED 观察值。 */
        Thread.State getTerminatedState() { return terminatedState; }

        /** 返回 WAITING 时 blocker 是否可见。 */
        boolean isWaitingBlockerVisible() { return waitingBlockerVisible; }
    }

    /**
     * 保存一位 permit 场景的观察值。
     */
    static final class PermitObservation {
        private final boolean firstParkReturned;
        private final boolean secondParkWaiting;
        private final boolean blockerVisible;
        private final boolean completedAfterFreshUnpark;
        private final int spuriousReturns;

        /**
         * 创建不可变的许可观察结果。
         */
        PermitObservation(boolean firstParkReturned, boolean secondParkWaiting,
                          boolean blockerVisible, boolean completedAfterFreshUnpark,
                          int spuriousReturns) {
            this.firstParkReturned = firstParkReturned;
            this.secondParkWaiting = secondParkWaiting;
            this.blockerVisible = blockerVisible;
            this.completedAfterFreshUnpark = completedAfterFreshUnpark;
            this.spuriousReturns = spuriousReturns;
        }

        /** 返回第一次 park 是否已使用预发许可返回。 */
        boolean isFirstParkReturned() { return firstParkReturned; }

        /** 返回第二次 park 是否实际进入等待。 */
        boolean isSecondParkWaiting() { return secondParkWaiting; }

        /** 返回等待期间 blocker 是否可见。 */
        boolean isBlockerVisible() { return blockerVisible; }

        /** 返回补发许可后线程是否完成。 */
        boolean isCompletedAfterFreshUnpark() { return completedAfterFreshUnpark; }

        /** 返回业务条件成立前观察到的伪唤醒次数。 */
        int getSpuriousReturns() { return spuriousReturns; }
    }

    /**
     * 保存 park 因中断返回时的观察值。
     */
    static final class InterruptObservation {
        private final boolean blockerVisibleWhileParked;
        private final boolean parkReturned;
        private final boolean interruptedAfterPark;
        private final boolean interruptedResult;
        private final boolean flagAfterClear;
        private final boolean blockerClearedAfterReturn;

        /**
         * 创建不可变的中断观察结果。
         */
        InterruptObservation(boolean blockerVisibleWhileParked, boolean parkReturned,
                             boolean interruptedAfterPark, boolean interruptedResult,
                             boolean flagAfterClear, boolean blockerClearedAfterReturn) {
            this.blockerVisibleWhileParked = blockerVisibleWhileParked;
            this.parkReturned = parkReturned;
            this.interruptedAfterPark = interruptedAfterPark;
            this.interruptedResult = interruptedResult;
            this.flagAfterClear = flagAfterClear;
            this.blockerClearedAfterReturn = blockerClearedAfterReturn;
        }

        /** 返回 park 期间 blocker 是否可见。 */
        boolean isBlockerVisibleWhileParked() { return blockerVisibleWhileParked; }

        /** 返回 park 是否已因中断返回。 */
        boolean isParkReturned() { return parkReturned; }

        /** 返回 park 刚返回时 isInterrupted 的结果。 */
        boolean isInterruptedAfterPark() { return interruptedAfterPark; }

        /** 返回 Thread.interrupted 的读取结果。 */
        boolean isInterruptedResult() { return interruptedResult; }

        /** 返回 Thread.interrupted 清除后的标记。 */
        boolean isFlagAfterClear() { return flagAfterClear; }

        /** 返回 park 返回后 blocker 是否已经清空。 */
        boolean isBlockerClearedAfterReturn() { return blockerClearedAfterReturn; }
    }

    /**
     * 保存 parkNanos 截止时间循环的观察值。
     */
    static final class TimedParkObservation {
        private final long requestedNanos;
        private final long elapsedNanos;
        private final int parkCalls;
        private final boolean interrupted;
        private final boolean blockerCleared;

        /**
         * 创建不可变的定时等待观察结果。
         */
        TimedParkObservation(long requestedNanos, long elapsedNanos, int parkCalls,
                             boolean interrupted, boolean blockerCleared) {
            this.requestedNanos = requestedNanos;
            this.elapsedNanos = elapsedNanos;
            this.parkCalls = parkCalls;
            this.interrupted = interrupted;
            this.blockerCleared = blockerCleared;
        }

        /** 返回请求等待的纳秒数。 */
        long getRequestedNanos() { return requestedNanos; }

        /** 返回截止时间循环的实际纳秒数。 */
        long getElapsedNanos() { return elapsedNanos; }

        /** 返回 parkNanos 实际调用次数。 */
        int getParkCalls() { return parkCalls; }

        /** 返回等待期间是否意外收到中断。 */
        boolean isInterrupted() { return interrupted; }

        /** 返回线程退出后 blocker 是否清空。 */
        boolean isBlockerCleared() { return blockerCleared; }
    }

    /**
     * 为调试器提供稳定、可读的 blocker 标识。
     */
    private static final class LabBlocker {
        private final String name;

        /**
         * 创建带名称的 blocker。
         *
         * @param name 调试显示名
         */
        private LabBlocker(String name) {
            this.name = name;
        }

        /**
         * 返回调试显示名。
         *
         * @return blocker 名称
         */
        @Override
        public String toString() {
            return name;
        }
    }
}
