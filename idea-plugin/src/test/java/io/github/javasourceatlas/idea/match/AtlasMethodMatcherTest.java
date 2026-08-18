package io.github.javasourceatlas.idea.match;

import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasLab;
import io.github.javasourceatlas.idea.model.AtlasSource;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证人类可读方法签名与 PSI 简单方法名之间的匹配规则。
 */
class AtlasMethodMatcherTest {

    /**
     * 验证类名前缀、参数列表和 C++ 风格分隔符都能被移除。
     */
    @Test
    void shouldExtractSimpleMethodName() {
        assertEquals("putVal", AtlasMethodMatcher.extractSimpleMethodName(
                "HashMap.putVal(int,K,V,boolean,boolean)"
        ));
        assertEquals("resize", AtlasMethodMatcher.extractSimpleMethodName("resize()"));
        assertEquals("enter", AtlasMethodMatcher.extractSimpleMethodName("ObjectMonitor::enter(Thread*)"));
        assertEquals(
                "AutoConfigurationImportSelector.ConfigurationClassFilter",
                AtlasMethodMatcher.extractOwnerName(
                        "AutoConfigurationImportSelector.ConfigurationClassFilter.filter(List<String>)"
                )
        );
    }

    /**
     * 验证泛型内逗号、数组、可变参数和参数数量都参与匹配。
     */
    @Test
    void shouldMatchNormalizedParameterTypes() {
        AtlasMethodMatcher.ParsedSignature generic = AtlasMethodMatcher.parse(
                "resolve(Map<String,List<Integer>>,Object...)"
        );
        assertTrue(generic.matchesParameterTypes(List.of("java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>", "java.lang.Object[]")));

        AtlasMethodMatcher.ParsedSignature overload = AtlasMethodMatcher.parse("put(int,Object)");
        assertTrue(overload.matchesParameterTypes(List.of("int", "java.lang.Object")));
        assertTrue(!overload.matchesParameterTypes(List.of("java.lang.Object")));
    }

    /**
     * 验证关联源码类必须通过 sourceClass 才能命中对应方法。
     */
    @Test
    void shouldMatchEntryPointByClassAndMethod() {
        AtlasEntryPoint primaryEntry = new AtlasEntryPoint("put(K,V)", "/put", "公开入口", null);
        AtlasEntryPoint relatedEntry = new AtlasEntryPoint(
                "Helper.resize()",
                "/resize",
                "扩容入口",
                "sample.Helper"
        );
        AtlasTopic topic = new AtlasTopic(
                "sample",
                "示例专题",
                "示例版本",
                "v1",
                List.of(),
                new AtlasLab("labs/sample", "sample.SampleLab", "labs/sample/SampleLab.java"),
                new AtlasSource("sample.Map", "Map.java"),
                List.of(new AtlasSource("sample.Helper", "Helper.java")),
                List.of(primaryEntry, relatedEntry),
                List.of()
        );

        assertEquals(
                relatedEntry,
                AtlasMethodMatcher.findBestEntryPoint(topic, "sample.Helper", "resize").orElseThrow()
        );
        assertTrue(AtlasMethodMatcher.findBestEntryPoint(topic, "sample.Map", "resize").isEmpty());
    }

    /**
     * 验证内部类 owner 可补全为外部源码类下的完整类名。
     */
    @Test
    void shouldResolveNestedSourceClass() {
        AtlasTopic topic = new AtlasTopic(
                "sample",
                "示例专题",
                "示例版本",
                "v1",
                List.of(),
                new AtlasLab("labs/sample", "sample.SampleLab", "labs/sample/SampleLab.java"),
                new AtlasSource("java.util.ArrayList", "ArrayList.java"),
                List.of(),
                List.of(),
                List.of()
        );

        assertEquals(
                "java.util.ArrayList.Itr",
                topic.resolveSourceClass("Itr.checkForComodification()", null)
        );
        assertTrue(topic.containsSourceClass("java.util.ArrayList.Itr"));
    }
}
