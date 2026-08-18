# 参数、返回值与异常：请求链的三个策略组合器

## 为什么把三者放在一起读

它们都采用相同结构：

```text
框架拿到一个待处理对象
  → 按顺序询问策略 supports...(...)
  → 缓存或选中第一个匹配策略
  → 委托策略完成转换
```

但失败语义不同：参数解析失败发生在 Controller 前，返回值处理失败发生在 Controller 正常返回后，异常解析器则尝试接住前两者以及 Controller 自己抛出的异常。

## 参数解析完整数据流

```text
MethodParameter
  → HandlerMethodArgumentResolverComposite
  → resolver.resolveArgument
      → 从 request / URI variables / headers / body 取原始值
      → WebDataBinderFactory.createBinder
      → ConversionService / PropertyEditor 类型转换
      → 可选 Validator 校验
      → 生成 Java 参数值或 BindingResult
  → Object[] args
```

组合器的选择逻辑可以压缩为：

```text
resolveArgument(parameter, mavContainer, webRequest, binderFactory)
  → argumentResolverCache.get(parameter)
  → 未命中时按 argumentResolvers 顺序调用 supportsParameter
  → 缓存第一个支持者
  → resolver.resolveArgument(...)
```

缓存命中只代表策略不再重选，绝不代表跳过参数读取与转换。`InvocableHandlerMethod` 还会先检查 `providedArgs`：异常处理方法接收的异常对象、原始 handler 等参数可由调用方直接提供，其余参数才进入 resolver 组合器。

### `@RequestBody` 的额外链路

`RequestResponseBodyMethodProcessor` 根据 `Content-Type` 遍历 `HttpMessageConverter`，选择 `canRead(targetType, mediaType)` 的转换器读取 body。随后执行 `RequestBodyAdvice`、数据绑定与校验。

选择 converter 的核心输入是“目标 Java 类型 + 请求媒体类型”，不是只看是否存在 Jackson。请求没有 body、媒体类型不支持、JSON 语法错误、字段校验失败分别对应不同异常。

更完整的顺序是：

```text
resolveArgument
  → readWithMessageConverters
      → RequestBodyAdvice.beforeBodyRead
      → converter.read / GenericHttpMessageConverter.read
      → RequestBodyAdvice.afterBodyRead（空 body 则走 handleEmptyBody）
  → binderFactory.createBinder
  → validateIfApplicable（存在 @Valid / @Validated 等提示时）
  → 校验失败且后面没有可接收的 Errors/BindingResult：抛 MethodArgumentNotValidException
  → mavContainer 加入 BindingResult
```

body 输入流通常只能消费一次。Filter 或日志组件若提前读取却没有用可重复读取的 wrapper 替换 request，MVC 看到的会是空 body；这类问题不能靠换 converter 修复。

### `@ModelAttribute` 的额外链路

`ServletModelAttributeMethodProcessor` 先从 model 取同名对象，缺失时创建，然后通过 `WebDataBinder` 绑定 request 参数。它适合表单模型，但把任意请求参数绑定到领域对象可能产生过度绑定风险。应使用专用命令对象并配置允许字段，而不是直接绑定持久化实体。

## 返回值处理器如何分流

| 返回声明 | 主要 handler | 结果 |
| --- | --- | --- |
| `ModelAndView` | `ModelAndViewMethodReturnValueHandler` | 直接设置视图和模型 |
| `String`（普通 `@Controller`） | `ViewNameMethodReturnValueHandler` | 解释为视图名 |
| `@ResponseBody String` | `RequestResponseBodyMethodProcessor` | 经 converter 写 response body |
| `ResponseEntity<T>` | `HttpEntityMethodProcessor` | 写 status/header/body |
| `void` | 多分支 | 可能已写响应、使用默认视图名或仍需判断 |
| `Callable<T>` | `CallableMethodReturnValueHandler` | 启动异步处理 |
| `DeferredResult<T>` | `DeferredResultMethodReturnValueHandler` | 注册外部完成结果 |

相同的 Java 类型会因方法/类注解而改变含义。`String` 在 `@RestController` 中是响应体，在普通 `@Controller` 中通常是视图名。

### 内容协商与消息转换

对响应体，Spring 综合：

1. 请求 `Accept` 可接受的媒体类型；
2. mapping 的 `produces` 条件；
3. converter 对返回类型可写的媒体类型；
4. 选出兼容且最具体的媒体类型；
5. 调用 `HttpMessageConverter.write`。

