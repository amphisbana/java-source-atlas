<script setup lang="ts">
import { computed, ref } from 'vue'

type LaneId = 'boot' | 'ioc' | 'aop' | 'mvc' | 'transaction'
type ScenarioId = 'startup' | 'request' | 'rollback'

interface FlowLane {
  id: LaneId
  label: string
  shortLabel: string
}

interface FlowState {
  label: string
  value: string
}

interface FlowStep {
  lane: LaneId
  title: string
  method: string
  description: string
  state: FlowState[]
}

interface FlowScenario {
  id: ScenarioId
  label: string
  summary: string
  steps: FlowStep[]
}

const lanes: FlowLane[] = [
  { id: 'boot', label: 'Spring Boot', shortLabel: 'Boot' },
  { id: 'ioc', label: 'IOC 容器', shortLabel: 'IOC' },
  { id: 'aop', label: 'AOP 代理', shortLabel: 'AOP' },
  { id: 'mvc', label: 'Spring MVC', shortLabel: 'MVC' },
  { id: 'transaction', label: '事务基础设施', shortLabel: 'TX' }
]

const scenarios: FlowScenario[] = [
  {
    id: 'startup',
    label: '启动装配',
    summary: '从 SpringApplication.run 到 MVC 策略 Bean 和事务代理全部可用。',
    steps: [
      {
        lane: 'boot',
        title: '建立启动上下文',
        method: 'SpringApplication.run(String...)',
        description: '创建 BootstrapContext 和 RunListeners，随后准备 Environment；此时还没有可用的 ApplicationContext，也没有业务 Bean。',
        state: [
          { label: '输入', value: 'sources + args' },
          { label: '关键状态', value: 'Environment 准备中' },
          { label: '尚未发生', value: 'refresh / 单例创建' }
        ]
      },
      {
        lane: 'boot',
        title: '创建并准备容器',
        method: 'createApplicationContext -> prepareContext',
        description: '创建 Web ApplicationContext，应用 Initializer、注册主配置源并发布 contextPrepared/contextLoaded 事件；这里只是把定义来源交给容器。',
        state: [
          { label: '上下文', value: 'ConfigurableApplicationContext' },
          { label: 'BeanFactory', value: '尚未完成后处理' },
          { label: '主配置', value: '已作为 BeanDefinition 来源加载' }
        ]
      },
      {
        lane: 'ioc',
        title: '进入 refresh 模板',
        method: 'SpringApplication.refreshContext -> AbstractApplicationContext.refresh',
        description: 'Boot 把控制权交给 Framework 的容器模板。prepareBeanFactory 只装配基础能力，真正展开配置类发生在 BeanFactoryPostProcessor 阶段。',
        state: [
          { label: '容器状态', value: 'active=true，尚不可对外服务' },
          { label: '定义集合', value: '用户配置已登记，仍可扩展' },
          { label: '单例缓存', value: '基础设施之外大多为空' }
        ]
      },
      {
        lane: 'ioc',
        title: '展开配置类定义',
        method: 'PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors',
        description: '先执行 BeanDefinitionRegistryPostProcessor。ConfigurationClassPostProcessor 解析 @Configuration、@Import、@Bean 和延迟导入选择器。',
        state: [
          { label: '处理对象', value: 'BeanDefinitionRegistry' },
          { label: '重要处理器', value: 'ConfigurationClassPostProcessor' },
          { label: '约束', value: '此阶段不应提前创建普通业务 Bean' }
        ]
      },
      {
        lane: 'boot',
        title: '选择自动配置',
        method: 'AutoConfigurationImportSelector.AutoConfigurationGroup.selectImports',
        description: '延迟导入组加载候选、处理排除并批量评估条件。命中的自动配置被读成 BeanDefinition，而不是立即创建成对象。',
        state: [
          { label: '输入', value: '候选配置类名 + Environment + 当前定义' },
          { label: '输出', value: '通过条件的自动配置类名' },
          { label: '典型结果', value: 'AOP / TX / MVC 基础设施定义进入工厂' }
        ]
      },
      {
        lane: 'ioc',
        title: '注册 Bean 后处理器',
        method: 'PostProcessorRegistrationDelegate.registerBeanPostProcessors',
        description: '按 PriorityOrdered、Ordered、普通和内部处理器分组注册。自动代理创建器此时成为 BeanPostProcessor，才能观察后续业务 Bean 生命周期。',
        state: [
          { label: '处理对象', value: 'Bean 实例' },
          { label: '关键成员', value: 'AnnotationAwareAspectJAutoProxyCreator' },
          { label: '顺序意义', value: '必须早于业务单例创建' }
        ]
      },
      {
        lane: 'ioc',
        title: '创建非懒加载单例',
        method: 'finishBeanFactoryInitialization -> preInstantiateSingletons',
        description: 'BeanFactory 逐个 getBean。doCreateBean 完成实例化、三级缓存工厂、属性填充、初始化和销毁登记。',
        state: [
          { label: '当前对象', value: '原始 target' },
          { label: '循环依赖窗口', value: '实例化后、初始化完成前' },
          { label: '缓存目标', value: '最终只向 singletonObjects 发布稳定引用' }
        ]
      },
      {
        lane: 'aop',
        title: '把业务 Bean 包装为代理',
        method: 'AbstractAutoProxyCreator.postProcessAfterInitialization -> wrapIfNecessary',
        description: '自动代理创建器查找事务等适用 Advisor，创建 JDK 或 CGLIB 代理；发生 Setter 循环时则用 getEarlyBeanReference 提前产出同一代理。',
        state: [
          { label: 'target', value: '保存业务状态的真实实例' },
          { label: 'proxy', value: '容器最终暴露的引用' },
          { label: '一致性', value: '早期代理与最终暴露代理必须相同' }
        ]
      },
      {
        lane: 'mvc',
        title: '建立 Web 策略表',
        method: 'DispatcherServlet.onRefresh -> initStrategies',
        description: 'DispatcherServlet 从 WebApplicationContext 取得 HandlerMapping、HandlerAdapter、异常解析器等策略；RequestMappingHandlerMapping 已扫描容器 Bean 并登记 HandlerMethod。',
        state: [
          { label: '路由来源', value: '容器中的 Controller BeanDefinition / Bean' },
          { label: '执行入口', value: 'HandlerMethod + HandlerAdapter' },
          { label: '事务能力', value: 'Controller 调用的 Service 已是代理引用' }
        ]
      },
      {
        lane: 'boot',
        title: '发布就绪并运行 Runner',
        method: 'listeners.started -> callRunners -> listeners.running',
        description: 'refresh 成功后 Boot 才调用 ApplicationRunner/CommandLineRunner 并发布运行事件。Runner 失败属于 refresh 之后的启动失败，不能误判为 Bean 创建阶段。',
        state: [
          { label: '容器', value: 'refresh 已完成' },
          { label: '代理与路由', value: '均已建立' },
          { label: '下一阶段', value: '开始接收请求' }
        ]
      }
    ]
  },
  {
    id: 'request',
    label: '正常请求',
    summary: '一次 HTTP 请求怎样经过 MVC、业务代理和事务资源，再返回响应。',
    steps: [
      {
        lane: 'mvc',
        title: '请求进入前端控制器',
        method: 'DispatcherServlet.doDispatch(request, response)',
        description: '检查 multipart 后依次查找 HandlerMapping；DispatcherServlet 只负责编排，不直接解析 @PathVariable，也不直接反射调用 Controller。',
        state: [
          { label: '输入', value: 'HttpServletRequest' },
          { label: '当前变量', value: 'mappedHandler=null' },
          { label: '下一步', value: '选择 HandlerExecutionChain' }
        ]
      },
      {
        lane: 'mvc',
        title: '匹配路由与适配器',
        method: 'getHandler -> getHandlerAdapter',
        description: 'RequestMappingHandlerMapping 选择最具体映射并返回 HandlerMethod；RequestMappingHandlerAdapter 因 supports(handler) 接管注解方法。',
        state: [
          { label: 'handler', value: 'HandlerMethod' },
          { label: '附加对象', value: 'HandlerExecutionChain + interceptors' },
          { label: 'adapter', value: 'RequestMappingHandlerAdapter' }
        ]
      },
      {
        lane: 'mvc',
        title: '解析参数并调用 Controller',
        method: 'ServletInvocableHandlerMethod.invokeAndHandle',
        description: '参数解析器先把请求转换成 Java 参数；验证或转换失败时 Controller 尚未执行。doInvoke 最终对容器提供的 Bean 发起调用。',
        state: [
          { label: '参数', value: 'resolved arguments' },
          { label: 'Controller', value: '容器 Bean，可能也是代理' },
          { label: '失败边界', value: '参数异常发生在业务事务之前' }
        ]
      },
      {
        lane: 'aop',
        title: 'Service 外部调用进入代理',
        method: 'JdkDynamicAopProxy.invoke / CglibAopProxy.intercept',
        description: 'Controller 持有 IOC 注入的 Service 代理。代理按 method 和 targetClass 取得拦截器链，并创建 ReflectiveMethodInvocation。',
        state: [
          { label: '调用者', value: 'Controller target' },
          { label: '被调用对象', value: 'Service proxy' },
          { label: '链', value: '安全、日志、事务等已排序 Interceptor' }
        ]
      },
      {
        lane: 'transaction',
        title: '事务拦截器取得事务',
        method: 'TransactionInterceptor.invoke -> createTransactionIfNecessary',
        description: '解析 TransactionAttribute、选择管理器并调用 getTransaction。没有现有事务的 REQUIRED 会创建新 TransactionStatus。',
        state: [
          { label: '属性', value: 'propagation=REQUIRED' },
          { label: '状态', value: 'newTransaction=true' },
          { label: '调用栈', value: 'TransactionInfo 已绑定当前线程' }
        ]
      },
      {
        lane: 'transaction',
        title: '绑定物理资源',
        method: 'DataSourceTransactionManager.doBegin -> bindResource',
        description: '取得 Connection、关闭自动提交并把 ConnectionHolder 绑定到 TransactionSynchronizationManager。DAO 随后通过同一 DataSource key 复用它。',
        state: [
          { label: 'resources', value: '{dataSource -> connectionHolder}' },
          { label: '连接', value: 'autoCommit=false' },
          { label: '线程', value: '请求处理线程' }
        ]
      },
      {
        lane: 'aop',
        title: '推进到真实业务方法',
        method: 'ReflectiveMethodInvocation.proceed -> invokeJoinpoint',
        description: '链游标到末端后调用 target。target 内部 this 调用不会重新经过代理；跨 Bean 调用才会建立新的 AOP/事务边界。',
        state: [
          { label: 'target', value: 'Service 实例' },
          { label: '事务资源', value: '当前线程可见' },
          { label: '自调用', value: '不会重新进入 TransactionInterceptor' }
        ]
      },
      {
        lane: 'transaction',
        title: '提交并解绑资源',
        method: 'commitTransactionAfterReturning -> cleanupAfterCompletion',
        description: '目标正常返回后提交物理事务、触发同步回调，并在 finally 路径解绑 ConnectionHolder。提交失败会替代业务正常返回。',
        state: [
          { label: '完成动作', value: 'doCommit' },
          { label: 'TransactionStatus', value: 'completed=true' },
          { label: '线程资源', value: '已解绑并清理' }
        ]
      },
      {
        lane: 'mvc',
        title: '处理返回值并写响应',
        method: 'HandlerMethodReturnValueHandler.handleReturnValue',
        description: '事务已完成后，返回值处理器才把对象写成响应体或生成 ModelAndView。消息转换失败不会自动回滚已经提交的事务。',
        state: [
          { label: '业务结果', value: 'Controller returnValue' },
          { label: 'HTTP 输出', value: 'response body / ModelAndView' },
          { label: '事务', value: '此时通常已经完成' }
        ]
      }
    ]
  },
  {
    id: 'rollback',
    label: '异常回滚',
    summary: '业务异常先由事务边界完成资源决策，之后才交给 MVC 异常解析。',
    steps: [
      {
        lane: 'mvc',
        title: 'Controller 调用 Service',
        method: 'InvocableHandlerMethod.doInvoke',
        description: '参数已经解析完成，Controller 进入业务代码并调用容器注入的 Service 代理。MVC 此时尚不知道后面是否会发生业务异常。',
        state: [
          { label: 'handler', value: 'Controller method' },
          { label: '调用对象', value: 'Service proxy' },
          { label: 'dispatchException', value: 'null' }
        ]
      },
      {
        lane: 'aop',
        title: '事务通知包围目标调用',
        method: 'ReflectiveMethodInvocation.proceed -> TransactionInterceptor.invoke',
        description: '事务 Interceptor 位于 AOP 链中。它先取得事务，再调用 invocation.proceed；异常会沿同一 Java 调用栈返回事务通知。',
        state: [
          { label: '链游标', value: '位于 TransactionInterceptor' },
          { label: '目标', value: '尚未返回' },
          { label: '资源', value: '事务已激活' }
        ]
      },
      {
        lane: 'transaction',
        title: '内层 REQUIRED 标记回滚',
        method: 'processRollback -> doSetRollbackOnly',
        description: '参与外层物理事务的内层 REQUIRED 没有独立提交权。异常符合规则时，它把共享事务标记 global rollback-only。',
        state: [
          { label: '内层状态', value: 'newTransaction=false' },
          { label: '共享资源', value: 'globalRollbackOnly=true' },
          { label: '物理回滚', value: '留给最外层 owner' }
        ]
      },
      {
        lane: 'transaction',
        title: '异常规则决定完成动作',
        method: 'completeTransactionAfterThrowing(txInfo, throwable)',
        description: 'RuntimeException/Error 默认回滚，受检异常默认提交；rollbackFor/noRollbackFor 会改变 rollbackOn 的结果。通知完成后仍重新抛出原异常。',
        state: [
          { label: '异常', value: 'RuntimeException' },
          { label: 'rollbackOn', value: 'true' },
          { label: '传播', value: '异常继续向 Controller/MVC 冒泡' }
        ]
      },
      {
        lane: 'transaction',
        title: '最外层执行物理回滚',
        method: 'AbstractPlatformTransactionManager.rollback -> processRollback',
        description: '拥有新物理事务的 status 调用具体管理器 doRollback，随后执行 afterCompletion 并清理同步状态。',
        state: [
          { label: '完成动作', value: 'doRollback' },
          { label: 'Connection', value: 'rollback()' },
          { label: 'status', value: 'completed=true' }
        ]
      },
      {
        lane: 'transaction',
        title: '解绑线程资源',
        method: 'cleanupAfterCompletion -> doCleanupAfterCompletion',
        description: '解绑 ConnectionHolder、恢复 autoCommit 并释放连接。MVC 异常解析开始前，请求线程不应继续持有这次事务资源。',
        state: [
          { label: 'resources', value: '{}' },
          { label: 'TransactionInfo', value: '恢复上一层或清空' },
          { label: '事务同步', value: '已清理' }
        ]
      },
      {
        lane: 'mvc',
        title: 'MVC 捕获处理异常',
        method: 'DispatcherServlet.processHandlerException',
        description: '异常离开代理和 Controller 后，doDispatch 把它交给 HandlerExceptionResolver。异常解析器决定 HTTP 语义，不再决定数据库事务。',
        state: [
          { label: 'dispatchException', value: '业务异常' },
          { label: 'resolver', value: 'ExceptionHandlerExceptionResolver 等' },
          { label: '事务', value: '已回滚并清理' }
        ]
      },
      {
        lane: 'mvc',
        title: '异常处理方法生成响应',
        method: 'ExceptionHandlerExceptionResolver.doResolveHandlerMethodException',
        description: '@ExceptionHandler 或 @ControllerAdvice 可以把异常转换成响应体或 ModelAndView。它吞掉的是 HTTP 异常传播，不会撤销已经发生的回滚。',
        state: [
          { label: 'HTTP 状态', value: '由异常处理器决定' },
          { label: 'ModelAndView', value: '已处理或响应已写入' },
          { label: 'afterCompletion', value: '接收已解析后的结果' }
        ]
      },
      {
        lane: 'mvc',
        title: '完成请求收尾',
        method: 'processDispatchResult -> triggerAfterCompletion',
        description: '渲染或响应写入结束后执行拦截器收尾。若异常解析器自己失败，新异常沿外层 dispatch try/catch 继续传播。',
        state: [
          { label: '响应', value: '已写出错误结果' },
          { label: '拦截器', value: 'afterCompletion' },
          { label: '请求', value: '结束' }
        ]
      }
    ]
  }
]

