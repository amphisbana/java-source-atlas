package io.github.javasourceatlas.spring.ioc;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证三级缓存能够处理与不能处理的典型循环依赖边界。
 */
class CircularDependencyBehaviorTest {

    /**
     * 验证启用循环引用时，singleton Setter 循环可以取得同一份 A 引用。
     */
    @Test
    void shouldResolveSingletonSetterCycle() {
        DefaultListableBeanFactory factory = createSetterCycleFactory(
                ConfigurableBeanFactory.SCOPE_SINGLETON, true);

        CircularDependencyFixtures.SetterA a = factory.getBean(
                "setterA", CircularDependencyFixtures.SetterA.class);

        assertSame(a, a.getB().getA());
    }

    /**
     * 验证构造器循环在实例产生之前就失败，无法进入三级缓存提前暴露阶段。
     */
    @Test
    void shouldRejectConstructorCycle() {
        DefaultListableBeanFactory factory = createConstructorCycleFactory();

        assertThrows(BeanCreationException.class, () -> factory.getBean("constructorA"));
    }

    /**
     * 验证 prototype 不使用单例三级缓存，因此 Setter 循环仍然失败。
     */
    @Test
    void shouldRejectPrototypeSetterCycle() {
        DefaultListableBeanFactory factory = createSetterCycleFactory(
                ConfigurableBeanFactory.SCOPE_PROTOTYPE, true);

        assertThrows(BeanCreationException.class, () -> factory.getBean("setterA"));
    }

    /**
     * 验证关闭 allowCircularReferences 后，singleton Setter 循环也会失败。
     */
    @Test
    void shouldRejectSetterCycleWhenCircularReferencesAreDisabled() {
        DefaultListableBeanFactory factory = createSetterCycleFactory(
                ConfigurableBeanFactory.SCOPE_SINGLETON, false);

        assertThrows(BeanCreationException.class, () -> factory.getBean("setterA"));
    }

    /**
     * 创建由两个 RootBeanDefinition 组成的 Setter 循环。
     *
     * @param scope 两个 Bean 使用的作用域
     * @param allowCircularReferences 是否允许提前暴露循环引用
     * @return 已注册定义但尚未创建 Bean 的工厂
     */
    private DefaultListableBeanFactory createSetterCycleFactory(
            String scope, boolean allowCircularReferences) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.setAllowCircularReferences(allowCircularReferences);

        RootBeanDefinition aDefinition = new RootBeanDefinition(CircularDependencyFixtures.SetterA.class);
        aDefinition.setScope(scope);
        aDefinition.getPropertyValues().add("b", new RuntimeBeanReference("setterB"));

        RootBeanDefinition bDefinition = new RootBeanDefinition(CircularDependencyFixtures.SetterB.class);
        bDefinition.setScope(scope);
        bDefinition.getPropertyValues().add("a", new RuntimeBeanReference("setterA"));

        // 先注册完整依赖图，再触发 getBean，保证实验只观察创建阶段。
        factory.registerBeanDefinition("setterA", aDefinition);
        factory.registerBeanDefinition("setterB", bDefinition);
        return factory;
    }

    /**
     * 创建两个构造器参数互相引用的 BeanDefinition。
     *
     * @return 已注册构造器循环的工厂
     */
    private DefaultListableBeanFactory createConstructorCycleFactory() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

        RootBeanDefinition aDefinition = new RootBeanDefinition(CircularDependencyFixtures.ConstructorA.class);
        aDefinition.getConstructorArgumentValues().addIndexedArgumentValue(
                0, new RuntimeBeanReference("constructorB"));

        RootBeanDefinition bDefinition = new RootBeanDefinition(CircularDependencyFixtures.ConstructorB.class);
        bDefinition.getConstructorArgumentValues().addIndexedArgumentValue(
                0, new RuntimeBeanReference("constructorA"));

        // 构造器参数解析发生在实例产生之前，这里不会出现可用的早期引用工厂。
        factory.registerBeanDefinition("constructorA", aDefinition);
        factory.registerBeanDefinition("constructorB", bDefinition);
        return factory;
    }
}
