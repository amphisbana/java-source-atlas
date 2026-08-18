package io.github.javasourceatlas.spring.transaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 保存实验事务管理器与业务方法产生的可观察事件。
 */
public final class TransactionEvents {

    private static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /**
     * 工具类不需要创建实例。
     */
    private TransactionEvents() {
    }

    /**
     * 清空上一轮实验事件。
     */
    public static void clear() {
        EVENTS.clear();
    }

    /**
     * 记录一个不可变的实验事件。
     *
     * @param event 事务或业务事件
     */
    public static void record(String event) {
        EVENTS.add(event);
    }

    /**
     * 返回与内部集合隔离的只读事件快照。
     *
     * @return 当前全部事件
     */
    public static List<String> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(EVENTS));
    }
}
