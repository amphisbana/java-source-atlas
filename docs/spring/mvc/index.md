# Spring MVC 源码地图

本专题以 **Spring Framework 5.3.39** 为可执行基线，用一条真实 HTTP 请求把 `DispatcherServlet`、映射器、适配器、参数解析、返回值处理和异常解析连接起来。实验使用 `MockMvc` 驱动真实 `WebApplicationContext`，因此没有网络端口和外部 Servlet 容器也能进入同一套 MVC 核心源码。

完成 MVC 内部策略链后，进入 [MVC 请求怎样进入事务代理](/spring/deep-dive/request-transaction) 继续跟到 Service、DAO 和线程资源；异常路径则在 [异常、传播与清理边界](/spring/deep-dive/failure-boundaries) 中收口。

<TopicStudyPanel topic-id="spring-framework-5-3-mvc" />

## 源码入口

- 前端控制器：`DispatcherServlet.doService(...)` / `doDispatch(...)`
- 处理器映射：`AbstractHandlerMethodMapping.getHandlerInternal(...)`
- 映射方法查找：`AbstractHandlerMethodMapping.lookupHandlerMethod(...)`
- 方法适配器：`RequestMappingHandlerAdapter.invokeHandlerMethod(...)`
- 参数解析：`HandlerMethodArgumentResolverComposite.resolveArgument(...)`
- 方法调用：`InvocableHandlerMethod.doInvoke(...)`
- 返回值处理：`HandlerMethodReturnValueHandlerComposite.handleReturnValue(...)`
- 异常解析：`HandlerExceptionResolverComposite.resolveException(...)`

## 先分清启动期和请求期

MVC 源码阅读最常见的误区，是以为每次请求都会扫描所有 Controller。实际存在两条时间线：

```text
容器启动期
  RequestMappingHandlerMapping.afterPropertiesSet()
    → 扫描候选 Bean
    → 解析 @RequestMapping / @GetMapping
    → 注册 MappingRegistry: RequestMappingInfo → HandlerMethod

请求到达期
  DispatcherServlet.doDispatch(request, response)
    → 用请求条件查询已经建立的 MappingRegistry
    → 选中 HandlerMethod
    → 交给 RequestMappingHandlerAdapter 执行
```

启动期解决“系统有哪些路由”，请求期解决“当前请求匹配哪一个路由”。如果线上出现首个请求极慢、映射冲突或 404，应先判断问题属于哪条时间线。

## 一条请求的完整骨架

```text
Servlet 容器 / MockMvc
  → DispatcherServlet.service
      → FrameworkServlet.processRequest
          → DispatcherServlet.doService
              → DispatcherServlet.doDispatch
                  → checkMultipart
                  → getHandler
                      → HandlerMapping.getHandler
                      → HandlerExecutionChain(handler + interceptors)
                  → getHandlerAdapter
                  → interceptor.preHandle
                  → HandlerAdapter.handle
                      → RequestMappingHandlerAdapter.invokeHandlerMethod
                          → 参数解析与绑定
                          → 反射调用 Controller
                          → 返回值处理 / 消息转换
                  → interceptor.postHandle
                  → processDispatchResult
                      → 异常解析或视图渲染
                  → interceptor.afterCompletion
                  → cleanupMultipart
```

这条链中 `DispatcherServlet` 不理解 `@PathVariable`，也不直接反射调用 Controller。它只负责选择策略和编排阶段。注解方法的参数、返回值与异常语义主要由 `RequestMappingHandlerAdapter` 内部的组合对象完成。

## 六个核心对象分别回答什么

| 对象 | 典型实现 | 回答的问题 |
| --- | --- | --- |
| `HandlerMapping` | `RequestMappingHandlerMapping` | 当前请求应该交给哪个 handler？ |
| `HandlerExecutionChain` | handler + interceptors | handler 前后还要经过哪些拦截器？ |
| `HandlerAdapter` | `RequestMappingHandlerAdapter` | 这个 handler 应该以什么协议执行？ |
| `HandlerMethodArgumentResolver` | `PathVariableMethodArgumentResolver` 等 | 方法的每个参数从哪里取得并如何转换？ |
| `HandlerMethodReturnValueHandler` | `RequestResponseBodyMethodProcessor` 等 | Controller 返回值写响应体还是进入视图模型？ |
| `HandlerExceptionResolver` | 三个默认解析器的组合 | 异常应转换为哪个 ModelAndView 或 HTTP 响应？ |

Spring MVC 大量采用“策略接口 + 组合器 + 有序列表”。阅读时不要只记默认实现名称，更要看选择条件与列表顺序，因为第一个声称 `supports(...)` 的组件通常会接管当前值。

## 用四层模型定位源码

同一条请求链包含很多类，但排查时可以先把问题压缩到四层：

| 层次 | 核心问题 | 首个断点 | 典型现象 |
| --- | --- | --- | --- |
| 注册与匹配 | 路由是否注册，当前请求条件是否匹配 | `lookupHandlerMethod` | 404、405、415，Controller 完全没有进入 |
| 适配与调用 | handler 由谁执行，参数怎样生成 | `invokeHandlerMethod` / `getMethodArgumentValues` | 400、参数转换失败、校验失败 |
| 返回值处理 | 返回值写 body 还是进入视图模型 | `handleReturnValue` / `writeWithMessageConverters` | 406、响应体为空、错误进入 ViewResolver |
| 异常与收尾 | 哪个解析器接住异常，拦截器怎样完成 | `processHandlerException` / `triggerAfterCompletion` | 状态码不符合预期、Advice 未命中、资源未清理 |

