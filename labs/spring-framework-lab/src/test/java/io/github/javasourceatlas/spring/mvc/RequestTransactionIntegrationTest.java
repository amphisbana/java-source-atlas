package io.github.javasourceatlas.spring.mvc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用同一个 WebApplicationContext 验证 MVC、AOP、事务和 JDBC 资源的真实跨专题链路。
 */
class RequestTransactionIntegrationTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;
    private JdbcTemplate jdbcTemplate;
    private DataSource dataSource;
    private RequestResourceTrace resourceTrace;

    /**
     * 为每个用例创建独立的 WebApplicationContext、H2 数据库和 DispatcherServlet。
     */
    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(RequestTransactionConfiguration.class);
        context.refresh();

        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .build();
        jdbcTemplate = context.getBean(JdbcTemplate.class);
        dataSource = context.getBean(DataSource.class);
        resourceTrace = context.getBean(RequestResourceTrace.class);
        jdbcTemplate.execute("create table request_record (code varchar(64) not null)");
    }

    /**
     * 关闭上下文，并确认本次 DataSource 没有残留在线程资源中。
     */
    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            assertFalse(TransactionSynchronizationManager.hasResource(dataSource),
                    "测试结束时不应残留本次 DataSource 资源");
        }
        if (context != null) {
            context.close();
        }
    }

    /**
     * 验证正常请求经过事务代理后提交数据，并在返回 Controller 前解绑连接资源。
     *
     * @throws Exception MockMvc 请求执行失败时向上抛出
     */
    @Test
    void shouldCommitAndUnbindDataSourceResourceForSuccessfulRequest() throws Exception {
        assertTransactionalServiceProxy();
        long requestThreadId = Thread.currentThread().getId();

        MvcResult result = mockMvc.perform(post("/integration/records/{code}", "committed"))
                .andExpect(status().isCreated())
                .andReturn();

        assertEquals("saved:committed", result.getResponse().getContentAsString());
        assertEquals(1, recordCount("committed"));
        assertResourceLifecycle(requestThreadId, false);
        assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
    }

    /**
     * 验证运行时异常先触发真实 JDBC 回滚和资源解绑，再由 MVC 异常解析器生成响应。
     *
     * @throws Exception MockMvc 请求执行失败时向上抛出
     */
    @Test
    void shouldRollbackAndUnbindBeforeMvcResolvesException() throws Exception {
        assertTransactionalServiceProxy();
        long requestThreadId = Thread.currentThread().getId();

        MvcResult result = mockMvc.perform(post("/integration/records/{code}", "rolled-back")
                        .param("fail", "true"))
                .andExpect(status().isConflict())
                .andReturn();

        assertEquals("rolled-back:rolled-back", result.getResponse().getContentAsString());
        assertInstanceOf(IllegalStateException.class, result.getResolvedException());
        assertEquals(0, recordCount("rolled-back"));
        assertResourceLifecycle(requestThreadId, true);
        assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
    }

    /**
     * 证明容器暴露的是带 TransactionInterceptor 的 Spring AOP 代理，并使用真实 JDBC 事务管理器。
     */
    private void assertTransactionalServiceProxy() {
        RequestRecordService service = context.getBean(RequestRecordService.class);
        assertTrue(AopUtils.isAopProxy(service), "Service 必须由 Spring AOP 代理");
        assertTrue(Arrays.stream(((Advised) service).getAdvisors())
                        .anyMatch((advisor) -> advisor.getAdvice() instanceof TransactionInterceptor),
                "Service 代理链必须包含 TransactionInterceptor");

        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        DataSourceTransactionManager dataSourceTransactionManager = assertInstanceOf(
                DataSourceTransactionManager.class, transactionManager);
        assertSame(dataSource, dataSourceTransactionManager.getDataSource());
    }

    /**
     * 按业务编号查询已提交记录数；独立连接只能看见已经提交的数据。
     *
     * @param code 业务编号
     * @return 已提交记录数
     */
    private int recordCount(String code) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from request_record where code = ?", Integer.class, code);
        return count == null ? 0 : count;
    }

    /**
     * 校验资源只在 Service target 执行期间绑定，并且整条链使用 MockMvc 请求线程。
     *
     * @param requestThreadId 发起 MockMvc 请求的线程编号
     * @param includesResolver 是否应包含 MVC 异常解析阶段
     */
    private void assertResourceLifecycle(long requestThreadId, boolean includesResolver) {
        List<RequestResourceObservation> observations = resourceTrace.snapshot();
        List<String> stages = observations.stream()
                .map(RequestResourceObservation::getStage)
                .collect(Collectors.toList());
        List<String> expectedStages = includesResolver
                ? Arrays.asList("controller-before", "service-target", "controller-after", "mvc-resolver")
                : Arrays.asList("controller-before", "service-target", "controller-after");
        assertEquals(expectedStages, stages);

        for (RequestResourceObservation observation : observations) {
            assertEquals(requestThreadId, observation.getThreadId(),
                    "Controller、Service 与异常解析器必须位于同一个请求线程");
        }

        assertFalse(observations.get(0).isDataSourceBound());
        assertFalse(observations.get(0).isTransactionActive());
        assertTrue(observations.get(1).isDataSourceBound());
        assertTrue(observations.get(1).isTransactionActive());
        assertFalse(observations.get(2).isDataSourceBound());
        assertFalse(observations.get(2).isTransactionActive());
        if (includesResolver) {
            assertFalse(observations.get(3).isDataSourceBound());
            assertFalse(observations.get(3).isTransactionActive());
        }
    }

    /**
     * 为跨专题测试装配 MVC、事务代理、事务管理器与测试业务 Bean。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableTransactionManagement
    static class RequestTransactionConfiguration {

        /**
         * 创建每个测试上下文独享的内存数据库。
         *
         * @return 可由 Spring 关闭的 H2 数据源
         */
        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .generateUniqueName(true)
                    .setType(EmbeddedDatabaseType.H2)
                    .build();
        }

        /**
         * 创建使用 DataSourceUtils 参与线程资源绑定的 JDBC 操作入口。
         *
         * @param dataSource 测试数据源
         * @return JDBC 模板
         */
        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        /**
         * 创建真实管理 JDBC Connection 提交、回滚和解绑的事务管理器。
         *
         * @param dataSource 测试数据源
         * @return JDBC 事务管理器
         */
        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        /**
         * 创建请求线程资源观察器。
         *
         * @return 资源观察器
         */
        @Bean
        RequestResourceTrace requestResourceTrace() {
            return new RequestResourceTrace();
        }

        /**
         * 创建事务业务服务；容器会在 Bean 初始化后为它生成事务代理。
         *
         * @param jdbcTemplate JDBC 模板
         * @param dataSource 测试数据源
         * @param resourceTrace 资源观察器
         * @return 事务业务服务
         */
        @Bean
        RequestRecordService requestRecordService(JdbcTemplate jdbcTemplate,
                                                  DataSource dataSource,
                                                  RequestResourceTrace resourceTrace) {
            return new JdbcRequestRecordService(jdbcTemplate, dataSource, resourceTrace);
        }

        /**
         * 创建调用事务 Service 的 MVC Controller。
         *
         * @param service 容器暴露的事务代理
         * @param dataSource 测试数据源
         * @param resourceTrace 资源观察器
         * @return 请求控制器
         */
        @Bean
        RequestRecordController requestRecordController(RequestRecordService service,
                                                        DataSource dataSource,
                                                        RequestResourceTrace resourceTrace) {
            return new RequestRecordController(service, dataSource, resourceTrace);
        }

        /**
         * 创建异常解析器，用于观察事务回滚完成后的 MVC 阶段。
         *
         * @param dataSource 测试数据源
         * @param resourceTrace 资源观察器
         * @return MVC 异常处理器
         */
        @Bean
        RequestRecordExceptionHandler requestRecordExceptionHandler(DataSource dataSource,
                                                                    RequestResourceTrace resourceTrace) {
            return new RequestRecordExceptionHandler(dataSource, resourceTrace);
        }
    }

    /**
     * 定义 Controller 依赖的事务业务边界。
     */
    interface RequestRecordService {

        /**
         * 保存请求记录，并可在写入后抛出异常以验证回滚。
         *
         * @param code 业务编号
         * @param fail 是否在写入后失败
         * @return 成功响应文本
         */
        String save(String code, boolean fail);
    }

    /**
     * 在真实 JDBC 事务中执行写入的 Service target。
     */
    static final class JdbcRequestRecordService implements RequestRecordService {

        private final JdbcTemplate jdbcTemplate;
        private final DataSource dataSource;
        private final RequestResourceTrace resourceTrace;

        /**
         * 注入 JDBC 与资源观察依赖。
         *
         * @param jdbcTemplate JDBC 模板
         * @param dataSource 测试数据源
         * @param resourceTrace 资源观察器
         */
        JdbcRequestRecordService(JdbcTemplate jdbcTemplate,
                                 DataSource dataSource,
                                 RequestResourceTrace resourceTrace) {
            this.jdbcTemplate = jdbcTemplate;
            this.dataSource = dataSource;
            this.resourceTrace = resourceTrace;
        }

        /**
         * 记录进入 target 时的事务资源，写入 H2，并按参数触发运行时异常。
         *
         * @param code 业务编号
         * @param fail 是否在写入后失败
         * @return 成功响应文本
         */
        @Override
        @Transactional
        public String save(String code, boolean fail) {
            resourceTrace.record("service-target", dataSource);
            jdbcTemplate.update("insert into request_record(code) values (?)", code);
            if (fail) {
                // 运行时异常越过代理边界后，TransactionInterceptor 应回滚刚才的 JDBC 写入。
                throw new IllegalStateException(code);
            }
            return "saved:" + code;
        }
    }

    /**
     * 接收 MockMvc 请求，并把业务调用交给容器中的事务代理。
     */
    @RestController
    static final class RequestRecordController {

        private final RequestRecordService service;
        private final DataSource dataSource;
        private final RequestResourceTrace resourceTrace;

        /**
         * 注入事务服务代理和资源观察依赖。
         *
         * @param service 事务服务代理
         * @param dataSource 测试数据源
         * @param resourceTrace 资源观察器
         */
        RequestRecordController(RequestRecordService service,
                                DataSource dataSource,
                                RequestResourceTrace resourceTrace) {
            this.service = service;
            this.dataSource = dataSource;
            this.resourceTrace = resourceTrace;
        }

        /**
         * 在事务代理调用前后记录本次 DataSource 的绑定状态。
         *
         * @param code 业务编号
         * @param fail 是否触发回滚
         * @return 创建成功响应
         */
        @PostMapping("/integration/records/{code}")
        ResponseEntity<String> create(@PathVariable String code,
                                      @RequestParam(defaultValue = "false") boolean fail) {
            resourceTrace.record("controller-before", dataSource);
            try {
                return ResponseEntity.status(HttpStatus.CREATED).body(service.save(code, fail));
            } finally {
                // finally 在事务代理返回或重新抛出异常后执行，此时本次 JDBC 资源必须已解绑。
                resourceTrace.record("controller-after", dataSource);
            }
        }
    }

    /**
     * 把业务异常转换为 HTTP 409，并证明 MVC 接手前事务资源已经清理。
     */
    @RestControllerAdvice
    static final class RequestRecordExceptionHandler {

        private final DataSource dataSource;
        private final RequestResourceTrace resourceTrace;

        /**
         * 注入资源观察依赖。
         *
         * @param dataSource 测试数据源
         * @param resourceTrace 资源观察器
         */
        RequestRecordExceptionHandler(DataSource dataSource, RequestResourceTrace resourceTrace) {
            this.dataSource = dataSource;
            this.resourceTrace = resourceTrace;
        }

        /**
         * 记录异常解析阶段，并生成可断言的冲突响应。
         *
         * @param exception Service 抛出的运行时异常
         * @return HTTP 409 响应
         */
        @ExceptionHandler(IllegalStateException.class)
        ResponseEntity<String> handleIllegalState(IllegalStateException exception) {
            resourceTrace.record("mvc-resolver", dataSource);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("rolled-back:" + exception.getMessage());
        }
    }

    /**
     * 按发生顺序保存请求线程中的事务资源快照。
     */
    static final class RequestResourceTrace {

        private final List<RequestResourceObservation> observations = new ArrayList<>();

        /**
         * 记录当前线程是否绑定指定 DataSource，以及事务同步器是否处于真实事务中。
         *
         * @param stage 调用链阶段
         * @param dataSource 本次事务使用的数据源
         */
        void record(String stage, DataSource dataSource) {
            observations.add(new RequestResourceObservation(
                    stage,
                    Thread.currentThread().getId(),
                    TransactionSynchronizationManager.hasResource(dataSource),
                    TransactionSynchronizationManager.isActualTransactionActive()));
        }

        /**
         * 返回与内部列表隔离的快照，供测试验证严格先后顺序。
         *
         * @return 资源观察快照
         */
        List<RequestResourceObservation> snapshot() {
            return new ArrayList<>(observations);
        }
    }

    /**
     * 描述某个调用链阶段的线程、资源绑定和事务状态。
     */
    static final class RequestResourceObservation {

        private final String stage;
        private final long threadId;
        private final boolean dataSourceBound;
        private final boolean transactionActive;

        /**
         * 创建不可变的资源观察值。
         *
         * @param stage 调用链阶段
         * @param threadId 当前线程编号
         * @param dataSourceBound 是否绑定本次 DataSource
         * @param transactionActive 是否存在实际事务
         */
        RequestResourceObservation(String stage,
                                   long threadId,
                                   boolean dataSourceBound,
                                   boolean transactionActive) {
            this.stage = stage;
            this.threadId = threadId;
            this.dataSourceBound = dataSourceBound;
            this.transactionActive = transactionActive;
        }

        /**
         * 返回调用链阶段。
         *
         * @return 阶段名称
         */
        String getStage() {
            return stage;
        }

        /**
         * 返回执行该阶段的线程编号。
         *
         * @return 线程编号
         */
        long getThreadId() {
            return threadId;
        }

        /**
         * 返回该阶段是否绑定本次 DataSource。
         *
         * @return 已绑定时为 true
         */
        boolean isDataSourceBound() {
            return dataSourceBound;
        }

        /**
         * 返回该阶段是否位于实际事务中。
         *
         * @return 事务活动时为 true
         */
        boolean isTransactionActive() {
            return transactionActive;
        }
    }
}
