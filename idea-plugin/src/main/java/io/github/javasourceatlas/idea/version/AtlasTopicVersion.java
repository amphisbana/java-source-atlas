package io.github.javasourceatlas.idea.version;

import io.github.javasourceatlas.idea.model.AtlasTopic;

/**
 * 保存专题基线与按项目 JDK 解析后的可执行版本视图。
 *
 * @param baselineTopic 基线专题
 * @param topic         当前项目应使用的专题视图
 * @param projectMajor  项目 JDK 主版本；无法识别时为空
 * @param status        版本解析状态
 * @param message       可直接展示给用户的版本说明
 */
public record AtlasTopicVersion(
        AtlasTopic baselineTopic,
        AtlasTopic topic,
        Integer projectMajor,
        Status status,
        String message
) {

    /**
     * 判断当前版本是否可以安全执行源码定位和断点操作。
     *
     * @return 是否允许执行源码动作
     */
    public boolean sourceActionsAllowed() {
        return status == Status.BASELINE || status == Status.ADAPTED;
    }

    /**
     * 判断当前专题是否已经切换到非基线 JDK 视图。
     *
     * @return 是否使用适配视图
     */
    public boolean adapted() {
        return status == Status.ADAPTED;
    }

    /**
     * 描述版本视图是否精确、已适配、无法识别或不受支持。
     */
    public enum Status {
        BASELINE,
        ADAPTED,
        UNKNOWN,
        UNSUPPORTED
    }
}