先判断问题属于哪一层，再进入对应组合器，比从 `doDispatch` 一直单步到请求结束更高效。

## 三条请求对照

本专题的动画和 Lab 固定三条请求，后文所有变量都可以回到这张表核对：

| 请求 | 分派结果 | 是否解析参数 | 是否调用 Controller | 最终处理器 | 状态 |
| --- | --- | --- | --- | --- | --- |
| `GET /orders/42?detail=true` | 匹配 `findOrder` | 是，得到 `[42L, true]` | 是，正常返回 | `HttpEntityMethodProcessor` | 200 |
| `GET /orders/0` | 匹配 `findOrder` | 是，得到 `[0L, false]` | 是，抛业务异常 | `ExceptionHandlerExceptionResolver` | 404 |
| `POST /orders/42` | 路径命中但 method 条件失败 | 否 | 否 | `DefaultHandlerExceptionResolver` | 405 |

这三条链分别固定“正常返回”“Controller 异常”“映射阶段异常”。如果只看最终状态码，两个 404 或两个 400 很容易被误认为发生在同一阶段。

## 推荐阅读顺序

1. [DispatcherServlet 请求编排](./dispatch-chain.md)：先看总控流程、路由匹配和拦截器。
2. [HandlerAdapter 与方法调用](./handler-adapter.md)：跟进参数解析直到 Controller 方法真正执行。
3. [参数、返回值与异常解析](./argument-return-exception.md)：理解 REST 响应、视图和失败分支。
4. [断点实验](./debug-lab.md)：用正常、异常和方法不匹配三类请求固定行为。

## 第一次调试只盯这些变量

| 断点 | 关键变量 | 观察目标 |
| --- | --- | --- |
| `DispatcherServlet.doDispatch` | `processedRequest`、`mappedHandler`、`mv`、`dispatchException` | 当前请求处于哪个阶段 |
| `AbstractHandlerMapping.getHandler` | `handler`、`executionChain` | 映射结果以及加入的拦截器 |
| `lookupHandlerMethod` | `matchingMappings`、`bestMatch` | 候选如何按请求条件排序 |
| `getHandlerAdapter` | `handlerAdapters`、`handler` | 哪个适配器首先支持当前 handler |
| `invokeHandlerMethod` | `invocableMethod`、`mavContainer`、`webRequest` | 参数与返回值处理环境 |
| `getMethodArgumentValues` | `parameters`、`providedArgs`、`args` | 每个参数由哪个 resolver 解析 |
| `processDispatchResult` | `exception`、`mv`、`errorView` | 异常是否已被解析、是否需要渲染 |

## 三个容易混淆的边界

### Filter、HandlerInterceptor 和 AOP 不在同一层

```text
Servlet Filter
  → DispatcherServlet
      → HandlerInterceptor
          → Controller 代理上的 Spring AOP
```

- Filter 属于 Servlet 规范，可以覆盖静态资源、非 Spring Servlet 和请求包装。
- HandlerInterceptor 属于 MVC 的 `HandlerExecutionChain`，已经知道匹配到的 handler。
- Spring AOP 围绕 Bean 方法调用，对 Servlet 请求本身没有天然感知。

### `@ResponseBody` 不会经过 ViewResolver

`RequestResponseBodyMethodProcessor` 通过 `HttpMessageConverter` 直接写响应，并把 `ModelAndViewContainer.requestHandled` 标记为 true。此时 `invokeHandlerMethod` 返回的 `ModelAndView` 为 null，不代表 Controller 没有返回值。

### 已解析异常与未处理异常不同

异常解析器返回非 null `ModelAndView`，或返回空 `ModelAndView` 表示异常已经处理。只有解析器链都未处理时，异常才会在完成拦截器清理后继续向 Servlet 容器传播。

## Spring 6.x 边界

- Spring 6 要求 Java 17，Servlet API 从 `javax.servlet.*` 迁移到 `jakarta.servlet.*`；实验代码不能只改依赖版本就直接复用。
- `DispatcherServlet` 的前端控制器结构、HandlerMapping/HandlerAdapter/Resolver 策略体系仍保持连续。
- Spring 6.1 对方法参数校验、`ProblemDetail` 和错误响应有更完整支持；不要把 6.x 的异常返回能力反向套到 5.3。
- Spring 6 的 AOT 场景会提前生成部分运行时提示，但请求匹配、参数解析和返回值处理的公开扩展契约仍然适用。

## 学完后的自检问题

1. 为什么匹配到 `HandlerMethod` 后还需要 `HandlerAdapter`？
2. `preHandle` 返回 false 时，哪些拦截器会收到 `afterCompletion`？
3. `@ResponseBody` 返回值为什么经常让 `ModelAndView` 为 null？
4. Controller 抛异常后，`postHandle` 和 `afterCompletion` 是否都会执行？
5. 404、405、参数转换失败和业务异常分别可能在哪个阶段产生？

能沿源码回答这五个问题，才算真正建立了 MVC 请求链，而不是只会背 `DispatcherServlet` 的流程图。
