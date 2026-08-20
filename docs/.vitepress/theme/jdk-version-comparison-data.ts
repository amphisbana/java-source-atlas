import {
  sourceTopics,
  type SourceTopic
} from './source-explorer-data'

export const jdkComparisonVersions = ['8', '17', '21'] as const

export type JdkComparisonVersion = typeof jdkComparisonVersions[number]
export type JdkDifferenceKind = 'added' | 'removed' | 'signature' | 'implementation'

export interface JdkVersionMeta {
  label: string
  repository: string
  sourceRef: string
  snapshot: string
}

export interface JdkSourceCoordinate {
  sourceKey: string
  className: string
  sourcePath: string
}

export interface JdkDifferenceState {
  sourceKey: string
  line: number
  symbol: string
  fingerprint: string
  code: string
  note: string
}

export interface JdkVersionDifference {
  id: string
  title: string
  kind: Exclude<JdkDifferenceKind, 'added' | 'removed'>
  summary: string
  reason: string
  migrationImpact: string
  states: Partial<Record<JdkComparisonVersion, JdkDifferenceState>>
}

export interface JdkTimelinePoint {
  version: JdkComparisonVersion
  title: string
  summary: string
}

export interface JdkDemoStep {
  title: string
  method: string
  description: string
  states: Record<JdkComparisonVersion, string>
}

export interface JdkComparisonTopic {
  id: string
  sourceTopicId: string
  title: string
  packageName: string
  question: string
  conclusion: string
  sources: Record<JdkComparisonVersion, JdkSourceCoordinate[]>
  timeline: JdkTimelinePoint[]
  differences: JdkVersionDifference[]
  migrationChecklist: string[]
  demoTitle: string
  demoSteps: JdkDemoStep[]
  sourceTopic?: SourceTopic
}

export const jdkVersionMeta: Record<JdkComparisonVersion, JdkVersionMeta> = {
  '8': { label: 'JDK 8u412', repository: 'openjdk/jdk8u', sourceRef: 'jdk8u412-b08', snapshot: 'OpenJDK 8u412 固定快照' },
  '17': { label: 'JDK 17', repository: 'openjdk/jdk', sourceRef: 'jdk-17+35', snapshot: 'OpenJDK 17 GA 固定快照' },
  '21': { label: 'JDK 21', repository: 'openjdk/jdk', sourceRef: 'jdk-21+35', snapshot: 'OpenJDK 21 GA 固定快照' }
}

/**
 * 为单源码专题生成三个版本的固定仓库坐标。
 */
function singleSource(
  sourceKey: string,
  className: string,
  relativePath: string
): Record<JdkComparisonVersion, JdkSourceCoordinate[]> {
  return {
    '8': [{ sourceKey, className, sourcePath: `jdk/src/share/classes/${relativePath}` }],
    '17': [{ sourceKey, className, sourcePath: `src/java.base/share/classes/${relativePath}` }],
    '21': [{ sourceKey, className, sourcePath: `src/java.base/share/classes/${relativePath}` }]
  }
}

const HASH_MAP_SOURCES = singleSource('HashMap', 'java.util.HashMap', 'java/util/HashMap.java')
const CONCURRENT_HASH_MAP_SOURCES = singleSource('ConcurrentHashMap', 'java.util.concurrent.ConcurrentHashMap', 'java/util/concurrent/ConcurrentHashMap.java')
const THREAD_LOCAL_SOURCES = singleSource('ThreadLocal', 'java.lang.ThreadLocal', 'java/lang/ThreadLocal.java')
const COMPLETABLE_FUTURE_SOURCES = singleSource('CompletableFuture', 'java.util.concurrent.CompletableFuture', 'java/util/concurrent/CompletableFuture.java')
const AQS_SOURCES = singleSource(
  'AQS',
  'java.util.concurrent.locks.AbstractQueuedSynchronizer',
  'java/util/concurrent/locks/AbstractQueuedSynchronizer.java'
)
const REENTRANT_LOCK_SOURCES = singleSource(
  'ReentrantLock',
  'java.util.concurrent.locks.ReentrantLock',
  'java/util/concurrent/locks/ReentrantLock.java'
)
const AQS_REENTRANT_LOCK_SOURCES: Record<JdkComparisonVersion, JdkSourceCoordinate[]> = {
  '8': [...AQS_SOURCES['8'], ...REENTRANT_LOCK_SOURCES['8']],
  '17': [...AQS_SOURCES['17'], ...REENTRANT_LOCK_SOURCES['17']],
  '21': [...AQS_SOURCES['21'], ...REENTRANT_LOCK_SOURCES['21']]
}
const THREAD_POOL_EXECUTOR_SOURCES = singleSource(
  'ThreadPoolExecutor',
  'java.util.concurrent.ThreadPoolExecutor',
  'java/util/concurrent/ThreadPoolExecutor.java'
)
const EXECUTOR_SERVICE_SOURCES = singleSource(
  'ExecutorService',
  'java.util.concurrent.ExecutorService',
  'java/util/concurrent/ExecutorService.java'
)
const THREAD_POOL_EXECUTOR_COMPARISON_SOURCES: Record<JdkComparisonVersion, JdkSourceCoordinate[]> = {
  '8': [...THREAD_POOL_EXECUTOR_SOURCES['8'], ...EXECUTOR_SERVICE_SOURCES['8']],
  '17': [...THREAD_POOL_EXECUTOR_SOURCES['17'], ...EXECUTOR_SERVICE_SOURCES['17']],
  '21': [...THREAD_POOL_EXECUTOR_SOURCES['21'], ...EXECUTOR_SERVICE_SOURCES['21']]
}
const FUTURE_TASK_SOURCES = singleSource(
  'FutureTask',
  'java.util.concurrent.FutureTask',
  'java/util/concurrent/FutureTask.java'
)
const FUTURE_SOURCES = singleSource(
  'Future',
  'java.util.concurrent.Future',
  'java/util/concurrent/Future.java'
)
const FUTURE_TASK_COMPARISON_SOURCES: Record<JdkComparisonVersion, JdkSourceCoordinate[]> = {
  '8': [...FUTURE_TASK_SOURCES['8'], ...FUTURE_SOURCES['8']],
  '17': [...FUTURE_TASK_SOURCES['17'], ...FUTURE_SOURCES['17']],
  '21': [...FUTURE_TASK_SOURCES['21'], ...FUTURE_SOURCES['21']]
}
const BYTE_BUFFER_SELECTOR_SOURCES: Record<JdkComparisonVersion, JdkSourceCoordinate[]> = {
  '8': [
    { sourceKey: 'Buffer', className: 'java.nio.Buffer', sourcePath: 'jdk/src/share/classes/java/nio/Buffer.java' },
    { sourceKey: 'ByteBufferTemplate', className: 'java.nio.ByteBuffer（由模板生成）', sourcePath: 'jdk/src/share/classes/java/nio/X-Buffer.java.template' },
    { sourceKey: 'HeapByteBufferTemplate', className: 'java.nio.HeapByteBuffer（由模板生成）', sourcePath: 'jdk/src/share/classes/java/nio/Heap-X-Buffer.java.template' },
    { sourceKey: 'Selector', className: 'java.nio.channels.Selector', sourcePath: 'jdk/src/share/classes/java/nio/channels/Selector.java' },
    { sourceKey: 'SelectionKey', className: 'java.nio.channels.SelectionKey', sourcePath: 'jdk/src/share/classes/java/nio/channels/SelectionKey.java' }
  ],
  '17': [
    { sourceKey: 'Buffer', className: 'java.nio.Buffer', sourcePath: 'src/java.base/share/classes/java/nio/Buffer.java' },
    { sourceKey: 'ByteBufferTemplate', className: 'java.nio.ByteBuffer（由模板生成）', sourcePath: 'src/java.base/share/classes/java/nio/X-Buffer.java.template' },
    { sourceKey: 'HeapByteBufferTemplate', className: 'java.nio.HeapByteBuffer（由模板生成）', sourcePath: 'src/java.base/share/classes/java/nio/Heap-X-Buffer.java.template' },
    { sourceKey: 'Selector', className: 'java.nio.channels.Selector', sourcePath: 'src/java.base/share/classes/java/nio/channels/Selector.java' },
    { sourceKey: 'SelectionKey', className: 'java.nio.channels.SelectionKey', sourcePath: 'src/java.base/share/classes/java/nio/channels/SelectionKey.java' },
    { sourceKey: 'MemorySegment', className: 'jdk.incubator.foreign.MemorySegment', sourcePath: 'src/jdk.incubator.foreign/share/classes/jdk/incubator/foreign/MemorySegment.java' }
  ],
  '21': [
    { sourceKey: 'Buffer', className: 'java.nio.Buffer', sourcePath: 'src/java.base/share/classes/java/nio/Buffer.java' },
    { sourceKey: 'ByteBufferTemplate', className: 'java.nio.ByteBuffer（由模板生成）', sourcePath: 'src/java.base/share/classes/java/nio/X-Buffer.java.template' },
    { sourceKey: 'HeapByteBufferTemplate', className: 'java.nio.HeapByteBuffer（由模板生成）', sourcePath: 'src/java.base/share/classes/java/nio/Heap-X-Buffer.java.template' },
    { sourceKey: 'Selector', className: 'java.nio.channels.Selector', sourcePath: 'src/java.base/share/classes/java/nio/channels/Selector.java' },
    { sourceKey: 'SelectionKey', className: 'java.nio.channels.SelectionKey', sourcePath: 'src/java.base/share/classes/java/nio/channels/SelectionKey.java' },
    { sourceKey: 'MemorySegment', className: 'java.lang.foreign.MemorySegment（预览）', sourcePath: 'src/java.base/share/classes/java/lang/foreign/MemorySegment.java' }
  ]
}
const REFERENCE_WEAK_HASH_MAP_SOURCES: Record<JdkComparisonVersion, JdkSourceCoordinate[]> = {
  '8': [
    { sourceKey: 'Reference', className: 'java.lang.ref.Reference', sourcePath: 'jdk/src/share/classes/java/lang/ref/Reference.java' },
    { sourceKey: 'ReferenceQueue', className: 'java.lang.ref.ReferenceQueue', sourcePath: 'jdk/src/share/classes/java/lang/ref/ReferenceQueue.java' },
    { sourceKey: 'PhantomReference', className: 'java.lang.ref.PhantomReference', sourcePath: 'jdk/src/share/classes/java/lang/ref/PhantomReference.java' },
    { sourceKey: 'WeakHashMap', className: 'java.util.WeakHashMap', sourcePath: 'jdk/src/share/classes/java/util/WeakHashMap.java' }
  ],
  '17': [
    { sourceKey: 'Reference', className: 'java.lang.ref.Reference', sourcePath: 'src/java.base/share/classes/java/lang/ref/Reference.java' },
    { sourceKey: 'ReferenceQueue', className: 'java.lang.ref.ReferenceQueue', sourcePath: 'src/java.base/share/classes/java/lang/ref/ReferenceQueue.java' },
    { sourceKey: 'PhantomReference', className: 'java.lang.ref.PhantomReference', sourcePath: 'src/java.base/share/classes/java/lang/ref/PhantomReference.java' },
    { sourceKey: 'WeakHashMap', className: 'java.util.WeakHashMap', sourcePath: 'src/java.base/share/classes/java/util/WeakHashMap.java' }
  ],
  '21': [
    { sourceKey: 'Reference', className: 'java.lang.ref.Reference', sourcePath: 'src/java.base/share/classes/java/lang/ref/Reference.java' },
    { sourceKey: 'ReferenceQueue', className: 'java.lang.ref.ReferenceQueue', sourcePath: 'src/java.base/share/classes/java/lang/ref/ReferenceQueue.java' },
    { sourceKey: 'PhantomReference', className: 'java.lang.ref.PhantomReference', sourcePath: 'src/java.base/share/classes/java/lang/ref/PhantomReference.java' },
    { sourceKey: 'WeakHashMap', className: 'java.util.WeakHashMap', sourcePath: 'src/java.base/share/classes/java/util/WeakHashMap.java' }
  ]
}
const STREAM_SPLITERATOR_SOURCES: Record<JdkComparisonVersion, JdkSourceCoordinate[]> = {
  '8': [
    { sourceKey: 'Stream', className: 'java.util.stream.Stream', sourcePath: 'jdk/src/share/classes/java/util/stream/Stream.java' },
    { sourceKey: 'ReferencePipeline', className: 'java.util.stream.ReferencePipeline', sourcePath: 'jdk/src/share/classes/java/util/stream/ReferencePipeline.java' },
    { sourceKey: 'AbstractPipeline', className: 'java.util.stream.AbstractPipeline', sourcePath: 'jdk/src/share/classes/java/util/stream/AbstractPipeline.java' },
    { sourceKey: 'ReduceOps', className: 'java.util.stream.ReduceOps', sourcePath: 'jdk/src/share/classes/java/util/stream/ReduceOps.java' },
    { sourceKey: 'SliceOps', className: 'java.util.stream.SliceOps', sourcePath: 'jdk/src/share/classes/java/util/stream/SliceOps.java' },
    { sourceKey: 'Spliterators', className: 'java.util.Spliterators', sourcePath: 'jdk/src/share/classes/java/util/Spliterators.java' },
    { sourceKey: 'ForEachOps', className: 'java.util.stream.ForEachOps', sourcePath: 'jdk/src/share/classes/java/util/stream/ForEachOps.java' }
  ],
  '17': [
    { sourceKey: 'Stream', className: 'java.util.stream.Stream', sourcePath: 'src/java.base/share/classes/java/util/stream/Stream.java' },
    { sourceKey: 'ReferencePipeline', className: 'java.util.stream.ReferencePipeline', sourcePath: 'src/java.base/share/classes/java/util/stream/ReferencePipeline.java' },
    { sourceKey: 'AbstractPipeline', className: 'java.util.stream.AbstractPipeline', sourcePath: 'src/java.base/share/classes/java/util/stream/AbstractPipeline.java' },
    { sourceKey: 'ReduceOps', className: 'java.util.stream.ReduceOps', sourcePath: 'src/java.base/share/classes/java/util/stream/ReduceOps.java' },
    { sourceKey: 'SliceOps', className: 'java.util.stream.SliceOps', sourcePath: 'src/java.base/share/classes/java/util/stream/SliceOps.java' },
    { sourceKey: 'Spliterators', className: 'java.util.Spliterators', sourcePath: 'src/java.base/share/classes/java/util/Spliterators.java' },
    { sourceKey: 'ForEachOps', className: 'java.util.stream.ForEachOps', sourcePath: 'src/java.base/share/classes/java/util/stream/ForEachOps.java' },
    { sourceKey: 'WhileOps', className: 'java.util.stream.WhileOps', sourcePath: 'src/java.base/share/classes/java/util/stream/WhileOps.java' }
  ],
  '21': [
    { sourceKey: 'Stream', className: 'java.util.stream.Stream', sourcePath: 'src/java.base/share/classes/java/util/stream/Stream.java' },
    { sourceKey: 'ReferencePipeline', className: 'java.util.stream.ReferencePipeline', sourcePath: 'src/java.base/share/classes/java/util/stream/ReferencePipeline.java' },
    { sourceKey: 'AbstractPipeline', className: 'java.util.stream.AbstractPipeline', sourcePath: 'src/java.base/share/classes/java/util/stream/AbstractPipeline.java' },
    { sourceKey: 'ReduceOps', className: 'java.util.stream.ReduceOps', sourcePath: 'src/java.base/share/classes/java/util/stream/ReduceOps.java' },
    { sourceKey: 'SliceOps', className: 'java.util.stream.SliceOps', sourcePath: 'src/java.base/share/classes/java/util/stream/SliceOps.java' },
    { sourceKey: 'Spliterators', className: 'java.util.Spliterators', sourcePath: 'src/java.base/share/classes/java/util/Spliterators.java' },
    { sourceKey: 'ForEachOps', className: 'java.util.stream.ForEachOps', sourcePath: 'src/java.base/share/classes/java/util/stream/ForEachOps.java' },
    { sourceKey: 'WhileOps', className: 'java.util.stream.WhileOps', sourcePath: 'src/java.base/share/classes/java/util/stream/WhileOps.java' }
  ]
}
const LOADER_SOURCES: Record<JdkComparisonVersion, JdkSourceCoordinate[]> = {
  '8': [
    { sourceKey: 'ClassLoader', className: 'java.lang.ClassLoader', sourcePath: 'jdk/src/share/classes/java/lang/ClassLoader.java' },
    { sourceKey: 'ServiceLoader', className: 'java.util.ServiceLoader', sourcePath: 'jdk/src/share/classes/java/util/ServiceLoader.java' }
  ],
  '17': [
    { sourceKey: 'ClassLoader', className: 'java.lang.ClassLoader', sourcePath: 'src/java.base/share/classes/java/lang/ClassLoader.java' },
    { sourceKey: 'ServiceLoader', className: 'java.util.ServiceLoader', sourcePath: 'src/java.base/share/classes/java/util/ServiceLoader.java' }
  ],
  '21': [
    { sourceKey: 'ClassLoader', className: 'java.lang.ClassLoader', sourcePath: 'src/java.base/share/classes/java/lang/ClassLoader.java' },
    { sourceKey: 'ServiceLoader', className: 'java.util.ServiceLoader', sourcePath: 'src/java.base/share/classes/java/util/ServiceLoader.java' }
  ]
}

const SYNCHRONIZED_SOURCES: Record<JdkComparisonVersion, JdkSourceCoordinate[]> = {
  '8': [
    { sourceKey: 'Object', className: 'java.lang.Object', sourcePath: 'jdk/src/share/classes/java/lang/Object.java' },
    { sourceKey: 'ObjectMonitor', className: 'hotspot.runtime.ObjectMonitor', sourcePath: 'hotspot/src/share/vm/runtime/objectMonitor.cpp' },
    { sourceKey: 'ObjectSynchronizer', className: 'hotspot.runtime.ObjectSynchronizer', sourcePath: 'hotspot/src/share/vm/runtime/synchronizer.cpp' }
  ],
  '17': [
    { sourceKey: 'Object', className: 'java.lang.Object', sourcePath: 'src/java.base/share/classes/java/lang/Object.java' },
    { sourceKey: 'ObjectMonitor', className: 'hotspot.runtime.ObjectMonitor', sourcePath: 'src/hotspot/share/runtime/objectMonitor.cpp' },
    { sourceKey: 'ObjectSynchronizer', className: 'hotspot.runtime.ObjectSynchronizer', sourcePath: 'src/hotspot/share/runtime/synchronizer.cpp' }
  ],
  '21': [
    { sourceKey: 'Object', className: 'java.lang.Object', sourcePath: 'src/java.base/share/classes/java/lang/Object.java' },
    { sourceKey: 'ObjectMonitor', className: 'hotspot.runtime.ObjectMonitor', sourcePath: 'src/hotspot/share/runtime/objectMonitor.cpp' },
    { sourceKey: 'ObjectSynchronizer', className: 'hotspot.runtime.ObjectSynchronizer', sourcePath: 'src/hotspot/share/runtime/synchronizer.cpp' }
  ]
}

