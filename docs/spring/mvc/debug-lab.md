# Spring MVC 断点实验手册

## 实验入口

模块：`labs/spring-framework-lab`

主类：`io.github.javasourceatlas.spring.mvc.SpringMvcDebugLab`

```bash
mvn -pl labs/spring-framework-lab \
  -Dtest='SpringMvcBehaviorTest,RequestTransactionIntegrationTest' test

mvn -pl labs/spring-framework-lab compile exec:java -Dexec.args=mvc
```

也可以单独运行模块：

```bash
mvn -f labs/spring-framework-lab/pom.xml \
  -Dtest='SpringMvcBehaviorTest,RequestTransactionIntegrationTest' test
```

实验使用 `MockMvcBuilders.webAppContextSetup(context)` 创建 `DispatcherServlet`。它不监听端口，但会读取真实 `RequestMappingHandlerMapping`、`RequestMappingHandlerAdapter` 和异常解析器 Bean，不能与只调用 Controller 的单元测试等同。

## 案例结构

```text
GET /orders/42?detail=true
  → TraceHandlerInterceptor.preHandle
  → OrderController.findOrder(42, true)
  → ResponseEntity<String>
  → HttpEntityMethodProcessor
  → 200 + X-Atlas-Handler + body
  → postHandle
  → afterCompletion(null)

GET /orders/0
  → preHandle
  → OrderController 抛 OrderNotFoundException
  → OrderExceptionHandler.handleMissingOrder
  → 404 + body
  → 不执行 postHandle
  → afterCompletion(null)  // 异常已经被 resolver 处理

POST /orders/42
  → 路径存在但方法不匹配
  → HttpRequestMethodNotSupportedException
  → DefaultHandlerExceptionResolver
  → 405

POST /orders/echo
  Content-Type: text/plain
  Accept: text/plain
  body: atlas
  → RequestResponseBodyMethodProcessor
  → StringHttpMessageConverter.read
  → OrderController.echoOrder("atlas")
  → HttpEntityMethodProcessor
  → StringHttpMessageConverter.write
  → 201 + received=atlas
```

## 推荐断点顺序

| 序号 | 断点 | 观察变量 |
| --- | --- | --- |
| 1 | `DispatcherServlet.doDispatch` | `processedRequest`、`mappedHandler`、`mv` |
| 2 | `AbstractHandlerMethodMapping.lookupHandlerMethod` | `directPathMatches`、`matchingMappings`、`bestMatch` |
| 3 | `RequestMappingInfoHandlerMapping.handleNoMatch` | `partialMatches`、`methods`、`consumes`、`produces` |
| 4 | `AbstractHandlerMapping.getHandlerExecutionChain` | `handler`、`interceptors` |
| 5 | `DispatcherServlet.getHandlerAdapter` | `handlerAdapters`、`handler` |
| 6 | `RequestMappingHandlerAdapter.invokeHandlerMethod` | `invocableMethod`、`mavContainer` |
| 7 | `InvocableHandlerMethod.getMethodArgumentValues` | `parameters`、`args`、当前 resolver |
| 8 | `RequestResponseBodyMethodProcessor.resolveArgument` | `arg`、`binder`、当前 converter |
| 9 | `InvocableHandlerMethod.doInvoke` | `getBean()`、`getBridgedMethod()`、`args` |
| 10 | `HandlerMethodReturnValueHandlerComposite.handleReturnValue` | `returnType`、`returnValue`、handler |
| 11 | `DispatcherServlet.processHandlerException` | `exception`、`handlerExceptionResolvers` |
| 12 | `HandlerExecutionChain.triggerAfterCompletion` | `interceptorIndex`、`ex` |

IDEA 中不建议给 `DispatcherServlet.doDispatch` 设置全局方法断点后启动大型应用，它会拦截健康检查和静态资源。先以 `/orders/42` 为条件，或只运行这个最小 Lab。

## 实验一：路由与参数解析

运行 `SpringMvcBehaviorTest.shouldResolveArgumentsAndWriteResponse`。

在 `lookupHandlerMethod` 中确认最佳匹配为 `OrderController.findOrder(long, boolean)`。随后在参数组合器中观察：

- `orderId` 由 `PathVariableMethodArgumentResolver` 从 URI variables 取得字符串 `42`，再转换为 long；
- `detail` 由 `RequestParamMethodArgumentResolver` 取得 `true`，再转换为 boolean；
- `Object[] args` 最终为 `[42L, true]`。

返回值为 `ResponseEntity<String>`，应由 `HttpEntityMethodProcessor` 写入 status、header 和 body。此时 `MvcResult.getModelAndView()` 为 null。

## 实验二：拦截器回调顺序

正常请求的事件应为：

