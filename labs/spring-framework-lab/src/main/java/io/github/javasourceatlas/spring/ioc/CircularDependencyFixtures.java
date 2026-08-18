package io.github.javasourceatlas.spring.ioc;

/**
 * 提供循环依赖自动测试需要的简单对象模型。
 */
public final class CircularDependencyFixtures {

    /**
     * 工具类只承载内部实验类型，不需要实例化。
     */
    private CircularDependencyFixtures() {
    }

    /**
     * 通过 Setter 依赖 B 的对象。
     */
    public static class SetterA {

        private SetterB b;

        /**
         * 创建尚未注入 B 的 A。
         */
        public SetterA() {
        }

        /**
         * 注入 B。
         *
         * @param b 依赖的 B
         */
        public void setB(SetterB b) {
            this.b = b;
        }

        /**
         * 返回已注入的 B。
         *
         * @return 依赖的 B
         */
        public SetterB getB() {
            return b;
        }
    }

    /**
     * 通过 Setter 依赖 A 的对象。
     */
    public static class SetterB {

        private SetterA a;

        /**
         * 创建尚未注入 A 的 B。
         */
        public SetterB() {
        }

        /**
         * 注入 A。
         *
         * @param a 依赖的 A
         */
        public void setA(SetterA a) {
            this.a = a;
        }

        /**
         * 返回已注入的 A。
         *
         * @return 依赖的 A
         */
        public SetterA getA() {
            return a;
        }
    }

    /**
     * 必须在构造阶段取得 B 的对象。
     */
    public static class ConstructorA {

        private final ConstructorB b;

        /**
         * 创建 A，并要求 B 已经存在。
         *
         * @param b 构造器依赖 B
         */
        public ConstructorA(ConstructorB b) {
            this.b = b;
        }

        /**
         * 返回构造时注入的 B。
         *
         * @return 依赖的 B
         */
        public ConstructorB getB() {
            return b;
        }
    }

    /**
     * 必须在构造阶段取得 A 的对象。
     */
    public static class ConstructorB {

        private final ConstructorA a;

        /**
         * 创建 B，并要求 A 已经存在。
         *
         * @param a 构造器依赖 A
         */
        public ConstructorB(ConstructorA a) {
            this.a = a;
        }

        /**
         * 返回构造时注入的 A。
         *
         * @return 依赖的 A
         */
        public ConstructorA getA() {
            return a;
        }
    }
}