const HASH_MAP_TOPIC: JdkComparisonTopic = {
  id: 'hashmap',
  sourceTopicId: 'openjdk8-java-util-hashmap',
  title: 'HashMap',
  packageName: 'java.util',
  question: '核心桶结构基本不变时，为什么仍要关注查询入口、集合视图和反序列化边界？',
  conclusion: 'JDK 8 到 21 没有推翻数组、链表、红黑树骨架；变化主要集中在调用边界、数组导出、构造便利性与反序列化防护。',
  sources: HASH_MAP_SOURCES,
  timeline: [
    { version: '8', title: '经典桶模型定型', summary: 'hash、putVal、resize、treeifyBin 组成今天仍在使用的主干。' },
    { version: '17', title: '查询入口与集合视图收敛', summary: 'getNode 内聚 hash 计算，并为 key/value 视图提供专用 toArray。' },
    { version: '21', title: '容量工厂与反序列化强化', summary: '加入 newHashMap，并恢复显式读取、归一化 loadFactor。' }
  ],
  differences: [
    {
      id: 'hashmap-get-node-signature', title: 'getNode 不再要求调用方传 hash', kind: 'signature',
      summary: '查询入口由 getNode(int hash, Object key) 收敛为 getNode(Object key)，hash 计算移入方法内部。',
      reason: '所有查询调用方只表达“按 key 查找”，扰动 hash 的计算职责不再散落在 containsKey、get 等入口。',
      migrationImpact: '正常业务代码不受影响；维护 JDK 补丁、调试脚本或依赖深反射的工具需要调整定位点。',
      states: {
        '8': { sourceKey: 'HashMap', line: 556, symbol: 'get / getNode(int, Object)', fingerprint: 'caller-passes-hash', code: 'public V get(Object key) {\n    return (e = getNode(hash(key), key)) == null ? null : e.value;\n}\nfinal Node<K,V> getNode(int hash, Object key) { ... }', note: '调用方先执行 hash(key)，再把 hash 与 key 一起交给 getNode。' },
        '17': { sourceKey: 'HashMap', line: 553, symbol: 'get / getNode(Object)', fingerprint: 'get-node-computes-hash', code: 'public V get(Object key) {\n    return (e = getNode(key)) == null ? null : e.value;\n}\nfinal Node<K,V> getNode(Object key) {\n    int hash = (key == null) ? 0 : hash(key);\n    ...\n}', note: 'getNode 内部统一得到 hash，调用点更短。' },
        '21': { sourceKey: 'HashMap', line: 562, symbol: 'get / getNode(Object)', fingerprint: 'get-node-computes-hash', code: 'public V get(Object key) {\n    return (e = getNode(key)) == null ? null : e.value;\n}\nfinal Node<K,V> getNode(Object key) {\n    int hash = (key == null) ? 0 : hash(key);\n    ...\n}', note: 'JDK 21 延续 JDK 17 的职责划分。' }
      }
    },
    {
      id: 'hashmap-view-to-array', title: 'keySet / values 使用专用 toArray', kind: 'implementation',
      summary: 'JDK 17 起 HashMap 自己准备目标数组，并直接遍历 table 填充 key 或 value。',
      reason: '集合视图知道底层 size 与桶布局，可以避开通用 AbstractCollection.toArray 的迭代器路径。',
      migrationImpact: '返回数组契约不变；性能分析时应进入 keysToArray / valuesToArray，而不是只看 AbstractCollection。',
      states: {
        '17': { sourceKey: 'HashMap', line: 921, symbol: 'prepareArray / keysToArray', fingerprint: 'specialized-view-array', code: 'final <T> T[] prepareArray(T[] a) { ... }\n<T> T[] keysToArray(T[] a) {\n    for (Node<K,V> e : table)\n        for (; e != null; e = e.next) r[idx++] = e.key;\n    return a;\n}', note: 'KeySet 与 Values 分别走 keysToArray、valuesToArray。' },
        '21': { sourceKey: 'HashMap', line: 930, symbol: 'prepareArray / keysToArray', fingerprint: 'specialized-view-array', code: 'final <T> T[] prepareArray(T[] a) { ... }\n<T> T[] keysToArray(T[] a) {\n    for (Node<K,V> e : table)\n        for (; e != null; e = e.next) r[idx++] = e.key;\n    return a;\n}', note: 'JDK 21 保留专用数组导出路径。' }
      }
    },
    {
      id: 'hashmap-read-object-hardening', title: 'readObject 对 loadFactor 的处理往返演进', kind: 'implementation',
      summary: 'JDK 8 显式读取并钳制字段；17 依赖 defaultReadObject，只钳制容量计算的局部值；21 又显式读取并写回归一化值。',
      reason: '反序列化既要兼容旧数据，又要限制异常负载因子对容量与后续扩容的影响。',
      migrationImpact: '字节格式兼容，但极端 loadFactor 的对象状态可能不同；兼容测试应覆盖 0.25 以下与 4.0 以上的合法输入。',
      states: {
        '8': { sourceKey: 'HashMap', line: 1374, symbol: 'readObject', fingerprint: 'read-fields-clamp-unsafe', code: 'GetField fields = s.readFields();\nfloat lf = fields.get("loadFactor", 0.75f);\nlf = Math.min(Math.max(0.25f, lf), 4.0f);\nUnsafeHolder.putLoadFactor(this, lf);', note: '读取字段后把归一化值写回 final loadFactor。' },
        '17': { sourceKey: 'HashMap', line: 1507, symbol: 'readObject', fingerprint: 'default-read-local-clamp', code: 's.defaultReadObject();\nif (loadFactor <= 0 || Float.isNaN(loadFactor)) ...\nfloat lf = Math.min(Math.max(0.25f, loadFactor), 4.0f);\nfloat fc = (float)mappings / lf + 1.0f;', note: '对象字段保留合法原值，局部 lf 只参与容量计算。' },
        '21': { sourceKey: 'HashMap', line: 1516, symbol: 'readObject', fingerprint: 'read-fields-math-clamp-unsafe', code: 'GetField fields = s.readFields();\nfloat lf = fields.get("loadFactor", 0.75f);\nlf = Math.clamp(lf, 0.25f, 4.0f);\nUnsafeHolder.putLoadFactor(this, lf);', note: '重新显式读取并写回，使用 Math.clamp 表达边界。' }
      }
    },
    {
      id: 'hashmap-new-hash-map', title: '按预期映射数创建 HashMap', kind: 'signature',
      summary: 'JDK 21 增加 newHashMap(int numMappings)，调用方不再手算负载因子下的初始容量。',
      reason: 'new HashMap<>(n) 的参数是桶容量而不是计划放入的元素数，静态工厂消除常见误解。',
      migrationImpact: '面向 JDK 21 可直接使用；需要兼容 8/17 的库仍应保留容量换算或兼容工具方法。',
      states: {
        '21': { sourceKey: 'HashMap', line: 2580, symbol: 'newHashMap(int)', fingerprint: 'new-hashmap-factory', code: 'public static <K, V> HashMap<K, V> newHashMap(int numMappings) {\n    return new HashMap<>(calculateHashMapCapacity(numMappings));\n}', note: '参数直接表达预期映射数，内部统一换算容量。' }
      }
    }
  ],
  migrationChecklist: ['不要把 putVal / resize 主干稳定误解为源码完全未变。', '跨版本打断点先确认 getNode 签名与 readObject 行号。', '公共库使用 newHashMap 前确认最低运行版本。'],
  demoTitle: '一次 get 调用的职责如何移动',
  demoSteps: [
    { title: '调用入口', method: 'get(key)', description: '先观察调用方是否负责计算扰动 hash。', states: { '8': 'get 先执行 hash(key)，传入 hash + key', '17': 'get 只传 key，职责更单一', '21': 'get 只传 key，延续 JDK 17' } },
    { title: '定位桶', method: 'getNode(...)', description: '三版最终仍用 (n - 1) & hash 选桶，变化在入口边界。', states: { '8': 'getNode 接收已计算的 hash', '17': 'getNode 内部计算 hash', '21': 'getNode 内部计算 hash' } },
    { title: '核对断点', method: 'source breakpoint', description: '业务 get 行为不变，调试脚本需按版本选择签名。', states: { '8': 'getNode(int, Object)', '17': 'getNode(Object)', '21': 'getNode(Object)' } }
  ]
}
const CONCURRENT_HASH_MAP_TOPIC: JdkComparisonTopic = {
  id: 'concurrent-hashmap',
  sourceTopicId: 'openjdk8-java-util-concurrent-concurrenthashmap',
  title: 'ConcurrentHashMap',
  packageName: 'java.util.concurrent',
  question: '无锁读、空桶 CAS、桶头 synchronized 的主协议稳定后，底层原子操作和树桶等待为什么仍持续演进？',
  conclusion: '宏观并发协议保持稳定，变化集中在内部原子 API、视图条件删除、树桶等待者发布和类型层级约束。',
  sources: CONCURRENT_HASH_MAP_SOURCES,
  timeline: [
    { version: '8', title: 'CAS + 桶锁模型落地', summary: 'Node 数组、ForwardingNode、TreeBin 与协作扩容构成基线。' },
    { version: '17', title: '原子 API 与视图语义加固', summary: '切换内部 Unsafe，并为值/条目视图实现条件删除。' },
    { version: '21', title: '树桶等待与类型边界收紧', summary: '等待者字段用 CAS 发布，视图层级改 sealed，KeySetView 变 final。' }
  ],
  differences: [
    {
      id: 'chm-unsafe-cas-api', title: 'Unsafe 迁入内部模块，CAS 方法改名', kind: 'implementation',
      summary: 'JDK 8 的 sun.misc.Unsafe.compareAndSwapObject 在 17/21 中变为 jdk.internal.misc.Unsafe.compareAndSetReference。',
      reason: '模块化后核心类使用受控的内部 Unsafe，compareAndSet 命名也与 VarHandle 原子语义对齐。',
      migrationImpact: '公共行为不变；复制 JDK 内部 CAS 代码的项目不能把 Unsafe 调用当成稳定 API。',
      states: {
        '8': { sourceKey: 'ConcurrentHashMap', line: 758, symbol: 'casTabAt', fingerprint: 'sun-unsafe-cas', code: 'private static final sun.misc.Unsafe U;\nreturn U.compareAndSwapObject(\n    tab, ((long)i << ASHIFT) + ABASE, c, v);', note: '使用 sun.misc.Unsafe 与 compareAndSwapObject。' },
        '17': { sourceKey: 'ConcurrentHashMap', line: 763, symbol: 'casTabAt', fingerprint: 'internal-unsafe-cas', code: 'private static final Unsafe U = Unsafe.getUnsafe();\nreturn U.compareAndSetReference(\n    tab, ((long)i << ASHIFT) + ABASE, c, v);', note: 'Unsafe 来自 jdk.internal.misc，引用 CAS 改用 compareAndSetReference。' },
        '21': { sourceKey: 'ConcurrentHashMap', line: 763, symbol: 'casTabAt', fingerprint: 'internal-unsafe-cas', code: 'private static final Unsafe U = Unsafe.getUnsafe();\nreturn U.compareAndSetReference(\n    tab, ((long)i << ASHIFT) + ABASE, c, v);', note: 'JDK 21 延续这套内部原子 API。' }
      }
    },
    {
      id: 'chm-view-remove-if', title: '值视图与条目视图覆盖 removeIf', kind: 'implementation',
      summary: 'JDK 17 起 ValuesView 和 EntrySetView 把 removeIf 下沉到 map.removeValueIf / removeEntryIf。',
      reason: '谓词判断后映射可能已被更新，专用路径可按观察到的 key/value 条件删除，避免按 key 无条件移除新值。',
      migrationImpact: '并发更新与 removeIf 交错时语义更稳健；依赖 JDK 8 竞态结果的测试应重写。',
      states: {
        '17': { sourceKey: 'ConcurrentHashMap', line: 4762, symbol: 'ValuesView.removeIf', fingerprint: 'conditional-view-remove', code: 'public boolean removeIf(Predicate<? super V> filter) {\n    return map.removeValueIf(filter);\n}\n// EntrySetView 委托 removeEntryIf', note: '真正删除时再次校验映射值，缩小 check-then-remove 竞态。' },
        '21': { sourceKey: 'ConcurrentHashMap', line: 4765, symbol: 'ValuesView.removeIf', fingerprint: 'conditional-view-remove', code: 'public boolean removeIf(Predicate<? super V> filter) {\n    return map.removeValueIf(filter);\n}\n// EntrySetView 委托 removeEntryIf', note: 'JDK 21 保留条件删除实现。' }
      }
    },
    {
      id: 'chm-treebin-waiter-cas', title: 'TreeBin 等待线程改用 CAS 发布', kind: 'implementation',
      summary: 'JDK 21 的 contendedLock 不再直接写 waiter，而是通过 WAITERTHREAD 偏移执行 compareAndSetReference。',
      reason: '多个竞争线程可能同时观察等待位，CAS 让等待者注册与清理只在匹配状态时发生，避免覆盖其他线程。',
      migrationImpact: '业务 API 不变；分析树桶高竞争卡顿时，JDK 21 应同时观察 lockState、waiter 与 WAITERTHREAD CAS。',
      states: {
        '8': { sourceKey: 'ConcurrentHashMap', line: 2808, symbol: 'TreeBin.contendedLock', fingerprint: 'plain-waiter-write', code: 'boolean waiting = false;\n...\nwaiting = true;\nwaiter = Thread.currentThread();\n...\nif (waiting) waiter = null;', note: '等待线程直接写入和清空 waiter。' },
        '17': { sourceKey: 'ConcurrentHashMap', line: 2864, symbol: 'TreeBin.contendedLock', fingerprint: 'plain-waiter-write', code: 'boolean waiting = false;\n...\nwaiting = true;\nwaiter = Thread.currentThread();\n...\nif (waiting) waiter = null;', note: 'JDK 17 仍然直接发布 waiter。' },
        '21': { sourceKey: 'ConcurrentHashMap', line: 2864, symbol: 'TreeBin.contendedLock', fingerprint: 'cas-waiter-publication', code: 'Thread current = Thread.currentThread(), w;\n...\nU.compareAndSetReference(this, WAITERTHREAD, null, current);\n...\nU.compareAndSetReference(this, WAITERTHREAD, current, null);', note: '等待者注册与清理都受 CAS 保护。' }
      }
    },
    {
      id: 'chm-sealed-view-hierarchy', title: 'CollectionView 变 sealed，KeySetView 变 final', kind: 'signature',
      summary: 'JDK 21 把内部 CollectionView 的实现限制为三个视图类，并禁止继承公开的 KeySetView。',
      reason: '固定层级有利于维护不变量；KeySetView 构造器本就非公开，final 表明它不是扩展点。',
      migrationImpact: '常规 keySet 使用不受影响；通过特殊手段继承 KeySetView 的代码在 JDK 21 无法编译或加载。',
      states: {
        '8': { sourceKey: 'ConcurrentHashMap', line: 4371, symbol: 'CollectionView / KeySetView', fingerprint: 'open-view-hierarchy', code: 'abstract static class CollectionView<K,V,E> ...\npublic static class KeySetView<K,V>\n    extends CollectionView<K,V,K> ...', note: '类型声明没有 sealed / final 限制。' },
        '17': { sourceKey: 'ConcurrentHashMap', line: 4419, symbol: 'CollectionView / KeySetView', fingerprint: 'open-view-hierarchy', code: 'abstract static class CollectionView<K,V,E> ...\npublic static class KeySetView<K,V>\n    extends CollectionView<K,V,K> ...', note: 'JDK 17 仍保留开放声明。' },
        '21': { sourceKey: 'ConcurrentHashMap', line: 4419, symbol: 'CollectionView / KeySetView', fingerprint: 'sealed-view-hierarchy', code: 'abstract static sealed class CollectionView<K,V,E>\n    permits EntrySetView, KeySetView, ValuesView { ... }\npublic static final class KeySetView<K,V> ...', note: '允许的子类写入声明，KeySetView 明确 final。' }
      }
    }
  ],
  migrationChecklist: ['不要依赖 sun.misc.Unsafe 或 JDK 内部字段偏移。', '并发 removeIf 测试应验证只删除仍满足谓词的映射。', '树桶诊断要区分桶头 synchronized 与 TreeBin 状态锁。'],
  demoTitle: '树桶竞争时等待者怎样获得写锁',
  demoSteps: [
    { title: '发现写锁繁忙', method: 'TreeBin.contendedLock', description: '竞争线程读取 lockState，发现 WRITER 或 READER 尚未释放。', states: { '8': 'waiting=false，准备设置 WAITER 位', '17': 'waiting=false，准备设置 WAITER 位', '21': 'current 已缓存，准备设置 WAITER 位' } },
    { title: '登记等待者', method: 'waiter publication', description: 'JDK 21 把 waiter 字段也纳入原子状态转换。', states: { '8': 'waiter = Thread.currentThread()', '17': 'waiter = Thread.currentThread()', '21': 'CAS(waiter, null, current)' } },
    { title: '获得并清理', method: 'compareAndSet / unpark', description: '获得 WRITER 后清理自己的记录，读线程释放时仍按 waiter 唤醒。', states: { '8': 'waiting 为 true 时直接 waiter=null', '17': 'waiting 为 true 时直接 waiter=null', '21': 'CAS(waiter, current, null)' } }
  ]
}
const THREAD_LOCAL_TOPIC: JdkComparisonTopic = {
  id: 'thread-local',
  sourceTopicId: 'openjdk8-java-lang-threadlocal',
  title: 'ThreadLocal',
  packageName: 'java.lang',
  question: '弱 key、强 value、开放寻址都未改变，虚拟线程为什么仍迫使 ThreadLocal 增加“线程是谁”的显式边界？',
  conclusion: 'JDK 8 到 17 主要优化弱引用判断；JDK 21 为虚拟线程引入 carrier 访问与诊断路径，但惰性清理规则仍要求应用主动 remove。',
  sources: THREAD_LOCAL_SOURCES,
  timeline: [
    { version: '8', title: '弱 key + 开放寻址基线', summary: 'Entry 继承 WeakReference，value 仍由线程内 map 强引用。' },
    { version: '17', title: '弱引用判断更直接', summary: '内部查找使用 Reference.refersTo，减少不必要的 referent 暴露。' },
    { version: '21', title: '虚拟线程边界显式化', summary: 'get/set 内核接收 Thread，并增加 carrier 访问与追踪诊断。' }
  ],
  differences: [
    {
      id: 'threadlocal-refers-to', title: 'Entry 匹配由 get() 改为 refersTo()', kind: 'implementation',
      summary: 'JDK 17 起直接询问弱引用是否指向 key 或 null，而不是先取出 referent 再做恒等比较。',
      reason: 'Reference.refersTo 精确表达“只比较、不暴露引用对象”的意图，也给引用处理实现留下优化空间。',
      migrationImpact: '查找与过期槽清理语义不变；调试时不要把源码里的 WeakReference.get() 误认成 ThreadLocal.get()。',
      states: {
        '8': { sourceKey: 'ThreadLocal', line: 434, symbol: 'ThreadLocalMap.getEntry', fingerprint: 'weak-reference-get', code: 'Entry e = table[i];\nif (e != null && e.get() == key) return e;\n...\nif (e.get() == null) expungeStaleEntry(i);', note: '通过 WeakReference.get 取得 referent 后比较。' },
        '17': { sourceKey: 'ThreadLocal', line: 433, symbol: 'ThreadLocalMap.getEntry', fingerprint: 'weak-reference-refers-to', code: 'Entry e = table[i];\nif (e != null && e.refersTo(key)) return e;\n...\nif (e.refersTo(null)) expungeStaleEntry(i);', note: '直接判断弱引用是否指向目标或已经清空。' },
        '21': { sourceKey: 'ThreadLocal', line: 498, symbol: 'ThreadLocalMap.getEntry', fingerprint: 'weak-reference-refers-to', code: 'Entry e = table[i];\nif (e != null && e.refersTo(key)) return e;\n...\nif (e.refersTo(null)) expungeStaleEntry(i);', note: 'JDK 21 延续 refersTo 判断。' }
      }
    },
    {
      id: 'threadlocal-thread-parameter', title: 'get / set 内核显式接收 Thread', kind: 'implementation',
      summary: 'JDK 21 把当前线程的取得留在公共入口，内部 get(Thread)、set(Thread, value)、remove(Thread) 统一操作指定线程的 map。',
      reason: '公共 ThreadLocal 与内部 CarrierThreadLocal 可复用 map 操作，只在选择虚拟线程还是载体线程时分流。',
      migrationImpact: '公共行为不变；源码调试时应继续进入带 Thread 参数的私有重载。',
      states: {
        '8': { sourceKey: 'ThreadLocal', line: 161, symbol: 'get / setInitialValue', fingerprint: 'current-thread-inline', code: 'public T get() {\n    Thread t = Thread.currentThread();\n    ThreadLocalMap map = getMap(t);\n    ...\n    return setInitialValue();\n}', note: '公共方法内部直接取得 currentThread。' },
        '17': { sourceKey: 'ThreadLocal', line: 161, symbol: 'get / setInitialValue', fingerprint: 'current-thread-inline', code: 'public T get() {\n    Thread t = Thread.currentThread();\n    ThreadLocalMap map = getMap(t);\n    ...\n    return setInitialValue();\n}', note: 'JDK 17 仍由公共方法绑定当前线程。' },
        '21': { sourceKey: 'ThreadLocal', line: 171, symbol: 'get / get(Thread)', fingerprint: 'thread-parameter-core', code: 'public T get() {\n    return get(Thread.currentThread());\n}\nprivate T get(Thread t) {\n    ThreadLocalMap map = getMap(t);\n    ...\n}', note: '线程选择与 map 操作被拆成两层。' }
      }
    },
    {
      id: 'threadlocal-carrier-access', title: '增加 CarrierThreadLocal 内部访问路径', kind: 'signature',
      summary: 'JDK 21 增加 getCarrierThreadLocal、setCarrierThreadLocal、removeCarrierThreadLocal 等包级方法。',
      reason: '虚拟线程运行在载体线程上，JDK 内部少量状态必须跟随 carrier，而不是跟随当前虚拟线程。',
      migrationImpact: '这些不是公共 API；业务上下文通常仍绑定虚拟线程，并应评估 ScopedValue 等结构化方案。',
      states: {
        '21': { sourceKey: 'ThreadLocal', line: 179, symbol: 'getCarrierThreadLocal', fingerprint: 'carrier-thread-accessors', code: 'T getCarrierThreadLocal() {\n    assert this instanceof CarrierThreadLocal<T>;\n    return get(Thread.currentCarrierThread());\n}', note: '显式选择 carrier，并复用 get(Thread) 内核。' }
      }
    },
    {
      id: 'threadlocal-virtual-trace', title: '增加虚拟线程 ThreadLocal 追踪开关', kind: 'implementation',
      summary: 'JDK 21 读取 jdk.traceVirtualThreadLocals，并在虚拟线程首次初始化或 set 时输出调用栈。',
      reason: '大量短生命周期虚拟线程若依赖隐式线程上下文，会增加内存与诊断成本，追踪开关可定位写入来源。',
      migrationImpact: '迁移虚拟线程时可在测试环境开启该属性盘点 ThreadLocal；生产开启前评估日志量。',
      states: {
        '21': { sourceKey: 'ThreadLocal', line: 82, symbol: 'TRACE_VTHREAD_LOCALS', fingerprint: 'virtual-thread-local-trace', code: 'private static final boolean TRACE_VTHREAD_LOCALS =\n    traceVirtualThreadLocals();\n...\nif (TRACE_VTHREAD_LOCALS) {\n    dumpStackIfVirtualThread();\n}', note: '只在当前线程是 VirtualThread 时打印栈。' }
      }
    }
  ],
  migrationChecklist: ['弱 key 不等于 value 立即释放，线程池任务仍必须 finally remove。', '虚拟线程迁移先盘点 ThreadLocal 数量、对象大小与继承需求。', 'CarrierThreadLocal 是 JDK 内部机制，不是业务扩展点。'],
  demoTitle: '同一次 get，究竟选择哪一个线程的 map',
  demoSteps: [
    { title: '选择线程', method: 'get()', description: '公共入口读取当前执行线程；JDK 21 内部 carrier 入口才显式选择载体。', states: { '8': 't = Thread.currentThread()', '17': 't = Thread.currentThread()', '21': 'get(currentThread)；内部可 get(currentCarrierThread)' } },
    { title: '探测槽位', method: 'ThreadLocalMap.getEntry', description: '三版都开放寻址，弱引用判断写法在 17 起改变。', states: { '8': 'e.get() == key', '17': 'e.refersTo(key)', '21': 'e.refersTo(key)' } },
    { title: '缺失时初始化', method: 'setInitialValue', description: 'JDK 21 向下传 Thread，并可对虚拟线程写入发出诊断栈。', states: { '8': 'setInitialValue() 再取 currentThread', '17': 'setInitialValue() 再取 currentThread', '21': 'setInitialValue(t) + 可选 trace' } }
  ]
}
const COMPLETABLE_FUTURE_TOPIC: JdkComparisonTopic = {
  id: 'completable-future',
  sourceTopicId: 'openjdk8-java-util-concurrent-completablefuture',
  title: 'CompletableFuture',
  packageName: 'java.util.concurrent',
  question: '依赖栈与 result 状态机保持兼容时，JDK 9 之后如何补上执行器定制、超时和非阻塞观察能力？',
  conclusion: 'JDK 8 奠定 Completion 栈；17 已包含定制、超时与 VarHandle 实现；21 再对接 Future 的即时结果和状态观察。',
  sources: COMPLETABLE_FUTURE_SOURCES,
  timeline: [
    { version: '8', title: 'Completion 栈基线', summary: 'result、stack、Uni/Bi Completion 与 ForkJoinPool 组成异步编排核心。' },
    { version: '17', title: '可定制、可超时、可协作等待', summary: '具备 JDK 9+ API，状态改用 VarHandle，等待时可协助 ForkJoinPool。' },
    { version: '21', title: 'Future 状态观察补齐', summary: '实现 resultNow、exceptionNow、state，无需阻塞即可读取已完成结果。' }
  ],
  differences: [
    {
      id: 'cf-customization-hooks', title: '增加子类定制钩子', kind: 'signature',
      summary: 'JDK 17/21 提供 newIncompleteFuture 与 defaultExecutor，异步阶段通过它们创建同类下游并选择执行器。',
      reason: '自定义子类需要让整条依赖链保持自己的类型和线程策略，而不是每次显式传 executor。',
      migrationImpact: '从 JDK 8 迁移后可删除部分包装层；覆盖时须保证 executor 有独立线程，并返回正确子类实例。',
      states: {
        '17': { sourceKey: 'CompletableFuture', line: 2597, symbol: 'newIncompleteFuture / defaultExecutor', fingerprint: 'customization-hooks', code: 'public <U> CompletableFuture<U> newIncompleteFuture() {\n    return new CompletableFuture<U>();\n}\npublic Executor defaultExecutor() {\n    return ASYNC_POOL;\n}', note: '非静态阶段方法会调用这些虚方法。' },
        '21': { sourceKey: 'CompletableFuture', line: 2643, symbol: 'newIncompleteFuture / defaultExecutor', fingerprint: 'customization-hooks', code: 'public <U> CompletableFuture<U> newIncompleteFuture() {\n    return new CompletableFuture<U>();\n}\npublic Executor defaultExecutor() {\n    return ASYNC_POOL;\n}', note: 'JDK 21 延续子类定制协议。' }
      }
    },
    {
      id: 'cf-timeout-api', title: '增加超时完成与延迟执行器', kind: 'signature',
      summary: 'JDK 17/21 提供 orTimeout、completeOnTimeout、delayedExecutor，并由单例 Delayer 触发。',
      reason: '超时是异步编排基础能力，集中调度比每个调用点创建 ScheduledExecutorService 更轻量。',
      migrationImpact: 'orTimeout 会修改当前 CompletableFuture，而不是返回独立超时副本；须核对共享引用的完成语义。',
      states: {
        '17': { sourceKey: 'CompletableFuture', line: 2703, symbol: 'orTimeout / Delayer', fingerprint: 'timeout-api-delayer', code: 'public CompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {\n    if (result == null)\n        whenComplete(new Canceller(Delayer.delay(\n            new Timeout(this), timeout, unit)));\n    return this;\n}', note: '定时任务尝试异常完成同一个 future。' },
        '21': { sourceKey: 'CompletableFuture', line: 2749, symbol: 'orTimeout / Delayer', fingerprint: 'timeout-api-delayer', code: 'public CompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {\n    if (result == null)\n        whenComplete(new Canceller(Delayer.delay(\n            new Timeout(this), timeout, unit)));\n    return this;\n}', note: 'JDK 21 核心超时协议不变。' }
      }
    },
    {
      id: 'cf-varhandle-cooperative-wait', title: 'Unsafe + 自旋转为 VarHandle + 协作阻塞', kind: 'implementation',
      summary: 'JDK 8 保存 Unsafe 偏移并先自旋；17/21 用 VarHandle CAS，等待时调用 ForkJoinPool.helpAsyncBlocker。',
      reason: 'VarHandle 提供标准化内存语义；ForkJoin worker 等待时协助任务，可降低池内相互等待导致的饥饿。',
      migrationImpact: 'join/get 契约不变，但线程 dump 与性能画像会不同；不要把固定自旋次数写入容量估算。',
      states: {
        '8': { sourceKey: 'CompletableFuture', line: 1719, symbol: 'waitingGet / Unsafe mechanics', fingerprint: 'unsafe-spin-wait', code: 'int spins = -1;\nif (spins < 0) spins = SPINS;\nelse if (spins > 0) ...\n...\nprivate static final sun.misc.Unsafe UNSAFE;', note: '先按 CPU 数决定是否自旋，再把 Signaller 压栈并 park。' },
        '17': { sourceKey: 'CompletableFuture', line: 1877, symbol: 'waitingGet / VarHandle mechanics', fingerprint: 'varhandle-help-async-blocker', code: 'q = new Signaller(interruptible, 0L, 0L);\nif (Thread.currentThread() instanceof ForkJoinWorkerThread)\n    ForkJoinPool.helpAsyncBlocker(defaultExecutor(), q);\n...\nprivate static final VarHandle RESULT;', note: '不再使用 SPINS，ForkJoin worker 可协助推进任务。' },
        '21': { sourceKey: 'CompletableFuture', line: 1877, symbol: 'waitingGet / VarHandle mechanics', fingerprint: 'varhandle-help-async-blocker', code: 'q = new Signaller(interruptible, 0L, 0L);\nif (Thread.currentThread() instanceof ForkJoinWorkerThread)\n    ForkJoinPool.helpAsyncBlocker(defaultExecutor(), q);\n...\nprivate static final VarHandle RESULT;', note: 'JDK 21 延续协作阻塞与 VarHandle。' }
      }
    },
    {
      id: 'cf-future-state-api', title: '实现即时结果、异常与状态查询', kind: 'signature',
      summary: 'JDK 21 覆盖 Future.resultNow、exceptionNow、state，直接解码内部 result，无需 get/join 阻塞。',
      reason: '批量任务与监控代码需要在已知完成后读取结果或失败原因，而不是用零超时 get 模拟。',
      migrationImpact: '调用前仍应确认 state；未完成或状态不匹配会抛 IllegalStateException，不是返回 null。',
      states: {
        '21': { sourceKey: 'CompletableFuture', line: 2138, symbol: 'resultNow / exceptionNow / state', fingerprint: 'future-state-observers', code: 'public T resultNow() { ... }\npublic Throwable exceptionNow() { ... }\npublic State state() {\n    Object r = result;\n    if (r == null) return State.RUNNING;\n    ...\n}', note: '把 null、NIL、AltResult 与取消异常映射为 Future.State。' }
      }
    }
  ],
  migrationChecklist: ['区分返回新阶段的方法与修改当前对象的 orTimeout。', '自定义 defaultExecutor 时验证阻塞任务与拒绝策略。', 'resultNow / exceptionNow 只适合已确认状态后的读取。'],
  demoTitle: '等待一个未完成阶段时，线程做了什么',
  demoSteps: [
    { title: '检查 result', method: 'get / join', description: 'result 非 null 时直接解码；这里假设阶段未完成。', states: { '8': 'result == null，进入 waitingGet', '17': 'result == null，进入 waitingGet', '21': 'result == null；也可先用 state() 观察' } },
    { title: '准备等待节点', method: 'Signaller', description: '等待线程创建 Signaller 并尝试压入 Completion 栈。', states: { '8': '先执行 SPINS 次自旋', '17': 'ForkJoin worker 可 helpAsyncBlocker', '21': 'ForkJoin worker 可 helpAsyncBlocker' } },
    { title: '完成并唤醒', method: 'postComplete', description: '完成线程写 result、弹 Completion，并 unpark 等待者。', states: { '8': 'Unsafe CAS result / stack', '17': 'VarHandle CAS result / stack', '21': 'VarHandle CAS；state 变 SUCCESS/FAILED' } }
  ]
}

