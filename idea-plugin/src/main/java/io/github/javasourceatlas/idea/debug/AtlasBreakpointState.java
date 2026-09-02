package io.github.javasourceatlas.idea.debug;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 在项目工作区中记录由 Source Atlas 真正创建的断点位置。
 */
@Service(Service.Level.PROJECT)
@State(
        name = "JavaSourceAtlasBreakpoints",
        storages = @Storage(StoragePathMacros.WORKSPACE_FILE)
)
public final class AtlasBreakpointState implements PersistentStateComponent<AtlasBreakpointState> {

    public List<ManagedBreakpoint> managedBreakpoints = new ArrayList<>();

    /**
     * 取得当前项目的 Atlas 断点状态服务。
     *
     * @param project 当前项目
     * @return 项目级断点状态
     */
    public static AtlasBreakpointState getInstance(Project project) {
        return project.getService(AtlasBreakpointState.class);
    }

    /**
     * 返回需要持久化的当前状态。
     *
     * @return 当前状态
     */
    @Override
    public AtlasBreakpointState getState() {
        return this;
    }

    /**
     * 恢复工作区中的断点归属记录，并过滤旧配置中的空位置。
     *
     * @param state 已保存状态
     */
    @Override
    public void loadState(@NotNull AtlasBreakpointState state) {
        managedBreakpoints = new ArrayList<>();
        if (state.managedBreakpoints == null) {
            return;
        }
        for (ManagedBreakpoint breakpoint : state.managedBreakpoints) {
            if (breakpoint != null && breakpoint.fileUrl != null && !breakpoint.fileUrl.isBlank()) {
                // 2026-09-02：原恢复逻辑没有所有权字段，无法区分插件断点与复用的用户断点。
                // register(breakpoint.topicId, breakpoint.fileUrl, breakpoint.line, breakpoint.signature);
                register(
                        breakpoint.topicId,
                        breakpoint.fileUrl,
                        breakpoint.line,
                        breakpoint.signature,
                        breakpoint.owned
                );
            }
        }
    }

    /**
     * 登记插件新建的断点，同一文件同一行只保留一条归属记录。
     *
     * @param topicId  专题编号
     * @param fileUrl  源文件 URL
     * @param line     零基行号
     * @param signature 方法签名
     */
    public void register(String topicId, String fileUrl, int line, String signature) {
        register(topicId, fileUrl, line, signature, true);
    }

    /**
     * 登记插件复用的现有用户断点，使其可参与调试引导但不会被 Atlas 启停或删除。
     *
     * @param topicId  专题编号
     * @param fileUrl  源文件 URL
     * @param line     零基行号
     * @param signature 方法签名
     */
    public void registerReference(String topicId, String fileUrl, int line, String signature) {
        register(topicId, fileUrl, line, signature, false);
    }

    /**
     * 按所有权登记断点位置；重复登记时保留已经存在的插件所有权。
     *
     * @param topicId  专题编号
     * @param fileUrl  源文件 URL
     * @param line     零基行号
     * @param signature 方法签名
     * @param owned    是否由 Atlas 实际创建
     */
    private void register(String topicId, String fileUrl, int line, String signature, boolean owned) {
        if (fileUrl == null || fileUrl.isBlank() || line < 0) {
            return;
        }
        boolean effectiveOwnership = owned || managedBreakpoints.stream()
                .anyMatch(item -> item.line == line && fileUrl.equals(item.fileUrl) && item.owned);
        removeLocation(fileUrl, line);
        ManagedBreakpoint breakpoint = new ManagedBreakpoint();
        breakpoint.topicId = normalized(topicId);
        breakpoint.fileUrl = fileUrl;
        breakpoint.line = line;
        breakpoint.signature = normalized(signature);
        breakpoint.owned = effectiveOwnership;
        managedBreakpoints.add(breakpoint);
    }

    /**
     * 返回全部 Atlas 断点位置的独立快照。
     *
     * @return 断点位置快照
     */
    public List<ManagedBreakpoint> locations() {
        return managedBreakpoints.stream().map(ManagedBreakpoint::copy).toList();
    }

    /**
     * 删除一个已不存在或不再由插件管理的位置记录。
     *
     * @param fileUrl 源文件 URL
     * @param line    零基行号
     */
    public void removeLocation(String fileUrl, int line) {
        managedBreakpoints.removeIf(item -> item.line == line && fileUrl.equals(item.fileUrl));
    }

    /**
     * 删除指定专题的全部归属记录。
     *
     * @param topicId 专题编号；为空时删除全部记录
     */
    public void removeTopic(String topicId) {
        if (topicId == null || topicId.isBlank()) {
            managedBreakpoints.clear();
            return;
        }
        managedBreakpoints.removeIf(item -> topicId.equals(item.topicId));
    }

    /**
     * 把可空持久化文本转换为空字符串。
     *
     * @param value 原始文本
     * @return 非空文本
     */
    private static String normalized(String value) {
        return value == null ? "" : value;
    }

    /**
     * IDEA XML 序列化器使用的断点位置 Bean。
     */
    public static final class ManagedBreakpoint {

        public String topicId = "";
        public String fileUrl = "";
        public int line;
        public String signature = "";
        public boolean owned = true;

        /**
         * 创建与持久化对象隔离的断点位置副本。
         *
         * @return 独立断点位置
         */
        private ManagedBreakpoint copy() {
            ManagedBreakpoint copied = new ManagedBreakpoint();
            copied.topicId = normalized(topicId);
            copied.fileUrl = normalized(fileUrl);
            copied.line = line;
            copied.signature = normalized(signature);
            copied.owned = owned;
            return copied;
        }
    }
}
