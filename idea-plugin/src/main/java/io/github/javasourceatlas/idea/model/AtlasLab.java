package io.github.javasourceatlas.idea.model;

/**
 * 描述专题配套的可运行调试实验。
 *
 * @param module     仓库中的 Maven 模块路径
 * @param mainClass  可直接运行的 Lab 主类
 * @param sourcePath 主类源码相对仓库根目录的路径
 */
public record AtlasLab(String module, String mainClass, String sourcePath) {
}
