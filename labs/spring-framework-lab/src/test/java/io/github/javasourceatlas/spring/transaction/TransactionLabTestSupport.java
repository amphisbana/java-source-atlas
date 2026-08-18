package io.github.javasourceatlas.spring.transaction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提供事务事件顺序与数量的共享断言。
 */
abstract class TransactionLabTestSupport {

    /**
     * 断言多个事件前缀按给定顺序出现，中间允许存在其他框架事件。
     *
     * @param events 实际事件
     * @param prefixes 预期有序前缀
     */
    protected void assertEventsInOrder(List<String> events, String... prefixes) {
        int cursor = -1;
        for (String prefix : prefixes) {
            cursor = findAfter(events, prefix, cursor + 1);
            assertTrue(cursor >= 0, () -> "未按顺序找到事件前缀 " + prefix + "，实际事件=" + events);
        }
    }

    /**
     * 断言事件列表中不存在指定前缀。
     *
     * @param events 实际事件
     * @param prefix 不应出现的事件前缀
     */
    protected void assertNoEvent(List<String> events, String prefix) {
        assertTrue(events.stream().noneMatch(event -> event.startsWith(prefix)),
                () -> "不应出现事件前缀 " + prefix + "，实际事件=" + events);
    }

    /**
     * 断言指定事件前缀的出现次数。
     *
     * @param events 实际事件
     * @param prefix 事件前缀
     * @param expectedCount 预期次数
     */
    protected void assertEventCount(List<String> events, String prefix, long expectedCount) {
        long actualCount = events.stream().filter(event -> event.startsWith(prefix)).count();
        assertTrue(actualCount == expectedCount,
                () -> prefix + " 预期出现 " + expectedCount + " 次，实际事件=" + events);
    }

    /**
     * 从指定下标开始查找第一个匹配前缀的事件。
     *
     * @param events 实际事件
     * @param prefix 目标前缀
     * @param start 起始下标
     * @return 匹配下标，未找到时返回 -1
     */
    private int findAfter(List<String> events, String prefix, int start) {
        for (int index = start; index < events.size(); index++) {
            if (events.get(index).startsWith(prefix)) {
                return index;
            }
        }
        return -1;
    }
}
