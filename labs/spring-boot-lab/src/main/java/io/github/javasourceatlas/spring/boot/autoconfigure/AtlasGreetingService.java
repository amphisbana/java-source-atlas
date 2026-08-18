package io.github.javasourceatlas.spring.boot.autoconfigure;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动配置创建的最小服务，用于观察属性绑定结果和用户 Bean 回退行为。
 */
public class AtlasGreetingService {

    private final String message;

    private final int repeat;

    /**
     * 保存创建服务时已经绑定完成的配置快照。
     *
     * @param message 问候语前缀
     * @param repeat 重复次数
     */
    public AtlasGreetingService(String message, int repeat) {
        this.message = message;
        this.repeat = repeat;
    }

    /**
     * 按配置次数生成问候文本，并把每次结果用竖线分隔。
     *
     * @param name 被问候的名称
     * @return 完整问候文本
     */
    public String greet(String name) {
        int safeRepeat = Math.max(1, repeat);
        List<String> greetings = new ArrayList<>(safeRepeat);
        for (int index = 0; index < safeRepeat; index++) {
            // 重复构造被刻意保留为可观察逻辑，方便在方法内设置循环断点。
            greetings.add(message + "，" + name);
        }
        return String.join(" | ", greetings);
    }

    /**
     * 取得创建服务时采用的问候语前缀。
     *
     * @return 问候语前缀
     */
    public String getMessage() {
        return message;
    }

    /**
     * 取得创建服务时采用的重复次数。
     *
     * @return 重复次数
     */
    public int getRepeat() {
        return repeat;
    }
}
