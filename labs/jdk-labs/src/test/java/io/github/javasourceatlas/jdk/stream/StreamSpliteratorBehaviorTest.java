package io.github.javasourceatlas.jdk.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Spliterator;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Stream 与 Spliterator 教学案例依赖的公开可观察行为。
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class StreamSpliteratorBehaviorTest {

    private static final long WAIT_SECONDS = 5;

    /**
     * 验证中间操作在终止操作前不执行，并且同一 Stream 不能再次消费。
     */
    @Test
    void shouldEvaluateLazilyAndRejectSecondConsumption() {
        AtomicInteger filterCalls = new AtomicInteger();
        AtomicInteger mapCalls = new AtomicInteger();
        Stream<Integer> pipeline = Arrays.asList(1, 2, 3, 4).stream()
                .filter(value -> {
                    filterCalls.incrementAndGet();
                    return value % 2 == 0;
                })
                .map(value -> {
                    mapCalls.incrementAndGet();
                    return value * 10;
                });

        assertEquals(0, filterCalls.get());
        assertEquals(0, mapCalls.get());
        assertEquals(Arrays.asList(20, 40), pipeline.collect(Collectors.toList()));
        assertEquals(4, filterCalls.get());
        assertEquals(2, mapCalls.get());
        assertThrows(IllegalStateException.class, pipeline::count);
    }

    /**
     * 验证顺序流 findFirst 找到结果后不再推进后续源元素。
     */
    @Test
    void shouldStopSequentialTraversalAfterFirstMatch() {
        AtomicInteger visited = new AtomicInteger();
        int first = IntStream.rangeClosed(1, 100)
                .peek(ignored -> visited.incrementAndGet())
                .filter(value -> value % 7 == 0)
                .findFirst()
                .orElse(-1);

        assertEquals(7, first);
        assertEquals(7, visited.get());
    }

    /**
     * 验证元素沿 filter、map、limit 融合 Sink 链正向流动，并在 limit 满足后停止。
     */
    @Test
    void shouldFuseSinkStagesAndStopAtLimit() {
        AtomicInteger filterCalls = new AtomicInteger();
        List<Integer> result = Arrays.asList(1, 2, 3, 4).stream()
                .filter(value -> {
                    filterCalls.incrementAndGet();
                    return value % 2 == 0;
                })
                .map(value -> value * 10)
                .limit(1)
                .collect(Collectors.toList());

        assertEquals(Arrays.asList(20), result);
        assertEquals(2, filterCalls.get());
    }

    /**
     * 验证 ArrayList Spliterator 在首次绑定前会纳入同线程追加的元素。
     */
    @Test
    void shouldLateBindArrayListBeforeTraversal() {
        ArrayList<Integer> source = new ArrayList<>(Arrays.asList(1, 2));
        Spliterator<Integer> spliterator = source.spliterator();
        source.add(3);
        List<Integer> observed = new ArrayList<>();

        spliterator.forEachRemaining(observed::add);

        assertEquals(Arrays.asList(1, 2, 3), observed);
    }

    /**
     * 验证一次 ArrayList trySplit 返回的前缀与原 Spliterator 剩余部分完整覆盖源数据。
     */
    @Test
    void shouldSplitArrayListWithoutLossOrDuplication() {
        ArrayList<Integer> source = new ArrayList<>(
                Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8));
        Spliterator<Integer> remainder = source.spliterator();
        long sizeBeforeSplit = remainder.estimateSize();
        Spliterator<Integer> prefix = remainder.trySplit();
        assertNotNull(prefix);

        assertTrue(remainder.hasCharacteristics(Spliterator.ORDERED));
        assertTrue(remainder.hasCharacteristics(Spliterator.SIZED));
        assertTrue(remainder.hasCharacteristics(Spliterator.SUBSIZED));
        assertEquals(sizeBeforeSplit, prefix.estimateSize() + remainder.estimateSize());

        List<Integer> observed = new ArrayList<>();
        prefix.forEachRemaining(observed::add);
        remainder.forEachRemaining(observed::add);
        assertEquals(source, observed);
    }

    /**
     * 验证 ArrayList Spliterator 绑定后遇到结构性修改会尽力快速失败。
     */
    @Test
    void shouldFailFastAfterArrayListSpliteratorBinds() {
        ArrayList<Integer> source = new ArrayList<>(Arrays.asList(1, 2, 3));
        Spliterator<Integer> spliterator = source.spliterator();
        assertEquals(3, spliterator.estimateSize());
        source.add(4);

        assertThrows(ConcurrentModificationException.class,
                () -> spliterator.forEachRemaining(ignored -> { }));
    }

    /**
     * 验证在自建 ForkJoinPool 调用边界中，并行有序流仍返回正确的遇见顺序结果。
     *
     * @throws Exception 等待并行计算失败或被中断
     */
    @Test
    void shouldReturnOrderedParallelResultInsideManagedBoundary() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            Future<List<Integer>> result = pool.submit(() -> IntStream.rangeClosed(1, 100)
                    .parallel()
                    .filter(value -> value % 10 == 0)
                    .map(value -> value / 10)
                    .boxed()
                    .collect(Collectors.toList()));

            assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
                    result.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            shutdownNowAndAwait(pool);
        }
    }

    /**
     * 立即关闭自建 ForkJoinPool，并等待工作线程退出。
     *
     * @param pool 需要关闭的 ForkJoinPool
     * @throws InterruptedException 等待线程池终止时被中断
     */
    private static void shutdownNowAndAwait(ForkJoinPool pool) throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS),
                "Stream 测试线程池未在预期时间内终止");
    }
}
