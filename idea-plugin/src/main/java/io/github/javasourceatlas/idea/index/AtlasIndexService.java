package io.github.javasourceatlas.idea.index;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.components.Service;
import io.github.javasourceatlas.idea.model.AtlasTopic;
import io.github.javasourceatlas.idea.model.AtlasTopicRelation;
import io.github.javasourceatlas.idea.model.AtlasBreakpoint;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasEvidence;
import io.github.javasourceatlas.idea.match.AtlasMethodMatcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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
     * 按完整源码类名查找全部候选专题，并保持索引中的稳定顺序。
     *
     * @param className 完整类名
     * @return 命中的候选专题
     */
    public List<AtlasTopic> findBySourceClassCandidates(String className) {
        return topics.stream()
                .filter(topic -> topic.containsSourceClass(className))
                .toList();
    }

    /**
     * 按完整源码类名查找第一个专题，保留旧调用方的兼容行为。
     *
     * @param className 完整类名
     * @return 兼容模式下的第一个专题
     * @deprecated 编辑器上下文应使用 {@link #findBySourceClassCandidates(String)} 后自行消歧。
     */
    @Deprecated
    public Optional<AtlasTopic> findBySourceClass(String className) {
        // 2026-08-26：原逻辑保留给旧调用方；编辑器和 gutter 已改为候选专题匹配，避免共享类静默命中第一个专题。
        // return topics.stream().filter(topic -> topic.containsSourceClass(className)).findFirst();
        return findBySourceClassCandidates(className).stream().findFirst();
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
     * 查找当前专题索引中声明的下一站。
     *
     * @param topic 当前专题
     * @return 推荐的下一专题
     */
    public Optional<AtlasTopic> recommendedNext(AtlasTopic topic) {
        if (topic == null || topic.recommendedNextTopicId() == null
                || topic.recommendedNextTopicId().isBlank()) {
            return Optional.empty();
        }
        return findById(topic.recommendedNextTopicId());
    }

    /**
     * 从单向推荐关系双向推导关联专题，先展示下一站，再展示以当前专题为下一站的前置专题。
     *
     * @param topic 当前专题
     * @return 去重且保持学习方向的关联专题
     */
    public List<AtlasTopicRelation> relatedTopics(AtlasTopic topic) {
        if (topic == null) {
            return Collections.emptyList();
        }

        Map<String, AtlasTopicRelation> relations = new LinkedHashMap<>();
        recommendedNext(topic).ifPresent(next -> relations.put(
                next.topicId(),
                new AtlasTopicRelation(next, "推荐下一步", topic.recommendedNextReason())
        ));
        for (AtlasTopic candidate : topics) {
            if (topic.topicId().equals(candidate.recommendedNextTopicId())) {
                relations.putIfAbsent(
                        candidate.topicId(),
                        new AtlasTopicRelation(candidate, "前置专题", candidate.recommendedNextReason())
                );
            }
        }
        return List.copyOf(relations.values());
    }

    /**
     * 按编号顺序解析专题集合，自动忽略已经从索引删除的历史编号。
     *
     * @param topicIds 专题编号序列
     * @return 保持输入顺序的现有专题
     */
    public List<AtlasTopic> topicsByIds(List<String> topicIds) {
        if (topicIds == null || topicIds.isEmpty()) {
            return Collections.emptyList();
        }
        return topicIds.stream()
                .map(this::findById)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * 为推荐断点查找最贴近的方法讲解，优先完整签名，再匹配所属类和简单方法名。
     *
     * @param topic      当前专题
     * @param breakpoint 当前推荐断点
     * @return 可解释该断点的源码入口
     */
    public Optional<AtlasEntryPoint> explanationForBreakpoint(AtlasTopic topic, AtlasBreakpoint breakpoint) {
        if (topic == null || breakpoint == null) {
            return Optional.empty();
        }
        Optional<AtlasEntryPoint> exact = topic.entryPoints().stream()
                .filter(entryPoint -> entryPoint.method().equals(breakpoint.method()))
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }

        String methodName = AtlasMethodMatcher.extractSimpleMethodName(breakpoint.method());
        String sourceClass = topic.resolveSourceClass(breakpoint.method(), breakpoint.sourceClass());
        return topic.entryPoints().stream()
                .filter(entryPoint -> methodName.equals(entryPoint.simpleMethodName()))
                .filter(entryPoint -> sourceClass.equals(entryPoint.effectiveSourceClass(topic)))
                .findFirst();
    }

    /**
     * 为当前源码入口查找最贴近的推荐断点，优先返回带可执行证据的断点。
     *
     * @param topic      当前专题
     * @param entryPoint 当前源码入口
     * @return 对应推荐断点
     */
    public Optional<AtlasBreakpoint> breakpointForEntryPoint(AtlasTopic topic, AtlasEntryPoint entryPoint) {
        if (topic == null || entryPoint == null) {
            return Optional.empty();
        }
        List<AtlasBreakpoint> matches = topic.breakpoints().stream()
                .filter(breakpoint -> explanationForBreakpoint(topic, breakpoint)
                        .map(entryPoint::equals)
                        .orElse(false))
                .toList();
        return matches.stream()
                .filter(breakpoint -> breakpoint.evidenceId() != null && !breakpoint.evidenceId().isBlank())
                .findFirst()
                .or(() -> matches.stream().findFirst());
    }

    /**
     * 解析推荐断点绑定的可执行证据，未绑定或索引失配时保持禁用状态。
     *
     * @param topic      当前专题
     * @param breakpoint 当前推荐断点
     * @return 可直接创建 JUnit Debug 配置的证据
     */
    public Optional<AtlasEvidence> evidenceForBreakpoint(AtlasTopic topic, AtlasBreakpoint breakpoint) {
        if (topic == null || breakpoint == null) {
            return Optional.empty();
        }
        return topic.findEvidenceById(breakpoint.evidenceId());
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
