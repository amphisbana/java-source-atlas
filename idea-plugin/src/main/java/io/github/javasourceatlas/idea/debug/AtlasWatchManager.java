package io.github.javasourceatlas.idea.debug;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.evaluation.EvaluationMode;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 把推荐断点的观察变量加入当前 IDEA Debug 会话。
 */
public final class AtlasWatchManager {

    /**
     * 工具类不需要创建实例。
     */
    private AtlasWatchManager() {
    }

    /**
     * 判断当前项目是否已经存在可以接收 Watches 的 Debug 会话。
     *
     * @param project 当前项目
     * @return 是否存在活动调试会话
     */
    public static boolean hasCurrentSession(Project project) {
        return XDebuggerManager.getInstance(project).getCurrentSession() != null;
    }

    /**
     * 将非空且尚未存在的表达式追加到当前 Debug 会话，并刷新调试视图。
     *
     * @param project     当前项目
     * @param expressions 推荐观察表达式
     * @return Watches 添加结果
     */
    public static WatchResult addToCurrentSession(Project project, List<String> expressions) {
        XDebugSession session = XDebuggerManager.getInstance(project).getCurrentSession();
        if (session == null) {
            return new WatchResult(0, 0, true, false);
        }

        try {
            WatchAdapter adapter = WatchAdapter.create(project, session);
            List<Object> watches = new ArrayList<>(adapter.currentWatches());
            List<String> existingTexts = new ArrayList<>(adapter.expressionTexts(watches));
            int added = 0;
            int existing = 0;
            for (String expression : normalizedExpressions(expressions)) {
                if (existingTexts.contains(expression)) {
                    existing++;
                    continue;
                }
                watches.add(adapter.createWatch(expression));
                existingTexts.add(expression);
                added++;
            }
            adapter.save(watches);
            session.rebuildViews();
            return new WatchResult(added, existing, false, false);
        } catch (ReflectiveOperationException | LinkageError exception) {
            return new WatchResult(0, 0, false, true);
        }
    }

