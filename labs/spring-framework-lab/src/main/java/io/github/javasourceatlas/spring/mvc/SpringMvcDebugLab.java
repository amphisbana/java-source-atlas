package io.github.javasourceatlas.spring.mvc;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * 通过 MockMvc 驱动完整 DispatcherServlet 请求链的调试入口。
 */
public final class SpringMvcDebugLab {

    /**
     * 调试入口类不需要创建实例。
     */
    private SpringMvcDebugLab() {
    }

    /**
     * 依次执行正常、业务异常、请求体转换和方法不匹配请求，并打印完整观察结果。
     *
     * @param args 命令行参数，本实验不使用
     * @throws Exception MockMvc 执行失败时向上抛出
     */
    public static void main(String[] args) throws Exception {
        AnnotationConfigWebApplicationContext context = createContext();
        try {
            MockMvc mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                    .webAppContextSetup(context)
                    .build();

            runRequest(mockMvc, 42L, true);
            runRequest(mockMvc, 0L, false);
            runBodyRequest(mockMvc, "atlas");
            runUnsupportedMethodRequest(mockMvc);
        } finally {
            context.close();
        }
    }

    /**
     * 创建已刷新且绑定 MockServletContext 的 WebApplicationContext。
     *
     * @return 可供 DispatcherServlet 使用的上下文
     */
    private static AnnotationConfigWebApplicationContext createContext() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebMvcLabConfiguration.class);
        context.refresh();
        return context;
    }

    /**
     * 执行一次 GET 请求并打印完整可观察结果。
     *
     * @param mockMvc 请求驱动器
     * @param orderId 订单编号
     * @param detail 是否请求详情
     * @throws Exception 请求执行失败时向上抛出
     */
    private static void runRequest(MockMvc mockMvc, long orderId, boolean detail) throws Exception {
        MvcTrace.clear();
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/orders/{orderId}", orderId)
                        .param("detail", Boolean.toString(detail)))
                .andReturn();

        printResult("GET /orders/" + orderId + "?detail=" + detail, result);
    }

    /**
     * 执行一次纯文本请求体回显，观察消息转换器同时参与参数和返回值处理。
     *
     * @param mockMvc 请求驱动器
     * @param body 请求体内容
     * @throws Exception 请求执行失败时向上抛出
     */
    private static void runBodyRequest(MockMvc mockMvc, String body) throws Exception {
        MvcTrace.clear();
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/orders/echo")
                        .contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.TEXT_PLAIN)
                        .content(body))
                .andReturn();

        printResult("POST /orders/echo", result);
    }

    /**
     * 执行一次 HTTP 方法不匹配请求，观察 Controller 之前的映射异常。
     *
     * @param mockMvc 请求驱动器
     * @throws Exception 请求执行失败时向上抛出
     */
    private static void runUnsupportedMethodRequest(MockMvc mockMvc) throws Exception {
        MvcTrace.clear();
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/orders/{orderId}", 42L))
                .andReturn();

        printResult("POST /orders/42", result);
    }

    /**
     * 统一打印一次请求的状态、响应、handler、已解析异常和实验事件。
     *
     * @param requestLabel 请求标签
     * @param result MockMvc 执行结果
     * @throws Exception 响应体字符转换失败时向上抛出
     */
    private static void printResult(String requestLabel, MvcResult result) throws Exception {
        System.out.println("request=" + requestLabel);
        System.out.println("status=" + result.getResponse().getStatus());
        System.out.println("body=" + result.getResponse().getContentAsString());
        System.out.println("handler=" + result.getHandler());
        System.out.println("resolvedException=" + result.getResolvedException());
        System.out.println("events=" + MvcTrace.snapshot());
        System.out.println();
    }
}
