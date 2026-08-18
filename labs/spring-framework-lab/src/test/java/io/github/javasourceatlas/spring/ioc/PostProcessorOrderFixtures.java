package io.github.javasourceatlas.spring.ioc;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 提供 BeanFactory 后处理器顺序实验需要的处理器与事件记录器。
 */
final class PostProcessorOrderFixtures {

    private static final String LATE_PROCESSOR_BEAN_NAME = "lateRegistryPostProcessor";

    /**
     * 工具类只承载测试夹具，不需要创建实例。
     */
    private PostProcessorOrderFixtures() {
    }

    /**
     * 创建会在 registry 回调中继续注册处理器的 BeanDefinition。
     *
     * @param recorder 顺序事件记录器
     * @return 首个 BeanDefinitionRegistryPostProcessor 的定义
     */
    static RootBeanDefinition registeringRegistryProcessorDefinition(EventRecorder recorder) {
        RootBeanDefinition definition = new RootBeanDefinition(RegisteringRegistryPostProcessor.class);
        definition.setInstanceSupplier(() -> new RegisteringRegistryPostProcessor(recorder));
        return definition;
    }

    /**
     * 按回调发生顺序保存实验事件。
     */
    static final class EventRecorder {

        private final List<String> events = new ArrayList<>();

        /**
         * 记录一次后处理器回调。
         *
         * @param event 可读的回调名称
         */
        void record(String event) {
            events.add(event);
        }

        /**
         * 返回当前事件的只读快照，防止断言意外修改原始记录。
         *
         * @return 按发生顺序排列的事件
         */
        List<String> snapshot() {
            return Collections.unmodifiableList(new ArrayList<>(events));
        }
    }

    /**
     * 首轮无序 BDRPP；执行 registry 回调时再注册一个新的无序 BDRPP。
     */
    static final class RegisteringRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor {

        private final EventRecorder recorder;

        /**
         * 创建能动态扩展注册表的处理器。
         *
         * @param recorder 顺序事件记录器
         */
        RegisteringRegistryPostProcessor(EventRecorder recorder) {
            this.recorder = recorder;
        }

        /**
         * 记录首轮 registry 回调，并注册需要由后续扫描循环发现的新处理器。
         *
         * @param registry 当前 BeanDefinition 注册表
         */
        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            recorder.record("bdrpp:first:registry");
            if (!registry.containsBeanDefinition(LATE_PROCESSOR_BEAN_NAME)) {
                RootBeanDefinition definition = new RootBeanDefinition(LateRegistryPostProcessor.class);
                definition.setInstanceSupplier(() -> new LateRegistryPostProcessor(recorder));

                // 新处理器是在当前回调中出现的，只能由 Spring 的 reiterate 循环在下一轮发现。
                registry.registerBeanDefinition(LATE_PROCESSOR_BEAN_NAME, definition);
            }
        }

        /**
         * 记录首个 BDRPP 的 BeanFactory 阶段回调。
         *
         * @param beanFactory 已完成 BeanDefinition 注册阶段的工厂
         */
        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            recorder.record("bdrpp:first:factory");
        }
    }

    /**
     * 在首个 BDRPP 的 registry 回调中动态注册的无序 BDRPP。
     */
    static final class LateRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor {

        private final EventRecorder recorder;

        /**
         * 创建后续扫描循环发现的处理器。
         *
         * @param recorder 顺序事件记录器
         */
        LateRegistryPostProcessor(EventRecorder recorder) {
            this.recorder = recorder;
        }

        /**
         * 记录动态处理器的 registry 阶段回调。
         *
         * @param registry 当前 BeanDefinition 注册表
         */
        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            recorder.record("bdrpp:late:registry");
        }

        /**
         * 记录动态处理器的 BeanFactory 阶段回调。
         *
         * @param beanFactory 已完成 BeanDefinition 注册阶段的工厂
         */
        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            recorder.record("bdrpp:late:factory");
        }
    }

    /**
     * 带 Ordered 值的程序化普通 BFPP，用于证明程序化列表不会按 order 重新排序。
     */
    static final class RecordingOrderedBeanFactoryPostProcessor
            implements BeanFactoryPostProcessor, Ordered {

        private final String event;
        private final int order;
        private final EventRecorder recorder;

        /**
         * 创建一个带指定顺序值和事件名称的普通 BFPP。
         *
         * @param event 回调时记录的事件名称
         * @param order Ordered 返回值
         * @param recorder 顺序事件记录器
         */
        RecordingOrderedBeanFactoryPostProcessor(String event, int order, EventRecorder recorder) {
            this.event = event;
            this.order = order;
            this.recorder = recorder;
        }

        /**
         * 记录普通 BeanFactoryPostProcessor 回调。
         *
         * @param beanFactory 当前可配置 BeanFactory
         */
        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            recorder.record(event);
        }

        /**
         * 返回声明的排序值；程序化注册路径应忽略该值。
         *
         * @return 测试指定的排序值
         */
        @Override
        public int getOrder() {
            return order;
        }
    }
}
