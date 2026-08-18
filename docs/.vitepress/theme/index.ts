import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import HashMapIndexCalculator from './HashMapIndexCalculator.vue'
import HashMapStructure from './HashMapStructure.vue'
import HashMapPutAnimation from './HashMapPutAnimation.vue'
import HashMapResizeAnimation from './HashMapResizeAnimation.vue'
import ArrayListMutationAnimation from './ArrayListMutationAnimation.vue'
import ConcurrentHashMapAnimation from './ConcurrentHashMapAnimation.vue'
import AtomicStripedAnimation from './AtomicStripedAnimation.vue'
import JmmMemoryModelAnimation from './JmmMemoryModelAnimation.vue'
import SynchronizedMonitorAnimation from './SynchronizedMonitorAnimation.vue'
import ThreadPoolExecutorAnimation from './ThreadPoolExecutorAnimation.vue'
import ThreadPoolWorkerAnimation from './ThreadPoolWorkerAnimation.vue'
import AqsQueueAnimation from './AqsQueueAnimation.vue'
import AqsSharedPropagationAnimation from './AqsSharedPropagationAnimation.vue'
import ConditionTransferAnimation from './ConditionTransferAnimation.vue'
import LinkedHashMapAccessAnimation from './LinkedHashMapAccessAnimation.vue'
import TreeMapInsertionAnimation from './TreeMapInsertionAnimation.vue'
import TreeMapDeletionAnimation from './TreeMapDeletionAnimation.vue'
import CopyOnWriteSnapshotAnimation from './CopyOnWriteSnapshotAnimation.vue'
import CompletableFuturePipelineAnimation from './CompletableFuturePipelineAnimation.vue'
import BlockingQueueAnimation from './BlockingQueueAnimation.vue'
import FutureTaskAnimation from './FutureTaskAnimation.vue'
import SpringRefreshAnimation from './SpringRefreshAnimation.vue'
import SpringConfigurationClassAnimation from './SpringConfigurationClassAnimation.vue'
import SpringDependencyResolutionAnimation from './SpringDependencyResolutionAnimation.vue'
import ScheduledExecutorAnimation from './ScheduledExecutorAnimation.vue'
import ConcurrentLinkedQueueAnimation from './ConcurrentLinkedQueueAnimation.vue'
import ThreadLocalMapAnimation from './ThreadLocalMapAnimation.vue'
import ForkJoinPoolAnimation from './ForkJoinPoolAnimation.vue'
import StreamSpliteratorAnimation from './StreamSpliteratorAnimation.vue'
import ClassLoaderServiceLoaderAnimation from './ClassLoaderServiceLoaderAnimation.vue'
import ReflectionProxyAnimation from './ReflectionProxyAnimation.vue'
import ThreadLockSupportAnimation from './ThreadLockSupportAnimation.vue'
import NioBufferSelectorAnimation from './NioBufferSelectorAnimation.vue'
import ReferenceWeakHashMapAnimation from './ReferenceWeakHashMapAnimation.vue'
import SpringAopAnimation from './SpringAopAnimation.vue'
import SpringBeanLifecycleAnimation from './SpringBeanLifecycleAnimation.vue'
import SpringTransactionAnimation from './SpringTransactionAnimation.vue'
import SpringBootAutoConfigurationAnimation from './SpringBootAutoConfigurationAnimation.vue'
import SpringMvcDispatchAnimation from './SpringMvcDispatchAnimation.vue'
import SpringDeepDiveMap from './SpringDeepDiveMap.vue'
import SourceExplorer from './SourceExplorer.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  /**
   * 注册源码专题使用的交互式学习组件。
   */
  enhanceApp({ app }) {
    app.component('HashMapIndexCalculator', HashMapIndexCalculator)
    app.component('HashMapStructure', HashMapStructure)
    app.component('HashMapPutAnimation', HashMapPutAnimation)
    app.component('HashMapResizeAnimation', HashMapResizeAnimation)
    app.component('ArrayListMutationAnimation', ArrayListMutationAnimation)
    app.component('ConcurrentHashMapAnimation', ConcurrentHashMapAnimation)
    app.component('AtomicStripedAnimation', AtomicStripedAnimation)
    app.component('JmmMemoryModelAnimation', JmmMemoryModelAnimation)
    app.component('SynchronizedMonitorAnimation', SynchronizedMonitorAnimation)
    app.component('ThreadPoolExecutorAnimation', ThreadPoolExecutorAnimation)
    app.component('ThreadPoolWorkerAnimation', ThreadPoolWorkerAnimation)
    app.component('AqsQueueAnimation', AqsQueueAnimation)
    app.component('AqsSharedPropagationAnimation', AqsSharedPropagationAnimation)
    app.component('ConditionTransferAnimation', ConditionTransferAnimation)
    app.component('LinkedHashMapAccessAnimation', LinkedHashMapAccessAnimation)
    app.component('TreeMapInsertionAnimation', TreeMapInsertionAnimation)
    app.component('TreeMapDeletionAnimation', TreeMapDeletionAnimation)
    app.component('CopyOnWriteSnapshotAnimation', CopyOnWriteSnapshotAnimation)
    app.component('CompletableFuturePipelineAnimation', CompletableFuturePipelineAnimation)
    app.component('BlockingQueueAnimation', BlockingQueueAnimation)
    app.component('FutureTaskAnimation', FutureTaskAnimation)
    app.component('SpringRefreshAnimation', SpringRefreshAnimation)
    app.component('SpringConfigurationClassAnimation', SpringConfigurationClassAnimation)
    app.component('SpringDependencyResolutionAnimation', SpringDependencyResolutionAnimation)
    app.component('ScheduledExecutorAnimation', ScheduledExecutorAnimation)
    app.component('ConcurrentLinkedQueueAnimation', ConcurrentLinkedQueueAnimation)
    app.component('ThreadLocalMapAnimation', ThreadLocalMapAnimation)
    app.component('ForkJoinPoolAnimation', ForkJoinPoolAnimation)
    app.component('StreamSpliteratorAnimation', StreamSpliteratorAnimation)
    app.component('ClassLoaderServiceLoaderAnimation', ClassLoaderServiceLoaderAnimation)
    app.component('ReflectionProxyAnimation', ReflectionProxyAnimation)
    app.component('ThreadLockSupportAnimation', ThreadLockSupportAnimation)
    app.component('NioBufferSelectorAnimation', NioBufferSelectorAnimation)
    app.component('ReferenceWeakHashMapAnimation', ReferenceWeakHashMapAnimation)
    app.component('SpringAopAnimation', SpringAopAnimation)
    app.component('SpringBeanLifecycleAnimation', SpringBeanLifecycleAnimation)
    app.component('SpringTransactionAnimation', SpringTransactionAnimation)
    app.component('SpringBootAutoConfigurationAnimation', SpringBootAutoConfigurationAnimation)
    app.component('SpringMvcDispatchAnimation', SpringMvcDispatchAnimation)
    app.component('SpringDeepDiveMap', SpringDeepDiveMap)
    app.component('SourceExplorer', SourceExplorer)
  }
} satisfies Theme
