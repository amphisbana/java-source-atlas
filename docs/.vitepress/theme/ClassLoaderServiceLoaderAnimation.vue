<script setup lang="ts">
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type Tone = 'idle' | 'active' | 'visited' | 'success' | 'blocked'

interface LoaderState {
  id: string
  label: string
  scope: string
  tone: Tone
}

interface DescriptorLine {
  text: string
  tone: Tone
}

interface LoaderServiceSnapshot {
  call: string
  tccl: string
  classState: string
  instanceState: string
  decision: string
  loaders: LoaderState[]
  descriptor: DescriptorLine[]
  cache: string[]
}

const steps: SourceAnimationStep[] = [
  {
    title: 'ServiceLoader 保存 TCCL',
    method: 'ServiceLoader.load(Codec.class)',
    description: '默认入口读取当前线程 contextClassLoader 并保存为 configured loader。此时只创建发现器，不扫描 JAR，也不构造 provider。'
  },
  {
    title: '创建组合迭代器',
    method: 'knownProviders = providers.entrySet().iterator()',
    description: 'iterator() 只创建缓存 entry 的迭代器并保留共享 lookupIterator。此时既不扫描 SPI 描述文件，也不加载或构造 provider。'
  },
  {
    title: 'hasNext 才推进惰性发现',
    method: 'knownProviders.hasNext() || lookupIterator.hasNext()',
    description: '缓存为空时，第一次 hasNext 才进入 LazyIterator.hasNextService。它可以完成资源枚举和名称解析，但不会在 hasNext 阶段构造 provider。'
  },
  {
    title: '枚举 SPI 描述资源',
    method: 'loader.getResources(PREFIX + service.getName())',
    description: 'PluginLoader 枚举 META-INF/services/demo.spi.Codec。资源可见性由 configured loader 决定，不由 Codec.class 的名称自动推导。'
  },
  {
    title: '解析名称并忽略重复项',
    method: 'parse(url) -> pending provider names',
    description: '注释和空白被清理，重复的 PluginCodec 只保留一次。单文件行顺序可见，但多个 JAR 的全局资源顺序不应作为业务优先级。'
  },
  {
    title: 'next 选择待创建名称',
    method: 'next() -> lookupIterator.next() -> nextService()',
    description: '缓存没有 entry，next 转给 LazyIterator。nextService 取走 hasNext 已放入 nextName 的 PluginCodec；若调用方没先调用 hasNext，它也会自行检查。'
  },
  {
    title: '按名称请求 provider Class',
    method: 'Class.forName("plugin.PluginCodec", false, loader)',
    description: 'initialize=false 请求加载但不主动执行 static 初始化。Class.forName 把二进制名交给 configured PluginLoader。'
  },
  {
    title: '先检查本加载器的 initiating 记录',
    method: 'findLoadedClass("plugin.PluginCodec")',
    description: 'PluginLoader 在类名加载锁内查询 JVM 的 initiating-loader 记录。首次请求没有命中；若命中，返回 Class 的 defining loader 也可能是父层。'
  },
  {
    title: '沿 parent 链寻找共享定义',
    method: 'parent.loadClass(name, false)',
    description: '请求先经过 App、Platform/Extension 与 Bootstrap。它们看不见插件实现，于是把 ClassNotFoundException 交回子加载器；共享 Codec 接口则应在 App 层命中。'
  },
  {
    title: '插件加载器本地定义实现',
    method: 'PluginLoader.findClass -> defineClass',
    description: '父链失败后才读取插件字节并 define。PluginLoader 成为 PluginCodec 的定义加载器，Class 已产生但尚未因这次调用而初始化。'
  },
  {
    title: '校验服务类型身份',
    method: 'Codec.class.isAssignableFrom(providerClass)',
    description: 'provider 实现的 Codec 必须与调用方传入的 Codec 是同一个运行时类型。只有全限定名相同、定义加载器不同仍会失败。'
  },
  {
    title: '初始化并调用无参构造器',
    method: 'providerClass.newInstance()',
    description: 'JDK 8 的 classpath 路径在 nextService 中创建 public 无参 provider。new 会触发尚未完成的类初始化，并得到 PluginCodec#1。'
  },
  {
    title: '把实例写入有序缓存',
    method: 'providers.put(className, instance)',
    description: '实例按 provider 二进制名放入当前 ServiceLoader 的 LinkedHashMap。缓存属于这个发现器对象，不是 JVM 全局 singleton。'
  },
  {
    title: '下一次迭代复用实例',
    method: 'knownProviders.next().getValue()',
    description: '从同一个 ServiceLoader 再取得 iterator，会先返回缓存中的 PluginCodec#1，不会因为创建新 iterator 就重新调用构造器。'
  },
  {
    title: 'reload 清空发现状态',
    method: 'providers.clear(); lookupIterator = new LazyIterator(...)',
    description: 'reload 只清本 ServiceLoader 的实例缓存和发现游标。下一次迭代会重新扫描并创建实例，但不会卸载 Class 或自动关闭旧 provider。'
  }
]

