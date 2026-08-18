package io.github.javasourceatlas.jdk.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用公开 API 展示类加载委派、定义加载器身份、初始化边界与 ServiceLoader SPI 协议。
 */
public final class ClassLoaderServiceLoaderDebugLab {

    static final String INITIALIZATION_MARKER =
            "io.github.javasourceatlas.classloader.initialized";
    private static final String SERVICE_RESOURCE =
            "META-INF/services/" + GreetingService.class.getName();

    /**
     * 工具类不需要创建实例。
     */
    private ClassLoaderServiceLoaderDebugLab() {
    }

    /**
     * 按固定顺序运行全部 ClassLoader 与 ServiceLoader 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     * @throws Exception 读取类字节或加载实验类失败
     */
    public static void main(String[] args) throws Exception {
        printHeader("类加载器父链与 bootstrap 边界");
        observeLoaderHierarchy();

        printHeader("双亲委派与 findClass 兜底");
        observeParentDelegation();

        printHeader("发起加载器与定义加载器");
        observeInitiatingAndDefiningLoaders();

        printHeader("定义加载器参与类型身份");
        observeDefiningLoaderIdentity();

        printHeader("加载、链接与初始化边界");
        observeInitializationBoundary();

        printHeader("ServiceLoader 惰性发现、实例缓存与 reload");
        observeServiceDiscoveryAndCache();

        printHeader("线程上下文类加载器决定默认 SPI 可见范围");
        observeContextClassLoaderBoundary();
    }

    /**
     * 打印应用类加载器向上的父链，并明确 bootstrap 在 Java API 中以 null 表示。
     */
    static void observeLoaderHierarchy() {
        ClassLoader applicationLoader = ClassLoaderServiceLoaderDebugLab.class.getClassLoader();
        System.out.println("Object 定义加载器=" + Object.class.getClassLoader());
        System.out.println("实验类定义加载器=" + describeLoader(applicationLoader));

        ClassLoader cursor = applicationLoader;
        int level = 0;
        while (cursor != null) {
            System.out.printf("parent[%d]=%s%n", level, describeLoader(cursor));
            cursor = cursor.getParent();
            level++;
        }
        System.out.printf("parent[%d]=bootstrap(null)%n", level);
    }

    /**
     * 请求父加载器已经可见的类型，验证默认 loadClass 不会进入子加载器 findClass。
     *
     * @throws ClassNotFoundException 已知实验类意外不可见
     */
    static void observeParentDelegation() throws ClassNotFoundException {
        ClassLoader applicationLoader = ClassLoaderServiceLoaderDebugLab.class.getClassLoader();
        RecordingFindClassLoader child = new RecordingFindClassLoader(applicationLoader);

        Class<?> delegated = child.loadClass(ClassLoaderServiceLoaderDebugLab.class.getName());
        System.out.printf(
                "父加载命中=%s，定义加载器=%s，findClass 次数=%d%n",
                delegated == ClassLoaderServiceLoaderDebugLab.class,
                describeLoader(delegated.getClassLoader()),
                child.getFindAttempts());

        try {
            child.loadClass("io.github.javasourceatlas.missing.NotPresent");
        } catch (ClassNotFoundException expected) {
            System.out.printf("父链未命中后进入 findClass，累计次数=%d%n", child.getFindAttempts());
        }
    }

    /**
     * 让子加载器定义的类型解析 bootstrap 父类，展示 initiating loader 不一定是 defining loader。
     *
     * @throws IOException 读取实验类字节失败
     * @throws ClassNotFoundException 隔离加载器未能定义目标类型
     */
    static void observeInitiatingAndDefiningLoaders()
            throws IOException, ClassNotFoundException {
        String className = LoaderInitiatingFixture.class.getName();
        byte[] classBytes = readClassBytes(className);
        ClassLoader parent = ClassLoaderServiceLoaderDebugLab.class.getClassLoader();
        IsolatedTypeClassLoader child =
                new IsolatedTypeClassLoader(parent, className, classBytes);

        Class<?> fixture = child.loadClass(className);
        Class<?> initiatedParent = child.findAlreadyLoaded(ArrayList.class.getName());

        System.out.printf(
                "实验子类 defining loader=%s，ArrayList defining loader=%s，child 是 ArrayList initiating loader=%s%n",
                describeLoader(fixture.getClassLoader()),
                describeLoader(ArrayList.class.getClassLoader()),
                initiatedParent == ArrayList.class);
    }

