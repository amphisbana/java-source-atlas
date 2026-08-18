package io.github.javasourceatlas.spring.mvc;

/**
 * 表示实验订单不存在，用于观察 HandlerExceptionResolver 分支。
 */
public final class OrderNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 创建订单不存在异常。
     *
     * @param orderId 不存在的订单编号
     */
    public OrderNotFoundException(long orderId) {
        super("order " + orderId + " not found");
    }
}
