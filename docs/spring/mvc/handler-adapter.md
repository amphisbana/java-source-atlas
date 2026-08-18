# RequestMappingHandlerAdapter：把 HandlerMethod 变成一次 Java 调用

## 源码入口

- `AbstractHandlerMethodAdapter.handle(...)`
- `RequestMappingHandlerAdapter.handleInternal(...)`
- `RequestMappingHandlerAdapter.invokeHandlerMethod(...)`
- `ServletInvocableHandlerMethod.invokeAndHandle(...)`
- `InvocableHandlerMethod.invokeForRequest(...)`
- `InvocableHandlerMethod.getMethodArgumentValues(...)`
- `InvocableHandlerMethod.doInvoke(...)`

## HandlerMethod 只是描述，不是调用

`HandlerMethod` 保存 Bean（或 Bean 名称）、Java `Method`、桥接方法、参数元数据和注解上下文。路由匹配得到它时，尚未完成：

- URI 模板变量到 Java 类型的转换；
- `@RequestBody` 读取和消息转换；
- 数据绑定、校验与 `BindingResult`；
- `@ModelAttribute` 方法执行；
- Controller 方法反射调用；
- 返回值写响应或构建视图。

这些工作由 `RequestMappingHandlerAdapter` 建立执行环境，再委托多个小策略完成。

## afterPropertiesSet 固定默认策略顺序

`RequestMappingHandlerAdapter.afterPropertiesSet` 先发现 `@ControllerAdvice`，然后在调用方没有整体替换配置时建立三组默认列表：

```text
argumentResolvers
  → 注解型参数：@RequestParam、@PathVariable、@RequestBody ...
  → Servlet 类型：request、response、Principal ...
  → Model/Map/Errors/SessionStatus ...
  → 自定义 resolver
  → 兜底的 @RequestParam / @ModelAttribute resolver

initBinderArgumentResolvers
  → 只保留 @InitBinder 可使用的参数类型

returnValueHandlers
  → ModelAndView、Model、View、ResponseEntity ...
  → 异步类型
  → @ModelAttribute、@ResponseBody
  → 自定义 handler
  → 兜底的 ModelAndViewResolver / ModelAttribute handler
```

这里有两个容易误配的 API：

- `setArgumentResolvers`、`setReturnValueHandlers` 是整体替换，调用方要负责保留所需默认能力；
- `WebMvcConfigurer.addArgumentResolvers` 和 `addReturnValueHandlers` 是追加自定义策略，但插入位置由目标版本配置代码决定，不等同于抢在所有内建策略之前。

因此自定义 resolver 声称支持 `String` 或 `Object` 等宽泛类型时，既可能根本抢不到内建注解参数，也可能在整体替换配置中吞掉大量参数。应使用专用注解与精确类型作为支持条件，并用行为测试确认最终顺序。

## handleInternal 的外层约束

```text
handleInternal(request, response, handlerMethod)
  → checkRequest(request)
  → 可选 synchronizeOnSession
      → 取得 WebUtils.getSessionMutex(session)
      → 同一 session 内串行 invokeHandlerMethod
  → invokeHandlerMethod(...)
  → prepareResponse(response)
```

默认不会按 session 串行化。打开 `synchronizeOnSession` 能避免同一 session 的并发请求同时修改非线程安全会话状态，但也会降低吞吐，并且不能替代数据库或分布式并发控制。

## invokeHandlerMethod 组装哪些协作者

核心伪代码：

```text
ServletWebRequest webRequest = new ServletWebRequest(request, response)
WebDataBinderFactory binderFactory = getDataBinderFactory(handlerMethod)
ModelFactory modelFactory = getModelFactory(handlerMethod, binderFactory)

ServletInvocableHandlerMethod invocableMethod = createInvocableHandlerMethod(handlerMethod)
invocableMethod.setHandlerMethodArgumentResolvers(argumentResolvers)
invocableMethod.setHandlerMethodReturnValueHandlers(returnValueHandlers)
invocableMethod.setDataBinderFactory(binderFactory)
invocableMethod.setParameterNameDiscoverer(parameterNameDiscoverer)

ModelAndViewContainer mavContainer = new ModelAndViewContainer()
mavContainer.addAllAttributes(RequestContextUtils.getInputFlashMap(request))
modelFactory.initModel(webRequest, mavContainer, invocableMethod)
mavContainer.setIgnoreDefaultModelOnRedirect(...)

AsyncWebRequest asyncWebRequest = WebAsyncUtils.createAsyncWebRequest(...)
WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request)
asyncManager.setTaskExecutor(taskExecutor)
asyncManager.setAsyncWebRequest(asyncWebRequest)
asyncManager.registerCallableInterceptors(...)
asyncManager.registerDeferredResultInterceptors(...)

if asyncManager.hasConcurrentResult()
  → 取异步结果并包装为 concurrentResultMethod

invocableMethod.invokeAndHandle(webRequest, mavContainer)
if asyncManager.isConcurrentHandlingStarted()
  return null
return getModelAndView(mavContainer, modelFactory, webRequest)
```

`ModelFactory.initModel` 会先调用适用的 `@ModelAttribute` 方法，再合并 session attributes。它发生在 Controller 主方法之前，所以模型准备阶段也可能抛异常或触发参数解析。

### BinderFactory 也有 ControllerAdvice 顺序

`getDataBinderFactory` 会收集两类 `@InitBinder` 方法：先加入适用的全局 `@ControllerAdvice` 方法，再加入当前 Controller 的方法，最后构造 `ServletRequestDataBinderFactory`。这些方法只负责定制 binder，不应执行业务写操作；同一请求中参数绑定可能创建多个 binder。

