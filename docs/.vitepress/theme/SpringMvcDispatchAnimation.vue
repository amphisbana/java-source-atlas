<script setup lang="ts">
import { computed, ref } from 'vue'
import SourceAnimation from './SourceAnimation.vue'
import type { SourceAnimationStep } from './animation-types'

type ScenarioId = 'success' | 'business-error' | 'method-error'

interface MvcSnapshot {
  request: string
  handler: string
  arguments: string
  result: string
  response: string
  active: number
  completed: number[]
  skipped?: number[]
  tone: 'normal' | 'error' | 'success'
}

interface MvcScenario {
  id: ScenarioId
  label: string
  title: string
  request: string
  outcome: string
  steps: SourceAnimationStep[]
  snapshots: MvcSnapshot[]
}

const pipeline = [
  { title: 'DispatcherServlet', method: 'doDispatch' },
  { title: 'HandlerMapping', method: 'getHandler' },
  { title: 'HandlerAdapter', method: 'handle' },
  { title: 'ArgumentResolvers', method: 'resolveArgument' },
  { title: 'Controller', method: 'doInvoke' },
  { title: 'ReturnHandlers', method: 'handleReturnValue' },
  { title: 'ExceptionResolvers', method: 'resolveException' },
  { title: 'Interceptors', method: 'pre / post / after' },
  { title: 'HTTP Response', method: 'status + body' }
]

