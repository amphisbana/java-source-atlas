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

const HASH_MAP_TOPIC: JdkComparisonTopic = {
  id: 'hashmap',
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
const LOADER_TOPIC: JdkComparisonTopic = {
  id: 'classloader-service-loader',
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

export const jdkComparisonTopics: JdkComparisonTopic[] = [
  HASH_MAP_TOPIC,
  CONCURRENT_HASH_MAP_TOPIC,
  THREAD_LOCAL_TOPIC,
  COMPLETABLE_FUTURE_TOPIC,
  LOADER_TOPIC
]

/**
 * 在构建阶段校验版本对比数据的完整性，避免页面运行后才暴露漏版本或错误源码归属。
 */
function validateJdkComparisonTopics(topics: JdkComparisonTopic[]): void {
  const topicIds = new Set<string>()
  if (topics.length !== 5) {
    throw new Error(`JDK 版本对比必须包含 5 个试点专题，当前为 ${topics.length} 个`)
  }

  topics.forEach((topic) => {
    if (topicIds.has(topic.id)) {
      throw new Error(`JDK 版本对比包含重复专题编号: ${topic.id}`)
    }
    topicIds.add(topic.id)

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