const selectedScenarioId = ref<ScenarioId>('startup')
const activeStepIndex = ref(0)

/**
 * 返回当前选中的运行场景；数据异常时回退到启动装配场景。
 */
const activeScenario = computed(() => (
  scenarios.find((scenario) => scenario.id === selectedScenarioId.value) ?? scenarios[0]
))

/**
 * 返回当前场景正在展示的源码步骤。
 */
const activeStep = computed(() => activeScenario.value.steps[activeStepIndex.value])

/**
 * 按“当前步骤 / 总步骤”计算进度，使视觉进度与读屏步骤口径一致。
 */
const progressPercent = computed(() => (
  activeScenario.value.steps.length === 0
    ? 0
    : ((activeStepIndex.value + 1) / activeScenario.value.steps.length) * 100
))

/**
 * 切换跨链路场景，并从该场景的第一个源码步骤开始。
 *
 * @param scenarioId 目标场景编号
 */
function selectScenario(scenarioId: ScenarioId): void {
  selectedScenarioId.value = scenarioId
  activeStepIndex.value = 0
}

/**
 * 跳转到指定步骤；模板只传入当前场景的合法索引。
 *
 * @param index 目标步骤索引
 */
function selectStep(index: number): void {
  activeStepIndex.value = index
}

/**
 * 回到上一个步骤，位于起点时保持不变。
 */
