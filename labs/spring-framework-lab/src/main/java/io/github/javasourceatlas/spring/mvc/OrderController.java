package io.github.javasourceatlas.spring.mvc;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供最小 REST 接口，用来观察路径变量、查询参数和返回值处理。
 */
@RestController
@RequestMapping("/orders")
public final class OrderController {

    /**
     * 根据订单编号返回文本响应；编号为 0 时进入统一异常解析链。
     *
     * @param orderId 路径中的订单编号
     * @param detail 是否返回详情标记
     * @return 带实验响应头的 HTTP 结果
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<String> findOrder(
            @PathVariable("orderId") long orderId,
            @RequestParam(value = "detail", defaultValue = "false") boolean detail) {
        MvcTrace.add("controller:findOrder(" + orderId + "," + detail + ")");
        if (orderId == 0L) {
            throw new OrderNotFoundException(orderId);
        }
        return ResponseEntity.ok()
                .header("X-Atlas-Handler", "OrderController")
                .body("order=" + orderId + ",detail=" + detail);
    }

    /**
     * 回显纯文本请求体，用来观察 RequestResponseBodyMethodProcessor 的读写链路。
     *
     * @param body 由 StringHttpMessageConverter 读取的请求体
     * @return 带 201 状态的纯文本响应
     */
    @PostMapping(path = "/echo", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> echoOrder(@RequestBody String body) {
        MvcTrace.add("controller:echoOrder(" + body + ")");
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Atlas-Handler", "OrderController.echoOrder")
                .body("received=" + body);
    }
}
