package io.github.javasourceatlas.idea.debug;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.javasourceatlas.idea.model.AtlasBreakpoint;
import io.github.javasourceatlas.idea.model.AtlasEntryPoint;
import io.github.javasourceatlas.idea.model.AtlasEvidence;
import io.github.javasourceatlas.idea.model.AtlasLab;
import io.github.javasourceatlas.idea.model.AtlasSource;
import io.github.javasourceatlas.idea.model.AtlasTopic;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 在真实 IDEA Project、Java PSI 和 XDebugger 组件中验证源码阅读核心工作流。
 */
public final class AtlasIdeWorkflowIntegrationTest extends BasePlatformTestCase {

    /**
     * 每个用例结束后清理真实断点管理器中的 Atlas 断点，避免平台测试相互污染。
     */
    @Override
    protected void tearDown() throws Exception {
        try {
            AtlasBreakpointManager.removeManagedBreakpoints(getProject(), null);
        } finally {
            super.tearDown();
        }
    }

    /**
     * 验证源码入口可以定位到方法体第一条语句、创建真实行断点并解析下一步调试引导。
     */
    public void testShouldCompleteSourceBreakpointAndGuidanceWorkflow() {
        myFixture.configureByText(
                "WorkflowSample.java",
                """
                        package atlas.fixture;

                        public class WorkflowSample {
                            public void first(int value) {
                                int next = value + 1;
                                System.out.println(next);
                            }

                            public void second() {
                                System.out.println("done");
                            }
                        }
                        """
        );
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        AtlasTopic topic = fixtureTopic();

        AtlasBreakpointManager.Resolution resolution = ReadAction.compute(() ->
                AtlasBreakpointManager.resolveLocations(getProject(), topic, topic.breakpoints()));
        assertEmpty(resolution.unresolved());
        assertEquals(2, resolution.locations().size());
        assertEquals(4, resolution.locations().getFirst().line());

        AtomicReference<AtlasBreakpointManager.AddResult> resultReference = new AtomicReference<>();
        runOnUiThread(() -> AtlasBreakpointManager.addResolved(
                getProject(),
                resolution,
                resultReference::set
        ));
        AtlasBreakpointManager.AddResult result = resultReference.get();
        assertNotNull(result);
        assertEquals(2, result.added());
        assertEquals(2, AtlasBreakpointState.getInstance(getProject()).locations().size());

        AtlasBreakpointManager.BreakpointLocation firstLocation = resolution.locations().getFirst();
        AtlasDebugGuidance guidance = AtlasDebugGuidanceResolver.resolve(
                topic,
                AtlasBreakpointState.getInstance(getProject()),
                firstLocation.file().getUrl(),
                firstLocation.line()
        ).orElseThrow();
        assertEquals("first(int)", guidance.breakpointMethod());
        assertEquals("first-main", guidance.evidenceId());
        assertEquals("second()", guidance.nextBreakpointMethod());
        assertEquals("验证 first 的第一条语句已执行", guidance.claim());
    }

    /**
     * 在当前线程不是 IDEA UI 线程时同步切换到 UI 线程执行断点写入。
     *
     * @param action 需要在 UI 线程执行的动作
     */
    private void runOnUiThread(Runnable action) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            action.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(action);
        }
    }

    /**
     * 创建只依赖临时源码文件的完整专题、证据和断点索引。
     *
     * @return 集成测试专题
     */
    private AtlasTopic fixtureTopic() {
        AtlasEntryPoint first = new AtlasEntryPoint("first(int)", "/fixture#first", "第一步", null);
        AtlasEntryPoint second = new AtlasEntryPoint("second()", "/fixture#second", "第二步", null);
        AtlasEvidence evidence = new AtlasEvidence(
                "first-main",
                "main",
                "验证 first 的第一条语句已执行",
                "first(int)",
                "/fixture#first",
                "first",
                "atlas.fixture.WorkflowSample",
                "first",
                "next 等于 value + 1"
        );
        return new AtlasTopic(
                "fixture-workflow",
                "IDEA 工作流集成测试",
                "OpenJDK 21",
                "jdk-21+35",
                "验证真实 IDEA 平台边界",
                "断点是否位于方法体第一条语句",
                "完成源码定位、断点添加和引导解析",
                null,
                null,
                List.of("OpenJDK 21"),
                new AtlasLab("fixture", "atlas.fixture.WorkflowSample", "WorkflowSample.java"),
                new AtlasSource("atlas.fixture.WorkflowSample", "WorkflowSample.java"),
                List.of(),
                null,
                List.of(first, second),
                List.of(evidence),
                List.of(
                        new AtlasBreakpoint(
                                "first(int)",
                                "进入第一步",
                                List.of("value", "next"),
                                null,
                                "first-main"
                        ),
                        new AtlasBreakpoint(
                                "second()",
                                "进入第二步",
                                List.of("this"),
                                null,
                                null
                        )
                )
        );
    }
}