const baseDescriptor: DescriptorLine[] = [
  { text: 'plugin.PluginCodec', tone: 'idle' },
  { text: 'plugin.PluginCodec  # duplicate', tone: 'idle' },
  { text: 'plugin.BackupCodec', tone: 'idle' }
]

// 快照固定一种父层不含插件实现、共享 SPI 接口由 AppLoader 定义的合法插件布局。
const snapshots: LoaderServiceSnapshot[] = [
  {
    call: 'load(Codec.class)', tccl: 'PluginLoader', classState: '尚未查找', instanceState: '0 个实例',
    decision: '只保存 service 与 configured loader',
    loaders: loaderStates('plugin', 'active'), descriptor: baseDescriptor, cache: []
  },
  {
    call: 'iterator()', tccl: 'PluginLoader', classState: '尚未查找', instanceState: '0 个实例',
    decision: '只创建 providers.entrySet() 的 iterator',
    loaders: loaderStates('', 'idle'), descriptor: baseDescriptor, cache: []
  },
  {
    call: 'iterator.hasNext()', tccl: 'PluginLoader', classState: '开始发现名称', instanceState: '0 个实例',
    decision: '缓存 MISS，调用 lookupIterator.hasNext()',
    loaders: loaderStates('', 'idle'), descriptor: baseDescriptor, cache: []
  },
  {
    call: 'getResources(configName)', tccl: 'PluginLoader', classState: '尚未查找', instanceState: '0 个实例',
    decision: '找到 plugin.jar!/META-INF/services/...',
    loaders: loaderStates('plugin', 'active'),
    descriptor: baseDescriptor.map((line) => ({ ...line, tone: 'visited' })), cache: []
  },
  {
    call: 'parse(configUrl)', tccl: 'PluginLoader', classState: 'provider 名称已排队', instanceState: '0 个实例',
    decision: 'PluginCodec 重复行被忽略',
    loaders: loaderStates('', 'idle'),
    descriptor: [
      { text: 'plugin.PluginCodec', tone: 'success' },
      { text: 'plugin.PluginCodec  # duplicate', tone: 'blocked' },
      { text: 'plugin.BackupCodec', tone: 'visited' }
    ], cache: []
  },
  {
    call: 'iterator.next()', tccl: 'PluginLoader', classState: '选择 PluginCodec', instanceState: '0 个实例',
    decision: '缓存无 entry，转入 nextService()',
    loaders: loaderStates('', 'idle'), descriptor: parsedDescriptor(), cache: []
  },
  {
    call: 'Class.forName(name, false, loader)', tccl: 'PluginLoader', classState: 'LOAD 请求', instanceState: '0 个实例',
    decision: 'initialize=false，不主动运行 static 块',
    loaders: loaderStates('plugin', 'active'), descriptor: parsedDescriptor(), cache: []
  },
  {
    call: 'findLoadedClass(name)', tccl: 'PluginLoader', classState: '首次请求：未命中', instanceState: '0 个实例',
    decision: '在 class loading lock 内继续父委派',
    loaders: loaderStates('plugin', 'active'), descriptor: parsedDescriptor(), cache: []
  },
  {
    call: 'parent.loadClass(name)', tccl: 'PluginLoader', classState: '父链均未定义 provider', instanceState: '0 个实例',
    decision: 'App -> Platform/Ext -> Bootstrap：MISS',
    loaders: [
      loader('plugin', 'PluginLoader', '插件实现与资源', 'idle'),
      loader('app', 'AppLoader', '共享 Codec API', 'visited'),
      loader('platform', 'Platform / Ext', '平台 API', 'visited'),
      loader('bootstrap', 'Bootstrap', 'java.base / 核心类', 'visited')
    ], descriptor: parsedDescriptor(), cache: []
  },
  {
    call: 'findClass -> defineClass', tccl: 'PluginLoader', classState: '已定义 / 未初始化', instanceState: '0 个实例',
    decision: 'defining loader = PluginLoader',
    loaders: loaderStates('plugin', 'success'), descriptor: parsedDescriptor(), cache: []
  },
  {
    call: 'service.isAssignableFrom(c)', tccl: 'PluginLoader', classState: '类型检查通过', instanceState: '0 个实例',
    decision: '双方引用 AppLoader 定义的同一个 Codec',
    loaders: [
      loader('plugin', 'PluginLoader', 'PluginCodec 定义处', 'success'),
      loader('app', 'AppLoader', 'Codec 定义处', 'success'),
      loader('platform', 'Platform / Ext', '平台 API', 'idle'),
      loader('bootstrap', 'Bootstrap', 'java.base / 核心类', 'idle')
    ], descriptor: parsedDescriptor(), cache: []
  },
  {
    call: 'newInstance()', tccl: 'PluginLoader', classState: '已初始化', instanceState: 'PluginCodec#1',
    decision: 'static 初始化完成，public 无参构造器返回',
    loaders: loaderStates('plugin', 'success'), descriptor: parsedDescriptor(), cache: []
  },
  {
    call: 'providers.put(name, instance)', tccl: 'PluginLoader', classState: '已初始化', instanceState: 'PluginCodec#1',
    decision: '当前 ServiceLoader 缓存一个实例',
    loaders: loaderStates('plugin', 'success'), descriptor: parsedDescriptor(), cache: ['PluginCodec#1']
  },
  {
    call: 'knownProviders.next().getValue()', tccl: 'PluginLoader', classState: 'Class 直接复用', instanceState: 'PluginCodec#1',
    decision: '新 iterator 返回同一个缓存实例',
    loaders: loaderStates('', 'idle'), descriptor: parsedDescriptor(), cache: ['PluginCodec#1']
  },
  {
    call: 'reload()', tccl: 'PluginLoader', classState: 'Class 仍由 PluginLoader 定义', instanceState: '缓存已清空',
    decision: '下一次迭代重新发现；旧实例生命周期由调用方收口',
    loaders: loaderStates('plugin', 'visited'), descriptor: baseDescriptor, cache: []
  }
]

