package io.github.javasourceatlas.spring.boot.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 承接 atlas.feature 前缀下的实验配置，演示 JavaBean 方式的属性绑定。
 */
@ConfigurationProperties(prefix = "atlas.feature")
public class AtlasFeatureProperties {

    private boolean enabled;

    private String message = "你好";

    private int repeat = 1;

    /**
     * 判断实验功能是否开启。
     *
     * @return 开启状态
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置实验功能开关。
     *
     * @param enabled 开启状态
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 取得问候语前缀。
     *
     * @return 问候语前缀
     */
    public String getMessage() {
        return message;
    }

    /**
     * 设置问候语前缀。
     *
     * @param message 问候语前缀
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * 取得单次调用的重复次数。
     *
     * @return 重复次数
     */
    public int getRepeat() {
        return repeat;
    }

    /**
     * 设置单次调用的重复次数。
     *
     * @param repeat 重复次数
     */
    public void setRepeat(int repeat) {
        this.repeat = repeat;
    }
}
