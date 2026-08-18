package io.github.javasourceatlas.spring.transaction;

/**
 * 提供必须经过第二个代理调用的内层事务场景。
 */
public interface InnerWorkService {

    /**
     * 参与 REQUIRED 外层事务后抛出运行时异常。
     */
    void requiredFailure();

    /**
     * 使用 REQUIRES_NEW 提交一个独立内层事务。
     */
    void requiresNewSuccess();

    /**
     * 使用 NESTED 保存点失败，供外层捕获异常。
     */
    void nestedFailure();
}