const scenarios: MvcScenario[] = [
  {
    id: 'success',
    label: '正常 200',
    title: '正常请求：参数解析、Controller 与返回值写出',
    request: 'GET /orders/42?detail=true',
    outcome: 'ResponseEntity<String> -> 200',
    steps: [
      {
        title: '建立请求上下文',
        method: 'FrameworkServlet.processRequest(request, response)',
        description: '保存旧线程上下文，绑定 LocaleContext 与 ServletRequestAttributes，最终在 finally 中恢复。'
      },
      {
        title: '进入请求分派',
        method: 'DispatcherServlet.doDispatch(request, response)',
        description: '初始化 mappedHandler、ModelAndView 和异常变量，并检查是否需要 multipart 包装。'
      },
      {
        title: '匹配 HandlerMethod',
        method: 'DispatcherServlet.getHandler(processedRequest)',
        description: '按顺序询问 HandlerMapping，RequestMappingHandlerMapping 从启动期注册表选中 findOrder。'
      },
      {
        title: '组装拦截器链',
        method: 'AbstractHandlerMapping.getHandlerExecutionChain(handler, request)',
        description: '把 HandlerMethod 与 TraceHandlerInterceptor 组合为本次请求专属的 HandlerExecutionChain。'
      },
      {
        title: '选择执行适配器',
        method: 'DispatcherServlet.getHandlerAdapter(handler)',
        description: 'RequestMappingHandlerAdapter 首先支持 HandlerMethod，后续参数与返回值策略由它组织。'
      },
      {
        title: '执行 preHandle',
        method: 'HandlerExecutionChain.applyPreHandle(request, response)',
        description: '拦截器按注册顺序执行并把 interceptorIndex 更新为 0；返回 true 后才能进入 handler。'
      },
      {
        title: '解析两个参数',
        method: 'InvocableHandlerMethod.getMethodArgumentValues(...)',
        description: 'PathVariable 与 RequestParam resolver 读取字符串 42、true，再经 ConversionService 转成 long、boolean。'
      },
      {
        title: '调用 Controller',
        method: 'InvocableHandlerMethod.doInvoke(args)',
        description: '反射调用 OrderController.findOrder(42, true)，得到带响应头和文本 body 的 ResponseEntity。'
      },
      {
        title: '处理返回值',
        method: 'HandlerMethodReturnValueHandlerComposite.handleReturnValue(...)',
        description: 'HttpEntityMethodProcessor 写入 200、X-Atlas-Handler 与 body，并把 requestHandled 标记为 true。'
      },
      {
        title: '执行 postHandle',
        method: 'HandlerExecutionChain.applyPostHandle(request, response, null)',
        description: 'handler 正常结束，拦截器逆序执行；REST 已直接写响应，因此 ModelAndView 为 null。'
      },
      {
        title: '处理分派结果',
        method: 'DispatcherServlet.processDispatchResult(..., null, null)',
        description: '没有异常也没有 ModelAndView，无需异常解析和视图渲染，直接进入完成回调。'
      },
      {
        title: '完成请求',
        method: 'HandlerExecutionChain.triggerAfterCompletion(..., null)',
        description: '已成功 preHandle 的拦截器逆序完成；FrameworkServlet 随后恢复线程上下文。'
      }
    ],
    snapshots: [
      { request: 'GET /orders/42?detail=true', handler: '尚未匹配', arguments: '尚未解析', result: '绑定请求上下文', response: '未提交', active: 0, completed: [], tone: 'normal' },
      { request: 'processedRequest = request', handler: 'mappedHandler = null', arguments: '尚未解析', result: 'mv = null', response: '200（初始）', active: 0, completed: [], tone: 'normal' },
      { request: 'path=/orders/42, method=GET', handler: 'OrderController.findOrder', arguments: 'URI variables={orderId=42}', result: 'HandlerMethod', response: '未提交', active: 1, completed: [0], tone: 'normal' },
      { request: 'GET /orders/42', handler: 'HandlerMethod + TraceInterceptor', arguments: '等待适配器', result: 'HandlerExecutionChain', response: '未提交', active: 7, completed: [0, 1], tone: 'normal' },
      { request: 'GET /orders/42', handler: 'RequestMappingHandlerAdapter', arguments: 'resolver 列表已就绪', result: '准备执行', response: '未提交', active: 2, completed: [0, 1], tone: 'normal' },
      { request: 'GET /orders/42', handler: 'TraceInterceptor.preHandle=true', arguments: '等待解析', result: 'interceptorIndex=0', response: '未提交', active: 7, completed: [0, 1, 2], tone: 'normal' },
      { request: 'orderId="42", detail="true"', handler: 'findOrder(long, boolean)', arguments: '[42L, true]', result: 'ConversionService 已转换', response: '未提交', active: 3, completed: [0, 1, 2, 7], tone: 'normal' },
      { request: 'GET /orders/42?detail=true', handler: 'OrderController.findOrder', arguments: '[42L, true]', result: 'ResponseEntity<String>', response: '未提交', active: 4, completed: [0, 1, 2, 3, 7], tone: 'normal' },
      { request: 'Accept=*/*', handler: 'HttpEntityMethodProcessor', arguments: 'ResponseEntity<String>', result: 'requestHandled=true', response: '200 / order=42,detail=true', active: 5, completed: [0, 1, 2, 3, 4, 7], tone: 'success' },
      { request: 'GET /orders/42', handler: 'TraceInterceptor.postHandle', arguments: '已完成', result: 'ModelAndView=null', response: '200 body 已写入', active: 7, completed: [0, 1, 2, 3, 4, 5], tone: 'success' },
      { request: 'GET /orders/42', handler: '原 HandlerMethod', arguments: '已完成', result: '无需 render', response: '200 body 已写入', active: 0, completed: [1, 2, 3, 4, 5, 7], tone: 'success' },
      { request: 'GET /orders/42', handler: 'TraceInterceptor.afterCompletion', arguments: 'exception=null', result: '请求事件发布', response: '200 已完成', active: 8, completed: [0, 1, 2, 3, 4, 5, 7], skipped: [6], tone: 'success' }
    ]
  },
  {
    id: 'business-error',
    label: '业务异常 404',
    title: '业务异常：Controller 抛出后由 Advice 转成 404',
    request: 'GET /orders/0',
    outcome: 'OrderNotFoundException -> 404',
    steps: [
      {
        title: '建立请求上下文',
        method: 'FrameworkServlet.processRequest(request, response)',
        description: '为当前线程建立 LocaleContext 与 RequestAttributes，并保留最终恢复所需的旧值。'
      },
      {
        title: '进入请求分派',
        method: 'DispatcherServlet.doDispatch(request, response)',
        description: '初始化 mappedHandler、ModelAndView 与 dispatchException，随后进入内层分派 try。'
      },
      {
        title: '匹配 HandlerMethod',
        method: 'DispatcherServlet.getHandler(processedRequest)',
        description: 'GET 与路径条件都命中 findOrder，HandlerMapping 把 orderId=0 写入 URI 模板变量。'
      },
      {
        title: '组装拦截器链',
        method: 'AbstractHandlerMapping.getHandlerExecutionChain(handler, request)',
        description: '把 HandlerMethod 与 TraceHandlerInterceptor 组合成 HandlerExecutionChain。'
      },
      {
        title: '选择执行适配器',
        method: 'DispatcherServlet.getHandlerAdapter(handler)',
        description: 'RequestMappingHandlerAdapter 接管注解 HandlerMethod 并准备参数、返回值和异步协作者。'
      },
      {
        title: '执行 preHandle',
        method: 'HandlerExecutionChain.applyPreHandle(request, response)',
        description: '前置拦截器返回 true，记录为已成功执行，所以异常路径仍会收到 afterCompletion。'
      },
      {
        title: '解析方法参数',
        method: 'InvocableHandlerMethod.getMethodArgumentValues(...)',
        description: 'orderId 转成 0L；detail 使用 @RequestParam 的默认值 false，得到参数数组 [0L, false]。'
      },
      {
        title: 'Controller 抛异常',
        method: 'InvocableHandlerMethod.doInvoke(args)',
        description: '目标方法抛 OrderNotFoundException；InvocationTargetException 被解包，保留原业务异常类型。'
      },
      {
        title: '保存分派异常',
        method: 'DispatcherServlet.doDispatch: catch Exception',
        description: '内层 catch 把异常保存为 dispatchException，不立刻抛给 Servlet 容器，也不会执行 postHandle。'
      },
      {
        title: '选择异常处理方法',
        method: 'ExceptionHandlerExceptionResolver.getExceptionHandlerMethod(...)',
        description: '先查 Controller 局部方法，再查适用的 Advice，最终命中 handleMissingOrder。'
      },
      {
        title: '写入错误响应',
        method: 'HttpEntityMethodProcessor.handleReturnValue(...)',
        description: '异常方法复用参数与返回值体系，把 ResponseEntity 写成 404 文本响应并标记已处理。'
      },
      {
        title: '完成异常请求',
        method: 'HandlerExecutionChain.triggerAfterCompletion(..., null)',
        description: '异常已经解析，所以 afterCompletion 收到 null；MockMvc 仍在 resolvedException 中保留原异常。'
      }
    ],
    snapshots: [
      { request: 'GET /orders/0', handler: '尚未匹配', arguments: '尚未解析', result: '绑定请求上下文', response: '未提交', active: 0, completed: [], tone: 'normal' },
      { request: 'processedRequest = request', handler: 'mappedHandler = null', arguments: '尚未解析', result: 'dispatchException=null', response: '200（初始）', active: 0, completed: [], tone: 'normal' },
      { request: 'path=/orders/0, method=GET', handler: 'OrderController.findOrder', arguments: 'URI variables={orderId=0}', result: 'HandlerMethod', response: '未提交', active: 1, completed: [0], tone: 'normal' },
      { request: 'GET /orders/0', handler: 'HandlerMethod + TraceInterceptor', arguments: '等待适配器', result: 'HandlerExecutionChain', response: '未提交', active: 7, completed: [0, 1], tone: 'normal' },
      { request: 'GET /orders/0', handler: 'RequestMappingHandlerAdapter', arguments: 'resolver 列表已就绪', result: '准备执行', response: '未提交', active: 2, completed: [0, 1], tone: 'normal' },
      { request: 'GET /orders/0', handler: 'TraceInterceptor.preHandle=true', arguments: '等待解析', result: 'interceptorIndex=0', response: '未提交', active: 7, completed: [0, 1, 2], tone: 'normal' },
      { request: 'orderId="0", detail 缺省', handler: 'findOrder(long, boolean)', arguments: '[0L, false]', result: '默认值与类型转换完成', response: '未提交', active: 3, completed: [0, 1, 2, 7], tone: 'normal' },
      { request: 'GET /orders/0', handler: 'OrderController.findOrder', arguments: '[0L, false]', result: 'throw OrderNotFoundException', response: '未提交', active: 4, completed: [0, 1, 2, 3, 7], tone: 'error' },
      { request: 'GET /orders/0', handler: '原 HandlerMethod', arguments: '已完成', result: 'dispatchException 已保存', response: '未提交', active: 0, completed: [1, 2, 3, 7], tone: 'error' },
      { request: 'GET /orders/0', handler: 'OrderExceptionHandler', arguments: '[exception]', result: '@ExceptionHandler 命中', response: '准备 404', active: 6, completed: [0, 1, 2, 3, 4, 7], tone: 'error' },
      { request: 'GET /orders/0', handler: 'HttpEntityMethodProcessor', arguments: 'ResponseEntity<String>', result: 'requestHandled=true', response: '404 / order 0 not found', active: 5, completed: [0, 1, 2, 3, 4, 6, 7], tone: 'success' },
      { request: 'GET /orders/0', handler: 'TraceInterceptor.afterCompletion', arguments: 'exception=null（已解析）', result: 'resolvedException 保留原异常', response: '404 已完成', active: 8, completed: [0, 1, 2, 3, 4, 5, 6, 7], tone: 'success' }
    ]
  },
  {
    id: 'method-error',
    label: '方法不匹配 405',
    title: '映射异常：路径命中但 HTTP method 不支持',
    request: 'POST /orders/42',
    outcome: 'HttpRequestMethodNotSupportedException -> 405',
    steps: [
      {
        title: '建立请求上下文',
        method: 'FrameworkServlet.processRequest(request, response)',
        description: '请求仍会先建立线程上下文；此时还不知道是否存在可执行的 Controller。'
      },
      {
        title: '进入请求分派',
        method: 'DispatcherServlet.doDispatch(request, response)',
        description: '初始化分派变量并进入 getHandler；mappedHandler 此时仍为 null。'
      },
      {
        title: '询问 HandlerMapping',
        method: 'DispatcherServlet.getHandler(processedRequest)',
        description: 'RequestMappingHandlerMapping 接收 POST /orders/42，开始从注册表筛选 RequestMappingInfo。'
      },
      {
        title: '没有完整匹配',
        method: 'AbstractHandlerMethodMapping.lookupHandlerMethod(...)',
        description: 'findOrder 的 path 条件匹配，但 GET method 条件不匹配，因此完整 matchingMappings 为空。'
      },
      {
        title: '分析部分匹配',
        method: 'RequestMappingInfoHandlerMapping.handleNoMatch(...)',
        description: 'PartialMatchHelper 确认只是 HTTP method 失败，抛出 HttpRequestMethodNotSupportedException，并带出支持的 GET。'
      },
      {
        title: '保存映射异常',
        method: 'DispatcherServlet.doDispatch: catch Exception',
        description: '异常发生在 mappedHandler 赋值之前，所以没有执行链、参数数组、Controller 或拦截器事件。'
      },
      {
        title: '进入异常解析链',
        method: 'DispatcherServlet.processHandlerException(..., null, ex)',
        description: 'handler 参数为 null；前两个默认解析器不处理，继续交给 DefaultHandlerExceptionResolver。'
      },
      {
        title: '映射为 405',
        method: 'DefaultHandlerExceptionResolver.handleHttpRequestMethodNotSupported(...)',
        description: '把支持的方法写入 Allow header，并调用 sendError(405)，返回空 ModelAndView 表示已处理。'
      },
      {
        title: '完成映射失败请求',
        method: 'DispatcherServlet.processDispatchResult(...)',
        description: '没有 mappedHandler，所以无需 afterCompletion；FrameworkServlet finally 仍会恢复线程上下文。'
      }
    ],
    snapshots: [
      { request: 'POST /orders/42', handler: '尚未匹配', arguments: '尚未解析', result: '绑定请求上下文', response: '未提交', active: 0, completed: [], tone: 'normal' },
      { request: 'processedRequest = request', handler: 'mappedHandler = null', arguments: '不会生成', result: 'dispatchException=null', response: '200（初始）', active: 0, completed: [], tone: 'normal' },
      { request: 'path=/orders/42, method=POST', handler: '查询映射注册表', arguments: '不会生成', result: '筛选 RequestMappingInfo', response: '未提交', active: 1, completed: [0], tone: 'normal' },
      { request: 'path 条件=true, method 条件=false', handler: 'findOrder 仅部分匹配', arguments: '不会生成', result: 'matchingMappings=[]', response: '未提交', active: 1, completed: [0], tone: 'error' },
      { request: 'POST /orders/42', handler: '支持方法=[GET]', arguments: '不会生成', result: 'throw HttpRequestMethodNotSupportedException', response: '未提交', active: 1, completed: [0], tone: 'error' },
      { request: 'POST /orders/42', handler: 'mappedHandler=null', arguments: '未解析', result: 'dispatchException 已保存', response: '未提交', active: 0, completed: [1], skipped: [2, 3, 4, 5, 7], tone: 'error' },
      { request: 'POST /orders/42', handler: 'handler=null', arguments: '未解析', result: '遍历 resolver 列表', response: '准备错误响应', active: 6, completed: [0, 1], skipped: [2, 3, 4, 5, 7], tone: 'error' },
      { request: 'POST /orders/42', handler: 'DefaultHandlerExceptionResolver', arguments: 'supportedMethods=[GET]', result: '返回空 ModelAndView', response: '405 / Allow: GET', active: 6, completed: [0, 1], skipped: [2, 3, 4, 5, 7], tone: 'success' },
      { request: 'POST /orders/42', handler: 'mappedHandler=null', arguments: '未解析', result: '恢复请求线程上下文', response: '405 已完成', active: 8, completed: [0, 1, 6], skipped: [2, 3, 4, 5, 7], tone: 'success' }
    ]
  }
]

