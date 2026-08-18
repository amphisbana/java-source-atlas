package io.github.javasourceatlas.jdk.runtime;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用公开 API 展示 Method.invoke 与 JDK 动态代理的稳定行为边界。
 */
public final class ReflectionProxyDebugLab {

    /**
     * 工具类不需要创建实例。
     */
    private ReflectionProxyDebugLab() {
    }

    /**
     * 按固定顺序运行反射调用和动态代理调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws Exception 反射查找、调用或代理场景失败
     */
    public static void main(String[] args) throws Exception {
        printHeader("成员发现、参数转换与私有访问");
        observeMetadataAndInvoke();

        printHeader("Method 副本共享 accessor 与 JDK 8 inflation");
        observeMethodAccessorReuseAndInflation();

        printHeader("目标异常包装");
        observeTargetException();

        printHeader("代理类缓存与接口顺序");
        observeProxyCreationAndCache();

        printHeader("生成代理方法到 InvocationHandler");
        observeProxyDispatch();

        printHeader("重复方法的前置接口优先级");
        observeDuplicateMethodPrecedence();

        printHeader("受检异常边界");
        observeCheckedExceptionBoundary();
    }

    /**
     * 比较 getMethod 与 getDeclaredMethod，并展示反射调用的拆箱、拓宽和统一返回值外观。
     *
     * @throws Exception 方法查找或调用失败
     */
    static void observeMetadataAndInvoke() throws Exception {
        GreetingTarget target = new GreetingTarget();
        Method inherited = GreetingTarget.class.getMethod("inheritedLabel");
        Method welcome = GreetingTarget.class.getMethod("welcome", String.class, long.class);
        Method secret = GreetingTarget.class.getDeclaredMethod("secret", String.class);

        Object result = welcome.invoke(target, "atlas", Integer.valueOf(2));
        secret.setAccessible(true);
        Object privateResult = secret.invoke(target, "debug");

        System.out.printf("继承方法声明类=%s，public invoke=%s（返回类型=%s）%n",
                inherited.getDeclaringClass().getSimpleName(),
                result,
                result.getClass().getSimpleName());
        System.out.printf("declared private=%s，setAccessible 后结果=%s%n",
                secret.getName(), privateResult);
    }

    /**
     * 用两个 Method 副本完成总计 17 次调用，便于在 JDK 8 中观察 root accessor 复用和 inflation 切换。
     *
     * @throws Exception 方法查找或调用失败
     */
    static void observeMethodAccessorReuseAndInflation() throws Exception {
        InflationTarget target = new InflationTarget();
        Method firstCopy = InflationTarget.class.getMethod("ping", String.class);
        Method secondCopy = InflationTarget.class.getMethod("ping", String.class);

        Object firstResult = firstCopy.invoke(target, "call");
        Object lastResult = null;
        // 第一次调用创建 accessor，第二个副本随后从共同 root 取得它；总计 17 次可进入 generated 路径。
        for (int invocation = 2; invocation <= 17; invocation++) {
            lastResult = secondCopy.invoke(target, "call");
        }

        System.out.printf(
                "Method 是不同副本=%s，equals=%s，调用次数=%d，首次/第 17 次结果=%s/%s%n",
                firstCopy != secondCopy,
                firstCopy.equals(secondCopy),
                target.invocationCount(),
                firstResult,
                lastResult);
    }

    /**
     * 触发目标方法异常，证明 Method.invoke 使用 InvocationTargetException 隔离目标失败。
     *
     * @throws Exception 方法查找失败
     */
    static void observeTargetException() throws Exception {
        GreetingTarget target = new GreetingTarget();
        Method fail = GreetingTarget.class.getMethod("fail", String.class);
        try {
            fail.invoke(target, "boom");
            throw new IllegalStateException("失败方法不应正常返回");
        } catch (InvocationTargetException exception) {
            System.out.printf("invoke 外层=%s，目标异常=%s: %s%n",
                    exception.getClass().getSimpleName(),
                    exception.getCause().getClass().getSimpleName(),
                    exception.getCause().getMessage());
        }
    }

    /**
     * 创建多个代理实例，观察同一 loader 与同序接口列表复用代理类，而接口顺序参与缓存键。
     */
    static void observeProxyCreationAndCache() {
        ClassLoader loader = ReflectionProxyDebugLab.class.getClassLoader();
        InvocationHandler handler = (proxy, method, args) -> defaultValue(method.getReturnType());
        Object first = Proxy.newProxyInstance(
                loader, new Class<?>[]{GreetingService.class, Marker.class}, handler);
        Object sameOrder = Proxy.newProxyInstance(
                loader, new Class<?>[]{GreetingService.class, Marker.class}, handler);
        Object reversed = Proxy.newProxyInstance(
                loader, new Class<?>[]{Marker.class, GreetingService.class}, handler);

        System.out.printf("代理类=%s，父类=%s，接口=%s%n",
                first.getClass().getName(),
                first.getClass().getSuperclass().getName(),
                Arrays.toString(first.getClass().getInterfaces()));
        System.out.printf("同序复用 Class=%s，逆序得到不同 Class=%s，isProxyClass=%s%n",
                first.getClass() == sameOrder.getClass(),
                first.getClass() != reversed.getClass(),
                Proxy.isProxyClass(first.getClass()));
    }

