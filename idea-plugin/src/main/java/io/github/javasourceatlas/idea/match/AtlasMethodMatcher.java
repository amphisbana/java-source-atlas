package io.github.javasourceatlas.idea.match;

import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasTopic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 负责把 source-index 中的人类可读方法签名转换为 PSI 可比较的结构。
 */
public final class AtlasMethodMatcher {

    /**
     * 工具类不需要创建实例。
     */
    private AtlasMethodMatcher() {
    }

    /**
     * 解析索引签名中的类名、方法名与参数类型。
     *
     * @param signature 索引中的方法签名
     * @return 可用于精确匹配的签名结构
     */
    public static ParsedSignature parse(String signature) {
        if (signature == null || signature.isBlank()) {
            return ParsedSignature.empty();
        }

        String normalized = signature.trim();
        int parameterStart = normalized.indexOf('(');
        String methodHead = parameterStart >= 0 ? normalized.substring(0, parameterStart) : normalized;
        String owner = "";
        String methodName = methodHead;

        int doubleColon = methodHead.lastIndexOf("::");
        if (doubleColon >= 0) {
            owner = methodHead.substring(0, doubleColon).trim();
            methodName = methodHead.substring(doubleColon + 2).trim();
        } else {
            int dot = methodHead.lastIndexOf('.');
            if (dot >= 0) {
                owner = methodHead.substring(0, dot).trim();
                methodName = methodHead.substring(dot + 1).trim();
            }
        }

        if (parameterStart < 0) {
            return new ParsedSignature(owner, methodName, Collections.emptyList(), false, false);
        }

        int parameterEnd = normalized.indexOf(')', parameterStart);
        if (parameterEnd < 0) {
            parameterEnd = normalized.length();
        }
        String parameterText = normalized.substring(parameterStart + 1, parameterEnd).trim();
        if (parameterText.equals("...")) {
            return new ParsedSignature(owner, methodName, Collections.emptyList(), true, true);
        }
        return new ParsedSignature(owner, methodName, splitParameterTypes(parameterText), true, false);
    }

    /**
     * 从“类名.方法(参数)”或“方法(参数)”中提取简单方法名。
     *
     * @param signature 索引中的方法签名
     * @return 简单方法名；无法解析时返回空字符串
     */
    public static String extractSimpleMethodName(String signature) {
        return parse(signature).methodName();
    }

    /**
     * 从签名中提取可选的所属类名，保留内部类层级。
     *
     * @param signature 索引中的方法签名
     * @return 所属类名；签名没有类名前缀时返回空字符串
     */
    public static String extractOwnerName(String signature) {
        return parse(signature).ownerName();
    }

