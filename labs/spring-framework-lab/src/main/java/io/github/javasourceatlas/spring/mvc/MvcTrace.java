package io.github.javasourceatlas.spring.mvc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 保存一次 MVC 请求中由实验组件产生的可观察事件。
 */
public final class MvcTrace {

    private static final List<String> EVENTS = new ArrayList<String>();

    /**
     * 工具类不需要创建实例。
     */
    private MvcTrace() {
    }

    /**
     * 清空当前事件，隔离不同请求和测试用例。
     */
    public static synchronized void clear() {
        EVENTS.clear();
    }

    /**
     * 记录一个请求阶段。
     *
     * @param event 阶段说明
     */
    public static synchronized void add(String event) {
        EVENTS.add(event);
    }

    /**
     * 返回不可变事件快照，避免调用方修改内部集合。
     *
     * @return 当前事件的不可变副本
     */
    public static synchronized List<String> snapshot() {
        return Collections.unmodifiableList(new ArrayList<String>(EVENTS));
    }
}