“有 converter 仍返回 406”常见原因是 Accept 与 mapping 的 produces 条件或 converter 可写类型没有交集。反过来，415 既可能在 mapping 的 consumes 条件比较时产生，也可能在请求体读取时找不到可读 converter；必须结合 handler 是否已经匹配判断阶段。

对 `@ResponseBody`，`RequestResponseBodyMethodProcessor.handleReturnValue` 会先把 `requestHandled` 设为 true，再进入 `writeWithMessageConverters`：

```text
返回值与声明类型
  → ResponseBodyAdvice.beforeBodyWrite
  → 取得 request Accept 与 mapping producible media types
  → 求 compatible media types 并按具体程度排序
  → 遍历 converter.canWrite(targetType, selectedMediaType)
  → converter.write(body, selectedMediaType, outputMessage)
```

响应已经 committed 后再失败，异常解析器可能无法可靠修改 status/header/body。线上看到“日志记录 500，但客户端收到半截 200”时，应检查首次 flush 的位置以及 converter 写入异常，而不是只看最终异常类型。

### 组合器对异步返回值有一次特殊判断

`HandlerMethodReturnValueHandlerComposite.selectHandler` 会先判断返回值是否为异步类型；若是，只在实现 `AsyncHandlerMethodReturnValueHandler` 的候选中继续匹配。随后仍按列表顺序选择第一个 `supportsReturnType` 为 true 的处理器。自定义同步 handler 不应声称支持 `Callable`、`DeferredResult` 等类型，否则容易与异步二次分派协议冲突。

## 异常从哪里进入解析链

`DispatcherServlet.processHandlerException` 会先清除可能已设置的内容类型和 buffer，再按顺序询问 `HandlerExceptionResolver`。Spring MVC Java 配置的常见默认顺序是：

1. `ExceptionHandlerExceptionResolver`：处理 Controller 或 `@ControllerAdvice` 中的 `@ExceptionHandler`。
2. `ResponseStatusExceptionResolver`：处理 `@ResponseStatus` 与 `ResponseStatusException`。
3. `DefaultHandlerExceptionResolver`：把框架标准异常映射为 400、404、405、406、415、500 等状态。

第一个返回非 null 的解析器结束查找。自定义解析器顺序错误可能抢先吞掉异常，让 `@ControllerAdvice` 不再执行。

## `@ExceptionHandler` 怎样找到方法

`ExceptionHandlerExceptionResolver` 为 Controller 类型和适用的 `@ControllerAdvice` 缓存 `ExceptionHandlerMethodResolver`。Spring 5.3 基线会先查当前异常类型；没有方法再沿 cause 链查找。多个候选按异常继承深度选择更具体者。

查找顺序是“当前 Controller 的局部 `@ExceptionHandler`”优先，然后按 `ControllerAdviceBean` 顺序检查适用的全局 Advice。局部方法若能处理异常，全局 Advice 不会再参与；全局 Advice 之间也不是按异常类全局比较最具体者，而是第一个 Advice 内部找到匹配方法就返回。

需要注意版本边界：Spring 5.3 的 `@ExceptionHandler` 只有异常类型映射，不包含 `produces` 属性；媒体类型条件参与异常方法选择是 Spring 6.2 的能力。不要把 6.2 的同异常多媒体类型写法复制到本专题的 5.3 Lab。

得到异常处理方法后，它仍然使用 `ServletInvocableHandlerMethod`：异常对象、原 handlerMethod 等作为 provided args，其他参数继续走 argument resolvers，返回值继续走 return value handlers。

所以 `@ExceptionHandler` 不是绕开 MVC 的特殊回调，而是复用同一套方法调用基础设施。

### `doResolveHandlerMethodException` 的完整主线

精确入口是 `ExceptionHandlerExceptionResolver.doResolveHandlerMethodException(...)`。它不只负责“找方法”，还把异常处理方法重新接入参数解析、返回值处理和 `ModelAndView` 协议：

```text
doResolveHandlerMethodException(request, response, handlerMethod, exception)
  -> getExceptionHandlerMethod(handlerMethod, exception)
     -> 先查当前 Controller 的局部 @ExceptionHandler
     -> 未命中再按顺序查适用的 @ControllerAdvice
  -> 没有匹配方法：return null，让下一个 HandlerExceptionResolver 继续
  -> 为异常处理方法设置 argumentResolvers 与 returnValueHandlers
  -> 创建 ServletWebRequest 与 ModelAndViewContainer
  -> 把 exception、完整 cause 链、原 handlerMethod 作为 provided args
  -> ServletInvocableHandlerMethod.invokeAndHandle(...)
  -> requestHandled=true：返回空 ModelAndView，表示响应已直接处理
  -> 否则把 view、model、status 与 flash attributes 组装为 ModelAndView
```