function previousStep(): void {
  activeStepIndex.value = Math.max(0, activeStepIndex.value - 1)
}

/**
 * 前进到下一个步骤，位于终点时保持不变。
 */
function nextStep(): void {
  activeStepIndex.value = Math.min(
    activeScenario.value.steps.length - 1,
    activeStepIndex.value + 1
  )
}

/**
 * 返回某条链路截至当前步骤的状态，用于区分未进入、当前和已经过。
 *
 * @param laneId 链路编号
 * @return 当前、已经过或等待
 */
function laneStatus(laneId: LaneId): 'active' | 'visited' | 'pending' {
  if (activeStep.value.lane === laneId) {
    return 'active'
  }
  const lastVisitedIndex = activeScenario.value.steps
    .slice(0, activeStepIndex.value + 1)
    .map((step) => step.lane)
    .lastIndexOf(laneId)
  return lastVisitedIndex >= 0 ? 'visited' : 'pending'
}

/**
 * 返回链路节点的简短状态文字。
 *
 * @param laneId 链路编号
 * @return 页面展示文字
 */
function laneStatusLabel(laneId: LaneId): string {
  const status = laneStatus(laneId)
  if (status === 'active') {
    return '当前执行'
  }
  return status === 'visited' ? '已经过' : '等待进入'
}
</script>

