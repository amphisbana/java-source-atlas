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
version = "0.2.11"

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
              <li>JDK 专题根据项目 JDK 8、17 或 21 自动切换固定源码 Tag、模块路径、入口签名和推荐断点。</li>
              <li>命中 Atlas 断点后自动定位源码，记录已验证证据和实际调用路径。</li>
              <li>新增“添加下一断点并继续”，可连续完成引导式源码调试。</li>
              <li>Debug 结束后可复制包含断点、证据、结论和源码位置的 Markdown 摘要。</li>
              <li>复用用户已有断点时保留调试引导，并确保 Atlas 清理操作不会删除用户断点。</li>
              <li>新增真实 IDEA Project、Java PSI 与 XDebugger 端到端测试，并覆盖全部 JDK 专题版本矩阵。</li>
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
