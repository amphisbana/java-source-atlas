package io.github.javasourceatlas.idea.index;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.components.Service;
import io.github.javasourceatlas.idea.model.AtlasTopic;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 加载构建期从 source-index 合并得到的专题索引。
 */
@Service
public final class AtlasIndexService {

    private static final String INDEX_RESOURCE = "/atlas-index/topics.json";
    private static final Type TOPIC_LIST_TYPE = new TypeToken<List<AtlasTopic>>() {
    }.getType();

    private final List<AtlasTopic> topics;

    /**
     * 初始化应用级只读索引；资源缺失时立即失败，避免插件静默显示空页面。
     */
    public AtlasIndexService() {
        topics = loadBundledTopics();
    }

    /**
     * 返回全部专题的不可变快照。
     *
     * @return 全部专题
     */
    public List<AtlasTopic> topics() {
        return topics;
    }

    /**
     * 按稳定专题编号查找专题。
     *
     * @param topicId 专题编号
     * @return 命中的专题
     */
    public Optional<AtlasTopic> findById(String topicId) {
        return topics.stream().filter(topic -> topic.topicId().equals(topicId)).findFirst();
    }

    /**
     * 按完整源码类名查找第一个专题。
     *
     * @param className 完整类名
     * @return 命中的专题
     */
    public Optional<AtlasTopic> findBySourceClass(String className) {
        return topics.stream().filter(topic -> topic.containsSourceClass(className)).findFirst();
    }

    /**
     * 按用户输入过滤专题并保持索引原始顺序。
     *
     * @param query 搜索文本
     * @return 匹配专题
     */
    public List<AtlasTopic> search(String query) {
        return topics.stream().filter(topic -> topic.matchesQuery(query)).toList();
    }

    /**
     * 从插件资源读取全部专题。
     *
     * @return 不可变专题集合
     */
    private List<AtlasTopic> loadBundledTopics() {
        try (InputStream inputStream = AtlasIndexService.class.getResourceAsStream(INDEX_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("插件资源缺少 " + INDEX_RESOURCE);
            }
            return loadTopics(inputStream);
        } catch (IOException exception) {
            throw new IllegalStateException("关闭 Source Atlas 索引资源失败", exception);
        }
    }

    /**
     * 从输入流解析专题集合，独立方法便于单元测试覆盖格式兼容性。
     *
     * @param inputStream UTF-8 JSON 输入流
     * @return 不可变专题集合
     */
    static List<AtlasTopic> loadTopics(InputStream inputStream) {
        List<AtlasTopic> parsed = new Gson().fromJson(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8),
                TOPIC_LIST_TYPE
        );
        if (parsed == null) {
            return Collections.emptyList();
        }
        return List.copyOf(parsed);
    }
}
