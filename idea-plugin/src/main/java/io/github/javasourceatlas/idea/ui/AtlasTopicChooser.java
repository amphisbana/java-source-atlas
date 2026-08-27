package io.github.javasourceatlas.idea.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import io.github.javasourceatlas.idea.icons.AtlasIcons;
import io.github.javasourceatlas.idea.model.AtlasTopic;

import java.util.List;

/**
 * 统一展示共享源码类的专题选择器，保证工具窗口、gutter 和编辑器动作行为一致。
 */
public final class AtlasTopicChooser {

    /**
     * 工具类不需要创建实例。
     */
    private AtlasTopicChooser() {
    }

    /**
     * 让用户从排序后的专题候选中明确选择一个专题。
     *
     * @param project    当前 IDEA 项目
     * @param candidates 已按相关度排序的专题候选
     * @return 用户选中的专题；取消或没有候选时返回 null
     */
    public static AtlasTopic choose(Project project, List<AtlasTopic> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        String[] options = candidates.stream()
                .map(AtlasTopicChooser::optionText)
                .toArray(String[]::new);
        int selected = Messages.showChooseDialog(
                project,
                "当前源码类对应多个 Source Atlas 专题，请选择要使用的专题：",
                "选择 Source Atlas 专题",
                AtlasIcons.ATLAS,
                options,
                options[0]
        );
        return selected >= 0 && selected < candidates.size() ? candidates.get(selected) : null;
    }

    /**
     * 生成同时包含标题和版本基线的候选说明。
     *
     * @param topic 专题候选
     * @return 选择器展示文本
     */
    private static String optionText(AtlasTopic topic) {
        String version = topic.primaryVersion();
        return version == null || version.isBlank()
                ? topic.title()
                : topic.title() + "（" + version + "）";
    }
}