    /**
     * 让两个隔离加载器各自定义同一份字节码，展示类型身份包含定义加载器。
     *
     * @throws IOException 读取实验类字节失败
     * @throws ClassNotFoundException 隔离加载器未能定义目标类
     */
    static void observeDefiningLoaderIdentity() throws IOException, ClassNotFoundException {
        String className = LoaderIdentityFixture.class.getName();
        byte[] classBytes = readClassBytes(className);
        ClassLoader parent = ClassLoaderServiceLoaderDebugLab.class.getClassLoader();
        IsolatedTypeClassLoader loaderA =
                new IsolatedTypeClassLoader(parent, className, classBytes);
        IsolatedTypeClassLoader loaderB =
                new IsolatedTypeClassLoader(parent, className, classBytes);

        Class<?> typeA = loaderA.loadClass(className);
        Class<?> typeAAgain = loaderA.loadClass(className);
        Class<?> typeB = loaderB.loadClass(className);

        System.out.printf("同一加载器重复请求复用 Class=%s%n", typeA == typeAAgain);
        System.out.printf(
                "二进制名相同=%s，Class 对象相同=%s，可相互赋值=%s%n",
                typeA.getName().equals(typeB.getName()),
                typeA == typeB,
                typeA.isAssignableFrom(typeB));
        System.out.printf(
                "定义加载器 A=%s，定义加载器 B=%s%n",
                describeLoader(typeA.getClassLoader()),
                describeLoader(typeB.getClassLoader()));
    }

    /**
     * 使用 Class.forName 的 initialize 参数区分“已经加载”与“执行类初始化器”。
     *
     * @throws ClassNotFoundException 初始化实验类不可见
     */
    static void observeInitializationBoundary() throws ClassNotFoundException {
        String previousMarker = System.getProperty(INITIALIZATION_MARKER);
        System.clearProperty(INITIALIZATION_MARKER);
        String className = LoaderInitializationFixture.class.getName();
        ClassLoader loader = ClassLoaderServiceLoaderDebugLab.class.getClassLoader();

        try {
            Class<?> loaded = Class.forName(className, false, loader);
            String beforeInitialization = System.getProperty(INITIALIZATION_MARKER);
            Class<?> initialized = Class.forName(className, true, loader);
            String afterInitialization = System.getProperty(INITIALIZATION_MARKER);

            System.out.printf(
                    "Class 复用=%s，initialize=false 标记=%s，initialize=true 标记=%s%n",
                    loaded == initialized, beforeInitialization, afterInitialization);
        } finally {
            // 系统属性只承担实验探针职责，结束后恢复调用方进入实验前的进程状态。
            restoreSystemProperty(INITIALIZATION_MARKER, previousMarker);
        }
    }

    /**
     * 展示 ServiceLoader 创建时不实例化 provider、迭代后缓存实例以及 reload 清缓存。
     */
    static void observeServiceDiscoveryAndCache() {
        int before = providerInstanceCount();
        ServiceLoader<GreetingService> loader = ServiceLoader.load(
                GreetingService.class,
                ClassLoaderServiceLoaderDebugLab.class.getClassLoader());
        int afterLoad = providerInstanceCount();

        Iterator<GreetingService> iterator = loader.iterator();
        int afterIteratorCreation = providerInstanceCount();
        boolean hasFirst = iterator.hasNext();
        int afterHasNext = providerInstanceCount();
        GreetingService first = iterator.next();
        int afterFirst = providerInstanceCount();
        List<String> ids = new ArrayList<>();
        ids.add(first.id());
        while (iterator.hasNext()) {
            ids.add(iterator.next().id());
        }

        GreetingService cachedFirst = loader.iterator().next();
        loader.reload();
        GreetingService reloadedFirst = loader.iterator().next();

        System.out.printf(
                "load/iterator/hasNext 前后实例数=%d/%d/%d/%d，hasNext=%s，首次 next 后=%d，provider 顺序=%s%n",
                before, afterLoad, afterIteratorCreation, afterHasNext, hasFirst, afterFirst, ids);
        System.out.printf(
                "重复迭代复用首实例=%s，reload 后创建新实例=%s%n",
                first == cachedFirst, first != reloadedFirst);
    }

