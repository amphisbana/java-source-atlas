package io.github.javasourceatlas.spring.ioc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 记录实验 Bean 的生命周期事件，便于控制台观察和自动测试断言。
 */
public final class LifecycleEvents {

    private static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /**
     * 工具类不需要创建实例。
     */
    private LifecycleEvents() {
    }

    /**
     * 清空上一轮上下文留下的事件。
     */
    public static void clear() {
        EVENTS.clear();
    }

    /**
     * 记录一个生命周期事件。
     *
     * @param event 可读的事件名称
     */
    public static void record(String event) {
        EVENTS.add(event);
    }

    /**
     * 返回当前事件的不可变快照，避免调用方修改共享记录。
     *
     * @return 按发生顺序排列的事件
     */
    public static List<String> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(EVENTS));
    }
}

