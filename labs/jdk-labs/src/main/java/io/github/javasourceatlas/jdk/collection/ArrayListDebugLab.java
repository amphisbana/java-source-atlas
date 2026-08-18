package io.github.javasourceatlas.jdk.collection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;

/**
 * 用公开 API 稳定触发 ArrayList 核心源码分支的调试入口。
 */
public final class ArrayListDebugLab {

    /**
     * 工具类不需要创建实例。
     */
    private ArrayListDebugLab() {
    }

    /**
     * 按固定顺序运行全部 ArrayList 调试场景。
     *
     * @param args 命令行参数，本实验不使用
     */
    public static void main(String[] args) {
        printHeader("首次分配与扩容");
        observeGrowth();

        printHeader("中间插入和删除");
        observeMiddleMutation();

        printHeader("remove 重载");
        observeRemoveOverload();

        printHeader("SubList 视图");
        observeSubListView();

        printHeader("迭代器快速失败");
        observeFailFast();
    }

    /**
     * 连续加入十一个元素，触发无参列表的首次分配和后续扩容。
     */
    static void observeGrowth() {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            list.add(i);
        }
        System.out.printf("写入后 size=%d，首元素=%d，末元素=%d%n",
                list.size(), list.get(0), list.get(list.size() - 1));
    }

    /**
     * 在列表中部插入和删除元素，观察数组引用的左右搬移。
     */
    static void observeMiddleMutation() {
        List<String> list = new ArrayList<>(Arrays.asList("A", "B", "C"));
        list.add(1, "X");
        String removed = list.remove(2);
        System.out.printf("删除=%s，当前列表=%s%n", removed, list);
    }

    /**
     * 对比整型参数选择下标删除和对象删除两个重载。
     */
    static void observeRemoveOverload() {
        List<Integer> byIndex = new ArrayList<>(Arrays.asList(1, 2, 1));
        List<Integer> byValue = new ArrayList<>(byIndex);

        Integer removedByIndex = byIndex.remove(1);
        boolean removedByValue = byValue.remove(Integer.valueOf(1));

        System.out.printf("按下标删除=%d，结果=%s；按值删除=%s，结果=%s%n",
                removedByIndex, byIndex, removedByValue, byValue);
    }

    /**
     * 通过子列表修改元素，观察父列表共享变化的视图语义。
     */
    static void observeSubListView() {
        List<String> parent = new ArrayList<>(Arrays.asList("A", "B", "C", "D"));
        List<String> view = parent.subList(1, 3);
        view.set(0, "X");
        System.out.printf("子列表=%s，父列表=%s%n", view, parent);
    }

    /**
     * 创建迭代器后直接结构性修改列表，观察快速失败检查。
     */
    static void observeFailFast() {
        List<String> list = new ArrayList<>(Arrays.asList("A", "B"));
        Iterator<String> iterator = list.iterator();
        list.add("C");

        try {
            iterator.next();
        } catch (ConcurrentModificationException exception) {
            System.out.println("捕获到 ConcurrentModificationException");
        }
    }

    /**
     * 打印场景标题，使控制台输出与断点实验步骤保持一致。
     *
     * @param title 场景名称
     */
    private static void printHeader(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }
}