<template>
  <section class="deep-map" aria-label="Spring 五条源码链路交互图">
    <div class="deep-map__scenario" role="group" aria-label="选择运行场景">
      <button
        v-for="scenario in scenarios"
        :key="scenario.id"
        type="button"
        :class="{ 'is-active': selectedScenarioId === scenario.id }"
        :aria-pressed="selectedScenarioId === scenario.id"
        @click="selectScenario(scenario.id)"
      >
        {{ scenario.label }}
      </button>
    </div>

    <p class="deep-map__summary">{{ activeScenario.summary }}</p>

    <div
      class="deep-map__progress"
      role="progressbar"
      :aria-label="`${activeScenario.label}进度`"
      :aria-valuenow="activeStepIndex + 1"
      aria-valuemin="1"
      :aria-valuemax="activeScenario.steps.length"
      :aria-valuetext="`第 ${activeStepIndex + 1} 步，共 ${activeScenario.steps.length} 步`"
    >
      <span :style="{ width: `${progressPercent}%` }"></span>
    </div>

    <div class="deep-map__lanes" aria-label="链路状态">
      <div
        v-for="(lane, index) in lanes"
        :key="lane.id"
        class="deep-map__lane"
        :class="[`is-${lane.id}`, `is-${laneStatus(lane.id)}`]"
      >
        <span class="deep-map__lane-index">{{ index + 1 }}</span>
        <span>
          <strong>{{ lane.label }}</strong>
          <small>{{ laneStatusLabel(lane.id) }}</small>
        </span>
      </div>
    </div>

    <div class="deep-map__steps" role="group" :aria-label="`${activeScenario.label}步骤`">
      <button
        v-for="(step, index) in activeScenario.steps"
        :key="`${activeScenario.id}-${step.method}`"
        type="button"
        :class="{ 'is-active': activeStepIndex === index, 'is-visited': activeStepIndex > index }"
        :aria-pressed="activeStepIndex === index"
        :aria-label="`第 ${index + 1} 步：${step.title}`"
        @click="selectStep(index)"
      >
        {{ index + 1 }}
      </button>
    </div>

    <div class="deep-map__detail-live" aria-live="polite" aria-atomic="true">
      <Transition name="deep-map-step" mode="out-in">
        <article :key="`${selectedScenarioId}-${activeStepIndex}`" class="deep-map__detail">
          <div class="deep-map__detail-heading">
            <span>第 {{ activeStepIndex + 1 }} / {{ activeScenario.steps.length }} 步 · {{ activeStep.lane.toUpperCase() }}</span>
            <strong>{{ activeStep.title }}</strong>
            <code>{{ activeStep.method }}</code>
          </div>
          <p>{{ activeStep.description }}</p>
          <dl>
            <div v-for="state in activeStep.state" :key="state.label">
              <dt>{{ state.label }}</dt>
              <dd>{{ state.value }}</dd>
            </div>
          </dl>
        </article>
      </Transition>
    </div>

    <div class="deep-map__navigation">
      <button
        type="button"
        aria-label="上一步"
        :disabled="activeStepIndex === 0"
        @click="previousStep"
      >
        ←
      </button>
      <span>{{ activeStep.title }}</span>
      <button
        type="button"
        aria-label="下一步"
        :disabled="activeStepIndex === activeScenario.steps.length - 1"
        @click="nextStep"
      >
        →
      </button>
    </div>
  </section>
