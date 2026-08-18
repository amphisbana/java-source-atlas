package io.github.javasourceatlas.jdk.runtime;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 ClassLoader 与 ServiceLoader 教学案例依赖的公开可观察行为。
 */
class ClassLoaderServiceLoaderBehaviorTest {

    /**
     * 验证 bootstrap 在 Java API 中以 null 表示，应用类保留自己的定义加载器。
     */
    @Test
    void shouldExposeBootstrapAndApplicationDefiningLoaders() {
        assertNull(Object.class.getClassLoader());
        assertNotNull(ClassLoaderServiceLoaderBehaviorTest.class.getClassLoader());
    }

    /**
     * 验证默认 loadClass 先委派父加载器，只有父链失败才进入 findClass。
     *
     * @throws ClassNotFoundException 已知应用类意外不可见
     */
    @Test
    void shouldDelegateToParentBeforeCallingFindClass() throws ClassNotFoundException {
        ClassLoader parent = ClassLoaderServiceLoaderBehaviorTest.class.getClassLoader();
        ClassLoaderServiceLoaderDebugLab.RecordingFindClassLoader child =
                new ClassLoaderServiceLoaderDebugLab.RecordingFindClassLoader(parent);

        Class<?> loaded = child.loadClass(ClassLoaderServiceLoaderDebugLab.class.getName());

        assertSame(ClassLoaderServiceLoaderDebugLab.class, loaded);
        assertSame(parent, loaded.getClassLoader());
        assertNotSame(child, loaded.getClassLoader());
        assertEquals(0, child.getFindAttempts());

        assertThrows(ClassNotFoundException.class,
                () -> child.loadClass("io.github.javasourceatlas.missing.NotPresent"));
        assertEquals(1, child.getFindAttempts());
    }

    /**
     * 验证 JVM 可把 child 记录为父层类型的 initiating loader，而类型的 defining loader 保持不变。
     *
     * @throws Exception 读取或定义实验类失败
     */
    @Test
    void shouldSeparateInitiatingLoaderFromDefiningLoader() throws Exception {
        String className = LoaderInitiatingFixture.class.getName();
        byte[] bytes = ClassLoaderServiceLoaderDebugLab.readClassBytes(className);
        ClassLoader parent = ClassLoaderServiceLoaderBehaviorTest.class.getClassLoader();
        ClassLoaderServiceLoaderDebugLab.IsolatedTypeClassLoader child =
                new ClassLoaderServiceLoaderDebugLab.IsolatedTypeClassLoader(
                        parent, className, bytes);

        Class<?> fixture = child.loadClass(className);

        assertSame(child, fixture.getClassLoader());
        assertNull(java.util.ArrayList.class.getClassLoader());
        assertSame(java.util.ArrayList.class,
                child.findAlreadyLoaded(java.util.ArrayList.class.getName()));
    }

    /**
     * 验证一个定义加载器会缓存同名 Class，而两个定义加载器产生不同类型身份。
     *
     * @throws Exception 读取或定义实验类失败
     */
    @Test
    void shouldScopeTypeIdentityToDefiningLoader() throws Exception {
        String className = LoaderIdentityFixture.class.getName();
        byte[] bytes = ClassLoaderServiceLoaderDebugLab.readClassBytes(className);
        ClassLoader parent = ClassLoaderServiceLoaderBehaviorTest.class.getClassLoader();
        ClassLoaderServiceLoaderDebugLab.IsolatedTypeClassLoader loaderA =
                new ClassLoaderServiceLoaderDebugLab.IsolatedTypeClassLoader(
                        parent, className, bytes);
        ClassLoaderServiceLoaderDebugLab.IsolatedTypeClassLoader loaderB =
                new ClassLoaderServiceLoaderDebugLab.IsolatedTypeClassLoader(
                        parent, className, bytes);

        Class<?> typeA = loaderA.loadClass(className);
        Class<?> typeAAgain = loaderA.loadClass(className);
        Class<?> typeB = loaderB.loadClass(className);

        assertSame(typeA, typeAAgain);
        assertEquals(typeA.getName(), typeB.getName());
        assertNotSame(typeA, typeB);
        assertSame(loaderA, typeA.getClassLoader());
        assertSame(loaderB, typeB.getClassLoader());
        assertFalse(typeA.isAssignableFrom(typeB));
        assertFalse(typeB.isAssignableFrom(typeA));
    }