/**
 * 创建一个固定位置的加载器节点。
 *
 * @param id 节点标识
 * @param label 页面显示名称
 * @param scope 负责范围
 * @param tone 当前步骤状态
 * @return 加载器节点快照
 */
function loader(id: string, label: string, scope: string, tone: Tone): LoaderState {
  return { id, label, scope, tone }
}

/**
 * 创建标准四层加载器快照，并激活一个指定节点。
 *
 * @param activeId 需要着色的节点，空字符串表示没有活动节点
 * @param activeTone 活动节点状态
 * @return 按请求端到父端排列的节点数组
 */
function loaderStates(activeId: string, activeTone: Tone): LoaderState[] {
  return [
    loader('plugin', 'PluginLoader', '插件实现与资源', activeId === 'plugin' ? activeTone : 'idle'),
    loader('app', 'AppLoader', '共享 Codec API', activeId === 'app' ? activeTone : 'idle'),
    loader('platform', 'Platform / Ext', '平台 API', activeId === 'platform' ? activeTone : 'idle'),
    loader('bootstrap', 'Bootstrap', 'java.base / 核心类', activeId === 'bootstrap' ? activeTone : 'idle')
  ]
}

/**
 * 返回已经完成去重的描述文件视图。
 *
 * @return 三行配置及其解析状态
 */
function parsedDescriptor(): DescriptorLine[] {
  return [
    { text: 'plugin.PluginCodec', tone: 'success' },
    { text: 'plugin.PluginCodec  # duplicate', tone: 'blocked' },
    { text: 'plugin.BackupCodec', tone: 'idle' }
  ]
}
</script>