const SYNCHRONIZED_TOPIC: JdkComparisonTopic = {
  id: 'synchronized-monitor',
  sourceTopicId: 'openjdk8-synchronized-objectmonitor',
  title: 'synchronized / ObjectMonitor',
  packageName: 'java.lang + hotspot.runtime',
  question: 'Java 层 wait/notify 契约没有变化，为什么 HotSpot 的锁竞争路径仍需要按版本重新定位？',
  conclusion: 'monitorenter、monitorexit、wait 和 notify 的语义稳定；JDK 17 之后偏向锁退出历史舞台，ObjectSynchronizer 与 ObjectMonitor 的内部竞争路径也随之调整。',
  sources: SYNCHRONIZED_SOURCES,
  timeline: [
    { version: '8', title: '偏向锁参与快速路径', summary: '对象头、轻量级锁和膨胀 monitor 共同组成 synchronized 的多级入口。' },
    { version: '17', title: '偏向锁路径退出', summary: 'JDK 15 起默认关闭偏向锁，JDK 17 源码阅读应把重点放到轻量级与 monitor 竞争。' },
    { version: '21', title: 'monitor 竞争继续重构', summary: 'Java 层契约不变，HotSpot 私有字段、膨胀和唤醒细节仍不能跨版本照抄。' }
  ],
  differences: [
    {
      id: 'sync-biased-locking-path',
      title: '偏向锁快速路径退出主线',
      kind: 'implementation',
      summary: 'JDK 8 的进入逻辑会检查偏向锁标记；JDK 17/21 不再把偏向锁作为默认竞争路径。',
      reason: '偏向锁适合低竞争重复进入，但撤销与批量重偏向的维护成本让后续 HotSpot 更重视可预测的轻量级路径。',
      migrationImpact: 'synchronized 的 Java 代码无需修改；锁竞争性能分析不能继续套用 JDK 8 对象头状态图。',
      states: {
        '8': { sourceKey: 'ObjectSynchronizer', line: 215, symbol: 'fast_enter / biased locking', fingerprint: 'biased-locking-fast-path', code: 'if (UseBiasedLocking &&\n    (mark->has_bias_pattern() || mark->is_biased_anonymously())) {\n    // 尝试撤销或重偏向\n}', note: 'JDK 8 进入快速路径仍可能先处理偏向锁标记。' },
        '17': { sourceKey: 'ObjectSynchronizer', line: 255, symbol: 'enter / lightweight-or-monitor', fingerprint: 'biased-locking-removed', code: 'if (mark.is_neutral()) {\n    // 轻量级栈锁或后续 monitor 膨胀\n}\n// 不再进入偏向锁撤销分支', note: '偏向锁退出后，断点应转向轻量级锁和膨胀竞争。' },
        '21': { sourceKey: 'ObjectSynchronizer', line: 264, symbol: 'enter / lightweight-or-monitor', fingerprint: 'biased-locking-removed', code: 'if (mark.is_neutral()) {\n    // 轻量级锁或 monitor 竞争\n}\n// 继续沿新的 HotSpot 锁入口定位', note: 'JDK 21 延续无偏向锁主线，但私有实现仍需按 tag 核对。' }
      }
    },
    {
      id: 'sync-monitor-enter-owner',
      title: 'ObjectMonitor 进入参数与线程类型收紧',
      kind: 'signature',
      summary: '膨胀 monitor 的 enter/exit 入口在后续 HotSpot 中逐步使用更明确的 JavaThread 语义，不能只按 JDK 8 的 Thread* 搜索。',
      reason: '锁竞争、Safepoint 和 Java 线程生命周期信息都依赖更精确的线程类型，入口参数收紧有助于减少错误调用。',
      migrationImpact: '业务 API 不变；JNI、Serviceability Agent 和 native 调试脚本需要按版本更新方法签名。',
      states: {
        '8': { sourceKey: 'ObjectMonitor', line: 150, symbol: 'enter(Thread*)', fingerprint: 'object-monitor-thread-pointer', code: 'void ObjectMonitor::enter(TRAPS) {\n    Thread * const Self = THREAD;\n    // owner / recursions / EntryList\n}', note: 'JDK 8 通过宏参数取得当前线程。' },
        '17': { sourceKey: 'ObjectMonitor', line: 180, symbol: 'enter(JavaThread*)', fingerprint: 'object-monitor-java-thread', code: 'void ObjectMonitor::enter(JavaThread* current) {\n    // 以 JavaThread 作为 owner 竞争上下文\n}', note: '入口类型更明确，调试时要先确认当前线程对象。' },
        '21': { sourceKey: 'ObjectMonitor', line: 190, symbol: 'enter(JavaThread*)', fingerprint: 'object-monitor-java-thread', code: 'void ObjectMonitor::enter(JavaThread* current) {\n    // 继续处理 owner、重入与竞争者唤醒\n}', note: 'JDK 21 仍需使用对应版本的 monitor 私有入口。' }
      }
    },
    {
      id: 'sync-waitset-reacquire',
      title: 'wait 的释放与重新竞争仍是两个阶段',
      kind: 'implementation',
      summary: '三版都遵守“加入 WaitSet → 完整释放 → 被唤醒 → 重新竞争 → 恢复重入深度”，变化主要在 native/HotSpot 节点组织。',
      reason: 'notify 只改变等待者的竞争资格，不替它释放当前 owner；把两个阶段混为一次唤醒是排查死锁和假唤醒的常见误区。',
      migrationImpact: '业务条件循环写法跨版本稳定；线程 dump 中的 ObjectMonitor 字段和队列名称不能跨版本直接比较。',
      states: {
        '8': { sourceKey: 'ObjectMonitor', line: 425, symbol: 'wait / WaitSet', fingerprint: 'waitset-release-reacquire', code: 'AddWaiter(Self);\nexit(Self);\nParkEvent->park();\nenter(Self);', note: '等待者保存重入深度，释放 monitor 后进入 WaitSet。' },
        '17': { sourceKey: 'ObjectMonitor', line: 455, symbol: 'wait / WaitSet', fingerprint: 'waitset-release-reacquire', code: '// WaitSet 中等待\n// 被通知后转入重新竞争\nenter(current);', note: '语义不变，字段和队列维护位置已重构。' },
        '21': { sourceKey: 'ObjectMonitor', line: 470, symbol: 'wait / WaitSet', fingerprint: 'waitset-release-reacquire', code: '// 重新取得 monitor 后恢复递归深度\nrestore_recursions(current);', note: '继续把唤醒与重新取得区分开。' }
      }
    },
    {
      id: 'sync-object-native-contract',
      title: 'Object.wait/notify 的 Java 契约保持稳定',
      kind: 'signature',
      summary: '公共 wait、notify、notifyAll 仍是 native monitor 桥接入口；版本差异更多体现在注解、实现归属和 HotSpot 调试坐标。',
      reason: '稳定的 Java 契约让 synchronized 代码可以跨版本运行，同时把锁优化留在 JVM 内部完成。',
      migrationImpact: '不要依赖 native 方法行号或 ObjectMonitor 私有字段；迁移测试应验证条件循环、超时和中断语义。',
      states: {
        '8': { sourceKey: 'Object', line: 363, symbol: 'wait(long)', fingerprint: 'native-monitor-bridge', code: 'public final native void wait(long timeout)\n    throws InterruptedException;', note: 'Java 层把等待交给当前对象的 monitor。' },
        '17': { sourceKey: 'Object', line: 442, symbol: 'wait(long)', fingerprint: 'native-monitor-bridge-intrinsic', code: '@IntrinsicCandidate\npublic final native void wait(long timeout)\n    throws InterruptedException;', note: '增加 JVM intrinsic 标记，公共调用契约不变。' },
        '21': { sourceKey: 'Object', line: 459, symbol: 'wait(long)', fingerprint: 'native-monitor-bridge-intrinsic', code: '@IntrinsicCandidate\npublic final native void wait(long timeout)\n    throws InterruptedException;', note: 'JDK 21 延续 intrinsic 桥接入口。' }
      }
    }
  ],
  migrationChecklist: [
    '不要把 JDK 8 的偏向锁状态图直接套到 JDK 17/21。',
    '断点先从 Object.wait/notify 的 Java 契约进入，再按当前 tag 定位 ObjectSynchronizer。',
    '验证 wait 必须完整释放、被通知后重新竞争，并在条件循环中处理超时和中断。'
  ],
  demoTitle: '一次 wait 为什么要经过释放和重新竞争',
  demoSteps: [
    { title: '进入 monitor', method: 'monitorenter / ObjectMonitor::enter', description: '先观察 owner、重入深度和竞争路径。', states: { '8': '可能先检查偏向锁，再进入轻量级或膨胀路径', '17': '跳过偏向锁，进入轻量级或 monitor 路径', '21': '沿当前 HotSpot 的 monitor 入口继续定位' } },
    { title: '加入 WaitSet', method: 'Object.wait(long)', description: '等待线程保存重入深度并完整释放 monitor。', states: { '8': 'AddWaiter → exit → park', '17': 'WaitSet → release → park', '21': '保存递归深度后等待' } },
    { title: '唤醒后重竞争', method: 'notify / ObjectMonitor::enter', description: 'notify 不释放 owner，被通知线程仍需重新取得 monitor。', states: { '8': 'notify 只转移竞争资格', '17': '等待者转入重新竞争', '21': '重新取得后恢复重入深度' } }
  ]
}