</template>

<style scoped>
.deep-map {
  --deep-line: color-mix(in srgb, var(--vp-c-divider) 88%, transparent);
  margin: 24px 0 32px;
  color: var(--vp-c-text-1);
  letter-spacing: 0;
}

.deep-map__scenario {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border: 1px solid var(--deep-line);
  border-radius: 6px;
  overflow: hidden;
}

.deep-map__scenario button {
  min-height: 42px;
  border: 0;
  border-right: 1px solid var(--deep-line);
  background: var(--vp-c-bg);
  color: var(--vp-c-text-2);
  cursor: pointer;
  font: inherit;
  font-weight: 700;
}

.deep-map__scenario button:last-child {
  border-right: 0;
}

.deep-map__scenario button.is-active {
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
}

.deep-map__summary {
  min-height: 26px;
  margin: 12px 0 8px;
  color: var(--vp-c-text-2);
  font-size: 0.84rem;
}

.deep-map__progress {
  height: 3px;
  overflow: hidden;
  background: var(--deep-line);
}

.deep-map__progress span {
  display: block;
  height: 100%;
  background: var(--vp-c-brand-1);
  transition: width 220ms ease;
}

.deep-map__lanes {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
  margin-top: 16px;
}

.deep-map__lane {
  --lane-color: var(--vp-c-text-3);
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  min-height: 68px;
  padding: 10px;
  border-top: 3px solid var(--lane-color);
  background: var(--vp-c-bg-soft);
  opacity: 0.62;
  transition: opacity 180ms ease, transform 180ms ease, background-color 180ms ease;
}

