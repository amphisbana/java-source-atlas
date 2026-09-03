import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

val localIdeaPath = providers.gradleProperty("atlas.localIdeaPath")
    .orElse("/Applications/IntelliJ IDEA.app")
val minimumIdeaVersion = "2024.2.5"
val maximumIdeaVersion = "2026.2"

group = "io.github.java-source-atlas"
version = "0.2.12"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(minimumIdeaVersion)
        bundledPlugin("com.intellij.java")
        bundledPlugin("JUnit")
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
    }

    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
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

/**
 * 复用 IntelliJ Platform 插件为标准 test 任务注入的 JVM 参数，避免自定义 Test 进程遗漏模块开放配置。
 */
val ideaIntegrationTest by tasks.registering {
    description = "运行 Java Source Atlas IDEA Platform 工作流集成测试"
    group = "verification"
    dependsOn(tasks.test)
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
            url = "https://github.com/amphisbana/java-source-atlas"
        }

        description = """
            Java Source Atlas connects JDK and Spring source guides directly to the IntelliJ IDEA editor.
            在阅读源码时，插件根据当前类和方法匹配 Java Source Atlas 教程、
            关键源码入口与推荐断点，并提供教程打开、源码反向跳转和版本基线提示。
        """.trimIndent()

        changeNotes = """
            <ul>
              <li>新增“当前方法”阅读助手，展示方法职责、执行过程、设计精妙、易错边界和关联方法。</li>
              <li>关联方法支持双击定位源码，并根据项目 JDK 版本同步适配方法签名。</li>
              <li>支持调用 Translation 插件翻译源码注释或方法 Javadoc，缺少插件时提供安装引导。</li>
              <li>支持跟随编辑器光标刷新讲解和定位源码后自动翻译，不覆盖用户手动选择的专题。</li>
              <li>优化窄工具窗口布局，将入口导航与当前方法操作拆分到对应子页签。</li>
              <li>补充 HashMap、ConcurrentHashMap、AQS、ThreadPoolExecutor 和 Spring IoC 主干方法讲解。</li>
            </ul>
        """.trimIndent()
    }

    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, minimumIdeaVersion)
            // 2026-08-31：2026.2 不再提供 ideaIC Community 分发，最高版本改用可下载的 Ultimate 构件验证。
            // ide(IntelliJPlatformType.IntellijIdeaCommunity, maximumIdeaVersion)
            ide(IntelliJPlatformType.IntellijIdeaUltimate, maximumIdeaVersion)
            if (file(localIdeaPath.get()).exists()) {
                local(localIdeaPath.get())
            }
        }
    }

    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
        channels = providers.environmentVariable("JETBRAINS_MARKETPLACE_CHANNEL")
            .map { listOf(it) }
            .orElse(listOf("default"))
    }
}
