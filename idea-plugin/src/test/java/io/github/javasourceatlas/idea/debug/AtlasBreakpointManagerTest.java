package io.github.javasourceatlas.idea.debug;

import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiStatement;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 验证推荐断点只选择源码方法体中的第一条可执行语句。
 */
class AtlasBreakpointManagerTest {

    /**
     * 验证编译方法会切换到源码导航方法，并返回方法体第一条语句。
     */
    @Test
    void shouldUseFirstStatementFromSourceNavigationMethod() {
        PsiStatement firstStatement = proxy(PsiStatement.class, null, null);
        PsiStatement secondStatement = proxy(PsiStatement.class, null, null);
        PsiCodeBlock body = proxy(PsiCodeBlock.class, null, new PsiStatement[]{firstStatement, secondStatement});
        PsiMethod sourceMethod = proxy(PsiMethod.class, null, body);
        PsiMethod compiledMethod = proxy(PsiMethod.class, sourceMethod, null);

        PsiMethod resolvedSource = AtlasBreakpointManager.sourceMethod(compiledMethod);

        assertSame(sourceMethod, resolvedSource);
        assertSame(firstStatement, AtlasBreakpointManager.firstExecutableStatement(resolvedSource));
    }

    /**
     * 验证缺少源码方法体时返回空，不再回退到方法声明位置。
     */
    @Test
    void shouldRejectMethodWithoutExecutableBody() {
        PsiMethod compiledMethod = proxy(PsiMethod.class, null, null);

        PsiMethod resolvedSource = AtlasBreakpointManager.sourceMethod(compiledMethod);

        assertSame(compiledMethod, resolvedSource);
        assertNull(AtlasBreakpointManager.firstExecutableStatement(resolvedSource));
    }

    /**
     * 创建只响应断点定位所需方法的轻量 PSI 接口代理。
     *
     * @param type       PSI 接口类型
     * @param navigation 可选导航元素；为空时返回代理自身
     * @param body       可选方法体或语句数组
     * @param <T>        PSI 接口类型
     * @return PSI 代理
     */
    private <T extends PsiElement> T proxy(Class<T> type, PsiElement navigation, Object body) {
        Object value = Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (instance, method, arguments) -> switch (method.getName()) {
                    case "getNavigationElement" -> navigation == null ? instance : navigation;
                    case "getBody" -> body;
                    case "getStatements" -> body;
                    case "toString" -> type.getSimpleName() + "Proxy";
                    case "hashCode" -> System.identityHashCode(instance);
                    case "equals" -> instance == arguments[0];
                    default -> defaultValue(method.getReturnType());
                }
        );
        return type.cast(value);
    }

    /**
     * 为未使用的 PSI 方法返回对应基本类型默认值。
     *
     * @param returnType 方法返回类型
     * @return 基本类型默认值；引用类型返回 null
     */
    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        return null;
    }
}
