# DispatcherServlet：匹配、执行与收尾

## 源码入口

- `FrameworkServlet.processRequest(...)`
- `DispatcherServlet.doService(...)`
- `DispatcherServlet.doDispatch(...)`
- `DispatcherServlet.getHandler(...)`
- `DispatcherServlet.getHandlerAdapter(...)`
- `DispatcherServlet.processDispatchResult(...)`
- `AbstractHandlerMapping.getHandler(...)`

## 请求进入 doDispatch 之前发生了什么

Servlet 的 `service` 调用先进入 `FrameworkServlet.processRequest`。它会保存并建立当前线程的 `LocaleContext` 和 `RequestAttributes`，调用 `doService`，最后在 `finally` 中恢复原线程上下文并发布 `ServletRequestHandledEvent`。

`DispatcherServlet.doService` 把当前 WebApplicationContext、locale/theme resolver 等基础设施放入 request attribute，同时为 include 请求保存原属性快照。真正的分派循环在 `doDispatch`。

因此这些 ThreadLocal 上下文通常只在请求线程有效。把任务交给普通线程池时不会自动复制完整 MVC 上下文；即使使用可继承 ThreadLocal，也必须考虑线程池复用造成的污染。

## 策略列表什么时候准备好

`DispatcherServlet` 并不是在每次请求中查 Bean。WebApplicationContext 刷新后，`onRefresh` 调用 `initStrategies`，依次初始化 multipart、locale、theme、HandlerMapping、HandlerAdapter、异常解析器、视图解析器等策略。

以 HandlerMapping 为例：

```text
initHandlerMappings(context)
  → detectAllHandlerMappings=true（默认）
  → BeanFactoryUtils.beansOfTypeIncludingAncestors(...)
  → AnnotationAwareOrderComparator.sort(handlerMappings)
  → 若一个 Bean 都没有，读取 DispatcherServlet.properties 的默认策略
```

HandlerAdapter 和 HandlerExceptionResolver 的发现方式相同：先从容器取有序 Bean，必要时再回退到框架默认策略。因此“第一个支持者获胜”的顺序在启动期已经固定，请求期只是遍历现成列表。自定义 Bean 后行为改变时，应同时检查 Bean 是否被发现以及 `Ordered` 值，而不是只看接口实现。

## 动画：请求如何穿过策略链

<SpringMvcDispatchAnimation />

动画可以切换正常 200、业务异常 404、方法不匹配 405 三条链。点击步骤可观察 `mappedHandler`、参数数组、返回值和响应状态在哪个阶段发生变化；切换场景会从第一步重新开始。

## doDispatch 的准确主干

```text
doDispatch(request, response)
  processedRequest = request
  mappedHandler = null
  multipartRequestParsed = false
  try
    try
      processedRequest = checkMultipart(request)
      multipartRequestParsed = processedRequest != request

      mappedHandler = getHandler(processedRequest)
      if mappedHandler == null
        noHandlerFound(...)
        return

      HandlerAdapter ha = getHandlerAdapter(mappedHandler.getHandler())

      if GET or HEAD
        lastModified = ha.getLastModified(...)
        if new ServletWebRequest(...).checkNotModified(lastModified) && GET
          return

      if !mappedHandler.applyPreHandle(...)
        return

      mv = ha.handle(processedRequest, response, mappedHandler.getHandler())

      if asyncManager.isConcurrentHandlingStarted()
        return

      applyDefaultViewName(processedRequest, mv)
      mappedHandler.applyPostHandle(processedRequest, response, mv)
    catch Exception ex
      dispatchException = ex
    catch Throwable err
      dispatchException = new NestedServletException(..., err)

    processDispatchResult(processedRequest, response, mappedHandler, mv, dispatchException)
  catch Exception ex
    triggerAfterCompletion(..., ex)
  catch Throwable err
    triggerAfterCompletion(..., new NestedServletException(..., err))
  finally
    if async started
      mappedHandler.applyAfterConcurrentHandlingStarted(...)
    else if multipartRequestParsed
      cleanupMultipart(processedRequest)
```