这里的“空 `ModelAndView`”与 `null` 必须分开理解：前者表示当前解析器已经处理异常且不需要渲染视图，后者表示当前解析器没有处理，`DispatcherServlet` 还要继续询问后续解析器。若异常处理方法自己再次失败，Spring 5.3 会记录警告并返回 `null`；当新异常只是原异常或其 cause 链中的对象时不会重复打印同一条失败警告。

### 三个默认解析器分别解决什么

| 解析器 | 主要输入 | 已处理标志 |
| --- | --- | --- |
| `ExceptionHandlerExceptionResolver` | 局部/全局 `@ExceptionHandler` | 异常方法正常执行并生成 ModelAndView 或直接写响应 |
| `ResponseStatusExceptionResolver` | `@ResponseStatus`、`ResponseStatusException`，以及 cause | 调用 `sendError` 或应用状态与 reason |
| `DefaultHandlerExceptionResolver` | MVC/Servlet 标准异常 | 设置 4xx/5xx、必要 header，通常返回空 ModelAndView |

解析器返回 `null` 表示“我不处理，请继续”；返回空 `ModelAndView` 表示“已经处理，不需要视图”。这两个值的语义相反，是自定义异常解析器最容易写错的地方。

## 已处理异常为什么还能在测试里看到

实验的 `OrderController` 抛 `OrderNotFoundException`，`OrderExceptionHandler` 返回 404。此时：

- HTTP 客户端得到正常的 404 响应；
- `DispatcherServlet` 认为异常已经解析，`afterCompletion` 收到 null；
- `MockMvc` 的 `MvcResult.getResolvedException()` 仍保留原异常，便于测试断言。

这是三个不同观察面，不应互相替代。线上诊断也应同时看响应状态、异常解析日志与最终未处理异常。

## postHandle 与 afterCompletion 的区别

| 场景 | `postHandle` | `afterCompletion` |
| --- | --- | --- |
| handler 正常完成 | 逆序执行 | 渲染/结果处理后逆序执行 |
| handler 抛异常 | 不执行 | 已成功 preHandle 的拦截器会执行 |
| 某 preHandle 返回 false | handler 不执行 | 只回调此前 preHandle 成功的拦截器 |
| 启动异步处理 | 普通 postHandle 不执行 | 先走 async 回调，最终完成取决于二次分派 |

资源清理通常放在 `afterCompletion` 或 Filter finally 中，不应只依赖 `postHandle`。

## 常见状态码与源码位置

| 状态 | 常见原因 | 主要阶段 |
| --- | --- | --- |
| 400 | 参数缺失、类型转换或 body 读取失败 | argument resolver / binder / converter |
| 404 | 没有 handler，或业务异常主动映射 | HandlerMapping / exception resolver |
| 405 | 路径有候选但 HTTP 方法不支持 | RequestMappingInfo 部分匹配处理 |
| 406 | produces 条件失败，或没有可写响应 converter | HandlerMapping，或 return value + content negotiation |
| 415 | consumes 条件失败，或没有可读请求 converter | HandlerMapping，或 `@RequestBody` 参数解析 |
| 500 | 未处理异常或响应写入失败 | handler / return handler / resolver 之后 |

## 自定义策略的工程边界

- 自定义 argument resolver 应使用明确注解或类型条件，避免声明支持所有参数。
- 自定义 return handler 必须明确何时设置 `requestHandled`，否则可能在写 body 后又进入视图渲染。
- 自定义 exception resolver 不应无条件返回空 `ModelAndView`，那会把所有错误标成已处理。
- converter 需要同时考虑 `canRead/canWrite`、媒体类型、泛型类型与线程安全。

这些接口本身是扩展点，但组合器缓存和默认顺序属于目标版本实现细节。修改配置后应通过行为测试固定最终选择，而不是反射依赖内部缓存。

## Spring 6 的重要变化

Spring 6 使用 Jakarta Servlet，并增加 `ProblemDetail`、`ErrorResponse` 等 RFC 7807 风格错误表达。Spring 6.1 还加强了 MVC 方法级校验。前端控制器和三类组合器的整体结构仍连续，但异常类型、默认 handler 列表及响应格式应按具体 6.x 版本源码复核。
