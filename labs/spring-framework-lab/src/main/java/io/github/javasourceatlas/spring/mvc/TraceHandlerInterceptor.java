package io.github.javasourceatlas.spring.mvc;

import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 记录 HandlerExecutionChain 中拦截器的三段回调。
 */
public final class TraceHandlerInterceptor implements HandlerInterceptor {

    /**
     * 在处理器执行前记录事件并允许请求继续。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param handler 已匹配的处理器
     * @return true 表示继续进入 HandlerAdapter
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        MvcTrace.add("interceptor:preHandle");
        return true;
    }

    /**
     * 在处理器正常返回后记录事件；REST 返回通常没有 ModelAndView。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param handler 已执行的处理器
     * @param modelAndView 视图结果，REST 请求通常为 null
     */
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView modelAndView) {
        MvcTrace.add("interceptor:postHandle");
    }

    /**
     * 在请求完成后记录最终异常状态；已被异常解析器处理时 exception 为 null。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param handler 已执行的处理器
     * @param exception 未被解析的异常
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        MvcTrace.add("interceptor:afterCompletion(exception="
                + (exception == null ? "null" : exception.getClass().getSimpleName()) + ")");
    }
}
