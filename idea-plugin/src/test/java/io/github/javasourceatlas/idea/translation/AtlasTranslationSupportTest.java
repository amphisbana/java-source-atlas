package io.github.javasourceatlas.idea.translation;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * 在真实 Java PSI 中验证源码注释选择规则。
 */
public final class AtlasTranslationSupportTest extends BasePlatformTestCase {

    /**
     * 验证光标位于行注释时优先选择该注释。
     */
    public void testShouldSelectCommentAtCaret() {
        myFixture.configureByText(
                "CommentSample.java",
                """
                        class CommentSample {
                            void run() {
                                int value = 1; // <caret>translate this comment
                            }
                        }
                        """
        );

        assertEquals("// translate this comment", selectedCommentText());
    }

    /**
     * 验证光标位于方法体时回退到当前方法 Javadoc。
     */
    public void testShouldFallbackToCurrentMethodJavadoc() {
        myFixture.configureByText(
                "JavadocSample.java",
                """
                        class JavadocSample {
                            /** Explains the current method. */
                            void run() {
                                <caret>System.out.println("running");
                            }
                        }
                        """
        );

        assertEquals("/** Explains the current method. */", selectedCommentText());
    }

    /**
     * 验证当前方法没有任何注释时不返回源码文本范围。
     */
    public void testShouldRejectMethodWithoutComment() {
        myFixture.configureByText(
                "PlainSample.java",
                """
                        class PlainSample {
                            void run() {
                                <caret>System.out.println("running");
                            }
                        }
                        """
        );

        assertNull(resolveCommentRange());
    }

    /**
     * 解析并读取当前光标对应的注释文本。
     *
     * @return 当前选中的注释文本
     */
    private String selectedCommentText() {
        TextRange range = resolveCommentRange();
        assertNotNull(range);
        return range.substring(myFixture.getEditor().getDocument().getText());
    }

    /**
     * 提交测试文档后通过 Translation 适配器解析注释范围。
     *
     * @return 当前注释范围；没有注释时返回 null
     */
    private TextRange resolveCommentRange() {
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        return AtlasTranslationSupport.findCurrentCommentRange(getProject(), myFixture.getEditor());
    }
}
