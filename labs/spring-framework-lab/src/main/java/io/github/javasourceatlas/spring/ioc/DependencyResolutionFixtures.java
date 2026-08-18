package io.github.javasourceatlas.spring.ioc;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 提供依赖候选筛选、集合排序、延迟与可选依赖实验使用的夹具。
 */
public final class DependencyResolutionFixtures {

    private static final AtomicInteger HEAVY_SERVICE_CREATIONS = new AtomicInteger();

    /**
     * 工具类只承载依赖解析夹具，不需要创建实例。
     */
    private DependencyResolutionFixtures() {
    }

    /**
     * 清空重量服务创建次数，隔离多个上下文。
     */
    public static void resetHeavyServiceCreations() {
        HEAVY_SERVICE_CREATIONS.set(0);
    }

    /**
     * 返回真实重量服务的创建次数。
     *
     * @return 创建次数
     */
    public static int heavyServiceCreations() {
        return HEAVY_SERVICE_CREATIONS.get();
    }

    /**
     * 注册可用于验证候选筛选全部主要分支的依赖图。
     */
    @Configuration(proxyBeanMethods = false)
    public static class ResolutionConfiguration {

        /**
         * 注册类型注入默认选择的主网关。
         *
         * @return 主网关
         */
        @Bean
        @Primary
        public Gateway primaryGateway() {
            return new NamedGateway("primary");
        }

        /**
         * 注册带 batch 限定符的候选网关。
         *
         * @return 批处理网关
         */
        @Bean
        @Qualifier("batch")
        public Gateway batchGateway() {
            return new NamedGateway("batch");
        }

        /**
         * 注册 Customer 泛型仓库。
         *
         * @return Customer 仓库
         */
        @Bean
        public GenericRepository<Customer> customerRepository() {
            return new NamedRepository<>("customer");
        }

        /**
         * 注册 Order 泛型仓库。
         *
         * @return Order 仓库
         */
        @Bean
        public GenericRepository<Order> orderRepository() {
            return new NamedRepository<>("order");
        }

        /**
         * 注册排序值较小、应排在集合前面的处理器。
         *
         * @return 第一处理器
         */
        @Bean
        public Handler firstHandler() {
            return new OrderedHandler("first", 10);
        }

        /**
         * 注册排序值较大、应排在集合后面的处理器。
         *
         * @return 第二处理器
         */
        @Bean
        public Handler secondHandler() {
            return new OrderedHandler("second", 20);
        }

        /**
         * 注册真实重量服务，并让容器推迟其实例化。
         *
         * @return 重量服务
         */
        @Bean
        @Lazy
        public HeavyService heavyService() {
            return new CountingHeavyService(HEAVY_SERVICE_CREATIONS.incrementAndGet());
        }

        /**
         * 通过工厂方法参数触发类型、Qualifier、泛型、集合、Optional、ObjectProvider 与 Lazy 解析。
         *
         * @param gateway            类型注入选择的主网关
         * @param batchGateway       限定符选择的批处理网关
         * @param customerRepository 泛型限定后的 Customer 仓库
         * @param handlers           按 Ordered 排序的全部处理器
         * @param missingService     缺失可选依赖
         * @param missingProvider    缺失依赖提供者
         * @param heavyService       延迟解析代理
         * @return 聚合全部解析结果的目标对象
         */
        @Bean
        public ResolutionTarget resolutionTarget(
                Gateway gateway,
                @Qualifier("batch") Gateway batchGateway,
                GenericRepository<Customer> customerRepository,
                List<Handler> handlers,
                Optional<MissingService> missingService,
                ObjectProvider<MissingService> missingProvider,
                @Lazy HeavyService heavyService) {
            return new ResolutionTarget(
                    gateway,
                    batchGateway,
                    customerRepository,
                    handlers,
                    missingService,
                    missingProvider,
                    heavyService);
        }
    }

    /**
     * 故意注册两个等价候选但不提供 Primary 或 Qualifier，用于验证歧义失败。
     */
    @Configuration(proxyBeanMethods = false)
    public static class AmbiguousConfiguration {

        /**
         * 注册第一个无主次候选。
         *
         * @return 第一个网关
         */
        @Bean
        public Gateway leftGateway() {
            return new NamedGateway("left");
        }

        /**
         * 注册第二个无主次候选。
         *
         * @return 第二个网关
         */
        @Bean
        public Gateway rightGateway() {
            return new NamedGateway("right");
        }

        /**
         * 创建需要唯一网关的对象；解析时应因两个候选无法裁决而失败。
         *
         * @param gateway 无法唯一选择的网关
         * @return 不应成功创建的消费者
         */
        @Bean
        public AmbiguousConsumer ambiguousConsumer(Gateway gateway) {
            return new AmbiguousConsumer(gateway);
        }
    }

    /**
     * 支付网关抽象，用于演示单值候选筛选。
     */
    public interface Gateway {

        /**
         * 返回网关名称。
         *
         * @return 网关名称
         */
        String name();
    }

    /**
     * 带名称的网关实现。
     */
    private static final class NamedGateway implements Gateway {
        private final String name;

        /**
         * 创建命名网关。
         *
         * @param name 网关名称
         */
        private NamedGateway(String name) {
            this.name = name;
        }

        /**
         * 返回网关名称。
         *
         * @return 网关名称
         */
        @Override
        public String name() {
            return name;
        }
    }

    /**
     * 保留领域泛型的仓库抽象。
     *
     * @param <T> 领域类型
     */
    public interface GenericRepository<T> {

        /**
         * 返回仓库领域名称。
         *
         * @return 领域名称
         */
        String domain();
    }

