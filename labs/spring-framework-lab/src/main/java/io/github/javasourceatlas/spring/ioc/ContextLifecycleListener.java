package io.github.javasourceatlas.spring.ioc;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationContextEvent;

/**
 * 记录上下文刷新和关闭事件的实验监听器。
 */
public class ContextLifecycleListener implements ApplicationListener<ApplicationContextEvent> {

    /**
     * 记录收到的 ApplicationContext 生命周期事件类型。
     *
     * @param event 上下文事件
     */
    @Override
    public void onApplicationEvent(ApplicationContextEvent event) {
        LifecycleEvents.record("event:" + event.getClass().getSimpleName());
    }
}