const LOADER_TOPIC: JdkComparisonTopic = {
  id: 'classloader-service-loader',
  sourceTopicId: 'openjdk8-classloader-serviceloader',
  title: 'ClassLoader / ServiceLoader',
  packageName: 'java.lang + java.util',
  question: '模块系统加入后，双亲委派和 META-INF/services 为什么没有消失，而是扩展成两套可组合的查找空间？',
  conclusion: 'JDK 17/21 同时保留类路径兼容路径与模块层路径；ClassLoader 负责模块感知定位，ServiceLoader 先查模块服务目录，再兼容 META-INF/services。',
  sources: LOADER_SOURCES,
  timeline: [
    { version: '8', title: '类路径与扩展类加载器', summary: 'bootstrap / extension / application 层级，SPI 解析 META-INF/services。' },
    { version: '17', title: '模块层与平台类加载器', summary: 'platform loader 取代 extension 概念，加载与 SPI 都理解 Module。' },
    { version: '21', title: '调用者敏感边界加固', summary: '主体架构稳定，并行注册对反射/JNI 调用者做显式校验。' }
  ],
  differences: [
    {
      id: 'classloader-platform-loader', title: '增加 Platform ClassLoader', kind: 'signature',
      summary: 'JDK 17/21 提供 getPlatformClassLoader，表示 Java SE 与 JDK 模块的加载层。',
      reason: '模块化运行时不再以可安装扩展目录为核心模型，platform loader 成为 application loader 的父级。',
      migrationImpact: '不要依赖 sun.misc.Launcher.ExtClassLoader 名称或扩展目录；需要父加载器时调用公开 API。',
      states: {
        '17': { sourceKey: 'ClassLoader', line: 1839, symbol: 'getPlatformClassLoader', fingerprint: 'platform-class-loader', code: 'public static ClassLoader getPlatformClassLoader() {\n    SecurityManager sm = System.getSecurityManager();\n    ClassLoader loader = getBuiltinPlatformClassLoader();\n    ...\n    return loader;\n}', note: '公开获取内建 platform loader。' },
        '21': { sourceKey: 'ClassLoader', line: 1868, symbol: 'getPlatformClassLoader', fingerprint: 'platform-class-loader', code: 'public static ClassLoader getPlatformClassLoader() {\n    @SuppressWarnings("removal")\n    SecurityManager sm = System.getSecurityManager();\n    ClassLoader loader = getBuiltinPlatformClassLoader();\n    ...\n}', note: 'API 保持，安全管理器路径带移除期注解。' }
      }
    },
    {
      id: 'classloader-module-aware-load', title: '加载与查找入口理解 Module', kind: 'signature',
      summary: 'JDK 17/21 增加 loadClass(Module, String) 内部入口和 findClass(String moduleName, String name) 扩展点。',
      reason: '同一类加载器可服务多个命名模块，仅有二进制类名不足以表达目标归属。',
      migrationImpact: '自定义类加载器参与模块层时应覆盖 moduleName 版本；纯类路径场景仍可沿用 findClass(String)。',
      states: {
        '17': { sourceKey: 'ClassLoader', line: 627, symbol: 'loadClass(Module, String)', fingerprint: 'module-aware-class-loading', code: 'final Class<?> loadClass(Module module, String name) {\n    Class<?> c = findLoadedClass(name);\n    if (c == null) c = findClass(module.getName(), name);\n    return (c != null && c.getModule() == module) ? c : null;\n}', note: '结果还要校验实际 Module 身份。' },
        '21': { sourceKey: 'ClassLoader', line: 633, symbol: 'loadClass(Module, String)', fingerprint: 'module-aware-class-loading', code: 'final Class<?> loadClass(Module module, String name) {\n    Class<?> c = findLoadedClass(name);\n    if (c == null) c = findClass(module.getName(), name);\n    return (c != null && c.getModule() == module) ? c : null;\n}', note: 'JDK 21 保持模块身份校验。' }
      }
    },
    {
      id: 'serviceloader-provider-stream', title: 'Provider 与 stream 支持先筛类型、后实例化', kind: 'signature',
      summary: 'JDK 17/21 增加 ServiceLoader.Provider、stream 和 findFirst，可先查看 provider 类型再决定是否 get。',
      reason: '增强 for 会在迭代时实例化；Provider 流可按注解或类名筛选，减少不必要的构造与副作用。',
      migrationImpact: '迁移到 stream 后，真正实例化发生在 Provider.get；异常时点可能从 iterator.next 移到 get。',
      states: {
        '17': { sourceKey: 'ServiceLoader', line: 441, symbol: 'Provider / stream / findFirst', fingerprint: 'provider-stream-api', code: 'public static interface Provider<S> extends Supplier<S> {\n    Class<? extends S> type();\n    S get();\n}\npublic Stream<Provider<S>> stream() { ... }\npublic Optional<S> findFirst() { ... }', note: 'stream 元素是 Provider，不是已实例化服务。' },
        '21': { sourceKey: 'ServiceLoader', line: 441, symbol: 'Provider / stream / findFirst', fingerprint: 'provider-stream-api', code: 'public static interface Provider<S> extends Supplier<S> {\n    Class<? extends S> type();\n    S get();\n}\npublic Stream<Provider<S>> stream() { ... }\npublic Optional<S> findFirst() { ... }', note: 'JDK 21 保持惰性实例化协议。' }
      }
    },
    {
      id: 'serviceloader-module-layer', title: 'ServiceLoader 可从 ModuleLayer 查找服务', kind: 'signature',
      summary: 'JDK 17/21 增加 load(ModuleLayer, Class)，LayerLookupIterator 遍历当前层及父层服务目录。',
      reason: '插件模块可能以独立类加载器组成层，单个 ClassLoader 无法完整表达层级与 provides 声明。',
      migrationImpact: '模块化插件应声明 uses/provides 并从正确 ModuleLayer 加载；类路径仍使用 META-INF/services。',
      states: {
        '17': { sourceKey: 'ServiceLoader', line: 1782, symbol: 'load(ModuleLayer, Class)', fingerprint: 'module-layer-service-load', code: 'public static <S> ServiceLoader<S> load(\n        ModuleLayer layer, Class<S> service) {\n    return new ServiceLoader<>(\n        Reflection.getCallerClass(), layer, service);\n}', note: '查找器先遍历 layer 服务目录。' },
        '21': { sourceKey: 'ServiceLoader', line: 1783, symbol: 'load(ModuleLayer, Class)', fingerprint: 'module-layer-service-load', code: 'public static <S> ServiceLoader<S> load(\n        ModuleLayer layer, Class<S> service) {\n    return new ServiceLoader<>(\n        Reflection.getCallerClass(), layer, service);\n}', note: 'JDK 21 保持 ModuleLayer 入口。' }
      }
    },
    {
      id: 'classloader-parallel-caller-adapter', title: '并行类加载器注册显式校验调用者', kind: 'implementation',
      summary: 'JDK 21 为 registerAsParallelCapable 增加 CallerSensitiveAdapter，反射或 JNI 场景显式抛 IllegalCallerException。',
      reason: '调用者敏感方法不能假定栈上必有 ClassLoader 子类，适配器把身份校验与注册分开。',
      migrationImpact: '正常在自定义 ClassLoader 静态初始化块调用不受影响；反射转发或 native 附加线程会明确失败。',
      states: {
        '8': { sourceKey: 'ClassLoader', line: 1195, symbol: 'registerAsParallelCapable', fingerprint: 'direct-caller-register', code: 'protected static boolean registerAsParallelCapable() {\n    Class<? extends ClassLoader> callerClass =\n        Reflection.getCallerClass().asSubclass(ClassLoader.class);\n    return ParallelLoaders.register(callerClass);\n}', note: '直接取得调用类、转换并注册。' },
        '17': { sourceKey: 'ClassLoader', line: 1617, symbol: 'registerAsParallelCapable', fingerprint: 'direct-caller-register', code: 'protected static boolean registerAsParallelCapable() {\n    Class<? extends ClassLoader> callerClass =\n        Reflection.getCallerClass().asSubclass(ClassLoader.class);\n    return ParallelLoaders.register(callerClass);\n}', note: 'JDK 17 仍由公开入口直接转换调用者。' },
        '21': { sourceKey: 'ClassLoader', line: 1639, symbol: 'registerAsParallelCapable', fingerprint: 'validated-caller-adapter', code: 'protected static boolean registerAsParallelCapable() {\n    return registerAsParallelCapable(Reflection.getCallerClass());\n}\nprivate static boolean registerAsParallelCapable(Class<?> caller) {\n    if (caller == null || !ClassLoader.class.isAssignableFrom(caller))\n        throw new IllegalCallerException(...);\n    return ParallelLoaders.register(caller.asSubclass(ClassLoader.class));\n}', note: '适配器先校验调用者，再进入注册表。' }
      }
    }
  ],
  migrationChecklist: ['区分类路径委派与 ModuleLayer 的可读性、uses/provides 约束。', 'SPI stream 只发现 Provider，Provider.get 才构造服务。', '并行类加载器须在创建实例前完成静态注册。'],
  demoTitle: '同一次 SPI 查找如何经过模块与类路径',
  demoSteps: [
    { title: '确定查找空间', method: 'ServiceLoader.load', description: '入口决定只沿 ClassLoader，还是从显式 ModuleLayer 开始。', states: { '8': 'service + ClassLoader', '17': 'service + ClassLoader，或 layer + service', '21': 'service + ClassLoader，或 layer + service' } },
    { title: '发现 Provider', method: 'newLookupIterator', description: '模块化版本组合模块服务目录与 META-INF/services 兼容路径。', states: { '8': '解析 META-INF/services/<service>', '17': '模块服务目录 → 类路径配置文件', '21': '模块服务目录 → 类路径配置文件' } },
    { title: '加载与实例化', method: 'Provider.get / iterator.next', description: 'stream 可先看类型，传统 iterator 直接实例化。', states: { '8': 'Class.forName + public 无参构造', '17': 'Provider.type 先筛选，get 时构造', '21': '保持惰性 Provider；调用者边界更严格' } }
  ]
}

const AQS_REENTRANT_LOCK_TOPIC: JdkComparisonTopic = {
  id: 'aqs-reentrantlock',
  sourceTopicId: 'openjdk8-reentrantlock-aqs',
  title: 'AQS / ReentrantLock',
  packageName: 'java.util.concurrent.locks',
  question: 'AQS 仍然用 state 和等待队列组织同步，为什么 JDK 17/21 的节点、获取和 Condition 路径却几乎换了一套？',
  conclusion: 'JDK 8 的独占/共享协议和 Condition 语义仍是主线，但 JDK 17/21 将 Node 改成状态位与专用子类，统一 acquire 状态机并引入可协作阻塞的 ConditionNode；ReentrantLock 的公开公平、重入和取消语义保持不变。',
  sources: AQS_REENTRANT_LOCK_SOURCES,
  timeline: [
    { version: '8', title: 'Node 状态与模板分散', summary: 'waitStatus、addWaiter、acquireQueued、doAcquireShared 和 transferForSignal 分别承担队列协议。' },
    { version: '17', title: '统一获取与状态位重构', summary: 'ExclusiveNode/SharedNode/ConditionNode 取代单一 Node，所有获取入口汇入统一 acquire，并批量清理取消节点。' },
    { version: '21', title: '延续协议并增强极端容错', summary: '主体延续 JDK 17，额外为节点分配失败增加退避重试；普通业务仍应只依赖公开 Lock/Condition 语义。' }
  ],
  differences: [
    {
      id: 'aqs-node-status-model',
      title: 'Node 与等待状态从数值枚举改为位标记',
      kind: 'signature',
      summary: 'JDK 8 用一个 Node 和 waitStatus 常量表示独占、共享、取消、SIGNAL、CONDITION、PROPAGATE；JDK 17/21 拆出 ExclusiveNode、SharedNode、ConditionNode，并用 status 位组合 WAITING、COND 和 CANCELLED。',
      reason: '位标记让等待、条件归属和取消可以在同一状态字段中协作，专用节点类型也减少了 nextWaiter 和 thread 字段的歧义。',
      migrationImpact: '公开 Lock、CountDownLatch、Semaphore 行为不变；依赖 waitStatus 数值、Node 反射或旧字段名的调试脚本必须按目标 JDK 重定位。',
      states: {
        '8': { sourceKey: 'AQS', line: 380, symbol: 'Node.waitStatus', fingerprint: 'single-node-wait-status', code: 'static final class Node {\n    static final int SIGNAL = -1;\n    static final int CONDITION = -2;\n    static final int PROPAGATE = -3;\n    volatile int waitStatus;\n    Node nextWaiter;\n}', note: '同一个 Node 通过 waitStatus 和 nextWaiter 区分同步队列、条件队列与共享模式。' },
        '17': { sourceKey: 'AQS', line: 449, symbol: 'Node.status / ConditionNode', fingerprint: 'status-bits-and-node-subclasses', code: 'static final int WAITING = 1;\nstatic final int CANCELLED = 0x80000000;\nstatic final int COND = 2;\nabstract static class Node {\n    volatile int status;\n}\nstatic final class ConditionNode extends Node { ... }', note: '状态位与专用节点类型分离职责，取消状态为负数。' },
        '21': { sourceKey: 'AQS', line: 462, symbol: 'Node.status / ConditionNode', fingerprint: 'status-bits-and-node-subclasses', code: 'static final int WAITING = 1;\nstatic final int CANCELLED = 0x80000000;\nstatic final int COND = 2;\nabstract static class Node {\n    volatile int status;\n}\nstatic final class ConditionNode extends Node { ... }', note: 'JDK 21 延续 JDK 17 的状态位协议。' }
      }
    },
    {
      id: 'aqs-unified-acquire',
      title: '分散的 acquire 慢路径合并为统一状态机',
      kind: 'implementation',
      summary: 'JDK 8 按普通、可中断、定时和共享模式分别进入 acquireQueued/doAcquire*；JDK 17/21 用一个 acquire(Node, arg, shared, interruptible, timed, time) 解释全部组合。',
      reason: '统一循环可以集中处理节点创建、入队、前驱取消、park、超时和中断，减少多个慢路径之间的协议漂移。',
      migrationImpact: '插件断点和源码阅读路线不能再假设 doAcquireShared 或 acquireQueued 一定存在；应先看统一 acquire 的 shared、interruptible、timed 参数。',
      states: {
        '8': { sourceKey: 'AQS', line: 857, symbol: 'acquireQueued / doAcquireShared', fingerprint: 'split-acquire-loops', code: 'final boolean acquireQueued(Node node, int arg) { ... }\nprivate void doAcquireShared(int arg) { ... }\nprivate boolean doAcquireNanos(...) { ... }', note: '独占、共享、中断和超时各有一套相邻但独立的循环。' },
        '17': { sourceKey: 'AQS', line: 636, symbol: 'acquire(Node,int,boolean,boolean,boolean,long)', fingerprint: 'unified-acquire-state-machine', code: 'final int acquire(Node node, int arg,\n        boolean shared, boolean interruptible,\n        boolean timed, long time) {\n    ...\n}', note: '公共入口只传模式和等待策略，统一循环决定重试、阻塞或取消。' },
        '21': { sourceKey: 'AQS', line: 670, symbol: 'acquire(Node,int,boolean,boolean,boolean,long)', fingerprint: 'unified-acquire-state-machine', code: 'final int acquire(Node node, int arg,\n        boolean shared, boolean interruptible,\n        boolean timed, long time) {\n    ...\n}', note: 'JDK 21 延续统一获取入口，并在分配失败时增加退避分支。' }
      }
    },
    {
      id: 'aqs-cancel-clean-queue',
      title: '取消节点由局部摘链变成批量 cleanQueue',
      kind: 'implementation',
      summary: 'JDK 8 的 cancelAcquire 主要沿前驱寻找有效节点并修复 next；JDK 17/21 在标记取消后从 tail 反向扫描，反复 unsplice 一批取消节点并协助修复链接。',
      reason: '批量清理能减少连续超时/中断带来的残留节点，并把取消节点的后继唤醒责任集中到清理过程。',
      migrationImpact: '取消后节点不保证立即从队列消失；不要把 getQueueLength 或反射看到的瞬时链表当作严格协议，应该验证后继最终仍能取得同步器。',
      states: {
        '8': { sourceKey: 'AQS', line: 742, symbol: 'cancelAcquire(Node)', fingerprint: 'local-cancel-unlink', code: 'node.waitStatus = Node.CANCELLED;\n...\nif (pred.next == node)\n    compareAndSetNext(pred, node, null);\nunparkSuccessor(node);', note: '取消路径围绕当前节点寻找前驱并立即尝试修复链。' },
        '17': { sourceKey: 'AQS', line: 733, symbol: 'cleanQueue()', fingerprint: 'backward-batch-cleanup', code: 'private void cleanQueue() {\n    for (Node q = tail, s = null, p, n;;) {\n        ...\n        if (q.status < 0) { ... }\n    }\n}', note: '从 tail 向前批量摘除取消节点，并在需要时 signalNext。' },
        '21': { sourceKey: 'AQS', line: 785, symbol: 'cleanQueue()', fingerprint: 'backward-batch-cleanup', code: 'private void cleanQueue() {\n    for (Node q = tail, s = null, p, n;;) {\n        ...\n        if (q.status < 0) { ... }\n    }\n}', note: 'JDK 21 保留批量清理协议。' }
      }
    },
    {
      id: 'aqs-condition-node-managed-block',
      title: 'Condition 从普通 Node 转为 ConditionNode',
      kind: 'implementation',
      summary: 'JDK 8 的 ConditionObject 复用 Node，并通过 transferForSignal/isOnSyncQueue 转移；JDK 17/21 用 ConditionNode 的 enableWait、canReacquire 和 doSignal，非定时等待还可尝试 ForkJoinPool.managedBlock。',
      reason: '条件队列与同步队列的状态转换被封装在专用节点中，并允许固定大小的 ForkJoinPool 感知阻塞，降低池耗尽风险。',
      migrationImpact: 'signal 后仍必须重新取得锁并用 while 检查条件；ManagedBlocker、ConditionNode 和 transferForSignal 都是实现细节，不能写入业务兼容判断。',
      states: {
        '8': { sourceKey: 'AQS', line: 1670, symbol: 'transferForSignal(Node)', fingerprint: 'condition-transfer-node', code: 'if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))\n    return false;\nNode p = enq(node);\n...\nreturn true;', note: 'signal 把条件 Node 改状态后入同步队列。' },
        '17': { sourceKey: 'AQS', line: 1448, symbol: 'ConditionNode.doSignal / enableWait', fingerprint: 'condition-node-managed-block', code: 'if ((first.getAndUnsetStatus(COND) & COND) != 0)\n    enqueue(first);\n...\nnode.setStatusRelaxed(COND | WAITING);', note: 'ConditionNode 同时记录条件等待和可唤醒状态，普通 await 可交给 managedBlock。' },
        '21': { sourceKey: 'AQS', line: 1506, symbol: 'ConditionNode.doSignal / enableWait', fingerprint: 'condition-node-managed-block', code: 'if ((first.getAndUnsetStatus(COND) & COND) != 0)\n    enqueue(first);\n...\nnode.setStatusRelaxed(COND | WAITING);', note: 'JDK 21 延续 ConditionNode 路径，并补充极端分配失败的恢复逻辑。' }
      }
    },
    {
      id: 'reentrantlock-initial-fast-path',
      title: 'ReentrantLock 把初始快速获取抽成 initialTryLock',
      kind: 'signature',
      summary: 'JDK 8 由 NonfairSync.lock、FairSync.lock 和 nonfairTryAcquire 分别组织入口；JDK 17/21 在 Sync 中统一提供 tryLock、initialTryLock、lockInterruptibly 和 tryLockNanos。',
      reason: '先做一次明确的无等待尝试，再把失败场景交给 AQS 统一队列，可以减少成功路径开销，同时保留公平锁的前驱判断。',
      migrationImpact: '公平锁的无参 tryLock 仍允许插队；调试时要区分 initialTryLock 的初试和 AQS.acquire 的排队慢路径，不要把方法名变化误解为公平策略变化。',
      states: {
        '8': { sourceKey: 'ReentrantLock', line: 129, symbol: 'Sync.nonfairTryAcquire', fingerprint: 'nonfair-try-acquire-helper', code: 'final boolean nonfairTryAcquire(int acquires) {\n    int c = getState();\n    if (c == 0 && compareAndSetState(0, acquires)) ...\n}', note: '非公平 tryLock 与重入判断集中在 nonfairTryAcquire。' },
        '17': { sourceKey: 'ReentrantLock', line: 125, symbol: 'Sync.tryLock / initialTryLock', fingerprint: 'initial-try-lock-template', code: '@ReservedStackAccess\nfinal boolean tryLock() { ... }\nabstract boolean initialTryLock();\nfinal void lock() {\n    if (!initialTryLock()) acquire(1);\n}', note: 'Sync 统一初试入口，失败后才进入 AQS。' },
        '21': { sourceKey: 'ReentrantLock', line: 125, symbol: 'Sync.tryLock / initialTryLock', fingerprint: 'initial-try-lock-template', code: '@ReservedStackAccess\nfinal boolean tryLock() { ... }\nabstract boolean initialTryLock();\nfinal void lock() {\n    if (!initialTryLock()) acquire(1);\n}', note: 'JDK 21 与 JDK 17 保持相同的 ReentrantLock 主路径。' }
      }
    }
  ],
  migrationChecklist: [
    '跨版本断点先确认 Node 状态模型：JDK 8 看 waitStatus，17/21 看 status 位和节点子类。',
    '不要依赖 acquireQueued、transferForSignal 或 cleanQueue 的固定存在；以当前版本统一 acquire 和 Condition 实现为准。',
    'Condition 的公开正确用法仍是“持锁、signal、释放后重新竞争、while 重检”，不要把唤醒当作条件成立。',
    '公平锁的 tryLock 特例和初始快速路径不能简化成绝对 FIFO。'
  ],
  demoTitle: '同一个等待者如何跨条件队列进入同步队列',
  demoSteps: [
    { title: '初次获取', method: 'ReentrantLock.lock()', description: '先观察版本是否存在独立的初始快速路径。', states: { '8': 'NonfairSync.lock / FairSync.lock', '17': 'Sync.initialTryLock → 失败后 AQS.acquire', '21': 'Sync.initialTryLock → 失败后 AQS.acquire' } },
    { title: '进入等待', method: 'AQS acquire', description: '比较独占、共享、可中断和定时等待如何汇入队列。', states: { '8': 'addWaiter + acquireQueued / doAcquireShared', '17': '统一 acquire(shared, interruptible, timed)', '21': '统一 acquire，并有分配失败退避' } },
    { title: 'Condition 转移', method: 'Condition.signal()', description: 'signal 只改变竞争资格，真正返回仍需重新获取锁。', states: { '8': 'transferForSignal → enq → isOnSyncQueue', '17': 'ConditionNode 清除 COND → enqueue', '21': 'ConditionNode 清除 COND → enqueue' } },
    { title: '共享传播', method: 'CountDownLatch.countDown()', description: '用共享 Lab 对照独占锁，观察一次释放怎样继续唤醒后继。', states: { '8': 'PROPAGATE + setHeadAndPropagate', '17': 'SharedNode + signalNextIfShared', '21': 'SharedNode + signalNextIfShared' } }
  ]
}