<template>
  <SourceAnimation title="ServiceLoader 如何借 ClassLoader 发现并缓存 provider" :steps="steps" :interval="2300">
    <template #visual="{ currentIndex }">
      <div class="loader-service-flow">
        <div class="loader-service-flow__summary">
          <span>TCCL <strong>{{ snapshots[currentIndex].tccl }}</strong></span>
          <span>Class <strong>{{ snapshots[currentIndex].classState }}</strong></span>
          <span>provider <strong>{{ snapshots[currentIndex].instanceState }}</strong></span>
          <code>{{ snapshots[currentIndex].call }}</code>
        </div>

        <section class="loader-chain" aria-label="类加载器父链">
          <div
            v-for="(item, index) in snapshots[currentIndex].loaders"
            :key="item.id"
            class="loader-chain__item"
          >
            <div class="loader-chain__node" :class="[`is-${item.tone}`]">
              <strong>{{ item.label }}</strong>
              <span>{{ item.scope }}</span>
            </div>
            <span v-if="index < snapshots[currentIndex].loaders.length - 1" class="loader-chain__arrow">
              <span>parent</span><i aria-hidden="true">→</i>
            </span>
          </div>
        </section>

        <div class="loader-service-flow__details">
          <section class="service-descriptor" aria-label="SPI 描述文件">
            <header>
              <strong>META-INF/services/demo.spi.Codec</strong>
              <code>UTF-8</code>
            </header>
            <div class="service-descriptor__lines">
              <code
                v-for="(line, index) in snapshots[currentIndex].descriptor"
                :key="`${index}-${line.text}`"
                :class="[`is-${line.tone}`]"
              >{{ line.text }}</code>
            </div>
          </section>

          <section class="provider-cache" aria-label="ServiceLoader provider 缓存">
            <header>
              <strong>providers / LinkedHashMap</strong>
              <code>{{ snapshots[currentIndex].cache.length }} entry</code>
            </header>
            <div class="provider-cache__body">
              <span v-if="!snapshots[currentIndex].cache.length">空缓存</span>
              <strong v-for="provider in snapshots[currentIndex].cache" :key="provider">
                plugin.PluginCodec → {{ provider }}
              </strong>
            </div>
          </section>
        </div>

        <div class="loader-service-flow__decision">
          <code>当前决策</code>
          <span>{{ snapshots[currentIndex].decision }}</span>
        </div>
      </div>
    </template>
  </SourceAnimation>
</template>

<style scoped>
.loader-service-flow {
  display: grid;
  gap: 17px;
  min-height: 360px;
}

.loader-service-flow__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 7px 17px;
  align-items: center;
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
}

.loader-service-flow__summary strong {
  margin-left: 4px;
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
}

.loader-service-flow__summary code {
  margin-left: auto;
  color: var(--vp-c-brand-1);
  font-size: 0.68rem;
  overflow-wrap: anywhere;
}

.loader-chain {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 48px minmax(0, 1fr) 48px minmax(0, 1fr) 48px minmax(0, 1fr);
  gap: 0;
  align-items: center;
}

.loader-chain__item {
  display: contents;
}

.loader-chain__node {
  display: grid;
  min-width: 0;
  min-height: 76px;
  place-content: center;
  gap: 5px;
  padding: 9px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
  text-align: center;
  transition: border-color 260ms ease, background-color 260ms ease, transform 260ms ease;
}

.loader-chain__node.is-active {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
  transform: translateY(-3px);
}

.loader-chain__node.is-visited {
  border-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 8%, transparent);
}

.loader-chain__node.is-success {
  border-color: var(--vp-c-tip-1);
  background: color-mix(in srgb, var(--vp-c-tip-1) 9%, transparent);
}

.loader-chain__node strong {
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
  overflow-wrap: anywhere;
}

.loader-chain__node span {
  color: var(--vp-c-text-3);
  font-size: 0.63rem;
  overflow-wrap: anywhere;
}

.loader-chain__arrow {
  position: relative;
  display: flex;
  place-items: center;
  align-items: center;
  justify-content: center;
  width: auto;
  min-width: 0;
  height: 28px;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.55rem;
  text-align: center;
}