    /**
     * 将 PSI 方法转换为稳定的完整签名，用于区分同名重载的编辑器上下文。
     *
     * @param method IDEA 方法
     * @return 类名、方法名和参数类型组成的签名
     */
    public static String signatureOf(PsiMethod method) {
        if (method == null) {
            return "";
        }
        String owner = method.getContainingClass() == null
                ? ""
                : method.getContainingClass().getQualifiedName();
        StringBuilder signature = new StringBuilder();
        if (owner != null && !owner.isBlank()) {
            signature.append(owner).append('.');
        }
        signature.append(method.getName()).append('(');
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int index = 0; index < parameters.length; index++) {
            if (index > 0) {
                signature.append(',');
            }
            signature.append(parameters[index].getType().getCanonicalText());
        }
        return signature.append(')').toString();
    }

    /**
     * 判断 PSI 方法是否与索引签名精确匹配。
     *
     * @param method    IDEA 方法
     * @param signature 索引签名
     * @return 方法名、参数数量和参数类型是否匹配
     */
    public static boolean matches(PsiMethod method, String signature) {
        if (method == null) {
            return false;
        }
        ParsedSignature parsed = parse(signature);
        String actualName = method.isConstructor() && method.getContainingClass() != null
                ? method.getContainingClass().getName()
                : method.getName();
        if (!parsed.methodName().equals(actualName)) {
            return false;
        }
        List<String> actualTypes = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            actualTypes.add(parameter.getType().getCanonicalText());
        }
        return parsed.matchesParameterTypes(actualTypes);
    }

    /**
     * 在专题入口中查找与当前类和 PSI 方法最精确的匹配项。
     *
     * @param topic     当前专题
     * @param className 当前完整类名
     * @param method    当前 PSI 方法
     * @return 命中的入口
     */
    public static Optional<AtlasEntryPoint> findBestEntryPoint(
            AtlasTopic topic,
            String className,
            PsiMethod method
    ) {
        if (topic == null || className == null || method == null) {
            return Optional.empty();
        }
        List<AtlasEntryPoint> candidates = topic.entryPoints().stream()
                .filter(entryPoint -> className.equals(entryPoint.effectiveSourceClass(topic)))
                .filter(entryPoint -> method.getName().equals(entryPoint.simpleMethodName()))
                .toList();
        return candidates.stream()
                .filter(entryPoint -> matches(method, entryPoint.method()))
                .findFirst()
                .or(() -> candidates.stream().findFirst());
    }

    /**
     * 在只有简单方法名时沿用第一版兼容匹配，供旧调用方和降级场景使用。
     *
     * @param topic      当前专题
     * @param className  当前完整类名
     * @param methodName 当前简单方法名
     * @return 命中的入口
     */
    public static Optional<AtlasEntryPoint> findBestEntryPoint(
            AtlasTopic topic,
            String className,
            String methodName
    ) {
        if (topic == null || className == null || methodName == null) {
            return Optional.empty();
        }
        return topic.entryPoints().stream()
                .filter(entryPoint -> className.equals(entryPoint.effectiveSourceClass(topic)))
                .filter(entryPoint -> methodName.equals(entryPoint.simpleMethodName()))
                .findFirst();
    }

    /**
     * 使用编辑器保存的完整方法签名匹配专题入口，供用户从歧义专题中选择后的动作恢复使用。
     *
     * @param topic           当前专题
     * @param className       当前完整类名
     * @param methodName      当前简单方法名
     * @param methodSignature 当前完整方法签名
     * @return 优先精确匹配参数类型的源码入口
     */
    public static Optional<AtlasEntryPoint> findBestEntryPoint(
            AtlasTopic topic,
            String className,
            String methodName,
            String methodSignature
    ) {
        if (topic == null || className == null || methodName == null) {
            return Optional.empty();
        }
        List<AtlasEntryPoint> candidates = topic.entryPoints().stream()
                .filter(entryPoint -> className.equals(entryPoint.effectiveSourceClass(topic)))
                .filter(entryPoint -> methodName.equals(entryPoint.simpleMethodName()))
                .toList();
        ParsedSignature actual = parse(methodSignature);
        if (!actual.parameterListSpecified()) {
            return candidates.stream().findFirst();
        }
        return candidates.stream()
                .filter(entryPoint -> parse(entryPoint.method()).matchesParameterTypes(actual.parameterTypes()))
                .findFirst()
                .or(() -> candidates.stream().findFirst());
    }

    /**
     * 按泛型嵌套层级切分参数，避免把泛型内部逗号误认为参数分隔符。
     *
     * @param parameterText 参数列表文本
     * @return 参数类型列表
     */
    private static List<String> splitParameterTypes(String parameterText) {
        if (parameterText.isBlank()) {
            return Collections.emptyList();
        }

        List<String> parameters = new ArrayList<>();
        int genericDepth = 0;
        int arrayDepth = 0;
        int start = 0;
        for (int index = 0; index < parameterText.length(); index++) {
            char character = parameterText.charAt(index);
            if (character == '<') {
                genericDepth++;
            } else if (character == '>') {
                genericDepth = Math.max(0, genericDepth - 1);
            } else if (character == '[') {
                arrayDepth++;
            } else if (character == ']') {
                arrayDepth = Math.max(0, arrayDepth - 1);
            } else if (character == ',' && genericDepth == 0 && arrayDepth == 0) {
                parameters.add(parameterText.substring(start, index).trim());
                start = index + 1;
            }
        }
        parameters.add(parameterText.substring(start).trim());
        return List.copyOf(parameters);
    }

    /**
     * 将完整类型、泛型和可变参数规范为可比较的简单类型。
     *
     * @param typeText 原始类型文本
     * @return 规范化类型
     */
    private static String simplifyType(String typeText) {
        String normalized = typeText == null ? "" : typeText.trim().replace("...", "[]");
        StringBuilder withoutGenerics = new StringBuilder();
        int genericDepth = 0;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character == '<') {
                genericDepth++;
            } else if (character == '>') {
                genericDepth = Math.max(0, genericDepth - 1);
            } else if (genericDepth == 0 && !Character.isWhitespace(character)) {
                withoutGenerics.append(character);
            }
        }

        String value = withoutGenerics.toString().replace('$', '.');
        int arrayIndex = value.indexOf('[');
        String arraySuffix = arrayIndex >= 0 ? value.substring(arrayIndex) : "";
        String component = arrayIndex >= 0 ? value.substring(0, arrayIndex) : value;
        int lastDot = component.lastIndexOf('.');
        if (lastDot >= 0) {
            component = component.substring(lastDot + 1);
        }
        return (component + arraySuffix).toLowerCase(Locale.ROOT);
    }

    /**
     * 保存一次方法签名解析结果。
     *
     * @param ownerName              可选所属类名
     * @param methodName             简单方法名
     * @param parameterTypes         索引参数类型
     * @param parameterListSpecified 是否显式写出参数列表
     * @param wildcardParameters     是否使用省略号表示任意参数
     */
    public record ParsedSignature(
            String ownerName,
            String methodName,
            List<String> parameterTypes,
            boolean parameterListSpecified,
            boolean wildcardParameters
    ) {

        /**
         * 创建无法匹配任何方法的空签名。
         *
         * @return 空签名
         */
        private static ParsedSignature empty() {
            return new ParsedSignature("", "", Collections.emptyList(), false, false);
        }

        /**
         * 比较参数数量与简化类型；未声明参数或使用省略号时按名称降级匹配。
         *
         * @param actualTypes PSI 参数的完整类型文本
         * @return 参数是否匹配
         */
        public boolean matchesParameterTypes(List<String> actualTypes) {
            if (!parameterListSpecified || wildcardParameters) {
                return true;
            }
            if (actualTypes == null || parameterTypes.size() != actualTypes.size()) {
                return false;
            }
            for (int index = 0; index < parameterTypes.size(); index++) {
                String expected = simplifyType(parameterTypes.get(index));
                String actual = simplifyType(actualTypes.get(index));
                if (!matchesType(expected, actual)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * 比较单个参数类型；单字母泛型变量可匹配任意引用类型。
         *
         * @param expected 索引中的简化类型
         * @param actual   PSI 中的简化类型
         * @return 类型是否等价
         */
        private boolean matchesType(String expected, String actual) {
            if (expected.equals(actual)) {
                return true;
            }
            boolean typeVariable = expected.length() == 1 && Character.isLetter(expected.charAt(0));
            return typeVariable && !isPrimitive(actual);
        }

        /**
         * 判断简化类型是否为 Java 基本类型。
         *
         * @param type 简化类型
         * @return 是否为基本类型
         */
        private boolean isPrimitive(String type) {
            return switch (type) {
                case "boolean", "byte", "char", "short", "int", "long", "float", "double", "void" -> true;
                default -> false;
            };
        }
    }
}
