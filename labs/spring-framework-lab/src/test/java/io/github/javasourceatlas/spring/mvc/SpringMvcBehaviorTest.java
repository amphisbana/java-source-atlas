package io.github.javasourceatlas.spring.mvc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 Spring MVC 请求链中的公开可观察行为。
 */
class SpringMvcBehaviorTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;

    /**
     * 为每个测试创建独立 WebApplicationContext 和 DispatcherServlet。
     */
    @BeforeEach
    void setUp() {
        MvcTrace.clear();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebMvcLabConfiguration.class);
        context.refresh();
        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .build();
    }

    /**
     * 测试结束后关闭上下文，避免基础设施资源泄漏。
     */
    @AfterEach
    void tearDown() {
        context.close();
    }

    /**
     * 验证路径变量、查询参数、返回值处理器和拦截器顺序。
     *
     * @throws Exception 请求执行失败时向上抛出
     */
    @Test
    void shouldResolveArgumentsAndWriteResponse() throws Exception {
        MvcResult result = mockMvc.perform(get("/orders/{orderId}", 42L).param("detail", "true"))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals("order=42,detail=true", result.getResponse().getContentAsString());
        assertEquals("OrderController", result.getResponse().getHeader("X-Atlas-Handler"));
        assertNull(result.getModelAndView());
        assertBefore(MvcTrace.snapshot(), "interceptor:preHandle", "controller:findOrder(42,true)");
        assertBefore(MvcTrace.snapshot(), "controller:findOrder(42,true)", "interceptor:postHandle");
        assertBefore(MvcTrace.snapshot(), "interceptor:postHandle", "interceptor:afterCompletion(exception=null)");
    }

    /**
     * 验证控制器异常被 ExceptionHandlerExceptionResolver 转为 404。
     *
     * @throws Exception 请求执行失败时向上抛出
     */
    @Test
    void shouldResolveControllerException() throws Exception {
        MvcResult result = mockMvc.perform(get("/orders/{orderId}", 0L))
                .andExpect(status().isNotFound())
                .andReturn();

        assertEquals("order 0 not found", result.getResponse().getContentAsString());
        assertInstanceOf(OrderNotFoundException.class, result.getResolvedException());
        assertBefore(MvcTrace.snapshot(), "controller:findOrder(0,false)",
                "exceptionResolver:OrderNotFoundException");
        assertTrue(MvcTrace.snapshot().contains("interceptor:afterCompletion(exception=null)"));
    }

    /**
     * 验证路径存在但 HTTP 方法不匹配时返回 405，控制器不会执行。
     *
     * @throws Exception 请求执行失败时向上抛出
     */
    @Test
    void shouldRejectUnsupportedHttpMethod() throws Exception {
        MvcResult result = mockMvc.perform(post("/orders/{orderId}", 42L))
                .andExpect(status().isMethodNotAllowed())
                .andReturn();

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED.value(), result.getResponse().getStatus());
        assertTrue(MvcTrace.snapshot().isEmpty());
    }

    /**
     * 验证 RequestResponseBodyMethodProcessor 使用消息转换器读取并写出纯文本。
     *
     * @throws Exception 请求执行失败时向上抛出
     */
    @Test
    void shouldReadRequestBodyAndWriteResponse() throws Exception {
        MvcResult result = mockMvc.perform(post("/orders/echo")
                        .contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.TEXT_PLAIN)
                        .content("atlas"))
                .andExpect(status().isCreated())
                .andReturn();

        assertEquals("received=atlas", result.getResponse().getContentAsString());
        assertEquals("OrderController.echoOrder", result.getResponse().getHeader("X-Atlas-Handler"));
        assertTrue(MvcTrace.snapshot().contains("controller:echoOrder(atlas)"));
        assertTrue(MvcTrace.snapshot().contains("interceptor:afterCompletion(exception=null)"));
    }

    /**
     * 验证路径变量类型转换失败发生在 Controller 调用之前，并由默认解析器转为 400。
     *
     * @throws Exception 请求执行失败时向上抛出
     */
    @Test
    void shouldRejectInvalidPathVariableBeforeController() throws Exception {
        MvcResult result = mockMvc.perform(get("/orders/not-a-number"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertInstanceOf(MethodArgumentTypeMismatchException.class, result.getResolvedException());
        assertTrue(MvcTrace.snapshot().contains("interceptor:preHandle"));
        assertTrue(MvcTrace.snapshot().contains("interceptor:afterCompletion(exception=null)"));
        assertTrue(MvcTrace.snapshot().stream().noneMatch((event) -> event.startsWith("controller:")));
    }

    /**
     * 验证必填请求体为空时在参数解析阶段返回 400，Controller 不会执行。
     *
     * @throws Exception 请求执行失败时向上抛出
     */
    @Test
    void shouldRejectMissingRequestBodyBeforeController() throws Exception {
        MvcResult result = mockMvc.perform(post("/orders/echo")
                        .contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertInstanceOf(HttpMessageNotReadableException.class, result.getResolvedException());
        assertTrue(MvcTrace.snapshot().contains("interceptor:preHandle"));
        assertTrue(MvcTrace.snapshot().stream().noneMatch((event) -> event.startsWith("controller:")));
    }

    /**
     * 验证 consumes 条件失败时在 HandlerMapping 阶段返回 415，执行链不会建立。
     *
     * @throws Exception 请求执行失败时向上抛出
     */
    @Test
    void shouldRejectUnsupportedRequestMediaTypeDuringMapping() throws Exception {
        MvcResult result = mockMvc.perform(post("/orders/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andReturn();

        assertInstanceOf(HttpMediaTypeNotSupportedException.class, result.getResolvedException());
        assertTrue(MvcTrace.snapshot().isEmpty());
    }

    /**
     * 验证 produces 条件与 Accept 不兼容时在 HandlerMapping 阶段返回 406。
     *
     * @throws Exception 请求执行失败时向上抛出
     */
    @Test
    void shouldRejectUnacceptableResponseMediaTypeDuringMapping() throws Exception {
        MvcResult result = mockMvc.perform(post("/orders/echo")
                        .contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("atlas"))
                .andExpect(status().isNotAcceptable())
                .andReturn();

        assertInstanceOf(HttpMediaTypeNotAcceptableException.class, result.getResolvedException());
        assertTrue(MvcTrace.snapshot().isEmpty());
    }

    /**
     * 断言两个事件均存在且符合指定先后顺序。
     *
     * @param events 请求事件快照
     * @param earlier 应先发生的事件
     * @param later 应后发生的事件
     */
    private void assertBefore(List<String> events, String earlier, String later) {
        int earlierIndex = events.indexOf(earlier);
        int laterIndex = events.indexOf(later);
        assertTrue(earlierIndex >= 0, () -> "未找到事件: " + earlier + "，实际事件=" + events);
        assertTrue(laterIndex >= 0, () -> "未找到事件: " + later + "，实际事件=" + events);
        assertTrue(earlierIndex < laterIndex,
                () -> earlier + " 应早于 " + later + "，实际事件=" + events);
    }
}
