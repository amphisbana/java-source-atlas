package io.github.javasourceatlas.jdk.stream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.OptionalInt;
import java.util.Spliterator;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * 用公开 API 展示 Stream 流水线、短路遍历、Spliterator 拆分和并行归并边界。
 */
public final class StreamSpliteratorDebugLab {

    private static final long WAIT_SECONDS = 5;

    /**
     * 工具类不需要创建实例。
     */
    private StreamSpliteratorDebugLab() {
    }

    /**
     * 按固定顺序运行全部 Stream 与 Spliterator 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws Exception 等待并行任务失败或被中断
     */
    public static void main(String[] args) throws Exception {
        printHeader("惰性流水线与一次消费");
        observeLazyPipelineAndSingleUse();

        printHeader("filter、map 与 limit 的融合短路");
        observeFilterMapLimitPipeline();

        printHeader("顺序流短路");
        observeShortCircuitTraversal();

        printHeader("ArrayList Spliterator 延迟绑定与拆分");
        observeLateBindingAndSplit();

        printHeader("ArrayList Spliterator 快速失败边界");
        observeFailFastAfterBinding();

        printHeader("受控 ForkJoinPool 中的并行归并");
        observeParallelReduction();
    }

    /**
     * 记录中间操作调用次数，证明构建阶段不遍历，并验证终止操作后不能重复消费同一 Stream。
     */
    static void observeLazyPipelineAndSingleUse() {
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

        System.out.printf("终止前 filter=%d，map=%d%n", filterCalls.get(), mapCalls.get());
        List<Integer> result = pipeline.collect(Collectors.toList());
        boolean reuseRejected = false;
        try {
            pipeline.count();
        } catch (IllegalStateException exception) {
            reuseRejected = true;
        }

        System.out.printf(
                "结果=%s，终止后 filter=%d，map=%d，重复消费被拒绝=%s%n",
                result, filterCalls.get(), mapCalls.get(), reuseRejected);
    }

    /**
     * 复现页面动画使用的 filter、map、limit 流水线，观察 limit 满足后停止拉取源元素。
     */
    static void observeFilterMapLimitPipeline() {
        AtomicInteger filterCalls = new AtomicInteger();
        List<Integer> result = Arrays.asList(1, 2, 3, 4).stream()
                .filter(value -> {
                    filterCalls.incrementAndGet();
                    return value % 2 == 0;
                })
                .map(value -> value * 10)
                .limit(1)
                .collect(Collectors.toList());

        System.out.printf("结果=%s，filter 实际接收次数=%d%n", result, filterCalls.get());
    }

    /**
     * 使用 findFirst 让顺序流在找到首个七的倍数后停止拉取后续元素。
     */
    static void observeShortCircuitTraversal() {
        AtomicInteger visited = new AtomicInteger();
        OptionalInt first = IntStream.rangeClosed(1, 100)
                .peek(ignored -> visited.incrementAndGet())
                .filter(value -> value % 7 == 0)
                .findFirst();

        System.out.printf("findFirst=%d，源端实际推进次数=%d%n",
                first.orElse(-1), visited.get());
    }

    /**
     * 在首次绑定前修改 ArrayList，并验证一次 trySplit 后两部分无丢失地覆盖全部元素。
     */
    static void observeLateBindingAndSplit() {
        ArrayList<Integer> source = new ArrayList<>(Arrays.asList(1, 2, 3, 4));
        Spliterator<Integer> remainder = source.spliterator();

        // fence 尚未绑定，此时追加的元素会被首次 estimateSize 纳入当前 ArrayList 实现的范围。
        source.add(5);
        long sizeBeforeSplit = remainder.estimateSize();
        Spliterator<Integer> prefix = remainder.trySplit();
        List<Integer> values = new ArrayList<>();
        if (prefix != null) {
            prefix.forEachRemaining(values::add);
        }
        remainder.forEachRemaining(values::add);

        System.out.printf(
                "绑定大小=%d，拆分后遍历=%s，ORDERED=%s，SIZED=%s，SUBSIZED=%s%n",
                sizeBeforeSplit,
                values,
                source.spliterator().hasCharacteristics(Spliterator.ORDERED),
                source.spliterator().hasCharacteristics(Spliterator.SIZED),
                source.spliterator().hasCharacteristics(Spliterator.SUBSIZED));
    }

    /**
     * 先通过 estimateSize 绑定 fence，再结构性修改列表，观察后续遍历尽力快速失败。
     */
    static void observeFailFastAfterBinding() {
        ArrayList<Integer> source = new ArrayList<>(Arrays.asList(1, 2, 3));
        Spliterator<Integer> spliterator = source.spliterator();
        long boundSize = spliterator.estimateSize();
        source.add(4);
        List<Integer> observedBeforeFailure = new ArrayList<>();
        boolean failFast = false;
        try {
            // forEachRemaining 可能先处理已绑定范围，再在末尾检查 modCount，不能把异常当成事务回滚。
            spliterator.forEachRemaining(observedBeforeFailure::add);
        } catch (ConcurrentModificationException exception) {
            failFast = true;
        }

        System.out.printf("绑定大小=%d，异常前已观察=%s，快速失败=%s%n",
                boundSize, observedBeforeFailure, failFast);
    }

    /**
     * 在可关闭的 ForkJoinPool 调用边界内执行并行归并，只验证结果，不依赖工作线程名称或拆分形态。
     *
     * @throws Exception 等待并行计算失败或被中断
     */
    static void observeParallelReduction() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            Future<Integer> result = pool.submit(() -> IntStream.rangeClosed(1, 1_000)
                    .parallel()
                    .map(value -> value * value)
                    .sum());
            System.out.printf("1..1000 平方和=%d%n",
                    result.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            shutdownNowAndAwait(pool);
        }
    }

    /**
     * 立即关闭自建 ForkJoinPool，并在限定时间内等待工作线程退出。
     *
     * @param pool 需要关闭的 ForkJoinPool
     * @throws InterruptedException 等待线程池终止时被中断
     */
    private static void shutdownNowAndAwait(ForkJoinPool pool) throws InterruptedException {
        pool.shutdownNow();
        if (!pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Stream 实验线程池未在预期时间内终止");
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
