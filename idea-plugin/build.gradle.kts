import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

val localIdeaPath = providers.gradleProperty("atlas.localIdeaPath")
    .orElse("/Applications/IntelliJ IDEA.app")

group = "io.github.java-source-atlas"
version = "0.2.4"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.5")
        bundledPlugin("com.intellij.java")
        pluginVerifier()
    }

    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// 共享仓库中的专题 JSON 是唯一数据源，插件构建时将它们合并成一个只读资源。
val generateAtlasIndex by tasks.registering {
    val indexFiles = fileTree(rootProject.file("../source-index")) {
        include("**/*.json")
        exclude("schema.json", "baselines.json")
    }
    val outputFile = layout.buildDirectory.file("generated-resources/atlas-index/topics.json")

    inputs.files(indexFiles)
    outputs.file(outputFile)

    doLast {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            indexFiles.files
                .sortedBy { it.relativeTo(rootProject.file("..")).invariantSeparatorsPath }
                .joinToString(prefix = "[\n", separator = ",\n", postfix = "\n]\n") { it.readText() },
            Charsets.UTF_8
        )
    }
}

sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated-resources"))
    }
}

tasks.processResources {
    dependsOn(generateAtlasIndex)
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        name = "Java Source Atlas"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "242"
            untilBuild = "262.*"
        }

        vendor {
            name = "Java Source Atlas"
        }

        description = """
            Java Source Atlas connects JDK and Spring source guides directly to the IntelliJ IDEA editor.
            在阅读源码时，插件根据当前类和方法匹配 Java Source Atlas 教程、
            关键源码入口与推荐断点，并提供教程打开、源码反向跳转和版本基线提示。
        """.trimIndent()

        changeNotes = """
            <ul>
              <li>默认连接公开教程站点，安装后无需启动本地 VitePress 服务。</li>
              <li>默认教程地址切换到自定义域名，并自动迁移旧版本地或 GitHub Pages 默认地址。</li>
              <li>修复 IDEA 2026.2 拆分 JCEF 模块后工具窗口无法显示内容的问题。</li>
              <li>JCEF 不可用时保留完整专题导航，并降级为系统浏览器阅读教程。</li>
              <li>在 IDEA 工具窗口内嵌阅读专题教程。</li>
              <li>支持推荐断点一键添加，以及配套 Lab 的打开和 Debug。</li>
              <li>增强重载方法签名与 JDK、Spring Framework、Spring Boot 版本匹配。</li>
              <li>按专题、源码入口和推荐断点分组展示操作按钮，优化窄工具窗口布局。</li>
            </ul>
        """.trimIndent()
    }

    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.5")
            if (file(localIdeaPath.get()).exists()) {
                local(localIdeaPath.get())
            }
        }
    }
}
