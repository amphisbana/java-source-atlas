package io.github.javasourceatlas.idea.icons;

import com.intellij.openapi.util.IconLoader;

import javax.swing.Icon;

/**
 * 集中管理插件使用的图标资源。
 */
public final class AtlasIcons {

    public static final Icon ATLAS = IconLoader.getIcon("/icons/atlas.svg", AtlasIcons.class);
    public static final Icon DOCUMENTATION = IconLoader.getIcon("/icons/documentation.svg", AtlasIcons.class);
    public static final Icon SOURCE = IconLoader.getIcon("/icons/source.svg", AtlasIcons.class);

    /**
     * 图标容器不需要创建实例。
     */
    private AtlasIcons() {
    }
}
