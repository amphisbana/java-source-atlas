package io.github.javasourceatlas.spring.mvc;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把业务异常转换为可观察的 HTTP 404 响应。
 */
@RestControllerAdvice
public final class OrderExceptionHandler {

    /**
     * 处理订单不存在异常，展示 ExceptionHandlerExceptionResolver 的正常命中路径。
     *
     * @param exception 控制器抛出的业务异常
     * @return 404 文本响应
     */
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<String> handleMissingOrder(OrderNotFoundException exception) {
        MvcTrace.add("exceptionResolver:OrderNotFoundException");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(exception.getMessage());
    }
}