const selectedScenarioId = ref<ScenarioId>('success')
const currentScenario = computed(() => scenarios.find((scenario) => scenario.id === selectedScenarioId.value) ?? scenarios[0])

/**
 * 切换请求场景；SourceAnimation 使用场景 key 重新挂载，从第一步展示新链路。
 */
function selectScenario(scenarioId: ScenarioId): void {
  selectedScenarioId.value = scenarioId
}

/**
 * 根据当前步骤返回管线节点的视觉状态，精确区分已完成与从未进入的节点。
 */
function nodeState(index: number, currentIndex: number): string {
  const snapshot = currentScenario.value.snapshots[currentIndex]
  if (index === snapshot.active) {
    return snapshot.tone === 'error' ? 'is-error' : 'is-active'
  }
  if (snapshot.completed.includes(index)) {
    return 'is-complete'
  }
  if (snapshot.skipped?.includes(index)) {
    return 'is-skipped'
  }
  return ''
}
</script>

<template>
  <div class="mvc-scenario">
    <div class="mvc-scenario__switcher" role="tablist" aria-label="选择 MVC 请求场景">
      <button
        v-for="scenario in scenarios"
        :key="scenario.id"
        type="button"
        role="tab"
        :aria-selected="scenario.id === selectedScenarioId"
        :class="{ 'is-active': scenario.id === selectedScenarioId }"
        @click="selectScenario(scenario.id)"
      >
        {{ scenario.label }}
      </button>
    </div>

    <div class="mvc-scenario__summary">
      <code>{{ currentScenario.request }}</code>
      <span aria-hidden="true">→</span>
      <strong>{{ currentScenario.outcome }}</strong>
    </div>

    <SourceAnimation
      :key="currentScenario.id"
      :title="currentScenario.title"
      :steps="currentScenario.steps"
      :interval="2200"
    >
      <template #visual="{ currentIndex }">
        <div class="mvc-dispatch">
          <div class="mvc-dispatch__pipeline" aria-label="MVC 请求执行管线">
            <div
              v-for="(node, index) in pipeline"
              :key="node.title"
              class="mvc-dispatch__node"
              :class="nodeState(index, currentIndex)"
            >
              <span>{{ index + 1 }}</span>
              <strong>{{ node.title }}</strong>
              <code>{{ node.method }}</code>
            </div>
          </div>

          <dl class="mvc-dispatch__snapshot" aria-label="当前请求变量快照">
            <div>
              <dt>request</dt>
              <dd>{{ currentScenario.snapshots[currentIndex].request }}</dd>
            </div>
            <div>
              <dt>handler</dt>
              <dd>{{ currentScenario.snapshots[currentIndex].handler }}</dd>
            </div>
            <div>
              <dt>arguments</dt>
              <dd>{{ currentScenario.snapshots[currentIndex].arguments }}</dd>
            </div>
            <div>
              <dt>result</dt>
              <dd>{{ currentScenario.snapshots[currentIndex].result }}</dd>
            </div>
            <div :class="`is-${currentScenario.snapshots[currentIndex].tone}`">
              <dt>response</dt>
              <dd>{{ currentScenario.snapshots[currentIndex].response }}</dd>
            </div>
          </dl>
        </div>
      </template>
    </SourceAnimation>
  </div>