`getModelFactory` 同样收集全局和局部 `@ModelAttribute` 方法，并配合 `SessionAttributesHandler` 恢复或保存会话模型。若 `@SessionAttributes` 声明了必须存在的属性但会话中没有，异常可能出现在 Controller 主方法之前。

## 参数解析器怎样选中

`InvocableHandlerMethod.getMethodArgumentValues` 遍历方法参数：

1. 先检查调用方传入的 `providedArgs`，常用于异常处理方法直接接收异常等框架对象。
2. 调用组合器 `supportsParameter(parameter)` 找 resolver。
3. 调用 resolver 的 `resolveArgument(...)` 得到值。
4. 把值放入 `Object[] args` 的同一位置。

`HandlerMethodArgumentResolverComposite` 会缓存“MethodParameter → resolver”，避免每次请求都线性扫描整个列表。缓存键包含参数位置和方法信息；第一次仍需按配置顺序寻找第一个支持者。

组合器只缓存“由谁解析”，不会缓存某次请求得到的参数值。路径变量、query、header 和 body 仍在每次请求中读取。修改 resolver 列表后复用旧 adapter 也不是受支持的热更新方式，通常应让容器重新初始化基础设施。

常见映射关系：

| 参数声明 | 主要 resolver | 数据来源 |
| --- | --- | --- |
| `@PathVariable long id` | `PathVariableMethodArgumentResolver` | HandlerMapping 写入的 URI 模板变量 |
| `@RequestParam boolean detail` | `RequestParamMethodArgumentResolver` | query/form 参数，再经 ConversionService 转换 |
| `@RequestHeader String token` | `RequestHeaderMethodArgumentResolver` | HTTP header |
| `@CookieValue` | `ServletCookieValueMethodArgumentResolver` | Cookie |
| `@RequestBody OrderCommand` | `RequestResponseBodyMethodProcessor` | request body + HttpMessageConverter |
| `@ModelAttribute Form form` | `ServletModelAttributeMethodProcessor` | 实例化对象并执行 WebDataBinder 绑定 |
| `HttpServletRequest` | `ServletRequestMethodArgumentResolver` | 当前 Servlet 请求对象 |
| `Principal` | `PrincipalMethodArgumentResolver` | `request.getUserPrincipal()` |

## 命名、转换、绑定、校验不是一件事

以 `@RequestParam int page` 为例：resolver 先确定参数名称并取得字符串，再由 `WebDataBinder` 使用 ConversionService/PropertyEditor 转成 int。缺少必填值和类型转换失败是不同异常：

- 缺少值通常产生 `MissingServletRequestParameterException`；
- 类型转换失败通常包装为 `MethodArgumentTypeMismatchException`；
- `@ModelAttribute` 绑定错误通常进入 `BindingResult`，没有紧邻的 `BindingResult` 参数时可能抛 `BindException`；
- `@RequestBody` 校验失败通常是 `MethodArgumentNotValidException`。

`BindingResult` 还存在严格的位置约束：它必须紧跟在对应的 model attribute、request body 或 request part 参数后。放到其他参数后面时，Spring 不会把它理解为前一个绑定对象的错误容器，校验异常仍会直接抛出。

排查“方法没有进入”时，参数解析和数据绑定是比 Controller 日志更靠前的证据点。

## doInvoke 才真正执行 Controller

参数数组准备完毕后，`invokeForRequest` 调用 `doInvoke(args)`。Spring 5.3 中它通过反射执行桥接后的目标方法，并拆分处理：

- `IllegalArgumentException`：通常表示参数类型/目标对象不匹配，Spring 增强诊断消息；
- `InvocationTargetException`：解包并重新抛出目标方法的实际异常；
- 目标抛 `RuntimeException`、`Error` 或声明过的受检异常时，尽可能保持原异常类型；
- 其他不可直接抛出的异常包装为 `IllegalStateException`。

如果 Controller Bean 已经被 Spring AOP 代理，`bean` 是代理实例。反射调用会先进入代理，再进入 AOP 拦截器链，最后才到目标方法。因此 MVC 参数解析发生在 AOP 之前，而事务拦截通常发生在 Controller/Service 代理方法边界。

## invokeAndHandle 如何解释返回值

```text
returnValue = invokeForRequest(webRequest, mavContainer, providedArgs)
setResponseStatus(webRequest)

if returnValue == null
  if request not modified / responseStatus set / requestHandled
    mavContainer.requestHandled = true
    return

returnValueHandlers.handleReturnValue(
  returnValue, getReturnValueType(returnValue), mavContainer, webRequest)
```

返回 null 不总等于无响应。方法可能已经通过 `HttpServletResponse` 写数据、设置 `@ResponseStatus`，或命中缓存协商的 not-modified 分支。

## ModelAndView 如何收尾

若 `mavContainer.isRequestHandled()` 为 true，适配器返回 null；否则把 viewName/view、model、status 组装成 `ModelAndView`，并通过 `ModelFactory.updateModel` 同步 session attributes、补充 `BindingResult`。

对于 REST，返回值处理器通常直接写 response 并标记 handled；对于模板页面，才把模型和视图名交回 `DispatcherServlet.render`。

## 可扩展但要注意顺序

应用可以通过 `WebMvcConfigurer` 增加 argument resolver、return value handler、converter 和 formatter。新增 resolver 通常被追加到自定义列表位置，并不一定覆盖框架内建语义。若确实要改全局顺序，需要检查目标版本的 `RequestMappingHandlerAdapter` 最终组合，而不是凭注解名称推断优先级。
