package io.github.javasourceatlas.jdk.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Reflection 与 JDK 动态代理专题依赖的公开可观察行为。
 */
class ReflectionProxyBehaviorTest {

    /**
     * 验证 getMethod 搜索继承的 public 方法，而 getDeclaredMethod 只搜索当前类声明。
     *
     * @throws Exception 反射查找失败
     */
    @Test
    void shouldSeparatePublicMemberSearchFromDeclaredMemberSearch() throws Exception {
        Method inherited = ChildTarget.class.getMethod("inherited");
        Method direct = ChildTarget.class.getDeclaredMethod("direct");
        Method hidden = ChildTarget.class.getDeclaredMethod("hidden");

        assertEquals(BaseTarget.class, inherited.getDeclaringClass());
        assertEquals(ChildTarget.class, direct.getDeclaringClass());
        assertEquals(ChildTarget.class, hidden.getDeclaringClass());
        assertThrows(NoSuchMethodException.class,
                () -> ChildTarget.class.getDeclaredMethod("inherited"));
        assertThrows(NoSuchMethodException.class,
                () -> ChildTarget.class.getMethod("hidden"));
    }

    /**
     * 验证 Method.invoke 支持拆箱后拓宽、装箱返回值，并要求可变参数数组由调用者显式提供。
     *
     * @throws Exception 反射调用失败
     */
    @Test
    void shouldApplyReflectionConversionsWithoutPackingTargetVarargs() throws Exception {
        InvocationTarget target = new InvocationTarget();
        Method sum = InvocationTarget.class.getMethod("sum", long.class, int.class);
        Method join = InvocationTarget.class.getMethod("join", String[].class);
        Method record = InvocationTarget.class.getMethod("record", String.class);

        Object sumResult = sum.invoke(target, Integer.valueOf(4), Short.valueOf((short) 3));
        Object joinResult = join.invoke(target, new Object[]{new String[]{"a", "b"}});
        Object voidResult = record.invoke(target, "done");

        assertEquals(Long.class, sumResult.getClass());
        assertEquals(7L, sumResult);
        assertEquals("a,b", joinResult);
        assertNull(voidResult);
        assertEquals("done", target.recorded());
        assertThrows(IllegalArgumentException.class,
                () -> sum.invoke(target, Integer.valueOf(4), Long.valueOf(3)));
        assertThrows(IllegalArgumentException.class,
                () -> join.invoke(target, "a", "b"));
    }

    /**
     * 验证目标方法抛出的原异常被 InvocationTargetException 包装且 cause 保持同一实例。
     *
     * @throws Exception 方法查找失败
     */
    @Test
    void shouldWrapTargetFailureInInvocationTargetException() throws Exception {
        InvocationTarget target = new InvocationTarget();
        Method fail = InvocationTarget.class.getMethod("fail");
        IOException failure = target.failure();

        InvocationTargetException thrown = assertThrows(
                InvocationTargetException.class,
                () -> fail.invoke(target));

        assertSame(failure, thrown.getCause());
    }

    /**
     * 验证对项目自身类设置可访问标记后，可以调用其 private 方法。
     *
     * @throws Exception 反射查找或调用失败
     */
    @Test
    void shouldInvokePrivateProjectMethodAfterSetAccessible() throws Exception {
        Method hidden = ChildTarget.class.getDeclaredMethod("hidden");
        hidden.setAccessible(true);

        assertEquals("hidden", hidden.invoke(new ChildTarget()));
    }