.loader-chain__arrow::before {
  position: absolute;
  z-index: 0;
  right: 4px;
  left: 4px;
  height: 1px;
  background: var(--atlas-line);
  content: '';
}

.loader-chain__arrow span,
.loader-chain__arrow i {
  position: relative;
  z-index: 1;
  background: var(--vp-c-bg);
  font-style: normal;
}

.loader-chain__arrow span {
  padding-left: 3px;
}

.loader-chain__arrow i {
  padding-right: 3px;
}

.loader-service-flow__details {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 0.8fr);
  gap: 12px;
}

.service-descriptor,
.provider-cache {
  min-width: 0;
  border-top: 1px solid var(--atlas-line);
}

.service-descriptor header,
.provider-cache header {
  display: flex;
  min-width: 0;
  justify-content: space-between;
  gap: 8px;
  padding: 9px 2px 7px;
}

.service-descriptor header strong,
.provider-cache header strong {
  min-width: 0;
  font-size: 0.68rem;
  overflow-wrap: anywhere;
}

.service-descriptor header code,
.provider-cache header code {
  flex: 0 0 auto;
  color: var(--vp-c-text-3);
  font-size: 0.62rem;
}

.service-descriptor__lines,
.provider-cache__body {
  display: grid;
  min-height: 104px;
  align-content: center;
  gap: 5px;
  padding: 10px;
  border: 1px solid var(--atlas-line);
  background: var(--atlas-surface);
}

.service-descriptor__lines code {
  min-width: 0;
  padding: 4px 6px;
  border-left: 3px solid transparent;
  color: var(--vp-c-text-2);
  font-size: 0.64rem;
  overflow-wrap: anywhere;
  transition: border-color 240ms ease, color 240ms ease, opacity 240ms ease;
}

.service-descriptor__lines code.is-visited {
  border-left-color: var(--vp-c-brand-1);
}

.service-descriptor__lines code.is-success {
  border-left-color: var(--vp-c-tip-1);
  color: var(--vp-c-tip-1);
}

.service-descriptor__lines code.is-blocked {
  color: var(--atlas-coral);
  opacity: 0.58;
  text-decoration: line-through;
}

.provider-cache__body {
  place-items: center;
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.provider-cache__body strong {
  min-width: 0;
  padding: 7px 9px;
  border: 1px solid var(--vp-c-tip-1);
  background: color-mix(in srgb, var(--vp-c-tip-1) 9%, transparent);
  color: var(--atlas-ink);
  font-family: var(--vp-font-family-mono);
  font-size: 0.64rem;
  overflow-wrap: anywhere;
  text-align: center;
  animation: provider-cache-enter 360ms ease-out both;
}

.loader-service-flow__decision {
  display: flex;
  min-width: 0;
  justify-content: space-between;
  gap: 14px;
  padding-top: 8px;
  border-top: 1px solid var(--atlas-line);
  color: var(--vp-c-text-2);
  font-size: 0.7rem;
}

.loader-service-flow__decision code {
  flex: 0 0 auto;
  color: var(--atlas-coral);
}

.loader-service-flow__decision span {
  min-width: 0;
  overflow-wrap: anywhere;
  text-align: right;
}

@keyframes provider-cache-enter {
  from { opacity: 0; transform: translateY(5px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 760px) {
  .loader-service-flow {
    min-height: 660px;
  }

  .loader-service-flow__summary code {
    width: 100%;
    margin-left: 0;
  }

  .loader-chain {
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .loader-chain__node {
    min-height: 58px;
  }

  .loader-chain__arrow {
    width: 100%;
    height: 24px;
  }

  .loader-chain__arrow::before {
    top: 0;
    bottom: 0;
    left: 50%;
    width: 1px;
    height: auto;
  }

  .loader-chain__arrow i {
    transform: rotate(90deg);
  }

  .loader-service-flow__details {
    grid-template-columns: 1fr;
  }

  .loader-service-flow__decision {
    display: grid;
    gap: 4px;
  }

  .loader-service-flow__decision span {
    text-align: left;
  }
}

@media (prefers-reduced-motion: reduce) {
  .loader-chain__node,
  .provider-cache__body strong {
    transition-duration: 0.01ms !important;
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
  }
}
</style>