const THREAD_POOL_EXECUTOR_TOPIC: JdkComparisonTopic = {
  id: 'thread-pool-executor',
  sourceTopicId: 'openjdk8-java-util-concurrent-threadpoolexecutor',
  title: 'ThreadPoolExecutor',
  packageName: 'java.util.concurrent',
  question: 'ctl、execute 三步决策和五态关闭在三版都很稳定，哪些内部边界却会影响升级后的调试与资源治理？',
  conclusion: 'JDK 8、17、21 都用 AtomicInteger ctl 编码 runState 与 workerCount，都保留“核心线程 → 入队复查 → 最大线程/拒绝”的提交协议，以及 Worker 锁、队列排空和 terminated 收口。变化主要集中在 finalization 退出、显式 close 生命周期、workerCount 回退写法、动态参数约束、异常向 Worker 外传播和 JDK 21 的 SharedThreadContainer；这些变化不应被误读成 execute 语义重写。',
  sources: THREAD_POOL_EXECUTOR_COMPARISON_SOURCES,
  timeline: [
    { version: '8', title: 'ctl 与 Worker 协议定型', summary: 'AtomicInteger ctl、三步 execute、Worker AQS 锁和 SHUTDOWN/STOP/TIDYING/TERMINATED 收口组成经典线程池骨架；finalize 仍可能触发 shutdown。' },
    { version: '17', title: '生命周期边界收紧', summary: '主协议保持不变，但 finalize 改为空实现、workerCount 回退简化为 addAndGet、动态 corePoolSize 校验更严格，SecurityManager 路径逐步退出。' },
    { version: '21', title: '显式关闭与线程归属现代化', summary: 'JDK 21 快照包含 JDK 19 新增的 ExecutorService.close，并由 SharedThreadContainer 登记 worker；ctl 与队列协议仍是传统执行器的核心。' }
  ],
  differences: [
    {
      id: 'tpe-finalization-lifecycle',
      title: 'finalize 不再替线程池调用 shutdown',
      kind: 'implementation',
      summary: 'JDK 8 的 finalize 会按 SecurityManager/AccessControlContext 调用 shutdown；JDK 17 改为空实现，JDK 21 继续为空实现并标记 forRemoval。',
      reason: '依赖终结器关闭线程池会让资源释放时机不可预测，也会拖慢或阻塞 GC；后续 JDK 把关闭责任明确交给应用生命周期和显式 shutdown。',
      migrationImpact: '不要把“忘记 shutdown 最终也会自动关闭”当成保证；应用、容器和测试都应显式调用 shutdown/try-with-resources 适配器，并用 awaitTermination 验证收口。',
      states: {
        '8': { sourceKey: 'ThreadPoolExecutor', line: 1486, symbol: 'finalize()', fingerprint: 'finalize-calls-shutdown', code: 'protected void finalize() {\n    SecurityManager sm = System.getSecurityManager();\n    if (sm == null || acc == null) shutdown();\n    else AccessController.doPrivileged(...);\n}', note: 'JDK 8 仍把终结器作为忘记关闭时的兜底路径，但时机不可控。' },
        '17': { sourceKey: 'ThreadPoolExecutor', line: 1482, symbol: 'finalize()', fingerprint: 'finalize-noop', code: '@Deprecated(since="9")\nprotected void finalize() { }', note: 'JDK 17 保留兼容方法签名，但不再自动推进线程池状态。' },
        '21': { sourceKey: 'ThreadPoolExecutor', line: 1498, symbol: 'finalize()', fingerprint: 'finalize-noop-removal', code: '@Deprecated(since="9", forRemoval=true)\nprotected void finalize() { }', note: 'JDK 21 明确终结器即将移除，资源治理必须走显式生命周期。' }
      }
    },
    {
      id: 'executor-service-auto-closeable',
      title: 'ExecutorService 增加显式 close 生命周期',
      kind: 'signature',
      summary: 'JDK 8/17 的 ExecutorService 只继承 Executor；JDK 21 快照已经包含 JDK 19 引入的 AutoCloseable 父接口和 default close()。',
      reason: '线程池退出从不可预测的 finalization 兜底转向结构化、显式的资源作用域；默认 close 会先 shutdown，再等待终止，中断时升级为 shutdownNow 并恢复中断标记。',
      migrationImpact: '面向 JDK 19+ 可在 try-with-resources 中管理 ExecutorService；兼容 JDK 8/17 的源码不能直接调用 close，仍需在 finally 中 shutdown 并 awaitTermination。还要注意 close 会等待任务结束，不等同于立即 shutdownNow。',
      states: {
        '8': { sourceKey: 'ExecutorService', line: 137, symbol: 'ExecutorService extends Executor', fingerprint: 'executor-service-no-close', code: 'public interface ExecutorService extends Executor {\n    void shutdown();\n    ...\n}', note: '没有 AutoCloseable，也没有 close 方法；调用方必须显式编排 shutdown。' },
        '17': { sourceKey: 'ExecutorService', line: 138, symbol: 'ExecutorService extends Executor', fingerprint: 'executor-service-no-close', code: 'public interface ExecutorService extends Executor {\n    void shutdown();\n    ...\n}', note: 'JDK 17 GA 仍没有 close；不能把 JDK 21 的用法反推到 17。' },
        '21': { sourceKey: 'ExecutorService', line: 149, symbol: 'ExecutorService extends Executor, AutoCloseable', fingerprint: 'executor-service-default-close', code: 'public interface ExecutorService extends Executor, AutoCloseable {\n    ...\n    default void close() {\n        shutdown();\n        while (!awaitTermination(...)) { ... }\n    }\n}', note: '该 API 自 JDK 19 引入；JDK 21 固定快照在第 410 行给出默认实现。' }
      }
    },
    {
      id: 'tpe-worker-count-decrement',
      title: 'workerCount 回退从 CAS 自旋变为原子加法',
      kind: 'implementation',
      summary: 'JDK 8 的 decrementWorkerCount 读取 ctl 后循环 compareAndSet；JDK 17/21 直接使用 ctl.addAndGet(-1)，而加 worker 仍保留 CAS 预占。',
      reason: '回退发生在已确认退出或创建失败的路径，直接原子减法足以表达单次扣减，减少无意义的失败重试代码；新增 worker 仍必须 CAS 防止超限。',
      migrationImpact: '业务线程数语义不变；诊断脚本不要假设每次 worker 退出都会经过 compareAndDecrementWorkerCount，自定义监控应读取公开 poolSize/activeCount 而非反射 ctl。',
      states: {
        '8': { sourceKey: 'ThreadPoolExecutor', line: 433, symbol: 'decrementWorkerCount()', fingerprint: 'cas-decrement-loop', code: 'private void decrementWorkerCount() {\n    do { } while (!compareAndDecrementWorkerCount(ctl.get()));\n}', note: '退出路径围绕旧 ctl 快照自旋，直到 CAS 成功。' },
        '17': { sourceKey: 'ThreadPoolExecutor', line: 437, symbol: 'decrementWorkerCount()', fingerprint: 'atomic-add-decrement', code: 'private void decrementWorkerCount() {\n    ctl.addAndGet(-1);\n}', note: '单次回退改用 AtomicInteger 的加法原子操作。' },
        '21': { sourceKey: 'ThreadPoolExecutor', line: 439, symbol: 'decrementWorkerCount()', fingerprint: 'atomic-add-decrement', code: 'private void decrementWorkerCount() {\n    ctl.addAndGet(-1);\n}', note: 'JDK 21 延续 JDK 17 的回退实现。' }
      }
    },
    {
      id: 'tpe-termination-predicate',
      title: 'tryTerminate 的关闭判断改用状态范围',
      kind: 'implementation',
      summary: 'JDK 8 直接判断 runState == SHUTDOWN 且队列非空便返回；JDK 17/21 使用 runStateLessThan(c, STOP) 覆盖 RUNNING 之外、STOP 之前的状态。',
      reason: '在先排除 RUNNING 和 TIDYING 及其后继状态后，五态模型中“等于 SHUTDOWN”与“低于 STOP”逻辑等价；改写只是更直接利用状态数值顺序表达排空区间。',
      migrationImpact: '这不是关闭语义扩张。排查迟迟不 TERMINATED 时仍要同时检查队列 isEmpty、workerCount 和当前版本的状态谓词，源码检索不要只依赖 SHUTDOWN 字面量。',
      states: {
        '8': { sourceKey: 'ThreadPoolExecutor', line: 696, symbol: 'tryTerminate()', fingerprint: 'shutdown-equals-queue-check', code: 'if (isRunning(c) || runStateAtLeast(c, TIDYING)\n    || (runStateOf(c) == SHUTDOWN && !workQueue.isEmpty()))\n    return;', note: 'JDK 8 把可排空状态写成 SHUTDOWN 的精确判断。' },
        '17': { sourceKey: 'ThreadPoolExecutor', line: 708, symbol: 'tryTerminate()', fingerprint: 'pre-stop-range-queue-check', code: 'if (isRunning(c) || runStateAtLeast(c, TIDYING)\n    || (runStateLessThan(c, STOP) && !workQueue.isEmpty()))\n    return;', note: 'JDK 17 用状态范围重写等价条件，没有增加新的可排空状态。' },
        '21': { sourceKey: 'ThreadPoolExecutor', line: 715, symbol: 'tryTerminate()', fingerprint: 'pre-stop-range-queue-check', code: 'if (isRunning(c) || runStateAtLeast(c, TIDYING)\n    || (runStateLessThan(c, STOP) && !workQueue.isEmpty()))\n    return;', note: 'JDK 21 保持等价的范围写法，并在终止 finally 中关闭线程容器。' }
      }
    },
    {
      id: 'tpe-core-size-validation',
      title: '动态 corePoolSize 不允许越过 maximumPoolSize',
      kind: 'implementation',
      summary: 'JDK 8 的 setCorePoolSize 只检查非负数；JDK 17/21 额外拒绝大于当前 maximumPoolSize 的新值。',
      reason: '把 corePoolSize 不得大于 maximumPoolSize 的构造期不变量同样固定在动态 setter。JDK 8 若先把 core 调到 max 以上，addWorker(core=true) 甚至可能按更大的 core 上限创建线程，因此这不是无效配置，而是约束不一致。',
      migrationImpact: '动态调参代码必须先提高 maximumPoolSize，再提高 corePoolSize；升级后原本依赖“先设 core、后设 max”的顺序会直接收到 IllegalArgumentException。',
      states: {
        '8': { sourceKey: 'ThreadPoolExecutor', line: 1553, symbol: 'setCorePoolSize(int)', fingerprint: 'core-only-nonnegative-check', code: 'public void setCorePoolSize(int corePoolSize) {\n    if (corePoolSize < 0)\n        throw new IllegalArgumentException();\n    ...\n}', note: 'JDK 8 只校验 corePoolSize 不得为负。' },
        '17': { sourceKey: 'ThreadPoolExecutor', line: 1543, symbol: 'setCorePoolSize(int)', fingerprint: 'core-must-fit-maximum', code: 'public void setCorePoolSize(int corePoolSize) {\n    if (corePoolSize < 0 || maximumPoolSize < corePoolSize)\n        throw new IllegalArgumentException();\n    ...\n}', note: 'JDK 17 将 core 不得超过 max 写入公开 setter 的前置校验。' },
        '21': { sourceKey: 'ThreadPoolExecutor', line: 1559, symbol: 'setCorePoolSize(int)', fingerprint: 'core-must-fit-maximum', code: 'public void setCorePoolSize(int corePoolSize) {\n    if (corePoolSize < 0 || maximumPoolSize < corePoolSize)\n        throw new IllegalArgumentException();\n    ...\n}', note: 'JDK 21 延续同一参数不变量。' }
      }
    },
    {
      id: 'tpe-runworker-throwable-forwarding',
      title: 'runWorker 向 Worker 外的异常传播收敛',
      kind: 'implementation',
      summary: 'JDK 8 分别捕获 RuntimeException、Error 和其他 Throwable；afterExecute 始终收到原异常，但未知 Throwable 向 Worker 外传播时会包装成 Error。JDK 17/21 用统一 catch 重新抛出原异常。',
      reason: '统一 catch 减少分支，并让极少见的 sneaky checked Throwable 也能保持原对象传播；常规 Runnable 的 RuntimeException/Error 行为以及 FutureTask 保存 submit 异常的协议没有改变。',
      migrationImpact: 'afterExecute 在三版都能看到原始任务异常；真正可能不同的是 Thread.UncaughtExceptionHandler 看到的外层异常。另需保证钩子本身不抛异常：JDK 17/21 的 afterExecute(task, null) 若失败，会进入 catch 并可能再次调用 afterExecute(task, ex)。',
      states: {
        '8': { sourceKey: 'ThreadPoolExecutor', line: 1127, symbol: 'runWorker(Worker)', fingerprint: 'typed-catch-wrap-throwable', code: 'try { task.run(); }\ncatch (RuntimeException x) { thrown = x; throw x; }\ncatch (Error x) { thrown = x; throw x; }\ncatch (Throwable x) { thrown = x; throw new Error(x); }\nfinally { afterExecute(task, thrown); }', note: 'thrown 保存原对象并交给钩子；只有向 Worker 外传播未知 Throwable 时才包装 Error。' },
        '17': { sourceKey: 'ThreadPoolExecutor', line: 1115, symbol: 'runWorker(Worker)', fingerprint: 'unified-catch-rethrow', code: 'try {\n    task.run();\n    afterExecute(task, null);\n} catch (Throwable ex) {\n    afterExecute(task, ex);\n    throw ex;\n}', note: '失败时钩子收到原异常，随后原对象向外传播；成功钩子抛错也会落入此 catch。' },
        '21': { sourceKey: 'ThreadPoolExecutor', line: 1123, symbol: 'runWorker(Worker)', fingerprint: 'unified-catch-rethrow', code: 'try {\n    task.run();\n    afterExecute(task, null);\n} catch (Throwable ex) {\n    afterExecute(task, ex);\n    throw ex;\n}', note: 'JDK 21 延续统一路径，钩子可靠性仍直接影响 Worker 生命周期。' }
      }
    },
    {
      id: 'tpe-shared-thread-container',
      title: 'JDK 21 用 SharedThreadContainer 管理 worker 归属',
      kind: 'implementation',
      summary: 'JDK 8/17 在 addWorker 中直接调用 Thread.start；JDK 21 为每个线程池创建 jdk.internal.vm.SharedThreadContainer，通过 container.start(t) 登记 worker，并在 TERMINATED finally 中 close。',
      reason: '线程容器让运行时可以统一观察和管理平台线程、虚拟线程相关的线程归属，同时不改变 ThreadPoolExecutor 的队列与 Worker 协议。',
      migrationImpact: 'SharedThreadContainer 是 JDK 内部实现，不应在业务代码中反射或复制；升级后的线程诊断应优先使用公开线程转储、Executor 指标和 ThreadFactory 命名。',
      states: {
        '8': { sourceKey: 'ThreadPoolExecutor', line: 957, symbol: 'addWorker / Thread.start', fingerprint: 'direct-thread-start', code: 'if (workerAdded) {\n    t.start();\n    workerStarted = true;\n}', note: 'JDK 8 直接启动 Thread，线程池自己维护 workers 集合。' },
        '17': { sourceKey: 'ThreadPoolExecutor', line: 945, symbol: 'addWorker / Thread.start', fingerprint: 'direct-thread-start', code: 'if (workerAdded) {\n    t.start();\n    workerStarted = true;\n}', note: 'JDK 17 仍直接启动平台 Thread。' },
        '21': { sourceKey: 'ThreadPoolExecutor', line: 953, symbol: 'container.start / container.close', fingerprint: 'shared-thread-container', code: 'container.start(t);\n...\nfinally {\n    container.close();\n}', note: 'JDK 21 把 worker 启停纳入内部线程容器生命周期。' }
      }
    }
  ],
  migrationChecklist: [
    'ctl、execute 三步复查、Worker 不可重入锁和五态关闭是跨版本稳定协议，先用这些不变量建立主线。',
    '不要依赖 JDK 8 finalize 自动 shutdown；JDK 19+ 可使用 ExecutorService.close，兼容旧版本则在 finally 中显式 shutdown 并等待 TERMINATED。',
    '动态调参按 maximumPoolSize → corePoolSize 的顺序更新，并为 IllegalArgumentException 增加升级测试。',
    'afterExecute 监控同时覆盖 execute 直抛和 submit/FutureTask 保存异常，并保证钩子自身不抛错；不要反射 SharedThreadContainer 或内部 ctl。'
  ],
  demoTitle: '同一条 execute 主线如何跨版本保持稳定又逐步收紧',
  demoSteps: [
    { title: '提交与复查', method: 'execute(Runnable)', description: '先确认核心线程、入队、复查和最大线程/拒绝的三步协议没有改变。', states: { '8': 'ctl.get → addWorker(core) → offer → recheck → reject/addWorker', '17': '同一三步协议；ctl 回退实现已简化', '21': '同一三步协议；worker 启动交给 SharedThreadContainer' } },
    { title: 'Worker 循环', method: 'runWorker(Worker)', description: '观察 Worker 锁如何保护任务执行，并区分 afterExecute 与 Worker 外层看到的异常。', states: { '8': '钩子见原异常；未知 Throwable 向外包装 Error', '17': '钩子见原异常；统一 catch 后原样重抛', '21': '延续统一路径，Worker 仍由 processWorkerExit 收口' } },
    { title: '关闭与终止', method: 'shutdown / close / tryTerminate', description: '让队列排空后观察 SHUTDOWN、TIDYING、TERMINATED 以及显式生命周期。', states: { '8': '只能显式 shutdown；SHUTDOWN + 队列空 + worker=0 → TIDYING', '17': '仍无 close；等价状态范围写法判断是否排空', '21': '包含 JDK 19 close；终止 finally 额外关闭线程容器' } },
    { title: '升级边界', method: 'finalize / setCorePoolSize', description: '把资源生命周期和动态调参放入迁移测试，而不是依赖私有字段。', states: { '8': 'finalize 可能 shutdown；core 可被调到 max 以上', '17': 'finalize 空实现；core 不能超过 max', '21': 'close 可结构化关闭；finalize 标记 forRemoval' } }
  ]
}

const FUTURE_TASK_TOPIC: JdkComparisonTopic = {
  id: 'future-task',
  sourceTopicId: 'openjdk8-java-util-concurrent-futuretask',
  title: 'FutureTask / Future',
  packageName: 'java.util.concurrent',
  question: '七状态、runner 和 WaitNode 协议都没有被推翻，为什么跨版本调试与结果观察方式仍然明显不同？',
  conclusion: 'JDK 8、17、21 的 FutureTask 都用 NEW 到终态的一次性 CAS 协议发布 outcome，用 runner CAS 限制 Callable 执行权，并用 WaitNode Treiber 栈唤醒 get 等待者。JDK 17 将 Unsafe 原子访问迁移到 VarHandle、重写定时等待边界并增加 toString 诊断；JDK 21 在 Future 接口增加 State、resultNow、exceptionNow，并由 FutureTask 直接把私有整数状态映射成公开观察结果。',
  sources: FUTURE_TASK_COMPARISON_SOURCES,
  timeline: [
    { version: '8', title: '一次完成协议定型', summary: '七个私有整数状态、runner CAS、outcome 发布、WaitNode 栈、cancel 中断窗口和 runAndReset 构成后续版本的稳定骨架。' },
    { version: '17', title: '原子访问与等待边界现代化', summary: 'STATE/RUNNER/WAITERS VarHandle 取代 Unsafe，awaitDone 用 elapsed 计算规避 deadline 溢出，并增加可读的 toString 完成状态。' },
    { version: '21', title: '公开非阻塞观察能力', summary: 'Future.State、resultNow 和 exceptionNow 已在 JDK 19 加入，FutureTask 在 JDK 21 GA 中直接映射私有 state 与 outcome，避免为了观察结果再次阻塞。' }
  ],
  differences: [
    {
      id: 'futuretask-unsafe-to-varhandle',
      title: '原子字段访问从 Unsafe 迁移到 VarHandle',
      kind: 'implementation',
      summary: 'JDK 8 通过字段偏移调用 compareAndSwapInt/Object 和 putOrderedInt；JDK 17/21 为 state、runner、waiters 建立三个 VarHandle，使用 compareAndSet、weakCompareAndSet 与 setRelease。',
      reason: 'VarHandle 把字段类型、访问模式和内存语义放在标准 API 中表达，减少对 sun.misc.Unsafe 和手工偏移量的依赖，同时保留 CAS 与 release 发布协议。',
      migrationImpact: 'FutureTask 公共行为不变；源码断点要从 UNSAFE 与 offset 移到 STATE/RUNNER/WAITERS，业务代码不应复制任一版本的私有原子实现。',
      states: {
        '8': { sourceKey: 'FutureTask', line: 466, symbol: 'UNSAFE / stateOffset / runnerOffset / waitersOffset', fingerprint: 'unsafe-field-offsets', code: 'private static final sun.misc.Unsafe UNSAFE;\nprivate static final long stateOffset;\n...\nUNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING);\nUNSAFE.putOrderedInt(this, stateOffset, NORMAL);', note: '字段偏移在静态初始化中通过反射取得，最终状态用 ordered write 发布。' },
        '17': { sourceKey: 'FutureTask', line: 518, symbol: 'STATE / RUNNER / WAITERS VarHandle', fingerprint: 'varhandle-field-access', code: 'private static final VarHandle STATE;\nprivate static final VarHandle RUNNER;\nprivate static final VarHandle WAITERS;\n...\nSTATE.compareAndSet(this, NEW, COMPLETING);\nSTATE.setRelease(this, NORMAL);', note: 'VarHandle 名称直接表达目标字段，setRelease 继续保证 outcome 先于终态可见。' },
        '21': { sourceKey: 'FutureTask', line: 571, symbol: 'STATE / RUNNER / WAITERS VarHandle', fingerprint: 'varhandle-field-access', code: 'STATE.compareAndSet(this, NEW, COMPLETING);\nRUNNER.compareAndSet(this, null, Thread.currentThread());\nWAITERS.weakCompareAndSet(this, q.next = waiters, q);', note: 'JDK 21 延续 VarHandle 协议，新查询 API 没有改变一次完成的 CAS 边界。' }
      }
    },
    {
      id: 'futuretask-timed-await-boundary',
      title: 'awaitDone 改为经过时间计算并调整完成/中断顺序',
      kind: 'implementation',
      summary: 'JDK 8 预先计算 deadline=now+nanos，每轮先检查中断；JDK 17/21 延迟取得 startTime，用 elapsed 与原预算比较，并先识别终态或 COMPLETING。',
      reason: 'deadline 相加可能溢出，nanoTime 也不应在零预算时无条件调用；完成优先检查还能兑现 isDone 已经观察到完成时“不空手返回”的承诺。',
      migrationImpact: 'get(timeout) 的公开契约保持不变，但极窄竞争下先返回结果还是抛 InterruptedException 的观察顺序可能不同；测试应控制先后关系，不要断言私有循环的某一轮分支。',
      states: {
        '8': { sourceKey: 'FutureTask', line: 396, symbol: 'awaitDone(boolean,long)', fingerprint: 'deadline-and-interrupt-first', code: 'final long deadline = timed ? System.nanoTime() + nanos : 0L;\nfor (;;) {\n    if (Thread.interrupted()) ...\n    int s = state;\n    ...\n    nanos = deadline - System.nanoTime();\n}', note: '一次加法得到绝对 deadline，循环入口优先处理中断。' },
        '17': { sourceKey: 'FutureTask', line: 393, symbol: 'awaitDone(boolean,long)', fingerprint: 'elapsed-and-completion-first', code: 'long startTime = 0L;\nfor (;;) {\n    int s = state;\n    if (s > COMPLETING) return s;\n    else if (s == COMPLETING) Thread.yield();\n    else if (Thread.interrupted()) ...\n    ...\n}', note: '首次真正等待时才取 startTime，并用 elapsed 避免 deadline 加法溢出。' },
        '21': { sourceKey: 'FutureTask', line: 446, symbol: 'awaitDone(boolean,long)', fingerprint: 'elapsed-and-completion-first', code: 'long startTime = 0L;\n...\nlong elapsed = System.nanoTime() - startTime;\nif (elapsed >= nanos) {\n    removeWaiter(q);\n    return state;\n}', note: 'JDK 21 保持 JDK 17 的定时等待与检查顺序。' }
      }
    },
    {
      id: 'futuretask-to-string-state',
      title: 'FutureTask 增加完成状态诊断文本',
      kind: 'signature',
      summary: 'JDK 8 继承 Object.toString；JDK 17/21 覆盖 toString，区分正常完成、异常完成、取消和未完成，并在未完成时附带 callable。',
      reason: '线程转储、日志和调试器经常只能拿到 Runnable/Future 对象，稳定的状态摘要比对象标识更有诊断价值。',
      migrationImpact: '不要把完整 toString 文本作为机器协议或测试断言；日志脱敏时还要注意未完成任务的 callable.toString 可能携带业务参数。',
      states: {
        '17': { sourceKey: 'FutureTask', line: 495, symbol: 'toString()', fingerprint: 'futuretask-state-to-string', code: 'public String toString() {\n    final String status;\n    switch (state) {\n        case NORMAL: status = "[Completed normally]"; break;\n        case EXCEPTIONAL: status = "[Completed exceptionally: " + outcome + "]"; break;\n        ...\n    }\n    return super.toString() + status;\n}', note: 'JDK 17 固定快照会输出 FutureTask 当前完成分类。' },
        '21': { sourceKey: 'FutureTask', line: 548, symbol: 'toString()', fingerprint: 'futuretask-state-to-string', code: 'public String toString() {\n    // Completed normally / exceptionally / Cancelled / Not completed\n    return super.toString() + status;\n}', note: 'JDK 21 保留同一诊断外观；它仍不是序列化格式。' }
      }
    },
    {
      id: 'future-nonblocking-observation-api',
      title: 'Future 增加非阻塞结果、异常和状态查询',
      kind: 'signature',
      summary: 'JDK 21 的 Future 接口包含 JDK 19 新增的 resultNow、exceptionNow、state 和 State 枚举；JDK 8/17 只有 isDone、isCancelled 与可能阻塞的 get。',
      reason: '批量 Future 已由外部条件确认完成后，需要按成功、失败和取消筛选并读取结果，不应再通过 get 的受检异常与阻塞语义间接推断。',
      migrationImpact: '仅面向 JDK 19+ 的代码可直接调用；兼容 JDK 8/17 的库需继续使用 isDone/isCancelled/get 或通过多版本发布、反射适配。resultNow 不是 getNow 默认值 API，状态不匹配会抛 IllegalStateException。',
      states: {
        '21': { sourceKey: 'Future', line: 188, symbol: 'resultNow / exceptionNow / state / State', fingerprint: 'future-nonblocking-observation', code: 'default V resultNow() { ... }\ndefault Throwable exceptionNow() { ... }\nenum State { RUNNING, SUCCESS, FAILED, CANCELLED }\ndefault State state() { ... }', note: '三个默认方法自 JDK 19 提供；JDK 21 GA 可直接按公开状态分类 Future。' }
      }
    },
    {
      id: 'futuretask-direct-state-observation',
      title: 'FutureTask 直接映射私有状态，避免默认 get 路径',
      kind: 'implementation',
      summary: 'JDK 21 的 FutureTask 覆盖三个新方法：state 先等待 COMPLETING 发布完成，再把七状态压缩成四种 Future.State；resultNow/exceptionNow 直接读取 outcome。',
      reason: 'Future 接口的默认实现需要组合 isDone、isCancelled 和 get；FutureTask 已掌握精确私有状态与 outcome，可以避免重复判断、受检异常和中断保存循环。',
      migrationImpact: '调试时不要把 Future.State.RUNNING 误认为新增的私有 int 状态：FutureTask 在 NEW（包括 Callable 已运行但未完成）时都映射为 RUNNING。COMPLETING 仍是极短的内部发布阶段。',
      states: {
        '21': { sourceKey: 'FutureTask', line: 209, symbol: 'resultNow / exceptionNow / state', fingerprint: 'futuretask-direct-state-mapping', code: 'public State state() {\n    int s = state;\n    while (s == COMPLETING) {\n        Thread.yield();\n        s = state;\n    }\n    switch (s) {\n        case NORMAL: return State.SUCCESS;\n        case EXCEPTIONAL: return State.FAILED;\n        ...\n        default: return State.RUNNING;\n    }\n}', note: '公开四状态是对私有七状态的观察映射，不改变任务执行状态机。' }
      }
    }
  ],
  migrationChecklist: [
    '先以 NEW、COMPLETING、NORMAL、EXCEPTIONAL、CANCELLED、INTERRUPTING、INTERRUPTED 建立 FutureTask 私有协议，再看公开 Future.State 映射。',
    'JDK 8 断点看 UNSAFE 与字段偏移，JDK 17/21 改看 STATE、RUNNER、WAITERS VarHandle；不要在业务代码中反射这些字段。',
    '兼容 JDK 8/17 的产物不能直接链接 resultNow、exceptionNow、state；按最低运行版本选择适配方式。',
    '对 get(timeout)、cancel(true) 和完成竞争的测试必须控制先后关系，不能依赖一次随机调度或私有分支顺序。',
    'toString 只用于人工诊断，不能作为持久化格式，并应评估 callable 内容是否包含敏感参数。'
  ],
  demoTitle: '一次 FutureTask 如何从执行权竞争走到可观察结果',
  demoSteps: [
    { title: '竞争执行权', method: 'run / runner CAS', description: '两个 run 调用只有一个线程能安装 runner 并进入 Callable。', states: { '8': 'UNSAFE.compareAndSwapObject(runnerOffset)', '17': 'RUNNER.compareAndSet', '21': 'RUNNER.compareAndSet；公开 state 仍为 RUNNING' } },
    { title: '发布 outcome', method: 'set / setException / cancel', description: '正常、异常与取消都先竞争 NEW，只有首个成功者可以发布终态。', states: { '8': 'Unsafe CAS → outcome → putOrderedInt', '17': 'STATE CAS → outcome → setRelease', '21': '同一 VarHandle 发布协议' } },
    { title: '唤醒等待者', method: 'finishCompletion / awaitDone', description: '完成线程摘走 WaitNode 栈并 unpark；等待者始终重读 state。', states: { '8': 'deadline 定时计算，UNSAFE 操作 waiters', '17': 'elapsed 定时计算，WAITERS VarHandle', '21': '等待协议稳定，可由平台线程或虚拟线程停放' } },
    { title: '读取结果', method: 'get / resultNow / exceptionNow / state', description: '比较传统阻塞读取与已知完成后的非阻塞分类读取。', states: { '8': 'isDone/isCancelled + get', '17': 'isDone/isCancelled + get；toString 辅助诊断', '21': 'Future.State + resultNow/exceptionNow，FutureTask 直接映射私有状态' } }
  ]
}

