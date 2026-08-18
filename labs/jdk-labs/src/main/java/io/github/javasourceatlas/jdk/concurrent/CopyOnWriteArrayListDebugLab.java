package io.github.javasourceatlas.jdk.concurrent;

import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 用公开 API 稳定触发 CopyOnWriteArrayList 快照和复合写入行为的调试入口。
 */
public final class CopyOnWriteArrayListDebugLab {

    /**
     * 工具类不需要创建实例。
     */
    private CopyOnWriteArrayListDebugLab() {
    }

    /**
     * 按固定顺序运行全部 CopyOnWriteArrayList 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     */
    public static void main(String[] args) {
        printHeader("迭代器快照");
        observeIteratorSnapshot();

        printHeader("存在才跳过");
        observeAddIfAbsent();

        printHeader("并发 addIfAbsent");
        observeConcurrentAddIfAbsent();

        printHeader("迭代器禁止修改");
        observeUnsupportedIteratorMutation();

        printHeader("SubList 数组身份检查");
        observeSubListInvalidation();
    }

    /**
     * 在创建迭代器后修改父列表，观察旧迭代器仍只读取创建时的快照。
     */
    static void observeIteratorSnapshot() {
        CopyOnWriteArrayList<String> list =
                new CopyOnWriteArrayList<>(Arrays.asList("A", "B", "C"));
        Iterator<String> snapshotIterator = list.iterator();

        list.add("D");

        StringBuilder snapshot = new StringBuilder();
        while (snapshotIterator.hasNext()) {
            snapshot.append(snapshotIterator.next());
        }
        System.out.printf("旧迭代器=%s，当前列表=%s%n", snapshot, list);
    }

    /**
     * 连续添加同一个值，观察 addIfAbsent 的返回值和去重结果。
     */
    static void observeAddIfAbsent() {
        CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
        boolean first = list.addIfAbsent("listener-A");
        boolean second = list.addIfAbsent("listener-A");

        System.out.printf("第一次=%s，第二次=%s，当前列表=%s%n", first, second, list);
    }

    /**
     * 让两个线程同时添加同一个新值，便于在锁内观察快照变化后的复查分支。
     */
    static void observeConcurrentAddIfAbsent() {
        CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
        CountDownLatch startGate = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            await(startGate);
            list.addIfAbsent("listener-A");
        }, "cow-writer-1");
        Thread second = new Thread(() -> {
            await(startGate);
            list.addIfAbsent("listener-A");
        }, "cow-writer-2");

        first.start();
        second.start();
        startGate.countDown();
        join(first);
        join(second);

        System.out.printf("并发添加后的列表=%s%n", list);
    }

    /**
     * 调用快照迭代器的 remove，观察固定抛出的不支持操作异常。
     */
    static void observeUnsupportedIteratorMutation() {
        CopyOnWriteArrayList<String> list =
                new CopyOnWriteArrayList<>(Arrays.asList("A", "B"));
        Iterator<String> iterator = list.iterator();
        iterator.next();

        try {
            iterator.remove();
        } catch (UnsupportedOperationException exception) {
            System.out.println("快照迭代器不支持 remove");
        }
    }

    /**
     * 绕过子视图修改父列表，观察子视图后续操作检查底层数组身份。
     */
    static void observeSubListInvalidation() {
        CopyOnWriteArrayList<String> parent =
                new CopyOnWriteArrayList<>(Arrays.asList("A", "B", "C"));
        List<String> view = parent.subList(0, 2);
        parent.add("D");

        try {
            view.size();
        } catch (ConcurrentModificationException exception) {
            System.out.println("父列表发布新数组后，旧 SubList 视图已失效");
        }
    }

    /**
     * 等待并发实验闸门；中断时恢复标记并终止当前调试场景。
     *
     * @param latch 需要等待的闸门
     */
    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发写入闸门时被中断", exception);
        }
    }

    /**
     * 等待写线程结束；中断时恢复标记并终止当前调试场景。
     *
     * @param thread 需要等待的线程
     */
    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发写线程时被中断", exception);
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
