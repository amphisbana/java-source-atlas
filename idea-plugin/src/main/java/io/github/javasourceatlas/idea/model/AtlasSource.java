package io.github.javasourceatlas.idea.model;

/**
 * 描述一个可由 IDEA 定位的上游源码类。
 *
 * @param className  完整类名
 * @param sourcePath 固定版本仓库中的源码路径
 */
public record AtlasSource(String className, String sourcePath) {
}
