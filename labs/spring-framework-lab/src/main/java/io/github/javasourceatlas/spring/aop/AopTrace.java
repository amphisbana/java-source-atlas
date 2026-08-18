package io.github.javasourceatlas.spring.aop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 保存实验中的目标方法与通知事件，便于主程序输出和测试断言。
 */
public final class AopTrace {

    private final List<String> events = new ArrayList<String>();

    /**
     * 记录一个可观察事件。
     *
     * @param event 事件文本
     */
    public void record(String event) {
        events.add(event);
    }

    /**
     * 返回不可修改的事件快照，避免调用方改变实验内部状态。
     *
     * @return 当前事件的独立快照
     */
    public List<String> snapshot() {
        return Collections.unmodifiableList(new ArrayList<String>(events));
    }

    /**
     * 清空事件，便于在同一个实验中观察下一条独立调用链。
     */
    public void clear() {
        events.clear();
    }
}