    /**
     * 带名称的泛型仓库实现。
     *
     * @param <T> 领域类型
     */
    private static final class NamedRepository<T> implements GenericRepository<T> {
        private final String domain;

        /**
         * 创建命名仓库。
         *
         * @param domain 领域名称
         */
        private NamedRepository(String domain) {
            this.domain = domain;
        }

        /**
         * 返回仓库领域名称。
         *
         * @return 领域名称
         */
        @Override
        public String domain() {
            return domain;
        }
    }

    /**
     * 集合依赖中的有序处理器。
     */
    public interface Handler {

        /**
         * 返回处理器名称。
         *
         * @return 处理器名称
         */
        String name();
    }

    /**
     * 同时实现业务接口与 Spring Ordered 的处理器。
     */
    private static final class OrderedHandler implements Handler, Ordered {
        private final String name;
        private final int order;

        /**
         * 创建有序处理器。
         *
         * @param name  处理器名称
         * @param order 排序值
         */
        private OrderedHandler(String name, int order) {
            this.name = name;
            this.order = order;
        }

        /**
         * 返回处理器名称。
         *
         * @return 处理器名称
         */
        @Override
        public String name() {
            return name;
        }

        /**
         * 返回 Spring 集合排序值，数值越小越靠前。
         *
         * @return 排序值
         */
        @Override
        public int getOrder() {
            return order;
        }
    }

    /**
     * 用于验证泛型候选过滤的 Customer 领域类型。
     */
    public static final class Customer {
    }

    /**
     * 用于验证泛型候选过滤的 Order 领域类型。
     */
    public static final class Order {
    }

    /**
     * 容器中故意没有实现的依赖类型。
     */
    public interface MissingService {
    }

    /**
     * 延迟依赖抽象，使用接口保证代理创建不会实例化真实目标。
     */
    public interface HeavyService {

        /**
         * 触发真实延迟目标创建并返回序号。
         *
         * @return 真实目标创建序号
         */
        int load();
    }

    /**
     * 记录真实目标创建序号的重量服务。
     */
    private static final class CountingHeavyService implements HeavyService {
        private final int sequence;

        /**
         * 创建真实重量服务。
         *
         * @param sequence 创建序号
         */
        private CountingHeavyService(int sequence) {
            this.sequence = sequence;
        }

        /**
         * 返回真实目标创建序号。
         *
         * @return 创建序号
         */
        @Override
        public int load() {
            return sequence;
        }
    }

    /**
     * 聚合一次依赖解析的全部可观察结果。
     */
    public static final class ResolutionTarget {
        private final Gateway gateway;
        private final Gateway batchGateway;
        private final GenericRepository<Customer> customerRepository;
        private final List<Handler> handlers;
        private final Optional<MissingService> missingService;
        private final ObjectProvider<MissingService> missingProvider;
        private final HeavyService heavyService;

        /**
         * 保存 Spring 为工厂方法参数解析出的依赖。
         *
         * @param gateway            主网关
         * @param batchGateway       批处理网关
         * @param customerRepository Customer 仓库
         * @param handlers           有序处理器集合
         * @param missingService     缺失可选依赖
         * @param missingProvider    缺失依赖提供者
         * @param heavyService       延迟代理
         */
        private ResolutionTarget(
                Gateway gateway,
                Gateway batchGateway,
                GenericRepository<Customer> customerRepository,
                List<Handler> handlers,
                Optional<MissingService> missingService,
                ObjectProvider<MissingService> missingProvider,
                HeavyService heavyService) {
            this.gateway = gateway;
            this.batchGateway = batchGateway;
            this.customerRepository = customerRepository;
            this.handlers = handlers;
            this.missingService = missingService;
            this.missingProvider = missingProvider;
            this.heavyService = heavyService;
        }

        /**
         * 返回类型注入选中的网关。
         *
         * @return 主网关
         */
        public Gateway getGateway() {
            return gateway;
        }

        /**
         * 返回 Qualifier 选中的网关。
         *
         * @return 批处理网关
         */
        public Gateway getBatchGateway() {
            return batchGateway;
        }

        /**
         * 返回泛型过滤后的 Customer 仓库。
         *
         * @return Customer 仓库
         */
        public GenericRepository<Customer> getCustomerRepository() {
            return customerRepository;
        }

        /**
         * 返回按 Ordered 排序的处理器。
         *
         * @return 处理器集合
         */
        public List<Handler> getHandlers() {
            return handlers;
        }

        /**
         * 返回缺失依赖的 Optional 结果。
         *
         * @return 空 Optional
         */
        public Optional<MissingService> getMissingService() {
            return missingService;
        }

        /**
         * 返回缺失依赖的延迟提供者。
         *
         * @return ObjectProvider
         */
        public ObjectProvider<MissingService> getMissingProvider() {
            return missingProvider;
        }

        /**
         * 返回尚未触发真实目标的延迟代理。
         *
         * @return 重量服务代理
         */
        public HeavyService getHeavyService() {
            return heavyService;
        }
    }

    /**
     * 需要唯一网关、但在歧义配置中无法创建的消费者。
     */
    public static final class AmbiguousConsumer {
        private final Gateway gateway;

        /**
         * 创建歧义消费者。
         *
         * @param gateway 理论上的唯一网关
         */
        private AmbiguousConsumer(Gateway gateway) {
            this.gateway = gateway;
        }

        /**
         * 返回注入网关；歧义场景中本方法不应被调用。
         *
         * @return 网关
         */
        public Gateway getGateway() {
            return gateway;
        }
    }
}
