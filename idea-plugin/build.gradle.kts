import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

val localIdeaPath = providers.gradleProperty("atlas.localIdeaPath")
    .orElse("/Applications/IntelliJ IDEA.app")

group = "io.github.java-source-atlas"
version = "0.2.10"

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
        bundledPlugin("JUnit")
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
              <li>工具窗口、gutter、右键菜单和快捷键统一使用专题选择器，完整支持共享源码类的歧义候选。</li>
              <li>用户选择专题后按完整方法签名恢复重载入口，并兼容最近阅读进度。</li>
              <li>教程设置新增地址格式校验、异步连接测试和恢复默认地址操作。</li>
              <li>教程根地址拒绝账号、查询参数和锚点，避免拼接专题路由后产生无效链接。</li>
              <li>文档站右侧目录限定当前正文并按锚点去重，修复路由更新后的重复目录。</li>
              <li>文档站侧栏增加响应式布局兜底，避免临界宽度或路由切换时遮挡正文。</li>
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
