package io.github.javasourceatlas.idea.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 IDEA 本地学习进度的更新、隔离与恢复行为。
 */
class AtlasLearningProgressStateTest {

    /**
     * 验证更新后的阅读与 Lab 状态能同时保存，返回值与内部对象保持隔离。
     */
    @Test
    void shouldUpdateAndReturnProgressSnapshot() {
        AtlasLearningProgressState state = new AtlasLearningProgressState();

        AtlasLearningProgressState.TopicProgress updated = state.update("hashmap", true, false);
        AtlasLearningProgressState.TopicProgress loaded = state.progressFor("hashmap");

        assertTrue(updated.readMain);
        assertFalse(updated.ranLab);
        assertTrue(loaded.readMain);
        assertFalse(loaded.ranLab);
        assertFalse(loaded.updatedAt.isBlank());
        assertNotSame(state.progressByTopic.get("hashmap"), loaded);
    }

    /**
     * 验证不同专题使用独立键保存，不会相互覆盖完成状态。
     */
    @Test
    void shouldIsolateProgressBetweenTopics() {
        AtlasLearningProgressState state = new AtlasLearningProgressState();

        state.update("hashmap", true, true);
        state.update("treemap", false, true);

        assertTrue(state.progressFor("hashmap").readMain);
        assertTrue(state.progressFor("hashmap").ranLab);
        assertFalse(state.progressFor("treemap").readMain);
        assertTrue(state.progressFor("treemap").ranLab);
    }

    /**
     * 验证恢复配置时深复制记录，后续修改来源状态不会污染当前服务。
     */
    @Test
    void shouldRestoreIndependentProgressState() {
        AtlasLearningProgressState saved = new AtlasLearningProgressState();
        saved.update("spring-ioc", true, false);
        AtlasLearningProgressState restored = new AtlasLearningProgressState();

        restored.loadState(saved);
        saved.progressByTopic.get("spring-ioc").readMain = false;

        assertTrue(restored.progressFor("spring-ioc").readMain);
        assertFalse(restored.progressFor("spring-ioc").ranLab);
    }

    /**
     * 验证收藏状态可切换，最近阅读会去重并把重复打开的专题移动到首位。
     */
    @Test
    void shouldManageFavoritesAndRecentTopics() {
        AtlasLearningProgressState state = new AtlasLearningProgressState();

        state.setFavorite("hashmap", true);
        state.setFavorite("hashmap", true);
        state.recordRecent("hashmap");
        state.recordRecent("treemap");
        state.recordRecent("hashmap");

        assertTrue(state.isFavorite("hashmap"));
        assertEquals(java.util.List.of("hashmap", "treemap"), state.recentTopicIds());
        state.setFavorite("hashmap", false);
        state.clearRecent();
        assertFalse(state.isFavorite("hashmap"));
        assertTrue(state.recentTopicIds().isEmpty());
    }

    /**
     * 验证恢复旧配置时会清除空值、重复值，并将最近阅读限制在二十条以内。
     */
    @Test
    void shouldNormalizeRestoredFavoritesAndRecentTopics() {
        AtlasLearningProgressState saved = new AtlasLearningProgressState();
        saved.favoriteTopicIds = new java.util.ArrayList<>(java.util.List.of("hashmap", "hashmap", ""));
        saved.recentTopicIds = new java.util.ArrayList<>();
        for (int index = 0; index < 25; index++) {
            saved.recentTopicIds.add("topic-" + index);
        }

        AtlasLearningProgressState restored = new AtlasLearningProgressState();
        restored.loadState(saved);

        assertEquals(java.util.List.of("hashmap"), restored.favoriteTopicIds());
        assertEquals(20, restored.recentTopicIds().size());
        assertEquals("topic-0", restored.recentTopicIds().getFirst());
    }

    /**
     * 验证源码入口和推荐断点会保存方法、文档、版本以及有序访问轨迹。
     */
    @Test
    void shouldRecordMethodLevelReadingSession() {
        AtlasLearningProgressState state = new AtlasLearningProgressState();

        state.recordEntry("hashmap", "put(K,V)", "/jdk/hashmap/put", "OpenJDK 8u");
        state.recordEntry("hashmap", "resize()", "/jdk/hashmap/resize", "OpenJDK 8u");
        state.recordEntry("hashmap", "put(K,V)", "/jdk/hashmap/put", "OpenJDK 8u");
        state.recordBreakpoint("hashmap", "resize()", "OpenJDK 8u");

        AtlasLearningProgressState.TopicProgress progress = state.progressFor("hashmap");
        assertEquals("put(K,V)", progress.lastEntryMethod);
        assertEquals("/jdk/hashmap/put", progress.lastDocument);
        assertEquals("resize()", progress.lastBreakpointMethod);
        assertEquals("OpenJDK 8u", progress.lastVersion);
        assertEquals(java.util.List.of("put(K,V)", "resize()"), progress.visitedEntryMethods);
        assertEquals(java.util.List.of("resize()"), progress.preparedBreakpointMethods);
        assertEquals(java.util.List.of("hashmap"), state.recentTopicIds());
    }

    /**
     * 验证更新专题复选框时会保留已经记录的方法级阅读上下文。
     */
    @Test
    void shouldKeepReadingSessionWhenUpdatingTopicProgress() {
        AtlasLearningProgressState state = new AtlasLearningProgressState();
        state.recordEntry("hashmap", "resize()", "/jdk/hashmap/resize", "OpenJDK 8u");

        state.update("hashmap", true, true);

        AtlasLearningProgressState.TopicProgress progress = state.progressFor("hashmap");
        assertTrue(progress.readMain);
        assertTrue(progress.ranLab);
        assertEquals("resize()", progress.lastEntryMethod);
        assertEquals(java.util.List.of("resize()"), progress.visitedEntryMethods);
    }
}
