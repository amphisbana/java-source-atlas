package io.github.javasourceatlas.spring.transaction;

/**
 * 用于对比默认受检异常提交与 rollbackFor 回滚的实验异常。
 */
public final class CheckedBusinessException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * 创建带有说明文本的受检异常。
     *
     * @param message 异常说明
     */
    public CheckedBusinessException(String message) {
        super(message);
    }
}
