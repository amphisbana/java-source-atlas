package io.github.javasourceatlas.jdk.concurrent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 CopyOnWriteArrayList 教学案例依赖的公开可观察行为。
 */
class CopyOnWriteArrayListBehaviorTest {

    /**
     * 验证创建好的迭代器不会观察到之后加入的新元素。
     */
    @Test
    void shouldKeepIteratorSnapshotAfterWrite() {
        CopyOnWriteArrayList<String> list =
                new CopyOnWriteArrayList<>(Arrays.asList("A", "B", "C"));
        Iterator<String> iterator = list.iterator();

        list.add("D");

        List<String> snapshotValues = new ArrayList<>();
        iterator.forEachRemaining(snapshotValues::add);
        assertEquals(Arrays.asList("A", "B", "C"), snapshotValues);
        assertEquals(Arrays.asList("A", "B", "C", "D"), list);
    }

    /**
     * 验证快照迭代器固定不支持删除操作。
     */
    @Test
    void shouldRejectIteratorMutation() {
        CopyOnWriteArrayList<String> list =
                new CopyOnWriteArrayList<>(Arrays.asList("A", "B"));
        Iterator<String> iterator = list.iterator();
        iterator.next();

        assertThrows(UnsupportedOperationException.class, iterator::remove);
    }

    /**
     * 验证 addIfAbsent 只在当前列表不存在目标值时发布新结果。
     */
    @Test
    void shouldAddValueOnlyWhenAbsent() {
        CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();

        assertTrue(list.addIfAbsent("listener-A"));
        assertFalse(list.addIfAbsent("listener-A"));
        assertEquals(Arrays.asList("listener-A"), list);
    }

    /**
     * 验证多个写线程竞争 addIfAbsent 后仍只保留一个目标值。
     *
     * @throws InterruptedException 等待并发线程时被中断
     */
    @Test
    void shouldKeepSingleValueAfterConcurrentAddIfAbsent() throws InterruptedException {
        CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
        int workerCount = 8;
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successfulAdds = new AtomicInteger();
        Thread[] workers = new Thread[workerCount];

        // 所有线程先停在同一闸门，再同时执行锁外检查和锁内复查。
        for (int index = 0; index < workerCount; index++) {
            workers[index] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待测试闸门时被中断", exception);
                }
                if (list.addIfAbsent("listener-A")) {
                    successfulAdds.incrementAndGet();
                }
            }, "cow-test-writer-" + index);
            workers[index].start();
        }

        ready.await();
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }

        assertEquals(Arrays.asList("listener-A"), list);
        assertEquals(1, successfulAdds.get());
    }

    /**
     * 验证绕过子视图修改父列表后，旧子视图会发现数组身份变化。
     */
    @Test
    void shouldInvalidateSubListAfterDirectParentWrite() {
        CopyOnWriteArrayList<String> parent =
                new CopyOnWriteArrayList<>(Arrays.asList("A", "B", "C"));
        List<String> view = parent.subList(0, 2);

        parent.add("D");

        assertThrows(ConcurrentModificationException.class, view::size);
    }

    /**
     * 验证 toArray 返回的数组可以独立修改而不影响当前列表。
     */
    @Test
    void shouldReturnIndependentArrayCopy() {
        CopyOnWriteArrayList<String> list =
                new CopyOnWriteArrayList<>(Arrays.asList("A", "B"));
        Object[] copy = list.toArray();

        copy[0] = "X";

        assertEquals("A", list.get(0));
        assertEquals("X", copy[0]);
    }
}