    /**
     * 让代理方法进入处理器，再由处理器通过 Method.invoke 转发真实目标并解包目标异常。
     */
    static void observeProxyDispatch() throws IOException {
        GreetingTarget target = new GreetingTarget();
        AtomicInteger calls = new AtomicInteger();
        ForwardingInvocationHandler handler = new ForwardingInvocationHandler(target, calls);

        GreetingService proxy = (GreetingService) Proxy.newProxyInstance(
                GreetingService.class.getClassLoader(),
                new Class<?>[]{GreetingService.class},
                handler);

        String welcome = proxy.welcome("atlas", 3);
        String defaultLabel = proxy.defaultLabel();
        String proxyText = proxy.toString();
        boolean selfEquals = proxy.equals(proxy);
        IOException observedFailure;
        try {
            proxy.fail("proxy-boom");
            throw new IllegalStateException("代理目标失败不应正常返回");
        } catch (IOException failure) {
            observedFailure = failure;
        }

        System.out.printf(
                "welcome=%s，default=%s，toString=%s，equals(self)=%s，handlerCalls=%d，目标异常已解包=%s%n",
                welcome,
                defaultLabel,
                proxyText,
                selfEquals,
                calls.get(),
                observedFailure == handler.lastTargetFailure());
    }

    /**
     * 用两种接口顺序创建重复签名代理，观察传给处理器的 Method 来自最前面的接口。
     */
    static void observeDuplicateMethodPrecedence() {
        InvocationHandler ownerReporter = (proxy, method, args) ->
                method.getDeclaringClass().getSimpleName();
        Object firstOrder = Proxy.newProxyInstance(
                ReflectionProxyDebugLab.class.getClassLoader(),
                new Class<?>[]{FirstView.class, SecondView.class},
                ownerReporter);
        Object secondOrder = Proxy.newProxyInstance(
                ReflectionProxyDebugLab.class.getClassLoader(),
                new Class<?>[]{SecondView.class, FirstView.class},
                ownerReporter);

        System.out.printf("[First, Second] 经 Second 引用调用 -> %s%n",
                ((SecondView) firstOrder).identity());
        System.out.printf("[Second, First] 经 First 引用调用 -> %s%n",
                ((FirstView) secondOrder).identity());
    }

    /**
     * 对比处理器抛出已声明和未声明受检异常时，生成代理方法的不同外观。
     *
     * @throws IOException 已声明受检异常按接口契约直接传播
     */
    static void observeCheckedExceptionBoundary() throws IOException {
        IOException failure = new IOException("remote unavailable");
        InvocationHandler throwingHandler = (proxy, method, args) -> {
            throw failure;
        };
        DeclaredFailure declared = (DeclaredFailure) Proxy.newProxyInstance(
                ReflectionProxyDebugLab.class.getClassLoader(),
                new Class<?>[]{DeclaredFailure.class},
                throwingHandler);
        NoDeclaredFailure undeclared = (NoDeclaredFailure) Proxy.newProxyInstance(
                ReflectionProxyDebugLab.class.getClassLoader(),
                new Class<?>[]{NoDeclaredFailure.class},
                throwingHandler);

        try {
            declared.execute();
        } catch (IOException exception) {
            System.out.printf("已声明异常直接传播，同一实例=%s%n", exception == failure);
        }

        try {
            undeclared.execute();
            throw new IllegalStateException("未声明受检异常不应直接返回");
        } catch (UndeclaredThrowableException exception) {
            System.out.printf("未声明异常外层=%s，内部同一实例=%s%n",
                    exception.getClass().getSimpleName(),
                    exception.getUndeclaredThrowable() == failure);
        }
    }

    /**
     * 为只用于生成代理类的处理器提供原始类型安全默认值。
     *
     * @param returnType 代理方法返回类型
     * @return 与返回类型兼容的默认值
     */
    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    /**
     * 打印场景标题，使控制台输出与断点实验步骤保持一致。
     *
     * @param title 场景名称
     */
    private static void printHeader(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }

    /**
     * 供动态代理实现的问候接口。
     */
    public interface GreetingService {