```text
interceptor:preHandle
controller:findOrder(42,true)
interceptor:postHandle
interceptor:afterCompletion(exception=null)
```

可再注册第二个拦截器观察 pre 正序、post/after 逆序。修改 `preHandle` 返回 false 时，确认 Controller 不执行，且当前返回 false 的拦截器自身不会收到 `afterCompletion`。

## 实验三：异常解析

运行 `shouldResolveControllerException`，在 `DispatcherServlet.processHandlerException` 和 `ExceptionHandlerExceptionResolver.doResolveHandlerMethodException` 设断点。

确认：

1. `OrderNotFoundException` 来源于反射调用目标，而不是参数解析。
2. resolver 找到全局 `OrderExceptionHandler.handleMissingOrder`。
3. 异常方法也使用 argument/return value 处理体系。
4. 响应变为 404，`resolvedException` 仍保留原异常。
5. handler 异常路径没有 `postHandle`，但有 `afterCompletion(null)`。

## 实验四：405 与 Controller 异常不是一类问题

运行 `shouldRejectUnsupportedHttpMethod`。`POST /orders/42` 在映射阶段就形成 method-not-supported 异常，实验事件列表为空，说明拦截器和 Controller 都没有执行。

在 `RequestMappingInfoHandlerMapping.handleNoMatch` 观察 partial matches：路径条件命中，HTTP method 条件不命中。默认异常解析器随后设置 405。

## 实验五：同一个消息转换器怎样读写 body

运行 `shouldReadRequestBodyAndWriteResponse`，在 `RequestResponseBodyMethodProcessor.resolveArgument` 和 `AbstractMessageConverterMethodProcessor.writeWithMessageConverters` 设断点。

确认参数阶段根据 `String + text/plain` 选中 `StringHttpMessageConverter.read`，Controller 得到 `atlas`；返回阶段由 `HttpEntityMethodProcessor` 处理 `ResponseEntity<String>`，再次选择该 converter 写出 `received=atlas`。读和写共享 converter 类型，但发生在不同组合器中。

## 实验六：四种失败发生在两个不同阶段

| 测试 | 异常 | 失败阶段 | Controller / interceptor |
| --- | --- | --- | --- |
| `shouldRejectInvalidPathVariableBeforeController` | `MethodArgumentTypeMismatchException` | 参数 resolver 转换 | preHandle 已执行，Controller 未执行 |
| `shouldRejectMissingRequestBodyBeforeController` | `HttpMessageNotReadableException` | 请求体 resolver | preHandle 已执行，Controller 未执行 |
| `shouldRejectUnsupportedRequestMediaTypeDuringMapping` | `HttpMediaTypeNotSupportedException` | mapping 的 consumes 条件 | 执行链尚未建立 |
| `shouldRejectUnacceptableResponseMediaTypeDuringMapping` | `HttpMediaTypeNotAcceptableException` | mapping 的 produces 条件 | 执行链尚未建立 |

前两种错误已经匹配到 handler，所以会先执行 `preHandle`，异常被默认 resolver 处理后再执行 `afterCompletion(null)`。后两种在 `getHandler` 内抛出，`mappedHandler` 仍为 null，因此实验事件列表为空。

## 自动测试覆盖

| 测试 | 固定的公开行为 |
| --- | --- |
| `shouldResolveArgumentsAndWriteResponse` | 路径变量、查询参数、ResponseEntity 与正常拦截器顺序 |
| `shouldResolveControllerException` | `@RestControllerAdvice`、404、resolved exception 与异常完成回调 |
| `shouldRejectUnsupportedHttpMethod` | 路径存在但方法错误返回 405，业务 handler 不执行 |
| `shouldReadRequestBodyAndWriteResponse` | `@RequestBody`、String converter、ResponseEntity 与 201 |
| `shouldRejectInvalidPathVariableBeforeController` | 路径变量类型转换失败返回 400 |
| `shouldRejectMissingRequestBodyBeforeController` | 必填 body 为空返回 400 |
| `shouldRejectUnsupportedRequestMediaTypeDuringMapping` | consumes 不匹配返回 415 |
| `shouldRejectUnacceptableResponseMediaTypeDuringMapping` | produces 与 Accept 不匹配返回 406 |

测试只断言公开输出和应用事件，不反射读取 Spring 内部缓存。内部字段适合断点学习，不适合作为兼容性测试契约。

## 调试时避免改变程序状态

- 不要在 Evaluate Expression 中主动调用 `getHandler`、`resolveArgument` 或 Controller 方法，它们可能消耗 body、写响应或产生业务副作用。
- 查看 request body 时注意输入流通常只能消费一次。
- 异步请求要区分首次和二次 dispatch，断点可能命中两轮。
- 调试真实服务时避免冻结所有线程的方法断点，否则请求线程持锁时可能导致整个应用假死。
