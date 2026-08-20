package io.github.javasourceatlas.idea.learning;

import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasLab;
import io.github.javasourceatlas.idea.model.AtlasSource;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证源码调用链复制文本包含稳定顺序、当前入口和教程地址。
 */
class AtlasCallChainFormatterTest {

    /**
     * 验证入口顺序来自专题索引，且链接转换器只负责补全地址。
     */
    @Test
    void shouldFormatEntryPointCallChain() {
        AtlasEntryPoint first = new AtlasEntryPoint(
                "HashMap.put(K,V)",
                "/jdk/collections/hashmap/put",
                "进入 put 流程",
                null
        );
        AtlasEntryPoint second = new AtlasEntryPoint(
                "HashMap.resize()",
                "/jdk/collections/hashmap/resize",
                "观察扩容",
                null
        );
        AtlasTopic topic = new AtlasTopic(
                "hashmap",
                "HashMap 源码",
                "OpenJDK 8",
                "jdk8",
                "",
                "",
                "",
                "",
                "",
                List.of("8"),
                new AtlasLab("labs/jdk", "HashMapLab", "HashMapLab.java"),
                new AtlasSource("java.util.HashMap", "HashMap.java"),
                List.of(),
                null,
                List.of(first, second),
                List.of(),
                List.of()
        );

        String formatted = AtlasCallChainFormatter.format(topic, second, path -> "https://atlas.test" + path);

        assertTrue(formatted.contains("HashMap 源码"));
        assertTrue(formatted.contains("当前入口：HashMap.resize()"));
        assertTrue(formatted.contains("1. java.util.HashMap#HashMap.put(K,V)"));
        assertTrue(formatted.contains("2. java.util.HashMap#HashMap.resize()  <- 当前"));
        assertTrue(formatted.contains("https://atlas.test/jdk/collections/hashmap/put"));
        assertEquals(formatted, formatted.stripTrailing());
    }
}
