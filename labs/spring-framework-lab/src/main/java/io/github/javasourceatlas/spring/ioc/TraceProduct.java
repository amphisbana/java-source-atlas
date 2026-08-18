package io.github.javasourceatlas.spring.ioc;

/**
 * 由 FactoryBean 创建的简单产品对象。
 */
public final class TraceProduct {

    private final String label;

    /**
     * 创建带有固定标签的产品。
     *
     * @param label 产品标签
     */
    public TraceProduct(String label) {
        this.label = label;
    }

    /**
     * 返回产品标签。
     *
     * @return 产品标签
     */
    public String getLabel() {
        return label;
    }
}