    /**
     * 验证 Class.forName 的 initialize=false 不执行类初始化器，true 才触发初始化。
     *
     * @throws ClassNotFoundException 初始化实验类不可见
     */
    @Test
    void shouldSeparateLoadingFromInitialization() throws ClassNotFoundException {
        String previousMarker = System.getProperty(
                ClassLoaderServiceLoaderDebugLab.INITIALIZATION_MARKER);
        System.clearProperty(ClassLoaderServiceLoaderDebugLab.INITIALIZATION_MARKER);
        String className = "io.github.javasourceatlas.jdk.runtime.LoaderInitializationFixture";
        ClassLoader loader = ClassLoaderServiceLoaderBehaviorTest.class.getClassLoader();

        try {
            Class<?> loaded = Class.forName(className, false, loader);
            assertNull(System.getProperty(ClassLoaderServiceLoaderDebugLab.INITIALIZATION_MARKER));

            Class<?> initialized = Class.forName(className, true, loader);
            assertSame(loaded, initialized);
            assertEquals("initialized",
                    System.getProperty(ClassLoaderServiceLoaderDebugLab.INITIALIZATION_MARKER));
        } finally {
            // 系统属性是进程级状态，测试结束后必须恢复，避免影响同一 Surefire JVM 的其他案例。
            restoreSystemProperty(
                    ClassLoaderServiceLoaderDebugLab.INITIALIZATION_MARKER,
                    previousMarker);
        }
    }

    /**
     * 验证 SPI 配置中的注释和重复名称不会制造重复 provider。
     */
    @Test
    void shouldDiscoverConfiguredProvidersOnceAndInConfigurationOrder() {
        ServiceLoader<ClassLoaderServiceLoaderDebugLab.GreetingService> loader =
                explicitServiceLoader();

        List<String> ids = ClassLoaderServiceLoaderDebugLab.serviceIds(loader);

        assertEquals(Arrays.asList("alpha", "beta"), ids);
    }

    /**
     * 验证创建 ServiceLoader 本身不实例化 provider，迭代会缓存实例，reload 会清缓存。
     */
    @Test
    void shouldInstantiateLazilyCacheAndReloadProviders() {
        int before = ClassLoaderServiceLoaderDebugLab.providerInstanceCount();
        ServiceLoader<ClassLoaderServiceLoaderDebugLab.GreetingService> loader =
                explicitServiceLoader();
        assertEquals(before, ClassLoaderServiceLoaderDebugLab.providerInstanceCount());

        Iterator<ClassLoaderServiceLoaderDebugLab.GreetingService> iterator = loader.iterator();
        assertTrue(iterator.hasNext());
        assertEquals(before, ClassLoaderServiceLoaderDebugLab.providerInstanceCount());
        ClassLoaderServiceLoaderDebugLab.GreetingService first = iterator.next();
        assertEquals(before + 1, ClassLoaderServiceLoaderDebugLab.providerInstanceCount());

        assertTrue(iterator.hasNext());
        ClassLoaderServiceLoaderDebugLab.GreetingService second = iterator.next();
        assertEquals("alpha", first.id());
        assertEquals("beta", second.id());
        assertEquals(before + 2, ClassLoaderServiceLoaderDebugLab.providerInstanceCount());
        assertFalse(iterator.hasNext());

        assertSame(first, loader.iterator().next());
        assertEquals(before + 2, ClassLoaderServiceLoaderDebugLab.providerInstanceCount());

        loader.reload();
        ClassLoaderServiceLoaderDebugLab.GreetingService reloadedFirst =
                loader.iterator().next();
        assertNotSame(first, reloadedFirst);
        assertEquals("alpha", reloadedFirst.id());
        assertEquals(before + 3, ClassLoaderServiceLoaderDebugLab.providerInstanceCount());
    }

    /**
     * 验证无显式 loader 的 load(service) 使用当前线程上下文类加载器查找配置资源。
     */
    @Test
    void shouldUseThreadContextClassLoaderForDefaultDiscovery() {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader applicationLoader = ClassLoaderServiceLoaderBehaviorTest.class.getClassLoader();
        ClassLoader hidden = new ClassLoaderServiceLoaderDebugLab.ServiceResourceHidingClassLoader(
                applicationLoader);

        try {
            thread.setContextClassLoader(hidden);
            ServiceLoader<ClassLoaderServiceLoaderDebugLab.GreetingService> loader =
                    ServiceLoader.load(ClassLoaderServiceLoaderDebugLab.GreetingService.class);
            assertFalse(loader.iterator().hasNext());
        } finally {
            // 测试线程会被 Surefire 复用，必须恢复 TCCL，不能把资源视图泄漏给其他案例。
            thread.setContextClassLoader(original);
        }

        assertEquals(Arrays.asList("alpha", "beta"),
                ClassLoaderServiceLoaderDebugLab.serviceIds(explicitServiceLoader()));
    }

    /**
     * 使用实验类的定义加载器创建 ServiceLoader，避免测试依赖运行器设置的 TCCL。
     *
     * @return 可以看见教学 SPI 描述文件的 ServiceLoader
     */
    private static ServiceLoader<ClassLoaderServiceLoaderDebugLab.GreetingService>
            explicitServiceLoader() {
        return ServiceLoader.load(
                ClassLoaderServiceLoaderDebugLab.GreetingService.class,
                ClassLoaderServiceLoaderDebugLab.class.getClassLoader());
    }

    /**
     * 恢复测试临时修改的系统属性。
     *
     * @param name 属性名称
     * @param previousValue 测试开始前的值，null 表示原本不存在
     */
    private static void restoreSystemProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }
}