        /**
         * 按次数生成问候文本。
         *
         * @param name 被问候者
         * @param times 次数
         * @return 问候文本
         */
        String welcome(String name, long times);

        /**
         * 返回接口默认标签，用于证明 default 方法同样先进入 InvocationHandler。
         *
         * @return 默认标签
         */
        default String defaultLabel() {
            return "default-label";
        }

        /**
         * 触发真实目标异常，验证转发处理器会解包 InvocationTargetException。
         *
         * @param message 异常消息
         * @throws IOException 真实目标失败
         */
        void fail(String message) throws IOException;
    }

    /**
     * 仅用于改变代理接口列表的标记接口。
     */
    public interface Marker {
    }

    /**
     * 第一个重复签名视图。
     */
    public interface FirstView {

        /**
         * 返回对象标识。
         *
         * @return 标识文本
         */
        String identity();
    }

    /**
     * 第二个重复签名视图。
     */
    public interface SecondView {

        /**
         * 返回对象标识。
         *
         * @return 标识文本
         */
        String identity();
    }

    /**
     * 声明 IOException 的代理接口。
     */
    public interface DeclaredFailure {

        /**
         * 执行可能失败的动作。
         *
         * @throws IOException 模拟远程失败
         */
        void execute() throws IOException;
    }

    /**
     * 未声明受检异常的代理接口。
     */
    public interface NoDeclaredFailure {

        /**
         * 执行动作。
         */
        void execute();
    }

    /**
     * 提供可继承公共方法的基类。
     */
    public static class BaseTarget {

        /**
         * 返回继承方法标签。
         *
         * @return 基类标签
         */
        public String inheritedLabel() {
            return "base";
        }
    }

    /**
     * 同时作为反射目标和动态代理真实目标的实现类。
     */
    public static final class GreetingTarget extends BaseTarget implements GreetingService {

        /**
         * 按指定次数构造问候结果。
         *
         * @param name 被问候者
         * @param times 次数
         * @return 包含参数的结果文本
         */
        @Override
        public String welcome(String name, long times) {
            return name + " x " + times;
        }

        /**
         * 抛出固定异常，供 InvocationTargetException 场景观察。
         *
         * @param message 异常消息
         * @throws IOException 固定抛出的目标异常
         */
        @Override
        public void fail(String message) throws IOException {
            throw new IOException(message);
        }

        /**
         * 返回私有结果，供 setAccessible 场景观察。
         *
         * @param value 输入文本
         * @return 私有结果
         */
        private String secret(String value) {
            return "secret:" + value;
        }
    }

    /**
     * 为 JDK 8 inflation 提供独立的、不会被其他场景提前调用的目标方法。
     */
    public static final class InflationTarget {

        private int invocations;

        /**
         * 记录调用序号并返回稳定可读的结果。
         *
         * @param value 结果前缀
         * @return 前缀与当前调用序号
         */
        public String ping(String value) {
            invocations++;
            return value + "#" + invocations;
        }

        /**
         * 返回已经进入目标方法的真实次数。
         *
         * @return 调用次数
         */
        int invocationCount() {
            return invocations;
        }
    }

    /**
     * 使用真实目标对象完成代理转发，提供稳定的 InvocationHandler 断点入口。
     */
    static final class ForwardingInvocationHandler implements InvocationHandler {

        private final GreetingTarget target;
        private final AtomicInteger calls;
        private Throwable lastTargetFailure;

        /**
         * 创建转发处理器。
         *
         * @param target 真实目标对象
         * @param calls handler 进入次数计数器
         */
        ForwardingInvocationHandler(GreetingTarget target, AtomicInteger calls) {
            this.target = target;
            this.calls = calls;
        }

        /**
         * 处理 Object 方法并把接口方法反射转发给真实目标；目标异常按原实例解包。
         *
         * @param proxy 当前代理实例
         * @param method 生成代理方法选定的代表 Method
         * @param args 已装箱参数，无参数方法可能为 null
         * @return 与接口返回类型兼容的结果
         * @throws Throwable 真实目标异常或代理处理失败
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            calls.incrementAndGet();

            // Object 的三个代理方法也会进入处理器，不能无条件对 proxy 再次调用同名方法。
            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(method.getName())) {
                    return "GreetingProxy";
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
            }

            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                // 代理处理器应抛出真实业务异常，避免再套一层反射包装。
                lastTargetFailure = exception.getCause();
                throw lastTargetFailure;
            }
        }

        /**
         * 返回最近一次从 InvocationTargetException 中解包出的目标异常。
         *
         * @return 最近目标异常，尚未失败时为 null
         */
        Throwable lastTargetFailure() {
            return lastTargetFailure;
        }
    }
}
