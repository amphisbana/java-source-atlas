package io.github.javasourceatlas.jdk.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
     * 验证 JDK 8 会逐元素执行 SIZED 流水线的 count，而 JDK 9 起可直接读取精确尺寸。
     */
    @Test
    void shouldUseVersionSpecificCountPathForSizedPipeline() {
        AtomicInteger peekCalls = new AtomicInteger();
        long count = Arrays.asList(1, 2, 3, 4).stream()
                .peek(ignored -> peekCalls.incrementAndGet())
                .count();

        assertEquals(4L, count);
        assertEquals(javaMajorVersion() >= 9 ? 0 : 4, peekCalls.get());
    }

    /**
     * 验证 JDK 9 起公开 takeWhile 在首个失败元素处停止。
     *
     * @throws Exception 反射调用新版 Stream API 失败
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeTakeWhileFromJdk9() throws Exception {
        if (javaMajorVersion() < 9) {
            assertThrows(NoSuchMethodException.class,
                    () -> Stream.class.getMethod("takeWhile", Predicate.class));
            return;
        }

        AtomicInteger tested = new AtomicInteger();
        Predicate<Integer> predicate = value -> {
            tested.incrementAndGet();
            return value < 3;
        };
        Method takeWhile = Stream.class.getMethod("takeWhile", Predicate.class);
        Stream<Integer> prefix = (Stream<Integer>) takeWhile.invoke(
                Arrays.asList(1, 2, 3, 4).stream(), predicate);

        assertEquals(Arrays.asList(1, 2), prefix.collect(Collectors.toList()));
        assertEquals(3, tested.get());
    }

    /**
     * 验证 JDK 17/21 能为顺序 SIZED 流水线推导 skip/limit 的精确输出尺寸。
     */
    @Test
    void shouldAdjustExactSliceSizeFromJdk17() {
        Spliterator<Integer> sliced = Arrays.asList(1, 2, 3, 4).stream()
                .skip(1)
                .limit(2)
                .spliterator();

        if (javaMajorVersion() >= 17) {
            assertEquals(2L, sliced.getExactSizeIfKnown());
            assertTrue(sliced.hasCharacteristics(Spliterator.SIZED));
        } else {
            assertEquals(-1L, sliced.getExactSizeIfKnown());
            assertFalse(sliced.hasCharacteristics(Spliterator.SIZED));
        }
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
     * 验证未知尺寸 Iterator 的首个数组批次在 JDK 19 起改用非 SIZED 的启发式大估计。
     */
    @Test
    void shouldUseVersionSpecificEstimateForUnknownSizeSplit() {
        Spliterator<Integer> unknown = Spliterators.spliteratorUnknownSize(
                Arrays.asList(1, 2, 3, 4).iterator(), Spliterator.ORDERED);
        Spliterator<Integer> prefix = unknown.trySplit();
        assertNotNull(prefix);

        long estimateBeforeTraversal = prefix.estimateSize();
        boolean sized = prefix.hasCharacteristics(Spliterator.SIZED);
        List<Integer> observed = new ArrayList<>();
        prefix.forEachRemaining(observed::add);
        assertEquals(Arrays.asList(1, 2, 3, 4), observed);

        if (javaMajorVersion() >= 19) {
            assertFalse(sized);
            assertFalse(prefix.hasCharacteristics(Spliterator.SUBSIZED));
            assertEquals(Long.MAX_VALUE / 2, estimateBeforeTraversal);
        } else {
            assertTrue(sized);
            assertTrue(prefix.hasCharacteristics(Spliterator.SUBSIZED));
            assertEquals(4L, estimateBeforeTraversal);
        }
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
     * 验证并行 forEachOrdered 在内部依赖重构后仍保持公开遇见顺序。
     */
    @Test
    void shouldKeepForEachOrderedContractAcrossInternalRewrite() {
        List<Integer> ordered = Collections.synchronizedList(new ArrayList<>());
        IntStream.range(0, 16)
                .parallel()
                .boxed()
                .forEachOrdered(ordered::add);
        assertEquals(IntStream.range(0, 16).boxed().collect(Collectors.toList()), ordered);
    }

    /**
     * 验证 JDK 16 起 mapMulti 可由一个输入直接产生零到多个输出。
     *
     * @throws Exception 反射调用新版 Stream API 失败
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeMapMultiFromJdk16() throws Exception {
        if (javaMajorVersion() < 16) {
            assertThrows(NoSuchMethodException.class,
                    () -> Stream.class.getMethod("mapMulti", BiConsumer.class));
            return;
        }

        BiConsumer<Integer, Consumer<Integer>> mapper = (value, downstream) -> {
            if (value % 2 == 0) {
                downstream.accept(value);
                downstream.accept(value * 10);
            }
        };
        Method mapMulti = Stream.class.getMethod("mapMulti", BiConsumer.class);
        Stream<Integer> mapped = (Stream<Integer>) mapMulti.invoke(
                Arrays.asList(1, 2, 3, 4).stream(), mapper);

        assertEquals(Arrays.asList(2, 20, 4, 40),
                mapped.collect(Collectors.toList()));
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

    /**
     * 读取当前 Java 规范主版本，兼容 Java 8 的 1.8 格式。
     *
     * @return Java 主版本号
     */
    private static int javaMajorVersion() {
        String specificationVersion = System.getProperty("java.specification.version");
        if (specificationVersion.startsWith("1.")) {
            return Integer.parseInt(specificationVersion.substring(2));
        }
        return Integer.parseInt(specificationVersion);
    }
}