两个嵌套 try 不能合并理解：内层把 handler 执行异常保存为 `dispatchException`，让 `processDispatchResult` 有机会交给异常解析器；外层处理连异常解析/视图渲染也失败的情况，并触发 `afterCompletion`。

## getHandler 如何选择 HandlerMapping

`DispatcherServlet` 启动时会从上下文发现 `HandlerMapping` Bean，按 `Ordered` 排序。每次请求依次调用：

```java
for (HandlerMapping mapping : this.handlerMappings) {
    HandlerExecutionChain handler = mapping.getHandler(request);
    if (handler != null) {
        return handler;
    }
}
```

第一个返回非 null 的映射器获胜。对注解 Controller，通常命中 `RequestMappingHandlerMapping`；静态资源、BeanName URL 或自定义映射器可能走其他实现。

### RequestMappingHandlerMapping 的匹配过程

`AbstractHandlerMethodMapping.lookupHandlerMethod` 先尝试 `urlLookup` 中的直接路径候选；没有直接命中时再检查全部注册映射。每个候选通过 `getMatchingMapping(mapping, request)` 过滤方法、参数、header、consumes、produces 等条件。

存在多个候选时，使用映射比较器选最具体项。例如固定路径通常比路径变量更具体，显式 HTTP 方法比无方法限制更具体。如果最佳两项比较结果相同，会抛出 ambiguous mapping 异常，而不是依赖注册顺序随机选择。

选中后生成 `HandlerMethod`，提取路径模式、URI 模板变量和 producible media types 写入 request attribute，供后续参数解析和内容协商复用。

把请求期查找展开后更接近源码：

```text
RequestMappingInfoHandlerMapping.getHandlerInternal(request)
  → initLookupPath(request)
  → mappingRegistry.acquireReadLock()
  → lookupHandlerMethod(lookupPath, request)
      → 先取 mappingRegistry.getMappingsByDirectPath(lookupPath)
      → addMatchingMappings(directCandidates, matches, request)
      → 若没有结果，再扫描全部 registrations
      → matches.sort(mappingComparator)
      → 比较 bestMatch 与 secondBestMatch
      → 相同则抛 IllegalStateException（Ambiguous handler methods）
      → handleMatch(bestMatch.mapping, lookupPath, request)
  → mappingRegistry.releaseReadLock()
  → HandlerMethod.createWithResolvedBean()
```

这里的读锁保护“查映射”和运行期动态注册/注销之间的一致性，并不表示每个请求会扫描 Controller。直接路径索引命中时通常只比较少量候选；带模式的路径才更可能进入较大的候选集合。

### 没有最佳匹配时也可能已经找到部分候选

`RequestMappingInfoHandlerMapping.handleNoMatch` 会把路径匹配但其他条件失败的映射归为 partial matches，再按固定优先级诊断：

| 已匹配条件 | 失败条件 | 抛出的异常 | 常见响应 |
| --- | --- | --- | --- |
| path | HTTP method | `HttpRequestMethodNotSupportedException` | 405 + `Allow` |
| path + method | `consumes` | `HttpMediaTypeNotSupportedException` | 415 |
| path + method + consumes | `produces` | `HttpMediaTypeNotAcceptableException` | 406 |
| path + method + media type | request params | `UnsatisfiedServletRequestParameterException` | 400 |

完全没有 path 候选时才返回 null 给 `DispatcherServlet`。所以“Controller 没进入”既可能是没有 handler，也可能是 HandlerMapping 主动抛出一个可被异常解析器转换的协议错误。

## HandlerExecutionChain 不只是 handler

`AbstractHandlerMapping.getHandler` 先取得 handler，再调用 `getHandlerExecutionChain` 合并：

- 映射器公共的 adapted interceptors；
- URL 模式匹配的 `MappedInterceptor`；
- 某些 HandlerMapping 为当前 handler 追加的专用拦截器。

它得到的是 `HandlerExecutionChain(handler, interceptors)`。因此拦截器属于一次映射结果，而不是 `DispatcherServlet` 的全局硬编码列表。