.deep-map__lane.is-boot {
  --lane-color: var(--vp-c-warning-1);
}

.deep-map__lane.is-ioc {
  --lane-color: var(--vp-c-brand-1);
}

.deep-map__lane.is-aop {
  --lane-color: var(--atlas-coral);
}

.deep-map__lane.is-mvc {
  --lane-color: var(--vp-c-text-2);
}

.deep-map__lane.is-transaction {
  --lane-color: var(--vp-c-danger-1);
}

.deep-map__lane.is-visited {
  opacity: 0.82;
}

.deep-map__lane.is-active {
  background: color-mix(in srgb, var(--lane-color) 11%, var(--vp-c-bg));
  opacity: 1;
  transform: translateY(-3px);
}

.deep-map__lane-index {
  display: grid;
  flex: 0 0 25px;
  width: 25px;
  height: 25px;
  place-items: center;
  border-radius: 50%;
  background: var(--lane-color);
  color: var(--vp-c-bg);
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
  font-weight: 700;
}

.deep-map__lane > span:last-child {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.deep-map__lane strong,
.deep-map__lane small {
  overflow-wrap: anywhere;
}

.deep-map__lane strong {
  font-size: 0.8rem;
}

.deep-map__lane small {
  color: var(--vp-c-text-3);
  font-size: 0.68rem;
}

.deep-map__steps {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 18px;
}

.deep-map__steps button,
.deep-map__navigation button {
  display: grid;
  place-items: center;
  border: 1px solid var(--deep-line);
  background: var(--vp-c-bg);
  color: var(--vp-c-text-2);
  cursor: pointer;
  font: inherit;
  font-weight: 700;
}

.deep-map__steps button {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  font-family: var(--vp-font-family-mono);
  font-size: 0.74rem;
}

.deep-map__steps button.is-visited {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.deep-map__steps button.is-active {
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-1);
  color: var(--vp-c-bg);
}

.deep-map__detail {
  min-height: 270px;
  margin-top: 14px;
  padding: 18px 0;
  border-block: 1px solid var(--deep-line);
}

.deep-map__detail-heading {
  display: grid;
  gap: 5px;
}

.deep-map__detail-heading > span {
  color: var(--vp-c-text-3);
  font-size: 0.72rem;
  font-weight: 700;
}

.deep-map__detail-heading > strong {
  font-size: 1rem;
}

.deep-map__detail-heading code {
  width: fit-content;
  max-width: 100%;
  overflow-wrap: anywhere;
  white-space: normal;
}

.deep-map__detail > p {
  margin: 13px 0 0;
  color: var(--vp-c-text-2);
  font-size: 0.86rem;
  line-height: 1.75;
}

.deep-map__detail dl {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 16px 0 0;
  border-top: 1px solid var(--deep-line);
}

.deep-map__detail dl > div {
  min-width: 0;
  padding: 11px 12px 0 0;
}

.deep-map__detail dt {
  color: var(--vp-c-text-3);
  font-size: 0.7rem;
  font-weight: 700;
}

.deep-map__detail dd {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-text-1);
  font-size: 0.8rem;
}