</template>

<style scoped>
.mvc-scenario {
  margin: 24px 0 32px;
}

.mvc-scenario :deep(.source-animation) {
  margin-top: 10px;
  margin-bottom: 0;
}

.mvc-scenario__switcher {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid var(--atlas-line);
  border-radius: 6px;
  background: var(--atlas-surface);
}

.mvc-scenario__switcher button {
  min-width: 0;
  min-height: 40px;
  padding: 7px 10px;
  border: 0;
  border-right: 1px solid var(--atlas-line);
  background: transparent;
  color: var(--vp-c-text-2);
  font: inherit;
  font-size: 0.82rem;
  font-weight: 700;
  cursor: pointer;
}

.mvc-scenario__switcher button:last-child {
  border-right: 0;
}

.mvc-scenario__switcher button:hover,
.mvc-scenario__switcher button.is-active {
  background: var(--vp-c-brand-soft);
  color: var(--vp-c-brand-1);
}

.mvc-scenario__switcher button.is-active {
  box-shadow: inset 0 -3px 0 var(--vp-c-brand-1);
}

.mvc-scenario__summary {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 42px;
  padding: 8px 12px;
  border-right: 1px solid var(--atlas-line);
  border-bottom: 1px solid var(--atlas-line);
  border-left: 1px solid var(--atlas-line);
  color: var(--vp-c-text-2);
  font-size: 0.8rem;
}

