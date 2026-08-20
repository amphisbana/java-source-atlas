package io.github.javasourceatlas.idea.learning;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 IDEA 本地保存各专题的阅读与实验完成状态。
 */
@Service
@State(
        name = "JavaSourceAtlasLearningProgress",
        storages = @Storage("java-source-atlas-learning-progress.xml")
)
public final class AtlasLearningProgressState implements PersistentStateComponent<AtlasLearningProgressState> {

    private static final int MAX_RECENT_TOPICS = 20;

    public Map<String, TopicProgress> progressByTopic = new HashMap<>();
    public List<String> favoriteTopicIds = new ArrayList<>();
    public List<String> recentTopicIds = new ArrayList<>();

    /**
     * 取得应用级学习进度服务。
     *
     * @return 学习进度服务
     */
    public static AtlasLearningProgressState getInstance() {
        return ApplicationManager.getApplication().getService(AtlasLearningProgressState.class);
    }

    /**
     * 返回需要持久化的当前状态。
     *
     * @return 当前状态
     */
    @Override
    public AtlasLearningProgressState getState() {
        return this;
    }

    /**
     * 从 IDEA 配置恢复学习进度，并复制每条记录避免与反序列化对象共享可变引用。
     *
     * @param state 已保存状态
     */
    @Override
    public void loadState(@NotNull AtlasLearningProgressState state) {
        progressByTopic = new HashMap<>();
        favoriteTopicIds = distinctNonBlank(state.favoriteTopicIds);
        recentTopicIds = distinctNonBlank(state.recentTopicIds);
        if (recentTopicIds.size() > MAX_RECENT_TOPICS) {
            recentTopicIds = new ArrayList<>(recentTopicIds.subList(0, MAX_RECENT_TOPICS));
        }
        if (state.progressByTopic != null) {
            state.progressByTopic.forEach((topicId, progress) -> {
                if (topicId != null && progress != null) {
                    progressByTopic.put(topicId, progress.copy());
                }
            });
        }
    }

    /**
     * 读取指定专题的进度快照；尚未学习时返回默认未完成状态。
     *
     * @param topicId 专题编号
     * @return 与内部存储隔离的进度快照
     */
    public TopicProgress progressFor(String topicId) {
        TopicProgress progress = progressByTopic.get(topicId);
        return progress == null ? new TopicProgress() : progress.copy();
    }

    /**
     * 合并保存一个专题的阅读与实验状态，并记录最近修改时间。
     *
     * @param topicId  专题编号
     * @param readMain 是否完成主线阅读
     * @param ranLab   是否运行并理解 Lab
     * @return 保存后的进度快照
     */
    public TopicProgress update(String topicId, boolean readMain, boolean ranLab) {
        if (topicId == null || topicId.isBlank()) {
            return new TopicProgress();
        }
        TopicProgress updated = new TopicProgress();
        updated.readMain = readMain;
        updated.ranLab = ranLab;
        updated.updatedAt = Instant.now().toString();
        progressByTopic.put(topicId, updated);
        return updated.copy();
    }

    /**
     * 判断专题是否已被用户收藏。
     *
     * @param topicId 专题编号
     * @return 是否已收藏
     */
    public boolean isFavorite(String topicId) {
        return topicId != null && favoriteTopicIds.contains(topicId);
    }

    /**
     * 设置专题收藏状态，并保持收藏编号唯一且顺序稳定。
     *
     * @param topicId  专题编号
     * @param favorite 是否收藏
     */
    public void setFavorite(String topicId, boolean favorite) {
        if (topicId == null || topicId.isBlank()) {
            return;
        }
        favoriteTopicIds.remove(topicId);
        if (favorite) {
            favoriteTopicIds.add(topicId);
        }
    }

    /**
     * 返回收藏专题编号的不可变快照。
     *
     * @return 收藏专题编号
     */
    public List<String> favoriteTopicIds() {
        return List.copyOf(favoriteTopicIds);
    }

    /**
     * 记录一次主动阅读，把当前专题移动到最近列表首位并限制历史长度。
     *
     * @param topicId 专题编号
     */
    public void recordRecent(String topicId) {
        if (topicId == null || topicId.isBlank()) {
            return;
        }
        recentTopicIds.remove(topicId);
        recentTopicIds.add(0, topicId);
        if (recentTopicIds.size() > MAX_RECENT_TOPICS) {
            recentTopicIds = new ArrayList<>(recentTopicIds.subList(0, MAX_RECENT_TOPICS));
        }
    }

    /**
     * 返回最近阅读专题编号的不可变快照，第一项为最近一次阅读。
     *
     * @return 最近阅读专题编号
     */
    public List<String> recentTopicIds() {
        return List.copyOf(recentTopicIds);
    }

    /**
     * 清空最近阅读历史，不改变收藏和完成进度。
     */
    public void clearRecent() {
        recentTopicIds.clear();
    }

    /**
     * 复制并去重持久化编号，兼容旧配置中的空值与重复项。
     *
     * @param values 反序列化列表
     * @return 去重后的可变列表
     */
    private static List<String> distinctNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    /**
     * 可由 IDEA XML 序列化器恢复的单专题进度 Bean。
     */
    public static final class TopicProgress {

        public boolean readMain;
        public boolean ranLab;
        public String updatedAt = "";

        /**
         * 创建与当前记录相同的快照。
         *
         * @return 独立进度对象
         */
        private TopicProgress copy() {
            TopicProgress copied = new TopicProgress();
            copied.readMain = readMain;
            copied.ranLab = ranLab;
            copied.updatedAt = updatedAt == null ? "" : updatedAt;
            return copied;
        }
    }
}
