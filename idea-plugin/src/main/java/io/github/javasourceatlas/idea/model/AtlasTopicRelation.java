package io.github.javasourceatlas.idea.model;

/**
 * 专题之间从学习推荐图推导出的关系。
 *
 * @param topic  关联专题
 * @param label  关系方向标签
 * @param reason 建立关系的学习理由
 */
public record AtlasTopicRelation(AtlasTopic topic, String label, String reason) {
}