const BYTE_BUFFER_SELECTOR_TOPIC: JdkComparisonTopic = {
  id: 'bytebuffer-selector',
  sourceTopicId: 'openjdk8-bytebuffer-selector',
  title: 'ByteBuffer / Selector',
  packageName: 'java.nio / java.nio.channels',
  question: '四指标、selected-key set 和非阻塞 I/O 协议保持稳定时，新版 JDK 如何减少视图操作、兴趣位更新和事件消费中的样板代码？',
  conclusion: 'JDK 8、17、21 都以 position/limit/capacity/mark 保存 Buffer 进度，以 interestOps/readyOps 和三个 key 集合组织 Selector 事件。JDK 17 快照已经汇入 JDK 9 的协变返回、JDK 11 的 Consumer 选择与原子兴趣位、JDK 13 的绝对区间切片；JDK 21 又把 Buffer 层级封闭，并接入仍处于预览状态的 java.lang.foreign.MemorySegment。API 更易用，但 flip、半包、selectedKeys 消费和 wakeup 纪律没有改变。',
  sources: BYTE_BUFFER_SELECTOR_SOURCES,
  timeline: [
    { version: '8', title: '四指标与 selected-key set 基线', summary: 'Buffer 状态切换、共享视图、selectedKeys 显式移除、interestOps/readyOps 和 wakeup 许可构成稳定主线。' },
    { version: '17', title: 'JDK 9/11/13 API 已汇入', summary: '具体 Buffer 类型获得协变 fluent 方法；二参 slice、Consumer 选择和原子兴趣位已经可用，Foreign Memory 仍位于 incubator 模块。' },
    { version: '21', title: '类型层级与内存归属收紧', summary: 'Selector 新 API保持稳定；Buffer 层级已在 JDK 19 封闭，并桥接 java.lang.foreign 预览 API 的 segment 与 session。' }
  ],
  differences: [
    {
      id: 'nio-covariant-fluent-return',
      title: 'fluent 状态方法保留具体 Buffer 类型',
      kind: 'signature',
      summary: 'JDK 8 的 Buffer.position/limit/mark/reset/clear/flip/rewind 都是 final 且返回 Buffer；JDK 17/21 的生成模板覆盖这些方法，返回 ByteBuffer 等具体类型。',
      reason: 'JDK 9 为 Buffer 子类补齐协变返回，链式调用不再丢失静态类型；覆盖仍委托 super，四指标校验和移动规则没有复制成另一套。',
      migrationImpact: '面向 JDK 9+ 可以直接写 ByteBuffer readable = buffer.flip()；需要以 JDK 8 编译的源码仍应拆成两句或显式转换。javap 看到的 Buffer 返回桥接方法是编译器生成物，不是模板重复实现。',
      states: {
        '8': { sourceKey: 'Buffer', line: 356, symbol: 'Buffer.flip()', fingerprint: 'final-buffer-return', code: 'public final Buffer flip() {\n    limit = position;\n    position = 0;\n    mark = -1;\n    return this;\n}', note: 'final 阻止 ByteBuffer 覆盖；表达式静态类型退回 Buffer。' },
        '17': { sourceKey: 'ByteBufferTemplate', line: 1580, symbol: '$Type$Buffer.flip()', fingerprint: 'covariant-buffer-return', code: '@Override\npublic ByteBuffer flip() {\n    super.flip();\n    return this;\n}', note: '模板会按类型生成覆盖方法；状态变化仍由 Buffer.flip 完成。' },
        '21': { sourceKey: 'ByteBufferTemplate', line: 1620, symbol: '$Type$Buffer.flip()', fingerprint: 'covariant-buffer-return', code: '@Override\npublic ByteBuffer flip() {\n    super.flip();\n    return this;\n}', note: 'JDK 21 延续 JDK 9 的协变返回，并生成必要的桥接方法。' }
      }
    },
    {
      id: 'nio-absolute-range-slice',
      title: 'slice 可以直接指定绝对区间',
      kind: 'signature',
      summary: 'JDK 8 只有从当前 position 到 limit 的 slice()；JDK 17/21 快照包含 JDK 13 新增的 slice(index, length)。',
      reason: '调用者可以直接建立当前 limit 内的明确子区间，不必临时改动原 Buffer 的 position/limit 再恢复，减少状态污染和并发误用。',
      migrationImpact: 'index 是相对原 Buffer 逻辑索引 0 的绝对索引，不是相对当前 position；新视图 position=0、limit=capacity=length，内容共享、游标独立，ByteBuffer 新切片默认 BIG_ENDIAN。兼容 JDK 8 时可在 duplicate 上调整边界后 slice。',
      states: {
        '8': { sourceKey: 'ByteBufferTemplate', line: 517, symbol: '$Type$Buffer.slice()', fingerprint: 'remaining-slice-only', code: 'public abstract ByteBuffer slice();', note: '切片范围固定为当前 [position, limit)，没有 index/length 重载。' },
        '17': { sourceKey: 'HeapByteBufferTemplate', line: 120, symbol: 'slice(int,int)', fingerprint: 'absolute-range-slice', code: 'Objects.checkFromIndexSize(index, length, limit());\nreturn new HeapByteBuffer(hb, -1, 0, length, length,\n                          index + offset, segment);', note: '范围只到当前 limit；新视图从 0 开始并共享 hb。' },
        '21': { sourceKey: 'HeapByteBufferTemplate', line: 127, symbol: 'slice(int,int)', fingerprint: 'absolute-range-slice', code: 'Objects.checkFromIndexSize(index, length, limit());\nreturn new HeapByteBuffer(hb, -1, 0, length, length,\n                          index + offset, segment);', note: 'JDK 21 保持 JDK 17 的确定区间语义。' }
      }
    },
    {
      id: 'selector-consumer-action',
      title: 'Selector 可以用 Consumer 直接消费就绪 key',
      kind: 'signature',
      summary: 'JDK 8 只能 select 后遍历 selectedKeys；JDK 17/21 快照包含 JDK 11 新增的 select/selectNow(Consumer<SelectionKey>) 系列。',
      reason: 'action 形式把“选择到一个 ready key 后立即交给处理器”提升为公开入口；本轮新 key 不加入 selected-key set，减少忘记 iterator.remove 的机会。',
      migrationImpact: 'action 在 Selector 及 selected-key set 的同步边界内执行，应短小且避免重入同一 Selector。它不会替业务处理调用前已经遗留的 selected keys；传统集合循环仍有效，也仍需显式删除。',
      states: {
        '8': { sourceKey: 'Selector', line: 297, symbol: 'selectNow()', fingerprint: 'selected-key-set-only', code: 'public abstract int selectNow() throws IOException;\npublic abstract int select(long timeout) throws IOException;\npublic abstract int select() throws IOException;', note: '选择结果只能通过 selectedKeys 集合交给应用消费。' },
        '17': { sourceKey: 'Selector', line: 482, symbol: 'select(Consumer,long)', fingerprint: 'consumer-select', code: 'public int select(Consumer<SelectionKey> action, long timeout)\n        throws IOException { ... }\npublic int selectNow(Consumer<SelectionKey> action) { ... }', note: '本轮 ready key 直接传给 action，不新增到 selected-key set。' },
        '21': { sourceKey: 'Selector', line: 485, symbol: 'select(Consumer,long)', fingerprint: 'consumer-select', code: 'public int select(Consumer<SelectionKey> action, long timeout)\n        throws IOException { ... }\npublic int selectNow(Consumer<SelectionKey> action) { ... }', note: '公开实现与 JDK 17 保持一致。' }
      }
    },
    {
      id: 'selection-key-atomic-interest-ops',
      title: '兴趣位增加原子 OR / AND 更新',
      kind: 'signature',
      summary: 'JDK 8 只有读取与整体替换 interestOps；JDK 17/21 快照包含 JDK 11 新增的 interestOpsOr/interestOpsAnd，并返回更新前的位集合。',
      reason: '跨线程只增加或清除某个位时，不必用“读取 → 计算 → 整体写回”的易丢更新序列；默认实现同步，内建 key 可用 VarHandle 位原子操作。',
      migrationImpact: '原子性只明确保证相对于并发 Or/And。OR 仍校验 Channel 不支持的位，AND 故意允许补码用于清位；修改兴趣位也只保证后续 select 可见，想及时打断当前阻塞选择仍需 wakeup。',
      states: {
        '8': { sourceKey: 'SelectionKey', line: 179, symbol: 'interestOps / interestOps(int)', fingerprint: 'replace-interest-set', code: 'public abstract int interestOps();\npublic abstract SelectionKey interestOps(int ops);', note: '调用者自行读取、按位计算再整体替换，组合操作不是原子的。' },
        '17': { sourceKey: 'SelectionKey', line: 222, symbol: 'interestOpsOr / interestOpsAnd', fingerprint: 'atomic-interest-bit-ops', code: 'public int interestOpsOr(int ops) {\n    synchronized (this) {\n        int oldVal = interestOps();\n        interestOps(oldVal | ops);\n        return oldVal;\n    }\n}', note: 'AND 在第 262 行采用同样结构，返回值都是旧集合。' },
        '21': { sourceKey: 'SelectionKey', line: 222, symbol: 'interestOpsOr / interestOpsAnd', fingerprint: 'atomic-interest-bit-ops', code: 'public int interestOpsOr(int ops) {\n    synchronized (this) {\n        int oldVal = interestOps();\n        interestOps(oldVal | ops);\n        return oldVal;\n    }\n}', note: 'JDK 21 的公开默认实现与 JDK 17 相同。' }
      }
    },
    {
      id: 'buffer-sealed-hierarchy',
      title: 'Buffer 类型层级在 JDK 21 快照中已封闭',
      kind: 'signature',
      summary: 'JDK 8/17 的 Buffer 是普通 abstract class；JDK 21 快照包含 JDK 19 引入的 sealed Buffer，并列出七种被允许的具体 Buffer 家族。',
      reason: 'JDK 把原本受包级构造器限制的内部层级正式写入类型系统和类文件，便于维护自身实现边界，并让反射准确描述允许的子类型。',
      migrationImpact: '外部代码原本就无法正常调用包级构造器继承 Buffer，这不是常规扩展点突然消失；主要影响生成字节码、反射框架和维护 JDK 私有补丁的工具。四指标协议完全不变。',
      states: {
        '8': { sourceKey: 'Buffer', line: 175, symbol: 'class Buffer', fingerprint: 'abstract-open-hierarchy', code: 'public abstract class Buffer { ... }', note: '类型声明没有 sealed 元数据。' },
        '17': { sourceKey: 'Buffer', line: 194, symbol: 'class Buffer', fingerprint: 'abstract-open-hierarchy', code: 'public abstract class Buffer { ... }', note: 'JDK 17 GA 仍是普通抽象类。' },
        '21': { sourceKey: 'Buffer', line: 203, symbol: 'sealed class Buffer', fingerprint: 'sealed-buffer-hierarchy', code: 'public abstract sealed class Buffer\n    permits ByteBuffer, CharBuffer, DoubleBuffer, FloatBuffer,\n            IntBuffer, LongBuffer, ShortBuffer { ... }', note: '该封闭层级自 JDK 19 出现；ByteBuffer 自身也已 sealed。' }
      }
    },
    {
      id: 'buffer-foreign-memory-bridge',
      title: 'Buffer 的外部内存归属从内部代理走向预览 API',
      kind: 'implementation',
      summary: 'JDK 8 的 Buffer 没有 segment；JDK 17 保存内部 MemorySegmentProxy，并由 incubator API 桥接；JDK 21 改存 java.lang.foreign.MemorySegment，公开桥接仍是预览能力。',
      reason: '由外部内存创建的 ByteBuffer 必须继承内存段的生命周期、线程访问限制和有效性检查，不能只保存一个裸地址；JDK 21 将这层归属接入新的 Foreign Function & Memory API。',
      migrationImpact: '不要把 JDK 17 的 jdk.incubator.foreign 包名或 ofByteBuffer 工厂直接搬到 21；JDK 21 的 java.lang.foreign 仍需 --enable-preview。segment 关闭后访问关联 Buffer 会失败，但普通 allocate/allocateDirect 的四指标与 Cleaner 使用方式不因此改变。',
      states: {
        '8': { sourceKey: 'Buffer', line: 175, symbol: 'Buffer fields', fingerprint: 'no-memory-segment-owner', code: 'public abstract class Buffer {\n    private int mark;\n    private int position;\n    private int limit;\n    private int capacity;\n}', note: 'Buffer 只保存边界和地址相关实现，没有外部 MemorySegment 所有者。' },
        '17': { sourceKey: 'Buffer', line: 225, symbol: 'MemorySegmentProxy segment', fingerprint: 'internal-memory-segment-proxy', code: '// Used by buffers generated by the memory access API\nfinal MemorySegmentProxy segment;\nBuffer(..., MemorySegmentProxy segment) {\n    this.segment = segment;\n}', note: '公开实验 API 位于 jdk.incubator.foreign，反向桥接名为 ofByteBuffer。' },
        '21': { sourceKey: 'Buffer', line: 236, symbol: 'MemorySegment segment', fingerprint: 'preview-memory-segment-owner', code: 'final MemorySegment segment;\nBuffer(..., MemorySegment segment) {\n    this.segment = segment;\n}\nfinal MemorySessionImpl session() { ... }', note: '接入 java.lang.foreign 预览 API；asByteBuffer/ofBuffer 共享同一生命周期。' }
      }
    }
  ],
  migrationChecklist: [
    '先把 position、limit、capacity、mark 与 selectedKeys 显式消费当作跨版本不变量，再阅读便利 API。',
    '兼容 JDK 8 的源码不要直接链接协变 flip、slice(index,length)、Consumer select 或 interestOpsOr/And；用反射适配也要保留清晰回退路径。',
    'action 形式不会替你处理旧 selectedKeys，兴趣位原子更新也不会替你 wakeup 正在阻塞的 Selector。',
    'GitHub 固定源码应打开 X-Buffer/Heap-X-Buffer 模板；IDE 附加 src.zip 时才会看到生成后的 ByteBuffer.java。',
    'JDK 21 java.lang.foreign 仍是预览 API，升级前单独验证启动参数、Arena 生命周期和关联 Buffer 的失效行为。'
  ],
  demoTitle: '同一个非阻塞读写循环如何逐步减少状态样板',
  demoSteps: [
    { title: '切换读取边界', method: 'ByteBuffer.flip()', description: '三版都只移动 limit、position 和 mark；变化仅在表达式返回类型。', states: { '8': '返回 Buffer，ByteBuffer 链式类型丢失', '17': '生成协变覆盖，返回 ByteBuffer', '21': '协变返回稳定，四指标协议不变' } },
    { title: '建立消息视图', method: 'slice(index,length)', description: '用一个确定帧区间创建共享内容、独立游标的视图。', states: { '8': 'duplicate → limit/position → slice 手工组合', '17': '直接 slice(index,length)，index 不相对 position', '21': '同一 API；新 ByteBuffer 视图默认 BIG_ENDIAN' } },
    { title: '更新兴趣位', method: 'interestOpsOr / interestOpsAnd', description: '只增加 WRITE 或清除 READ 时比较整体替换与原子位操作。', states: { '8': 'read → bitwise → interestOps(newValue)', '17': 'Or/And 原子更新并返回旧值', '21': '位操作稳定；阻塞 select 仍需 wakeup' } },
    { title: '消费就绪事件', method: 'selectNow(Consumer)', description: '比较 selected-key set 所有权与 action 直接消费。', states: { '8': 'select → iterator → remove → handle', '17': '本轮 ready key 可直接传给 Consumer', '21': 'Consumer 路径稳定，处理器仍须短小且不重入' } },
    { title: '核对内存归属', method: 'MemorySegment.asByteBuffer', description: '区分普通 Buffer 生命周期与外部内存段派生视图。', states: { '8': '无 MemorySegment 桥接', '17': 'incubator API + 内部 proxy', '21': 'java.lang.foreign 预览 API + segment/session' } }
  ]
}