    /**
     * 验证相同 loader 与同序接口列表复用代理 Class，而接口顺序改变会生成另一代理 Class。
     */
    @Test
    void shouldCacheProxyClassByLoaderAndOrderedInterfaces() {
        ClassLoader loader = ReflectionProxyBehaviorTest.class.getClassLoader();
        InvocationHandler handler = (proxy, method, args) -> defaultValue(method.getReturnType());
        Object first = Proxy.newProxyInstance(
                loader, new Class<?>[]{Greeting.class, Marker.class}, handler);
        Object sameOrder = Proxy.newProxyInstance(
                loader, new Class<?>[]{Greeting.class, Marker.class}, handler);
        Object reversed = Proxy.newProxyInstance(
                loader, new Class<?>[]{Marker.class, Greeting.class}, handler);

        assertSame(first.getClass(), sameOrder.getClass());
        assertNotSame(first.getClass(), reversed.getClass());
        assertEquals(Proxy.class, first.getClass().getSuperclass());
        assertArrayEquals(new Class<?>[]{Greeting.class, Marker.class},
                first.getClass().getInterfaces());
        assertTrue(Proxy.isProxyClass(first.getClass()));
        assertSame(handler, Proxy.getInvocationHandler(first));
    }

    /**
     * 验证生成代理方法把 proxy、Method 和装箱参数传给处理器，并把结果返回给调用者。
     */
    @Test
    void shouldDispatchInterfaceInvocationToHandler() {
        AtomicReference<Object> observedProxy = new AtomicReference<>();
        AtomicReference<Method> observedMethod = new AtomicReference<>();
        AtomicReference<Object[]> observedArgs = new AtomicReference<>();
        InvocationHandler handler = (proxy, method, args) -> {
            observedProxy.set(proxy);
            observedMethod.set(method);
            observedArgs.set(args);
            return "hello " + args[0] + " x " + args[1];
        };
        Greeting proxy = (Greeting) Proxy.newProxyInstance(
                Greeting.class.getClassLoader(),
                new Class<?>[]{Greeting.class},
                handler);

        assertEquals("hello atlas x 2", proxy.hello("atlas", 2));
        assertSame(proxy, observedProxy.get());
        assertEquals(Greeting.class, observedMethod.get().getDeclaringClass());
        assertEquals("hello", observedMethod.get().getName());
        assertArrayEquals(new Object[]{"atlas", 2}, observedArgs.get());
    }

    /**
     * 验证接口 default 方法不会由代理类直接执行，而是先进入 InvocationHandler。
     */
    @Test
    void shouldDispatchDefaultMethodToHandler() {
        AtomicReference<Method> observedMethod = new AtomicReference<>();
        Greeting proxy = (Greeting) Proxy.newProxyInstance(
                Greeting.class.getClassLoader(),
                new Class<?>[]{Greeting.class},
                (currentProxy, method, args) -> {
                    observedMethod.set(method);
                    return "handler-default";
                });

        assertEquals("handler-default", proxy.defaultGreeting());
        assertEquals("defaultGreeting", observedMethod.get().getName());
        assertEquals(Greeting.class, observedMethod.get().getDeclaringClass());
        assertTrue(observedMethod.get().isDefault());
    }

    /**
     * 验证处理器反射转发真实目标时，解包后传播的是目标抛出的同一个异常实例。
     */
    @Test
    void shouldUnwrapRealTargetFailureInsideHandler() {
        FailingTarget target = new FailingTarget();
        InvocationHandler handler = (proxy, method, args) -> {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                // 转发代理应保留真实业务异常，不能把反射包装泄漏到接口调用方。
                throw exception.getCause();
            }
        };
        FailingOperation proxy = (FailingOperation) Proxy.newProxyInstance(
                FailingOperation.class.getClassLoader(),
                new Class<?>[]{FailingOperation.class},
                handler);

        IOException thrown = assertThrows(IOException.class, proxy::execute);

