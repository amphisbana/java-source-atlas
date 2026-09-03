package io.github.javasourceatlas.idea.model;

/**
 * 描述当前源码入口与另一个方法之间的阅读关系。
 *
 * @param method      关联方法签名
 * @param relation    关系名称，例如调用者、后续入口或状态协作者
 * @param reason      推荐关联阅读的原因
 * @param sourceClass 关联方法所属源码类；为空时交给专题解析
 */
public record AtlasMethodRelation(
        String method,
        String relation,
        String reason,
        String sourceClass
) {

    /**
     * 规范化可能缺省的说明字段，避免界面层重复判空。
     */
    public AtlasMethodRelation {
        method = method == null ? "" : method;
        relation = relation == null || relation.isBlank() ? "关联方法" : relation;
        reason = reason == null ? "" : reason;
    }

    /**
     * 返回适合列表降级展示的关联摘要。
     *
     * @return 关系、方法和阅读原因
     */
    @Override
    public String toString() {
        return relation + " · " + method + (reason.isBlank() ? "" : " - " + reason);
    }
}
