package io.github.javasourceatlas.spring.ioc;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.Ordered;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 Spring 5.3.39 调用 BeanFactory 后处理器时的关键顺序规则。
 */
class BeanFactoryPostProcessorOrderTest {

    private static final String FIRST_PROCESSOR_BEAN_NAME = "registeringRegistryPostProcessor";

    /**
     * 验证无序 BDRPP 在 registry 回调中新注册的 BDRPP 会被后续扫描循环发现。
     */
    @Test
    void shouldDiscoverRegistryProcessorRegisteredDuringRegistryCallback() {
        PostProcessorOrderFixtures.EventRecorder recorder = new PostProcessorOrderFixtures.EventRecorder();
        GenericApplicationContext context = createContextWithRegistryProcessor(recorder);

        try {
            context.refresh();

            assertEquals(Arrays.asList(
                    "bdrpp:first:registry",
                    "bdrpp:late:registry",
                    "bdrpp:first:factory",
                    "bdrpp:late:factory"), recorder.snapshot());
        } finally {
            context.close();
        }
    }

    /**
     * 验证全部 BDRPP 的 registry 阶段先完成，再执行其 factory 阶段，最后执行程序化普通 BFPP。
     */
    @Test
    void shouldCompleteAllRegistryCallbacksBeforeFactoryCallbacksAndRegularProcessor() {
        PostProcessorOrderFixtures.EventRecorder recorder = new PostProcessorOrderFixtures.EventRecorder();
        GenericApplicationContext context = createContextWithRegistryProcessor(recorder);
        context.addBeanFactoryPostProcessor(
                new PostProcessorOrderFixtures.RecordingOrderedBeanFactoryPostProcessor(
                        "bfpp:programmatic", Ordered.HIGHEST_PRECEDENCE, recorder));

        try {
            context.refresh();

            assertEquals(Arrays.asList(
                    "bdrpp:first:registry",
                    "bdrpp:late:registry",
                    "bdrpp:first:factory",
                    "bdrpp:late:factory",
                    "bfpp:programmatic"), recorder.snapshot());
        } finally {
            context.close();
        }
    }

    /**
     * 验证 context.addBeanFactoryPostProcessor 加入的 Ordered BFPP 仍按注册顺序执行。
     */
    @Test
    void shouldKeepRegistrationOrderForProgrammaticOrderedProcessors() {
        PostProcessorOrderFixtures.EventRecorder recorder = new PostProcessorOrderFixtures.EventRecorder();
        GenericApplicationContext context = new GenericApplicationContext();

        // 第一个处理器故意声明最低优先级，第二个声明最高优先级，以排除偶然的 order 正序结果。
        context.addBeanFactoryPostProcessor(
                new PostProcessorOrderFixtures.RecordingOrderedBeanFactoryPostProcessor(
                        "bfpp:registered-first", Ordered.LOWEST_PRECEDENCE, recorder));
        context.addBeanFactoryPostProcessor(
                new PostProcessorOrderFixtures.RecordingOrderedBeanFactoryPostProcessor(
                        "bfpp:registered-second", Ordered.HIGHEST_PRECEDENCE, recorder));

        try {
            context.refresh();

            assertEquals(Arrays.asList(
                    "bfpp:registered-first",
                    "bfpp:registered-second"), recorder.snapshot());
        } finally {
            context.close();
        }
    }

    /**
     * 创建只注册首个动态 BDRPP 的最小通用应用上下文。
     *
     * @param recorder 顺序事件记录器
     * @return 尚未刷新、可继续加入程序化处理器的上下文
     */
    private GenericApplicationContext createContextWithRegistryProcessor(
            PostProcessorOrderFixtures.EventRecorder recorder) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBeanDefinition(
                FIRST_PROCESSOR_BEAN_NAME,
                PostProcessorOrderFixtures.registeringRegistryProcessorDefinition(recorder));
        return context;
    }
}