.deep-map__navigation {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) 38px;
  gap: 12px;
  align-items: center;
  margin-top: 12px;
}

.deep-map__navigation button {
  width: 38px;
  height: 36px;
  border-radius: 4px;
  font-size: 1rem;
}

.deep-map__navigation button:disabled {
  cursor: not-allowed;
  opacity: 0.38;
}

.deep-map__navigation span {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--vp-c-text-2);
  font-size: 0.78rem;
  text-align: center;
}

.deep-map-step-enter-active,
.deep-map-step-leave-active {
  transition: opacity 120ms ease, transform 120ms ease;
}

.deep-map-step-enter-from {
  opacity: 0;
  transform: translateX(8px);
}

.deep-map-step-leave-to {
  opacity: 0;
  transform: translateX(-8px);
}

@media (max-width: 720px) {
  .deep-map__lanes {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .deep-map__lane:last-child {
    grid-column: 1 / -1;
  }
}

@media (max-width: 480px) {
  .deep-map__scenario {
    grid-template-columns: 1fr;
  }

  .deep-map__scenario button {
    border-right: 0;
    border-bottom: 1px solid var(--deep-line);
  }

  .deep-map__scenario button:last-child {
    border-bottom: 0;
  }

  .deep-map__lanes {
    grid-template-columns: 1fr;
  }

  .deep-map__lane:last-child {
    grid-column: auto;
  }

  .deep-map__detail {
    min-height: 390px;
  }

  .deep-map__detail dl {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .deep-map *,
  .deep-map *::before,
  .deep-map *::after {
    transition: none !important;
  }
}
</style>