## 拦截器的精确顺序

假设拦截器为 A、B、C：

```text
A.preHandle
  B.preHandle
    C.preHandle
      handler
    C.postHandle
  B.postHandle
A.postHandle
    render / exception result
    C.afterCompletion
  B.afterCompletion
A.afterCompletion
```

- `preHandle` 按注册顺序执行。
- `postHandle` 和 `afterCompletion` 逆序执行。
- 若 B 的 `preHandle` 返回 false，B 自己不会进入已成功索引，Spring 只对之前成功的 A 调用 `afterCompletion`。
- handler 抛异常时不会执行 `postHandle`，但在异常被处理或继续抛出后，已成功 `preHandle` 的拦截器仍会执行 `afterCompletion`。

实验中的异常已被 `@ExceptionHandler` 解析，所以传给 `afterCompletion` 的 exception 为 null；真实异常仍可通过 `MvcResult.getResolvedException()` 观察。不要把 null 误判为 Controller 没抛异常。

## HandlerAdapter 为什么必要

`DispatcherServlet` 可以接收多种 handler：注解方法、传统 `Controller`、`HttpRequestHandler`，以及用户自定义类型。它不知道这些对象怎样执行，因此遍历 `handlerAdapters`，选择第一个 `supports(handler)` 为 true 的适配器。

这个适配器模式把总控流程和执行协议解耦。注解方法由 `RequestMappingHandlerAdapter` 处理，下一页再展开其内部调用链。

常见 handler 与 adapter 对应关系：

| handler 形态 | adapter | `supports` 判断 |
| --- | --- | --- |
| `HandlerMethod` | `RequestMappingHandlerAdapter` | 是否为 `HandlerMethod`，再由 `supportsInternal` 扩展 |
| 实现 `Controller` 的对象 | `SimpleControllerHandlerAdapter` | `handler instanceof Controller` |
| 实现 `HttpRequestHandler` 的对象 | `HttpRequestHandlerAdapter` | `handler instanceof HttpRequestHandler` |

HandlerMapping 决定“选谁”，HandlerAdapter 决定“怎么调用”。二者是正交扩展点，不能因为拿到了 `HandlerMethod` 就跳过 adapter。

## processDispatchResult 做了什么

如果存在 `dispatchException`：

1. 若是 `ModelAndViewDefiningException`，直接取其中的 `ModelAndView`。
2. 否则调用 `processHandlerException`，顺序遍历 `HandlerExceptionResolver`。
3. 解析器返回结果后清理异常相关 request attribute，并标记是否为 error view。

随后，如果 `mv` 非 null 且未 cleared，则进入 `render`；如果返回值处理器已经直接写响应，通常 `mv` 为 null。最后调用 `mappedHandler.triggerAfterCompletion(..., null)`。

异常解析器返回空 `ModelAndView` 也代表“异常已处理，但无需渲染视图”，Spring 会把异常保存在 request attribute 供后续读取。

## 异步请求的提前返回

Controller 返回 `Callable`、`DeferredResult` 等异步类型后，`WebAsyncManager` 可能启动并发处理：

- 当前分派不再调用普通 `postHandle` 与渲染；
- 调用 `AsyncHandlerInterceptor.afterConcurrentHandlingStarted`；
- 异步结果就绪后重新 dispatch，重新进入 MVC 分派链；
- request 的线程上下文不能简单等同于异步工作线程上下文。

阅读异步问题时必须区分“首次分派”和“异步结果二次分派”，否则日志看起来像 Controller 被调用两次或拦截器顺序异常。

## 404 与 405 在哪里分开

- 所有 HandlerMapping 都返回 null：`noHandlerFound`，依据配置发送 404 或抛 `NoHandlerFoundException`。
- 路径存在但 HTTP 方法不匹配：`RequestMappingInfoHandlerMapping` 的部分匹配处理产生 `HttpRequestMethodNotSupportedException`，随后默认异常解析器转换为 405，并设置 `Allow` header。

两者都发生在 Controller 方法执行前，因此不应从业务方法日志是否出现来推断同一个原因。