.mvc-scenario__summary code,
.mvc-scenario__summary strong {
  min-width: 0;
  overflow-wrap: anywhere;
}

.mvc-scenario__summary code {
  color: var(--atlas-ink);
}

.mvc-scenario__summary strong {
  color: var(--vp-c-brand-1);
}

.mvc-dispatch {
  display: grid;
  gap: 22px;
  min-height: 350px;
}

.mvc-dispatch__pipeline {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.mvc-dispatch__node {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  gap: 2px 7px;
  align-content: center;
  min-width: 0;
  min-height: 68px;
  padding: 8px;
  border-left: 3px solid var(--atlas-line);
  background: var(--atlas-surface);
  transition: border-color 180ms ease, background 180ms ease, transform 180ms ease, opacity 180ms ease;
}

.mvc-dispatch__node > span {
  grid-row: 1 / 3;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 1px solid var(--atlas-line);
  border-radius: 50%;
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.68rem;
}

.mvc-dispatch__node strong,
.mvc-dispatch__node code {
  min-width: 0;
  overflow-wrap: anywhere;
}

.mvc-dispatch__node strong {
  color: var(--vp-c-text-2);
  font-size: 0.75rem;
}

.mvc-dispatch__node code {
  color: var(--vp-c-text-3);
  font-size: 0.66rem;
}

.mvc-dispatch__node.is-complete {
  border-left-color: var(--vp-c-brand-1);
}

.mvc-dispatch__node.is-complete > span {
  border-color: var(--vp-c-brand-1);
  color: var(--vp-c-brand-1);
}

.mvc-dispatch__node.is-active {
  border-left-color: var(--atlas-coral);
  background: color-mix(in srgb, var(--atlas-coral) 9%, transparent);
  transform: translateY(-2px);
}

.mvc-dispatch__node.is-error {
  border-left-color: var(--vp-c-danger-1);
  background: var(--vp-c-danger-soft);
  transform: translateY(-2px);
}

.mvc-dispatch__node.is-skipped {
  opacity: 0.42;
}

.mvc-dispatch__snapshot {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  margin: 0;
  border-top: 1px solid var(--atlas-line);
  border-bottom: 1px solid var(--atlas-line);
}

.mvc-dispatch__snapshot > div {
  min-width: 0;
  padding: 10px;
  border-right: 1px solid var(--atlas-line);
}

.mvc-dispatch__snapshot > div:last-child {
  border-right: 0;
}

.mvc-dispatch__snapshot dt {
  color: var(--vp-c-text-3);
  font-family: var(--vp-font-family-mono);
  font-size: 0.68rem;
}

.mvc-dispatch__snapshot dd {
  margin: 6px 0 0;
  overflow-wrap: anywhere;
  color: var(--atlas-ink);
  font-size: 0.72rem;
  font-weight: 650;
}

.mvc-dispatch__snapshot .is-error dd {
  color: var(--vp-c-danger-1);
}

.mvc-dispatch__snapshot .is-success dd {
  color: var(--vp-c-tip-1);
}

@media (max-width: 760px) {
  .mvc-dispatch__pipeline {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .mvc-dispatch__snapshot {
    grid-template-columns: 1fr;
  }

  .mvc-dispatch__snapshot > div {
    border-right: 0;
    border-bottom: 1px solid var(--atlas-line);
  }

  .mvc-dispatch__snapshot > div:last-child {
    border-bottom: 0;
  }
}

@media (max-width: 520px) {
  .mvc-scenario__switcher {
    grid-template-columns: 1fr;
  }

  .mvc-scenario__switcher button {
    border-right: 0;
    border-bottom: 1px solid var(--atlas-line);
  }

  .mvc-scenario__switcher button:last-child {
    border-bottom: 0;
  }

  .mvc-scenario__summary {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
  }
}

@media (max-width: 420px) {
  .mvc-dispatch__pipeline {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .mvc-dispatch__node {
    transition: none;
  }
}
</style>
