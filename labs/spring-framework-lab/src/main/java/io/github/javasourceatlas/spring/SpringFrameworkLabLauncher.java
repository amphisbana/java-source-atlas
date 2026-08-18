package io.github.javasourceatlas.spring;

import io.github.javasourceatlas.spring.aop.SpringAopDebugLab;
import io.github.javasourceatlas.spring.ioc.SpringIocDebugLab;
import io.github.javasourceatlas.spring.mvc.SpringMvcDebugLab;
import io.github.javasourceatlas.spring.transaction.SpringTransactionDebugLab;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 统一选择 Spring Framework 调试专题，但让每个专题继续创建彼此隔离的 ApplicationContext。
 */
public final class SpringFrameworkLabLauncher {

    private static final Map<String, LabCommand> LAB_COMMANDS = createLabCommands();

    /**
     * 工具类不需要创建实例。
     */
    private SpringFrameworkLabLauncher() {
    }

    /**
     * 根据第一个参数选择 IOC、AOP、事务或 MVC 实验。
     *
     * @param args 第一个参数为专题名，其余参数原样传给专题主类
     * @throws Exception 专题执行失败时向上抛出
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            printHelp();
            return;
        }

        String topic = args[0].toLowerCase(Locale.ROOT);
        LabCommand command = LAB_COMMANDS.get(topic);
        if (command == null) {
            printHelp();
            throw new IllegalArgumentException("未知 Spring Lab 专题: " + args[0]);
        }

        // 统一入口只做参数分发，不把多套配置装入同一个容器，避免自动代理器互相影响。
        String[] forwardedArguments = Arrays.copyOfRange(args, 1, args.length);
        command.run(forwardedArguments);
    }

    /**
     * 按帮助信息的展示顺序建立专题命令表。
     *
     * @return 不同专题到独立调试入口的映射
     */
    private static Map<String, LabCommand> createLabCommands() {
        Map<String, LabCommand> commands = new LinkedHashMap<>();
        commands.put("ioc", SpringIocDebugLab::main);
        commands.put("aop", SpringAopDebugLab::main);
        commands.put("transaction", SpringTransactionDebugLab::main);
        commands.put("mvc", SpringMvcDebugLab::main);
        return commands;
    }

    /**
     * 打印统一入口支持的专题和命令格式。
     */
    private static void printHelp() {
        System.out.println("用法: mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=<topic>");
        System.out.println("topic: ioc | aop | transaction | mvc");
    }

    /**
     * 统一描述可以抛出受检异常的专题调试命令。
     */
    @FunctionalInterface
    private interface LabCommand {

        /**
         * 运行一个独立的 Spring 调试专题。
         *
         * @param args 传给专题主类的参数
         * @throws Exception 专题执行失败时向上抛出
         */
        void run(String[] args) throws Exception;
    }
}