    /**
     * 清理空表达式并保持索引中的首次出现顺序。
     *
     * @param expressions 原始表达式
     * @return 可加入 Watches 的表达式
     */
    static List<String> normalizedExpressions(List<String> expressions) {
        if (expressions == null || expressions.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String expression : expressions) {
            if (expression == null || expression.isBlank()) {
                continue;
            }
            String value = expression.trim();
            if (!normalized.contains(value)) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    /**
     * 保存向当前 Debug 会话添加 Watches 的统计。
     *
     * @param added     新增表达式数
     * @param existing  已存在表达式数
     * @param noSession  当前是否没有 Debug 会话
     * @param unsupported 当前 IDEA 是否没有可适配的 Watches 写入接口
     */
    public record WatchResult(int added, int existing, boolean noSession, boolean unsupported) {
    }

    /**
     * 隔离 IDEA 2024.2 与 2026.2 两套内部 Watches 存储结构，避免静态绑定已移除方法。
     */
    private static final class WatchAdapter {

        private final Object manager;
        private final String configurationName;
        private final Method getter;
        private final Method setter;
        private final Constructor<?> watchConstructor;

        /**
         * 保存运行版本实际提供的 Watches 访问器。
         *
         * @param manager           IDEA Watches 管理器
         * @param configurationName 当前调试配置名称
         * @param getter            Watches 读取方法
         * @param setter            Watches 保存方法
         * @param watchConstructor  新版 Watch 包装构造器；旧版为空
         */
        private WatchAdapter(
                Object manager,
                String configurationName,
                Method getter,
                Method setter,
                Constructor<?> watchConstructor
        ) {
            this.manager = manager;
            this.configurationName = configurationName;
            this.getter = getter;
            this.setter = setter;
            this.watchConstructor = watchConstructor;
        }

        /**
         * 根据运行中 IDEA 的方法集合选择旧版表达式列表或新版 Watch 条目适配器。
         *
         * @param project 当前项目
         * @param session 当前 Debug 会话
         * @return 可读写当前配置 Watches 的适配器
         * @throws ReflectiveOperationException 运行版本结构不受支持时抛出
         */
        private static WatchAdapter create(Project project, XDebugSession session)
                throws ReflectiveOperationException {
            Object debuggerManager = XDebuggerManager.getInstance(project);
            Object watchesManager = findMethod(debuggerManager.getClass(), "getWatchesManager", 0)
                    .invoke(debuggerManager);
            String configurationName = configurationName(session);
            Method legacyGetter = optionalMethod(watchesManager.getClass(), "getWatches", 1);
            if (legacyGetter != null) {
                return new WatchAdapter(
                        watchesManager,
                        configurationName,
                        legacyGetter,
                        findMethod(watchesManager.getClass(), "setWatches", 2),
                        null
                );
            }

            ClassLoader classLoader = watchesManager.getClass().getClassLoader();
            Class<?> watchClass = Class.forName(
                    "com.intellij.xdebugger.impl.XWatchImpl",
                    true,
                    classLoader
            );
            return new WatchAdapter(
                    watchesManager,
                    configurationName,
                    findMethod(watchesManager.getClass(), "getWatchEntries", 1),
                    findMethod(watchesManager.getClass(), "setWatchEntries", 2),
                    watchClass.getConstructor(XExpression.class)
            );
        }

        /**
         * 读取当前运行配置已经保存的 Watch 条目。
         *
         * @return 可变前的原始条目快照
         * @throws ReflectiveOperationException 读取失败时抛出
         */
        @SuppressWarnings("unchecked")
        private List<Object> currentWatches() throws ReflectiveOperationException {
            Object value = getter.invoke(manager, configurationName);
            return value instanceof List<?> list ? (List<Object>) list : List.of();
        }

        /**
         * 从旧版表达式或新版 Watch 包装对象中提取表达式文本。
         *
         * @param watches 当前 Watch 条目
         * @return 表达式文本
         * @throws ReflectiveOperationException 新版条目无法读取表达式时抛出
         */
        private List<String> expressionTexts(List<Object> watches) throws ReflectiveOperationException {
            List<String> result = new ArrayList<>();
            for (Object watch : watches) {
                XExpression expression = watch instanceof XExpression direct
                        ? direct
                        : (XExpression) findMethod(watch.getClass(), "getExpression", 0).invoke(watch);
                result.add(expression.getExpression());
            }
            return result;
        }

        /**
         * 按当前版本创建一个可以写回管理器的 Watch 条目。
         *
         * @param expressionText 表达式文本
         * @return 旧版 XExpression 或新版 XWatchImpl
         * @throws ReflectiveOperationException 新版包装对象创建失败时抛出
         */
        private Object createWatch(String expressionText) throws ReflectiveOperationException {
            XExpression expression = XDebuggerUtil.getInstance().createExpression(
                    expressionText,
                    JavaLanguage.INSTANCE,
                    null,
                    EvaluationMode.EXPRESSION
            );
            return watchConstructor == null ? expression : watchConstructor.newInstance(expression);
        }

        /**
         * 将去重后的 Watch 条目保存回当前运行配置。
         *
         * @param watches 完整 Watch 条目
         * @throws ReflectiveOperationException 保存失败时抛出
         */
        private void save(List<Object> watches) throws ReflectiveOperationException {
            setter.invoke(manager, configurationName, List.copyOf(watches));
        }

        /**
         * 读取 Debug 会话内部保存的配置名称，失败时使用公开会话名称回退。
         *
         * @param session 当前 Debug 会话
         * @return Watches 管理器使用的配置名称
         */
        private static String configurationName(XDebugSession session) {
            try {
                Object sessionData = findMethod(session.getClass(), "getSessionData", 0).invoke(session);
                Object value = findMethod(sessionData.getClass(), "getConfigurationName", 0)
                        .invoke(sessionData);
                return value instanceof String text && !text.isBlank() ? text : session.getSessionName();
            } catch (ReflectiveOperationException exception) {
                return session.getSessionName();
            }
        }

        /**
         * 按名称和参数数量查找公开方法，找不到时抛出明确异常。
         *
         * @param type           目标类型
         * @param name           方法名
         * @param parameterCount 参数数量
         * @return 匹配方法
         * @throws NoSuchMethodException 不存在匹配方法时抛出
         */
        private static Method findMethod(Class<?> type, String name, int parameterCount)
                throws NoSuchMethodException {
            Method method = optionalMethod(type, name, parameterCount);
            if (method == null) {
                throw new NoSuchMethodException(type.getName() + "#" + name);
            }
            return method;
        }

        /**
         * 按名称和参数数量查找公开方法。
         *
         * @param type           目标类型
         * @param name           方法名
         * @param parameterCount 参数数量
         * @return 匹配方法；不存在时返回 null
         */
        private static Method optionalMethod(Class<?> type, String name, int parameterCount) {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name)
                        && method.getParameterCount() == parameterCount) {
                    return method;
                }
            }
            return null;
        }
    }
}
