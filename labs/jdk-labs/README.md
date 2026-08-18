# JDK Labs

该模块包含能够稳定触发 JDK 核心源码分支的最小实验。示例只使用公开 API，内部容量和树形通过 IDE 源码断点观察。

## 运行调试实验

```bash
mvn -pl labs/jdk-labs exec:java
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.collection.ArrayListDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.collection.LinkedHashMapDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.collection.TreeMapDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ConcurrentHashMapDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.CopyOnWriteArrayListDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.CompletableFutureDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.concurrent.ThreadPoolExecutorDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.runtime.ClassLoaderServiceLoaderDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.runtime.ReflectionProxyDebugLab
mvn -pl labs/jdk-labs exec:java -Dexec.mainClass=io.github.javasourceatlas.jdk.lock.ReentrantLockDebugLab
```

## 运行自动化测试

```bash
mvn -pl labs/jdk-labs test
```

不指定 `exec.mainClass` 时默认运行 HashMap。各专题调试步骤见文档站对应的“断点实验手册”。