    /**
     * 临时隐藏 TCCL 可见的 SPI 描述文件，证明 load(service) 使用当前线程上下文加载器。
     */
    static void observeContextClassLoaderBoundary() {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader applicationLoader = ClassLoaderServiceLoaderDebugLab.class.getClassLoader();
        ServiceResourceHidingClassLoader hiddenContext =
                new ServiceResourceHidingClassLoader(applicationLoader);

        List<String> hiddenIds;
        try {
            thread.setContextClassLoader(hiddenContext);
            hiddenIds = serviceIds(ServiceLoader.load(GreetingService.class));
        } finally {
            // TCCL 属于线程环境，实验无论成功还是失败都必须恢复，避免污染后续任务。
            thread.setContextClassLoader(original);
        }

        List<String> explicitIds = serviceIds(ServiceLoader.load(
                GreetingService.class, applicationLoader));
        System.out.printf("隐藏描述文件的默认发现=%s，显式应用加载器发现=%s%n",
                hiddenIds, explicitIds);
    }

    /**
     * 读取指定二进制名对应的 class 文件，供隔离加载器重复定义。
     *
     * @param className 目标类二进制名
     * @return 完整 class 文件字节
     * @throws IOException 资源不存在或读取失败
     */
    static byte[] readClassBytes(String className) throws IOException {
        String resourceName = "/" + className.replace('.', '/') + ".class";
        InputStream input = ClassLoaderServiceLoaderDebugLab.class.getResourceAsStream(resourceName);
        if (input == null) {
            throw new IOException("找不到实验类资源：" + resourceName);
        }

        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = source.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    /**
     * 收集一个 ServiceLoader 暴露的 provider 标识，避免断言实现类名。
     *
     * @param loader 待遍历的 ServiceLoader
     * @return 按配置发现顺序排列的 provider 标识
     */
    static List<String> serviceIds(ServiceLoader<GreetingService> loader) {
        List<String> ids = new ArrayList<>();
        for (GreetingService service : loader) {
            ids.add(service.id());
        }
        return ids;
    }

    /**
     * 返回两个教学 provider 到目前为止的构造总次数。
     *
     * @return provider 构造次数之和
     */
    static int providerInstanceCount() {
        return AlphaGreetingService.instanceCount() + BetaGreetingService.instanceCount();
    }

    /**
     * 生成不依赖 JDK 内部类名的加载器描述。
     *
     * @param loader 待描述的类加载器，null 表示 bootstrap
     * @return 类加载器类别与身份摘要
     */
    private static String describeLoader(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap(null)";
        }
        return loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    /**
     * 把实验临时修改的系统属性恢复为原值。
     *
     * @param name 属性名称
     * @param previousValue 实验开始前的属性值，null 表示原本不存在
     */
    private static void restoreSystemProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
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
     * 教学 SPI：provider 返回稳定标识和问候文本。
     */
    public interface GreetingService {

        /**
         * 返回 provider 的稳定标识。
         *
         * @return provider 标识
         */
        String id();

        /**
         * 生成一条问候文本。
         *
         * @param subject 问候对象
         * @return provider 生成的文本
         */
        String greet(String subject);
    }

    /**
     * 第一个教学 provider，用构造计数展示 ServiceLoader 的惰性与缓存。
     */
    public static final class AlphaGreetingService implements GreetingService {

        private static final AtomicInteger INSTANCES = new AtomicInteger();

        /**
         * 创建 Alpha provider，并记录真实实例化时点。
         */
        public AlphaGreetingService() {
            INSTANCES.incrementAndGet();
        }

        /**
         * 返回 Alpha 标识。
         *
         * @return 固定标识 alpha
         */
        @Override
        public String id() {
            return "alpha";
        }

        /**
         * 使用 Alpha provider 生成问候文本。
         *
         * @param subject 问候对象
         * @return Alpha 问候文本
         */
        @Override
        public String greet(String subject) {
            return "alpha:" + subject;
        }

        /**
         * 返回当前 provider 已构造的实例数。
         *
         * @return 构造次数
         */
        static int instanceCount() {
            return INSTANCES.get();
        }
    }

    /**
     * 第二个教学 provider，用于验证配置顺序和重复名称去重。
     */
    public static final class BetaGreetingService implements GreetingService {

        private static final AtomicInteger INSTANCES = new AtomicInteger();

        /**
         * 创建 Beta provider，并记录真实实例化时点。
         */
        public BetaGreetingService() {
            INSTANCES.incrementAndGet();
        }

        /**
         * 返回 Beta 标识。
         *
         * @return 固定标识 beta
         */
        @Override
        public String id() {
            return "beta";
        }

        /**
         * 使用 Beta provider 生成问候文本。
         *
         * @param subject 问候对象
         * @return Beta 问候文本
         */
        @Override
        public String greet(String subject) {
            return "beta:" + subject;
        }

        /**
         * 返回当前 provider 已构造的实例数。
         *
         * @return 构造次数
         */
        static int instanceCount() {
            return INSTANCES.get();
        }
    }

    /**
     * 记录默认 loadClass 在父链失败后是否调用了 findClass。
     */
    static final class RecordingFindClassLoader extends ClassLoader {

        private final AtomicInteger findAttempts = new AtomicInteger();

        /**
         * 创建带指定父加载器的观察加载器。
         *
         * @param parent 父加载器
         */
        RecordingFindClassLoader(ClassLoader parent) {
            super(parent);
        }

        /**
         * 记录查找请求并按教学约定报告未找到。
         *
         * @param name 目标类二进制名
         * @return 本实现不会返回类
         * @throws ClassNotFoundException 始终抛出，表示本地没有定义来源
         */
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            findAttempts.incrementAndGet();
            throw new ClassNotFoundException(name);
        }

        /**
         * 返回 findClass 被调用的累计次数。
         *
         * @return 查找次数
         */
        int getFindAttempts() {
            return findAttempts.get();
        }

    }