const REFERENCE_WEAK_HASH_MAP_TOPIC: JdkComparisonTopic = {
  id: 'reference-weakhashmap',
  sourceTopicId: 'openjdk8-reference-weakhashmap',
  title: 'Reference / WeakHashMap',
  packageName: 'java.lang.ref / java.util',
  question: '“清 referent、安排入队、消费队列、删除弱键”四个阶段不变时，VM 交接、GC 屏障和弱 referent 判断为何仍持续重构？',
  conclusion: 'JDK 8、17、21 都把 ReferenceQueue 当通知通道，WeakHashMap 也始终由弱 key、强 value 和操作时 expunge 组成。演进集中在 GC 感知的 referent 清除、VM pending-list 批量交接、refersTo 身份判断、队列发布与等待机制以及类型层级；JDK 21 还包含 JDK 19 新增的 newWeakHashMap。公开生命周期协议稳定，但任何版本都不保证 System.gc 后立即清除、入队或缩小 map。',
  sources: REFERENCE_WEAK_HASH_MAP_SOURCES,
  timeline: [
    { version: '8', title: 'Java pending 链与弱键清理基线', summary: 'Reference Handler 从 Java pending 链逐个处理；clear/enqueue 直接写 referent，WeakHashMap 用 Entry.get 判断 key。' },
    { version: '17', title: 'GC 边界与 referent 判断加固', summary: 'pending-list 改由 VM 批量交接，clear0 保留收集器屏障，refersTo 取代不必要的强 referent 读取，队列发布顺序也被修正。' },
    { version: '21', title: '虚拟线程等待与类型层级收紧', summary: 'ReferenceQueue 改用 ReentrantLock/Condition 适配虚拟线程，Reference 已 sealed，并提供按预期映射数创建 WeakHashMap 的工厂。' }
  ],
  differences: [
    {
      id: 'reference-gc-aware-clear',
      title: 'clear / enqueue 改用 GC 感知的 native clear0',
      kind: 'implementation',
      summary: 'JDK 8 的 clear 与 enqueue 直接写 referent=null；JDK 17/21 都调用 private native clear0，并明确普通字段赋值不适用于部分收集器。',
      reason: 'referent 是 GC 特殊字段，清除动作需要保留收集器的通知、屏障和并发 reference processing 语义；把它当普通 Java 字段可能丢失通知或错误延长生命周期。',
      migrationImpact: '公开契约没有改变：clear 不自动入队，enqueue 先清 referent 再尝试入队。不要反射 referent、复制字段赋值或用 GC 时机验证 native 屏障；测试只断言确定性的显式调用结果。',
      states: {
        '8': { sourceKey: 'Reference', line: 264, symbol: 'clear / enqueue', fingerprint: 'plain-referent-null', code: 'public void clear() {\n    this.referent = null;\n}\npublic boolean enqueue() {\n    this.referent = null;\n    return this.queue.enqueue(this);\n}', note: '两个 Java 入口都直接写特殊 referent 字段。' },
        '17': { sourceKey: 'Reference', line: 381, symbol: 'clear / clear0 / enqueue', fingerprint: 'native-clear0', code: 'public void clear() { clear0(); }\nprivate native void clear0();\npublic boolean enqueue() {\n    clear0();\n    return this.queue.enqueue(this);\n}', note: '源码注释明确普通赋值对部分垃圾收集器不够。' },
        '21': { sourceKey: 'Reference', line: 397, symbol: 'clear / clear0 / enqueue', fingerprint: 'native-clear0', code: 'public void clear() { clear0(); }\nprivate native void clear0();\npublic boolean enqueue() {\n    clear0();\n    return this.queue.enqueue(this);\n}', note: 'JDK 21 延续 GC 感知的清除边界；enqueue 位于第 483 行。' }
      }
    },
    {
      id: 'reference-pending-list-handoff',
      title: 'pending 引用从 Java 单项摘取改为 VM 批量交接',
      kind: 'implementation',
      summary: 'JDK 8 的 Reference Handler 在 Java lock 下从静态 pending 链取一个引用；JDK 17/21 先等待 VM pending-list，再一次 getAndClear 整条链后在 Java 侧循环处理。',
      reason: 'VM 与 Reference Handler 的交接需要覆盖并发 GC、Cleaner 进度等待和 NIO 直接内存压力；批量取得列表把 VM 同步与 Java 入队循环分开，减少共享 pending 头上的往返。',
      migrationImpact: '业务代码只能依赖“已注册引用随后可能入队”，不能依赖 pending/discovered 字段、Handler 批次或延迟。跨版本断点从 tryHandlePending 移到 processPendingReferences 与 native pending-list 入口。',
      states: {
        '8': { sourceKey: 'Reference', line: 174, symbol: 'tryHandlePending(boolean)', fingerprint: 'java-pending-single-pop', code: 'synchronized (lock) {\n    if (pending != null) {\n        r = pending;\n        pending = r.discovered;\n        r.discovered = null;\n    } else if (waitForNotify) { lock.wait(); }\n}', note: '每轮在 Java 静态 pending 头上摘一个 Reference。' },
        '17': { sourceKey: 'Reference', line: 248, symbol: 'processPendingReferences()', fingerprint: 'vm-pending-list-batch', code: 'waitForReferencePendingList();\nReference<?> pendingList;\nsynchronized (processPendingLock) {\n    pendingList = getAndClearReferencePendingList();\n    processPendingActive = true;\n}\nwhile (pendingList != null) { ... }', note: 'VM 原子交出整条链，Java Handler 再逐项 Cleaner/入队。' },
        '21': { sourceKey: 'Reference', line: 241, symbol: 'processPendingReferences()', fingerprint: 'vm-pending-list-batch', code: 'waitForReferencePendingList();\nReference<?> pendingList;\nsynchronized (processPendingLock) {\n    pendingList = getAndClearReferencePendingList();\n    processPendingActive = true;\n}\nwhile (pendingList != null) { ... }', note: 'JDK 21 维持同一 VM 批量交接主线。' }
      }
    },
    {
      id: 'reference-refers-to-api',
      title: 'refersTo 提供不强化 referent 的身份判断',
      kind: 'signature',
      summary: 'JDK 8 只有 get/clear/isEnqueued；JDK 17/21 快照包含 JDK 16 的 refersTo，并把 isEnqueued 标记 deprecated。JDK 21 又增加 refersToImpl 层以保住 C2 intrinsic。',
      reason: '判断“是否仍指向这个对象/是否已清除”不需要先把 referent 读成强局部变量；队列消费比 isEnqueued 的瞬时状态更可靠。JDK 21 的非 native 虚调用层避免优化器偏向真实 native 调用。',
      migrationImpact: 'JDK 16+ 用 refersTo(obj) 或 refersTo(null) 做身份判断；需要 JDK 8 兼容时仍只能谨慎保存 get 返回的强局部变量。PhantomReference.get 恒 null，但 refersTo(referent) 在未清除时可以为 true。',
      states: {
        '8': { sourceKey: 'Reference', line: 279, symbol: 'isEnqueued()', fingerprint: 'get-and-is-enqueued-only', code: 'public T get() { return this.referent; }\npublic boolean isEnqueued() {\n    return this.queue == ReferenceQueue.ENQUEUED;\n}', note: '没有 refersTo；读取 referent 会形成当前调用中的强引用。' },
        '17': { sourceKey: 'Reference', line: 365, symbol: 'refersTo(T)', fingerprint: 'refers-to-direct-native', code: 'public final boolean refersTo(T obj) {\n    return refersTo0(obj);\n}\n@IntrinsicCandidate\nnative boolean refersTo0(Object o);', note: 'API 自 JDK 16 引入；isEnqueued 同期被弃用。' },
        '21': { sourceKey: 'Reference', line: 374, symbol: 'refersTo / refersToImpl', fingerprint: 'refers-to-java-dispatch-native-leaf', code: 'public final boolean refersTo(T obj) {\n    return refersToImpl(obj);\n}\nboolean refersToImpl(T obj) { return refersTo0(obj); }\nprivate native boolean refersTo0(Object o);', note: 'Java 覆写层与 private native 叶子分离，公共身份语义不变。' }
      }
    },
    {
      id: 'weakhashmap-refers-to-matching',
      title: 'WeakHashMap 用 refersTo 避免无谓强化弱 key',
      kind: 'implementation',
      summary: 'JDK 8 查询先 Entry.get 再 eq，transfer 也用 get()==null；JDK 17/21 先用 refersTo 判断身份或已清除，只在 equals 回退时才读取活 key。',
      reason: '弱容器的内部判断不应仅为了比较就延长 referent 的强可达窗口；refersTo 可以直接完成身份/null 检查，同时保留不同但 equals key 的 Map 语义。',
      migrationImpact: 'get/put/resize 的公开结果不变，业务代码不要依赖一次查找是否临时保活 key。新版源码断点从 eq 与无条件 e.get 移到 matchesKey/refersTo，GC 清除时机仍不可断言。',
      states: {
        '8': { sourceKey: 'WeakHashMap', line: 286, symbol: 'eq / transfer', fingerprint: 'strong-get-for-match', code: 'private static boolean eq(Object x, Object y) {\n    return x == y || x.equals(y);\n}\n...\nObject key = e.get();\nif (key == null) { ... }', note: '查询与迁移都会先取得可能形成强局部变量的 referent。' },
        '17': { sourceKey: 'WeakHashMap', line: 285, symbol: 'matchesKey / transfer', fingerprint: 'refers-to-before-get', code: 'if (e.refersTo(key)) return true;\nObject k = e.get();\nreturn k != null && key.equals(k);\n...\nif (e.refersTo(null)) { ... }', note: '身份和 stale 判断不必取得 referent，equals 分支才 get。' },
        '21': { sourceKey: 'WeakHashMap', line: 291, symbol: 'matchesKey / transfer', fingerprint: 'refers-to-before-get', code: 'if (e.refersTo(key)) return true;\nObject k = e.get();\nreturn k != null && key.equals(k);\n...\nif (e.refersTo(null)) { ... }', note: 'JDK 21 保持同一弱 referent 判断边界。' }
      }
    },
    {
      id: 'weakhashmap-resize-threshold',
      title: 'JDK 21 在超过 threshold 后才扩容',
      kind: 'implementation',
      summary: 'JDK 8/17 插入后满足 ++size >= threshold 就 resize；JDK 21 改为 ++size > threshold，让表在恰好达到阈值时继续容纳当前映射。',
      reason: 'threshold 表达当前容量与负载因子允许的映射边界，改成超过后扩容可避免恰好到边界时提前分配新表，并与按预期映射数创建工厂的容量语义更一致。',
      migrationImpact: 'Map 键值与弱引用契约不变；只影响临界 put 的分配和 transfer 时点。精确统计扩容次数、峰值分配或在 resize 打断点的性能实验需要按版本调整。',
      states: {
        '8': { sourceKey: 'WeakHashMap', line: 465, symbol: 'put / resize threshold', fingerprint: 'resize-at-threshold', code: 'tab[i] = new Entry<>(k, value, queue, h, e);\nif (++size >= threshold)\n    resize(tab.length * 2);', note: 'size 到达 threshold 的这次 put 就触发扩容。' },
        '17': { sourceKey: 'WeakHashMap', line: 471, symbol: 'put / resize threshold', fingerprint: 'resize-at-threshold', code: 'tab[i] = new Entry<>(k, value, queue, h, e);\nif (++size >= threshold)\n    resize(tab.length * 2);', note: 'JDK 17 仍沿用到达阈值即扩容。' },
        '21': { sourceKey: 'WeakHashMap', line: 477, symbol: 'put / resize threshold', fingerprint: 'resize-after-threshold', code: 'tab[i] = new Entry<>(k, value, queue, h, e);\nif (++size > threshold)\n    resize(tab.length * 2);', note: '恰好达到 threshold 时不扩，下一次超过才触发。' }
      }
    },
    {
      id: 'reference-queue-publication-and-wait',
      title: 'ReferenceQueue 修正发布顺序并改造等待锁',
      kind: 'implementation',
      summary: 'JDK 8 在链入队列前先发布 ENQUEUED；JDK 17 改为先接 head 再发布 queue 状态；JDK 21 保持顺序并用 ReentrantLock/Condition 取代 synchronized/Object.wait。',
      reason: '先发布 ENQUEUED 会让并发 isEnqueued 或 fast-path poll 观察到尚未完成的链表状态；JDK 21 的显式锁与 Condition 还能让阻塞 remove 更好地服务虚拟线程。',
      migrationImpact: 'poll/remove、超时、显式 enqueue 唤醒和中断契约不变。不要用 isEnqueued 作为资源释放判据，也不要反射 queue/head/lock；消费者应以真正 poll/remove 到 Reference 为所有权信号。',
      states: {
        '8': { sourceKey: 'ReferenceQueue', line: 59, symbol: 'enqueue / remove', fingerprint: 'publish-before-link-object-wait', code: 'r.queue = ENQUEUED;\nr.next = (head == null) ? r : head;\nhead = r;\n...\nlock.wait(timeout);', note: '先发布状态再接链，等待使用私有 monitor。' },
        '17': { sourceKey: 'ReferenceQueue', line: 60, symbol: 'enqueue / remove', fingerprint: 'link-before-publish-object-wait', code: 'r.next = (head == null) ? r : head;\nhead = r;\nqueueLength++;\nr.queue = ENQUEUED;\n...\nlock.wait(timeout);', note: '先完成链表写入，再通过 volatile queue 发布 ENQUEUED。' },
        '21': { sourceKey: 'ReferenceQueue', line: 59, symbol: 'ReentrantLock / enqueue0 / remove', fingerprint: 'link-before-publish-condition-wait', code: 'private final ReentrantLock lock;\nprivate final Condition notEmpty;\n...\nr.next = (head == null) ? r : head;\nhead = r;\nr.queue = ENQUEUED;\nnotEmpty.signalAll();', note: '发布顺序保持，remove 在第 210 行通过显式锁与 Condition 等待。' }
      }
    },
    {
      id: 'reference-sealed-hierarchy',
      title: 'Reference 在 JDK 21 快照中成为 sealed 层级',
      kind: 'signature',
      summary: 'JDK 8/17 都是普通 abstract Reference；JDK 21 快照将它封闭为 Soft、Weak、Final、Phantom 四个直接家族，公开具体引用类仍为 non-sealed。',
      reason: 'Reference 必须与 GC 紧密协作，本来就不支持直接外部继承；sealed 把已有约束写入类型系统和类文件，同时允许通过具体引用家族继续受支持的扩展。',
      migrationImpact: '直接生成 Reference 子类的字节码或深反射工具需要适配；正常继承 WeakReference/SoftReference/PhantomReference 仍可行。不要把 sealed 误读成所有自定义引用子类都被禁止。',
      states: {
        '8': { sourceKey: 'Reference', line: 42, symbol: 'class Reference', fingerprint: 'abstract-reference', code: 'public abstract class Reference<T> { ... }', note: 'Javadoc 已说不能直接继承，但类文件没有 sealed 元数据。' },
        '17': { sourceKey: 'Reference', line: 44, symbol: 'class Reference', fingerprint: 'abstract-reference', code: 'public abstract class Reference<T> { ... }', note: 'JDK 17 GA 仍是普通抽象类。' },
        '21': { sourceKey: 'Reference', line: 47, symbol: 'sealed class Reference', fingerprint: 'sealed-reference', code: 'public abstract sealed class Reference<T>\n    permits SoftReference, WeakReference, FinalReference, PhantomReference { ... }', note: '具体公开引用类是 non-sealed，继续允许从对应家族扩展。' }
      }
    },
    {
      id: 'weakhashmap-capacity-factory',
      title: '按预期映射数创建 WeakHashMap',
      kind: 'signature',
      summary: 'JDK 21 快照包含 JDK 19 新增的 WeakHashMap.newWeakHashMap(int)，参数直接表达预期映射数而不是初始桶容量。',
      reason: '构造器容量参数容易与计划元素数混淆；工厂统一按照默认负载因子换算容量，减少批量写入前的重复扩容。',
      migrationImpact: '面向 JDK 19+ 可直接使用；兼容 JDK 8/17 的库仍需普通构造器或自己的容量换算。负数会抛 IllegalArgumentException，工厂不改变弱 key、强 value 或 GC 时机。',
      states: {
        '21': { sourceKey: 'WeakHashMap', line: 1359, symbol: 'newWeakHashMap(int)', fingerprint: 'expected-mappings-factory', code: 'public static <K, V> WeakHashMap<K, V> newWeakHashMap(int numMappings) {\n    if (numMappings < 0) throw new IllegalArgumentException(...);\n    return new WeakHashMap<>(HashMap.calculateHashMapCapacity(numMappings));\n}', note: 'API 自 JDK 19 引入；参数表达预计映射数。' }
      }
    }
  ],
  migrationChecklist: [
    '把 referent 清除、Reference 入队、队列消费和 WeakHashMap expunge 当作四个阶段，不把一次 System.gc 或 size 变化写成完成保证。',
    'JDK 16+ 优先用 refersTo 做身份/已清除判断；isEnqueued 已弃用，真正从队列取到 Reference 才是可靠消费信号。',
    '不要反射 referent、pending、queue/head 或复制 native clear0；这些字段与屏障属于 GC/VM 实现边界。',
    'WeakHashMap 始终是弱 key、强 value且非线程安全；value 回指 key 会跨所有版本破坏预期清理。',
    'Reference.reachabilityFence 自 JDK 9 可用，但它只约束调用点前的可达性，不能替代 close，也不应靠强制 GC 测试。'
  ],
  demoTitle: '一个弱 key 从失去强路径到桶摘除要经过什么',
  demoSteps: [
    { title: '失去外部强路径', method: 'reachability analysis', description: '只剩 Entry 的弱边后，key 才有资格进入弱可达。', states: { '8': '弱 Entry + 强 value', '17': '所有权图不变，refersTo 减少内部强化', '21': '所有权图不变；Reference 类型层级已 sealed' } },
    { title: '清除 referent', method: 'Reference.clear / GC', description: '区分应用显式 clear 与收集器处理，并观察屏障实现变化。', states: { '8': 'Java referent=null', '17': 'native clear0 保留 GC 屏障', '21': '延续 clear0；公开 clear 仍不入队' } },
    { title: '交给 Handler', method: 'pending processing', description: 'GC 与 Java Handler 交接待通知 Reference。', states: { '8': 'Java pending 头逐个摘取', '17': 'VM getAndClear 整条 pending-list', '21': 'VM 批量交接主线稳定' } },
    { title: '进入通知队列', method: 'ReferenceQueue.enqueue/remove', description: '只有消费者真正取到 Reference 才取得清理所有权。', states: { '8': '先 ENQUEUED 后接链，Object.wait', '17': '先接链后发布，Object.wait', '21': '先接链后发布，Condition 等待' } },
    { title: '摘除弱键', method: 'WeakHashMap.expungeStaleEntries', description: 'Map 操作 poll Entry，再按 Entry 身份摘桶并清强 value。', states: { '8': 'Entry.get 判断 stale', '17': 'Entry.refersTo(null) 判断 stale', '21': '判断协议稳定；可用 newWeakHashMap 预估容量' } }
  ]
}