        assertSame(target.failure(), thrown);
    }

    /**
     * 验证 hashCode、equals、toString 三个 Object 方法也被生成代理方法转发给处理器。
     */
    @Test
    void shouldDispatchObjectMethodsToHandler() {
        AtomicInteger calls = new AtomicInteger();
        InvocationHandler handler = (proxy, method, args) -> {
            calls.incrementAndGet();
            assertEquals(Object.class, method.getDeclaringClass());
            if ("toString".equals(method.getName())) {
                return "proxy-view";
            }
            if ("hashCode".equals(method.getName())) {
                return 41;
            }
            if ("equals".equals(method.getName())) {
                return proxy == args[0];
            }
            throw new AssertionError("未预期的 Object 方法：" + method);
        };
        Greeting proxy = (Greeting) Proxy.newProxyInstance(
                Greeting.class.getClassLoader(),
                new Class<?>[]{Greeting.class},
                handler);

        assertEquals("proxy-view", proxy.toString());
        assertEquals(41, proxy.hashCode());
        assertTrue(proxy.equals(proxy));
        assertFalse(proxy.equals(new Object()));
        assertEquals(4, calls.get());
    }

    /**
     * 验证重复签名只生成一个分派入口，传给处理器的 Method 来自接口列表中最靠前的接口。
     */
    @Test
    void shouldUseForemostInterfaceMethodForDuplicateSignature() {
        AtomicReference<Class<?>> declaringClass = new AtomicReference<>();
        InvocationHandler handler = (proxy, method, args) -> {
            declaringClass.set(method.getDeclaringClass());
            return "id";
        };
        Object proxy = Proxy.newProxyInstance(
                ReflectionProxyBehaviorTest.class.getClassLoader(),
                new Class<?>[]{FirstView.class, SecondView.class},
                handler);

        assertEquals("id", ((SecondView) proxy).identity());
        assertEquals(FirstView.class, declaringClass.get());
    }

    /**
     * 验证未声明的受检异常被包装，而接口已声明的受检异常保持原实例直接传播。
     */
    @Test
    void shouldEnforceProxyCheckedExceptionContract() {
        IOException failure = new IOException("remote unavailable");
        InvocationHandler handler = (proxy, method, args) -> {
            throw failure;
        };
        NoDeclaredFailure undeclared = (NoDeclaredFailure) Proxy.newProxyInstance(
                ReflectionProxyBehaviorTest.class.getClassLoader(),
                new Class<?>[]{NoDeclaredFailure.class},
                handler);
        DeclaredFailure declared = (DeclaredFailure) Proxy.newProxyInstance(
                ReflectionProxyBehaviorTest.class.getClassLoader(),
                new Class<?>[]{DeclaredFailure.class},
                handler);

        UndeclaredThrowableException wrapped = assertThrows(
                UndeclaredThrowableException.class,
                undeclared::execute);
        IOException direct = assertThrows(IOException.class, declared::execute);

        assertSame(failure, wrapped.getUndeclaredThrowable());
        assertSame(failure, direct);
    }

    /**
     * 验证代理方法会按接口返回类型强制转换或拆箱处理器结果。
     */
    @Test
    void shouldValidateHandlerResultAtGeneratedProxyMethod() {
        Counter nullCounter = (Counter) Proxy.newProxyInstance(
                ReflectionProxyBehaviorTest.class.getClassLoader(),
                new Class<?>[]{Counter.class},
                (proxy, method, args) -> null);
        Counter wrongTypeCounter = (Counter) Proxy.newProxyInstance(
                ReflectionProxyBehaviorTest.class.getClassLoader(),
                new Class<?>[]{Counter.class},
                (proxy, method, args) -> "not-an-integer");

        assertThrows(NullPointerException.class, nullCounter::count);
        assertThrows(ClassCastException.class, wrongTypeCounter::count);
    }

    /**
     * 为只用于代理结构测试的处理器提供原始类型安全默认值。
     *
     * @param returnType 方法返回类型
     * @return 与返回类型兼容的默认值
     */
    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }

    /**
     * 提供继承公共方法的测试基类。
     */
    private static class BaseTarget {

        /**
         * 返回继承结果。
         *
         * @return 固定文本
         */
        public String inherited() {
            return "base";
        }
    }

    /**
     * 同时声明 public 与 private 方法的测试子类。
     */
    private static final class ChildTarget extends BaseTarget {

        /**
         * 返回当前类直接声明结果。
         *
         * @return 固定文本
         */
        public String direct() {
            return "direct";
        }

        /**
         * 返回私有结果。
         *
         * @return 固定文本
         */
        private String hidden() {
            return "hidden";
        }
    }

    /**
     * 提供反射参数转换、void 返回和异常包装场景的目标对象。
     */
    public static final class InvocationTarget {

        private final IOException failure = new IOException("target failure");
        private String recorded;

        /**
         * 对两个原始数值求和。
         *
         * @param left 长整数参数
         * @param right 整数参数
         * @return 求和结果
         */
        public long sum(long left, int right) {
            return left + right;
        }

        /**
         * 拼接可变参数数组。
         *
         * @param values 文本数组
         * @return 逗号连接结果
         */
        public String join(String... values) {
            return String.join(",", values);
        }

        /**
         * 记录输入值并以 void 结束。
         *
         * @param value 待记录文本
         */
        public void record(String value) {
            recorded = value;
        }

        /**
         * 抛出预先创建的目标异常。
         *
         * @throws IOException 固定目标异常
         */
        public void fail() throws IOException {
            throw failure;
        }

        /**
         * 返回已记录文本。
         *
         * @return 最近记录的文本
         */
        private String recorded() {
            return recorded;
        }

        /**
         * 返回预先创建的异常，便于断言引用身份。
         *
         * @return 固定异常
         */
        private IOException failure() {
            return failure;
        }
    }

    /**
     * 带原始参数的动态代理接口。
     */
    public interface Greeting {

        /**
         * 生成问候文本。
         *
         * @param name 被问候者
         * @param times 次数
         * @return 问候结果
         */
        String hello(String name, int times);

        /**
         * 提供会被 InvocationHandler 截获的接口默认实现。
         *
         * @return 默认问候文本
         */
        default String defaultGreeting() {
            return "interface-default";
        }
    }

    /**
     * 用于代理缓存键测试的标记接口。
     */
    public interface Marker {
    }

    /**
     * 第一个重复签名接口。
     */
    public interface FirstView {

        /**
         * 返回标识。
         *
         * @return 标识文本
         */
        String identity();
    }

    /**
     * 第二个重复签名接口。
     */
    public interface SecondView {

        /**
         * 返回标识。
         *
         * @return 标识文本
         */
        String identity();
    }

    /**
     * 未声明受检异常的接口。
     */
    public interface NoDeclaredFailure {

        /**
         * 执行动作。
         */
        void execute();
    }

    /**
     * 声明 IOException 的接口。
     */
    public interface DeclaredFailure {

        /**
         * 执行动作。
         *
         * @throws IOException 允许处理器传播的受检异常
         */
        void execute() throws IOException;
    }

    /**
     * 返回原始 int 的接口。
     */
    public interface Counter {

        /**
         * 返回计数值。
         *
         * @return 整数结果
         */
        int count();
    }

    /**
     * 声明真实目标可能抛出 IOException 的代理接口。
     */
    public interface FailingOperation {

        /**
         * 执行固定失败动作。
         *
         * @throws IOException 真实目标异常
         */
        void execute() throws IOException;
    }

    /**
     * 保存固定异常实例，便于验证处理器解包后没有替换 cause。
     */
    public static final class FailingTarget implements FailingOperation {

        private final IOException failure = new IOException("target failure through proxy");

        /**
         * 抛出预先创建的目标异常。
         *
         * @throws IOException 固定目标异常
         */
        @Override
        public void execute() throws IOException {
            throw failure;
        }

        /**
         * 返回固定目标异常供引用身份断言。
         *
         * @return 固定异常
         */
        IOException failure() {
            return failure;
        }
    }
}
