package io.github.javasourceatlas.spring.aop;

/**
 * AOP 实验的业务接口，用于触发 JDK 动态代理并演示自调用。
 */
public interface AtlasService {

    /**
     * 返回带前缀的问候语。
     *
     * @param name 名称
     * @return 问候语
     */
    String greet(String name);

    /**
     * 通过 this 直接调用内部方法，演示代理不会再次获得控制权。
     *
     * @param value 输入文本
     * @return 内部方法结果
     */
    String outerDirect(String value);

    /**
     * 通过当前代理再次调用内部方法，演示 exposeProxy 的显式补救边界。
     *
     * @param value 输入文本
     * @return 内部方法结果
     */
    String outerViaCurrentProxy(String value);

    /**
     * 作为外部调用和自调用共同使用的内部业务方法。
     *
     * @param value 输入文本
     * @return 加工后的文本
     */
    String inner(String value);
}