const STREAM_SPLITERATOR_TOPIC: JdkComparisonTopic = {
  id: 'stream-spliterator',
  sourceTopicId: 'openjdk8-java-util-stream-spliterator',
  title: 'Stream / Spliterator',
  packageName: 'java.util.stream / java.util',
  question: '惰性 stage、Sink 融合和 Spliterator 拆分主架构保持稳定时，为什么 count、切片、未知尺寸拆分和有序输出在升级后会走不同路径？',
  conclusion: 'JDK 8、17、21 都以 AbstractPipeline、Sink、Spliterator 和 CountedCompleter 组织惰性流水线与并行求值。JDK 17 快照已经包含精确尺寸 count 快路径、takeWhile 协作取消、skip/limit 尺寸调整和 mapMulti；JDK 21 又让未知尺寸来源的数组批次使用可继续二分的启发式大估计，并用 next + VarHandle 取代 forEachOrdered 的全局 completionMap。升级不会改变结果契约，却会改变副作用是否执行、断点位置和可依赖的尺寸特征。',
  sources: STREAM_SPLITERATOR_SOURCES,
  timeline: [
    { version: '8', title: '惰性流水线与任务树基线', summary: 'count 仍通过 mapToLong(1).sum 遍历，切片清除 SIZED；Sink 取消、批次拆分和 completionMap 组成经典实现。' },
    { version: '17', title: '尺寸推理与零到多映射完善', summary: '精确尺寸 count 可直接返回；copyIntoWithCancel 返回取消结果支持 takeWhile；skip/limit 保留可调整尺寸；mapMulti 进入公开 API。' },
    { version: '21', title: '未知尺寸并行度与有序依赖优化', summary: '快照包含 JDK 19 引入的未知尺寸批次大估计，使数组内部继续二分；forEachOrdered 用任务内 next 指针和 VarHandle CAS 维持前驱关系。' }
  ],
  differences: [
    {
      id: 'stream-count-exact-size-fast-path',
      title: 'count 可以直接返回流水线精确尺寸',
      kind: 'implementation',
      summary: 'JDK 8 的 ReferencePipeline.count 转成每元素 1L 后求和，必须推进元素；JDK 17/21 的 ReduceOps.makeRefCounting 先读取 exactOutputSizeIfKnown，已知时不创建逐元素归约。',
      reason: '对保持 SIZED 的流水线，元素内容不会影响 count；直接使用已知输出大小可以省去 Sink 包装和遍历。filter、flatMap、mapMulti 等破坏精确尺寸的操作仍会回退到归约。',
      migrationImpact: '不要让 peek、map 或其他中间操作承担业务副作用：在 JDK 9+ 的精确尺寸 count 路径中它们可能完全不执行。测试应断言 count 结果，不应跨版本断言这些 lambda 的调用次数。',
      states: {
        '8': { sourceKey: 'ReferencePipeline', line: 592, symbol: 'ReferencePipeline.count()', fingerprint: 'count-by-element-reduction', code: 'public final long count() {\n    return mapToLong(e -> 1L).sum();\n}', note: '每个元素都要变成 1L 并进入 sum，因此四元素源上的 peek 会执行四次。' },
        '17': { sourceKey: 'ReduceOps', line: 247, symbol: 'ReduceOps.makeRefCounting()', fingerprint: 'count-by-exact-size', code: 'long size = helper.exactOutputSizeIfKnown(spliterator);\nif (size != -1)\n    return size;\nreturn super.evaluateSequential(helper, spliterator);', note: '顺序与并行入口都先尝试精确尺寸；四元素 SIZED 源可让 peek 调用次数为 0。' },
        '21': { sourceKey: 'ReduceOps', line: 247, symbol: 'ReduceOps.makeRefCounting()', fingerprint: 'count-by-exact-size', code: 'long size = helper.exactOutputSizeIfKnown(spliterator);\nif (size != -1)\n    return size;\nreturn super.evaluateSequential(helper, spliterator);', note: 'JDK 21 延续尺寸快路径；是否遍历取决于整条流水线能否证明输出大小。' }
      }
    },
    {
      id: 'stream-cancel-result-takewhile',
      title: '取消遍历返回 boolean，供 takeWhile 取消后续分区',
      kind: 'signature',
      summary: 'JDK 8 的 forEachWithCancel 和 copyIntoWithCancel 返回 void；JDK 17/21 返回是否由 Sink 请求取消，WhileOps.TakeWhileTask 据此调用 cancelLaterNodes。',
      reason: 'takeWhile 不只需要停止当前叶，还必须区分“源自然耗尽”和“谓词首次失败”。只有后者才表示有序流后续分区都不应再贡献结果，因此遍历驱动器要把取消原因向任务层返回。',
      migrationImpact: '这是 java.util.stream 内部签名，不是业务 API；自定义代码应使用公开 takeWhile，而不要反射 PipelineHelper。调试 JDK 9+ takeWhile 时要同时观察 Sink 的短路信号、boolean 返回值和后续节点取消。',
      states: {
        '8': { sourceKey: 'AbstractPipeline', line: 492, symbol: 'copyIntoWithCancel(Sink,Spliterator)', fingerprint: 'cancel-copy-returns-void', code: 'final <P_IN> void copyIntoWithCancel(...) {\n    wrappedSink.begin(...);\n    p.forEachWithCancel(spliterator, wrappedSink);\n    wrappedSink.end();\n}', note: '取消只负责停止当前遍历，没有返回值可告诉上层停止原因。' },
        '17': { sourceKey: 'AbstractPipeline', line: 519, symbol: 'copyIntoWithCancel(Sink,Spliterator)', fingerprint: 'cancel-copy-returns-boolean', code: 'final <P_IN> boolean copyIntoWithCancel(...) {\n    boolean cancelled = p.forEachWithCancel(spliterator, wrappedSink);\n    wrappedSink.end();\n    return cancelled;\n}', note: 'WhileOps 第 1208 行接收该结果；谓词失败时会取消遇见顺序更晚的任务。' },
        '21': { sourceKey: 'AbstractPipeline', line: 519, symbol: 'copyIntoWithCancel(Sink,Spliterator)', fingerprint: 'cancel-copy-returns-boolean', code: 'final <P_IN> boolean copyIntoWithCancel(...) {\n    boolean cancelled = p.forEachWithCancel(spliterator, wrappedSink);\n    wrappedSink.end();\n    return cancelled;\n}', note: 'JDK 21 保持同一协作取消协议；并行在途任务仍可能先做少量额外工作。' }
      }
    },
    {
      id: 'stream-slice-size-adjusting',
      title: 'skip / limit 从丢弃尺寸改为精确调整尺寸',
      kind: 'implementation',
      summary: 'JDK 8 的 SliceOps.flags 直接设置 NOT_SIZED；JDK 17/21 改为 IS_SIZE_ADJUSTING，并由 AbstractPipeline.exactOutputSizeIfKnown 逐 stage 计算切片后的精确大小。',
      reason: '对已知大小 n 的顺序流水线，skip 和 limit 的输出数量可由边界公式精确计算，无需永久丢掉 SIZED 信息。这也让切片后的 count 继续使用尺寸快路径。',
      migrationImpact: '同一条 Stream.of(...).skip(1).limit(2).spliterator() 在 JDK 8 的 exact size 是 -1，在 17/21 是 2。不要把 characteristics 或 peek 次数写成跨版本常量；自定义 Spliterator 仍必须如实声明源尺寸。',
      states: {
        '8': { sourceKey: 'SliceOps', line: 548, symbol: 'SliceOps.flags(long)', fingerprint: 'slice-clears-sized', code: 'private static int flags(long limit) {\n    return StreamOpFlag.NOT_SIZED\n        | ((limit != -1) ? StreamOpFlag.IS_SHORT_CIRCUIT : 0);\n}', note: '即使源大小已知，经过 skip/limit 后也不再向下游报告精确尺寸。' },
        '17': { sourceKey: 'SliceOps', line: 562, symbol: 'SliceOps.flags(long)', fingerprint: 'slice-adjusts-sized', code: 'private static int flags(long limit) {\n    return StreamOpFlag.IS_SIZE_ADJUSTING\n        | ((limit != -1) ? StreamOpFlag.IS_SHORT_CIRCUIT : 0);\n}', note: '顺序流水线会沿 stage 调用 exactOutputSize，计算 max(0, min(size-skip, limit))。' },
        '21': { sourceKey: 'SliceOps', line: 562, symbol: 'SliceOps.flags(long)', fingerprint: 'slice-adjusts-sized', code: 'private static int flags(long limit) {\n    return StreamOpFlag.IS_SIZE_ADJUSTING\n        | ((limit != -1) ? StreamOpFlag.IS_SHORT_CIRCUIT : 0);\n}', note: 'JDK 21 保持可调整尺寸协议；并行 stateful stage 的输入 Spliterator 已体现切片大小。' }
      }
    },
    {
      id: 'spliterator-unknown-size-split',
      title: '未知尺寸批次改用可继续二分的启发式估计',
      kind: 'implementation',
      summary: 'JDK 8/17 把 IteratorSpliterator 的批次数组交给普通 ArraySpliterator，构造器自动加入 SIZED/SUBSIZED；JDK 21 对未知 est 使用专用构造器，移除尺寸特征并给出 Long.MAX_VALUE/2 估计。',
      reason: '旧实现返回 estimate=j 的精确数组批次，面对由根部 Long.MAX_VALUE 推导出的巨大叶阈值时，批次内部通常不会再拆。JDK 21 主动给首批次 Long.MAX_VALUE/2 的非精确估计并在二分时继续折半，让已经缓冲的数组也能形成更多并行叶任务；因为估计不再等于元素数，所以必须清除 SIZED/SUBSIZED。',
      migrationImpact: '不要假设 spliteratorUnknownSize(...).trySplit() 返回值一定 SIZED，也不要假设 estimateSize 等于当前数组元素数。并行框架和自定义算法应按 characteristics 解释估计值，而不是用实现样本反推契约。',
      states: {
        '8': { sourceKey: 'Spliterators', line: 1786, symbol: 'IteratorSpliterator.trySplit()', fingerprint: 'unknown-split-becomes-sized', code: 'if (est != Long.MAX_VALUE)\n    est -= j;\nreturn new ArraySpliterator<>(a, 0, j, characteristics);', note: '普通 ArraySpliterator 构造器会 OR SIZED/SUBSIZED，批次 estimate 等于实际 j。' },
        '17': { sourceKey: 'Spliterators', line: 1830, symbol: 'IteratorSpliterator.trySplit()', fingerprint: 'unknown-split-becomes-sized', code: 'if (est != Long.MAX_VALUE)\n    est -= j;\nreturn new ArraySpliterator<>(a, 0, j, characteristics);', note: 'JDK 17 GA 与 JDK 8 相同：未知尺寸父对象拆出的数组批次会报告精确尺寸。' },
        '21': { sourceKey: 'Spliterators', line: 1922, symbol: 'IteratorSpliterator.trySplit()', fingerprint: 'unknown-split-keeps-estimate', code: 'if (est != Long.MAX_VALUE) {\n    est -= j;\n    return new ArraySpliterator<>(a, 0, j, characteristics);\n}\nreturn new ArraySpliterator<>(a, 0, j, characteristics,\n                              Long.MAX_VALUE / 2);', note: '该变化自 JDK 19 引入；专用构造器清除 SIZED/SUBSIZED，并在继续二分时按估计值分摊。' }
      }
    },
    {
      id: 'stream-foreachordered-successor',
      title: 'forEachOrdered 从 completionMap 改为任务内 successor',
      kind: 'implementation',
      summary: 'JDK 8/17 的 ForEachOrderedTask 用 ConcurrentHashMap 保存“左任务完成后释放哪个任务”；JDK 21 把关系保存在 next 字段，并用 VarHandle CAS/getAndSet 原子替换和摘取。',
      reason: '前驱关系本质上是一条任务到后继的直接边。把它保存在任务对象中可避免共享 ConcurrentHashMap 的分配、哈希和竞争，同时继续用 pending count 建立相同的 happens-before 顺序。',
      migrationImpact: 'forEachOrdered 的公开遇见顺序没有变化，上游 map/filter/peek 仍可并行。JDK 21 调试应观察 next、NEXT 和 pending count，不再寻找 completionMap；业务代码不得依赖这些私有字段。',
      states: {
        '8': { sourceKey: 'ForEachOps', line: 367, symbol: 'ForEachOrderedTask.completionMap', fingerprint: 'ordered-completion-map', code: 'private final ConcurrentHashMap<ForEachOrderedTask<S, T>,\n        ForEachOrderedTask<S, T>> completionMap;\n...\ncompletionMap.put(leftChild, rightChild);', note: 'onCompletion 通过 completionMap.remove(this) 找到并释放后继。' },
        '17': { sourceKey: 'ForEachOps', line: 367, symbol: 'ForEachOrderedTask.completionMap', fingerprint: 'ordered-completion-map', code: 'private final ConcurrentHashMap<ForEachOrderedTask<S, T>,\n        ForEachOrderedTask<S, T>> completionMap;\n...\ncompletionMap.put(leftChild, rightChild);', note: 'JDK 17 GA 仍沿用全局完成关系表。' },
        '21': { sourceKey: 'ForEachOps', line: 372, symbol: 'ForEachOrderedTask.next / NEXT', fingerprint: 'ordered-varhandle-successor', code: 'private ForEachOrderedTask<S, T> next;\nprivate static final VarHandle NEXT;\n...\nleftChild.next = rightChild;\nNEXT.compareAndSet(task.leftPredecessor, task, leftChild);', note: 'onCompletion 用 NEXT.getAndSet(this, null) 取得后继，再调用 tryComplete。' }
      }
    },
    {
      id: 'stream-mapmulti-api',
      title: 'mapMulti 提供直接的零到多元素推送',
      kind: 'signature',
      summary: 'JDK 8 没有 mapMulti；JDK 17/21 包含 JDK 16 新增的 mapMulti 系列。Stream 接口提供基于 flatMap + SpinedBuffer 的默认回退，标准 ReferencePipeline 覆盖为直接向 downstream Sink 推送。',
      reason: '每个输入只产生少量结果时，调用 mapper 多次 accept 比为每个输入创建临时 Stream 更直接；默认方法仍让第三方 Stream 实现无需立刻重写即可获得正确语义。',
      migrationImpact: '以 JDK 16+ 为最低版本时可用 mapMulti 表达过滤加展开；兼容 JDK 8 的源码仍需 flatMap 或适配层。mapper 获得的 Consumer 只在本次调用期间有效，不能缓存后异步使用。',
      states: {
        '17': { sourceKey: 'ReferencePipeline', line: 434, symbol: 'ReferencePipeline.mapMulti(BiConsumer)', fingerprint: 'mapmulti-direct-downstream', code: 'public final <R> Stream<R> mapMulti(...) {\n    return new StatelessOp<>(...) {\n        public void accept(P_OUT u) {\n            mapper.accept(u, (Consumer<R>) downstream);\n        }\n    };\n}', note: '公开 API 自 JDK 16 引入；Stream 第 426 行的默认实现会缓冲，标准流水线在这里直接推送。' },
        '21': { sourceKey: 'ReferencePipeline', line: 434, symbol: 'ReferencePipeline.mapMulti(BiConsumer)', fingerprint: 'mapmulti-direct-downstream', code: 'public final <R> Stream<R> mapMulti(...) {\n    return new StatelessOp<>(...) {\n        public void accept(P_OUT u) {\n            mapper.accept(u, (Consumer<R>) downstream);\n        }\n    };\n}', note: 'JDK 21 保持直接 Sink 路径，并同时提供 int、long、double 专用变体。' }
      }
    }
  ],
  migrationChecklist: [
    '先判断整条流水线是否保持精确尺寸，再推导 count 是否遍历；不要从 source 是 ArrayList 就直接得出 peek 会执行。',
    '调试 takeWhile 时区分自然耗尽与 Sink 请求取消，并确认有序并行流怎样取消后续分区。',
    '把 Spliterator.characteristics 当契约读取；未知尺寸 trySplit 的子结果在 JDK 21 不再保证 SIZED。',
    'forEachOrdered 只约束 terminal action 的遇见顺序，不约束上游 lambda 的线程与执行顺序。',
    '按最低运行版本选择 mapMulti 或 flatMap，并且绝不逃逸 mapper 提供的下游 Consumer。'
  ],
  demoTitle: '同一条流水线怎样从逐元素遍历演进到可证明的尺寸与依赖',
  demoSteps: [
    { title: '终止 count', method: 'ReferencePipeline.count / ReduceOps.makeRefCounting', description: '先判断输出尺寸是否可证明，再决定直接返回还是逐元素归约。', states: { '8': 'mapToLong(1).sum，peek 随元素执行', '17': 'exactOutputSizeIfKnown 成功则直接返回', '21': '尺寸快路径稳定，未知尺寸仍回退遍历' } },
    { title: '切片尺寸', method: 'SliceOps.flags / exactOutputSize', description: '观察 skip 与 limit 是清除尺寸，还是对上游精确尺寸做公式调整。', states: { '8': 'NOT_SIZED，exact size = -1', '17': 'IS_SIZE_ADJUSTING，skip(1).limit(2) = 2', '21': '保持尺寸调整协议' } },
    { title: '短路原因', method: 'copyIntoWithCancel / WhileOps', description: '区分源自然耗尽与 takeWhile 谓词失败导致的取消。', states: { '8': 'void，只停止当前遍历；没有 takeWhile', '17': 'boolean 返回；失败时 cancelLaterNodes', '21': '同一协作取消协议' } },
    { title: '未知尺寸拆分', method: 'IteratorSpliterator.trySplit', description: '从未知 iterator 复制一个批次后，比较子 Spliterator 的特征与估计。', states: { '8': '数组批次自动 SIZED，estimate = 实际批次', '17': '仍自动 SIZED', '21': '不含 SIZED，estimate = Long.MAX_VALUE / 2' } },
    { title: '有序输出', method: 'ForEachOrderedTask', description: '让左右叶先并行计算，再沿完成依赖按遇见顺序执行 action。', states: { '8': 'completionMap + pending count', '17': '沿用 ConcurrentHashMap 依赖表', '21': 'next + VarHandle + pending count' } },
    { title: '零到多映射', method: 'Stream.mapMulti / ReferencePipeline.mapMulti', description: '一个输入可向 downstream 推送零个、一个或多个输出。', states: { '8': '没有 API，使用 flatMap', '17': '默认缓冲回退；标准流水线直接推送', '21': '保持直接 Sink 路径与原始类型变体' } }
  ]
}

const rawJdkComparisonTopics: JdkComparisonTopic[] = [
  HASH_MAP_TOPIC,
  CONCURRENT_HASH_MAP_TOPIC,
  THREAD_LOCAL_TOPIC,
  COMPLETABLE_FUTURE_TOPIC,
  LOADER_TOPIC,
  SYNCHRONIZED_TOPIC,
  AQS_REENTRANT_LOCK_TOPIC,
  THREAD_POOL_EXECUTOR_TOPIC,
  FUTURE_TASK_TOPIC,
  BYTE_BUFFER_SELECTOR_TOPIC,
  REFERENCE_WEAK_HASH_MAP_TOPIC,
  STREAM_SPLITERATOR_TOPIC
]

/**
 * 把详细差异与专题索引关联起来，保证版本对比入口不会脱离源码索引单独漂移。
 */
function enrichComparisonTopic(topic: JdkComparisonTopic): JdkComparisonTopic {
  const sourceTopic = sourceTopics.find((candidate) => candidate.topicId === topic.sourceTopicId)
  if (sourceTopic === undefined) {
    throw new Error(`版本对比找不到源码专题: ${topic.sourceTopicId}`)
  }
  if (sourceTopic.versionComparison?.id !== topic.id) {
    throw new Error(`版本对比编号与源码索引不一致: ${topic.id}`)
  }
  return { ...topic, sourceTopic }
}

export const jdkComparisonTopics: JdkComparisonTopic[] = rawJdkComparisonTopics.map(enrichComparisonTopic)

/**
 * 在构建阶段校验版本对比数据的完整性，避免页面运行后才暴露漏版本或错误源码归属。
 */
function validateJdkComparisonTopics(topics: JdkComparisonTopic[]): void {
  const topicIds = new Set<string>()
  // 2026-08-20：保留上一阶段的 7 个专题门槛，作为新增专题时的回归基线。
  // if (topics.length < 7) {
  //   throw new Error(`JDK 版本对比至少包含 7 个试点专题，当前为 ${topics.length} 个`)
  // }
  // 2026-08-20：ThreadPoolExecutor 接入后的 8 专题门槛继续作为回归记录。
  // if (topics.length < 8) {
  //   throw new Error(`JDK 版本对比至少包含 8 个试点专题，当前为 ${topics.length} 个`)
  // }
  // 2026-08-20：保留 FutureTask 接入后的 9 专题门槛，记录旧阶段的最低校验。
  // if (topics.length < 9) {
  //   throw new Error(`JDK 版本对比至少包含 9 个试点专题，当前为 ${topics.length} 个`)
  // }
  if (topics.length < 12) {
    throw new Error(`JDK 版本对比至少包含 12 个专题，当前为 ${topics.length} 个`)
  }

  topics.forEach((topic) => {
    if (topicIds.has(topic.id)) {
      throw new Error(`JDK 版本对比包含重复专题编号: ${topic.id}`)
    }
    topicIds.add(topic.id)
    if (topic.sourceTopic === undefined || topic.sourceTopic.versionComparison?.id !== topic.id) {
      throw new Error(`专题 ${topic.id} 缺少有效的 source-index 版本对比元数据`)
    }

    const differenceIds = new Set<string>()
    jdkComparisonVersions.forEach((version) => {
      const sources = topic.sources[version]
      const sourceKeys = new Set(sources.map((source) => source.sourceKey))
      if (sources.length === 0 || sourceKeys.size !== sources.length) {
        throw new Error(`专题 ${topic.id} 的 JDK ${version} 源码坐标为空或 sourceKey 重复`)
      }
      if (topic.timeline.filter((point) => point.version === version).length !== 1) {
        throw new Error(`专题 ${topic.id} 的 JDK ${version} 时间线节点必须且只能有一个`)
      }
    })

    topic.differences.forEach((difference) => {
      if (differenceIds.has(difference.id)) {
        throw new Error(`专题 ${topic.id} 包含重复差异编号: ${difference.id}`)
      }
      differenceIds.add(difference.id)

      const states = Object.entries(difference.states) as Array<[
        JdkComparisonVersion,
        JdkDifferenceState
      ]>
      if (states.length === 0) {
        throw new Error(`专题 ${topic.id} 的差异 ${difference.id} 没有任何版本状态`)
      }
      states.forEach(([version, state]) => {
        const sourceExists = topic.sources[version]
          .some((source) => source.sourceKey === state.sourceKey)
        if (!sourceExists || state.line < 1) {
          throw new Error(`专题 ${topic.id} 的差异 ${difference.id} 指向无效的 JDK ${version} 源码坐标`)
        }
      })
    })

    if (topic.demoSteps.length === 0) {
      throw new Error(`专题 ${topic.id} 缺少版本演示步骤`)
    }
    topic.demoSteps.forEach((step) => {
      jdkComparisonVersions.forEach((version) => {
        if (step.states[version].trim().length === 0) {
          throw new Error(`专题 ${topic.id} 的演示步骤 ${step.title} 缺少 JDK ${version} 状态`)
        }
      })
    })
  })
}

validateJdkComparisonTopics(jdkComparisonTopics)

/**
 * 按专题、版本和源码标识找到真实仓库坐标。
 */
export function findJdkSource(
  topic: JdkComparisonTopic,
  version: JdkComparisonVersion,
  sourceKey: string
): JdkSourceCoordinate | undefined {
  return topic.sources[version].find((source) => source.sourceKey === sourceKey)
}

/**
 * 生成包含固定 Tag 与行号的 GitHub 链接，避免链接随默认分支漂移。
 */
export function jdkSourceUrl(
  topic: JdkComparisonTopic,
  version: JdkComparisonVersion,
  sourceKey: string,
  line?: number
): string {
  const source = findJdkSource(topic, version, sourceKey)
  if (source === undefined) return ''
  const meta = jdkVersionMeta[version]
  const anchor = line === undefined ? '' : `#L${line}`
  return `https://github.com/${meta.repository}/blob/${encodeURIComponent(meta.sourceRef)}/${source.sourcePath}${anchor}`
}

/**
 * 判断一个精选差异在左右版本之间是否真的发生变化。
 */
export function hasDifferenceBetween(
  difference: JdkVersionDifference,
  leftVersion: JdkComparisonVersion,
  rightVersion: JdkComparisonVersion
): boolean {
  const left = difference.states[leftVersion]
  const right = difference.states[rightVersion]
  if (left === undefined || right === undefined) return left !== right
  return left.fingerprint !== right.fingerprint
}

/**
 * 根据比较方向计算差异类型；反向比较时新增与移除自动互换。
 */
export function differenceKindForDirection(
  difference: JdkVersionDifference,
  leftVersion: JdkComparisonVersion,
  rightVersion: JdkComparisonVersion
): JdkDifferenceKind {
  const left = difference.states[leftVersion]
  const right = difference.states[rightVersion]
  if (left === undefined && right !== undefined) return 'added'
  if (left !== undefined && right === undefined) return 'removed'
  return difference.kind
}
