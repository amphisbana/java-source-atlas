package io.github.javasourceatlas.idea.model;

/**
 * 展示项目运行环境与框架依赖版本。
 *
 * @param jdkVersion        项目 SDK 版本
 * @param springVersion     Spring Framework 版本
 * @param springBootVersion Spring Boot 版本
 */
public record AtlasVersionInfo(String jdkVersion, String springVersion, String springBootVersion) {
}