    /**
     * 仅对一个目标二进制名采用 child-first 定义，用于构造稳定的类身份实验。
     */
    static final class IsolatedTypeClassLoader extends ClassLoader {

        private final String isolatedName;
        private final byte[] classBytes;

        /**
         * 创建单类型隔离加载器。
         *
         * @param parent 父加载器，其他类型仍委派给它
         * @param isolatedName 需要在本加载器中定义的二进制名
         * @param classBytes 目标 class 文件字节
         */
        IsolatedTypeClassLoader(ClassLoader parent, String isolatedName, byte[] classBytes) {
            super(parent);
            this.isolatedName = isolatedName;
            this.classBytes = classBytes.clone();
        }

        /**
         * 对教学目标先检查本地定义，其他名称保持 ClassLoader 的双亲委派算法。
         *
         * @param name 目标类二进制名
         * @param resolve 是否立即执行解析
         * @return 请求得到的 Class 对象
         * @throws ClassNotFoundException 父链和本加载器都无法加载目标类型
         */
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (!isolatedName.equals(name)) {
                    return super.loadClass(name, resolve);
                }

                // 同一加载器不能重复 define 同名类，必须先复用已加载 Class。
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = defineClass(name, classBytes, 0, classBytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        /**
         * 暴露受保护的已加载查询，验证 initiating loader 与 defining loader 可以不同。
         *
         * @param name 目标类二进制名
         * @return JVM 已为本加载器记录的 Class，尚未记录时返回 null
         */
        Class<?> findAlreadyLoaded(String name) {
            return findLoadedClass(name);
        }
    }

    /**
     * 只隐藏指定 SPI 配置资源，类请求本身仍委派给父加载器。
     */
    static final class ServiceResourceHidingClassLoader extends ClassLoader {

        /**
         * 创建资源过滤加载器。
         *
         * @param parent 提供普通类和其他资源的父加载器
         */
        ServiceResourceHidingClassLoader(ClassLoader parent) {
            super(parent);
        }

        /**
         * 对教学 SPI 描述文件返回空枚举，其他资源沿用父链查找。
         *
         * @param name 资源名
         * @return 可见资源枚举
         * @throws IOException 父加载器枚举资源失败
         */
        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (SERVICE_RESOURCE.equals(name)) {
                return Collections.enumeration(Collections.<URL>emptyList());
            }
            return super.getResources(name);
        }

        /**
         * 对教学 SPI 描述文件返回 null，保持单资源查找与枚举行为一致。
         *
         * @param name 资源名
         * @return 首个可见资源，教学 SPI 描述文件固定为 null
         */
        @Override
        public URL getResource(String name) {
            if (SERVICE_RESOURCE.equals(name)) {
                return null;
            }
            return super.getResource(name);
        }
    }
}

/**
 * 没有业务依赖的类型身份探针，允许多个教学加载器独立定义同一份字节码。
 */
final class LoaderIdentityFixture {
}

/**
 * 由隔离加载器定义，并在链接时通过该加载器解析 bootstrap 定义的 ArrayList。
 */
final class LoaderInitiatingFixture extends ArrayList<Object> {

    private static final long serialVersionUID = 1L;
}

/**
 * 通过系统属性留下类初始化痕迹，避免为观察结果提前读取自身静态字段。
 */
final class LoaderInitializationFixture {

    static {
        System.setProperty(ClassLoaderServiceLoaderDebugLab.INITIALIZATION_MARKER, "initialized");
    }

    /**
     * 初始化探针不需要创建实例。
     */
    private LoaderInitializationFixture() {
    }
}
