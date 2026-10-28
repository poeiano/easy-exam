package local.offerin.practice;

import org.junit.Test;
import static org.junit.Assert.*;

public class MathMarkdownTest {
    @Test public void acceptsModelInlineDelimitersAndKeepsFormulaCommands(){
        assertEquals("设 $$a_1,\\dots,a_n$$，则 $$\\frac{1}{2}$$。",MathMarkdown.normalize("设 $a_1,\\dots,a_n$，则 \\(\\frac{1}{2}\\)。"));
    }
    @Test public void displayMathHasRequiredBlockNewlines(){
        assertEquals("结果\n$$\nx^2+1\n$$\n结束",MathMarkdown.normalize("结果\\[x^2+1\\]结束"));
        assertEquals("$$\n\\sum_{i=1}^n i\n$$\n",MathMarkdown.normalize("$$\\sum_{i=1}^n i$$"));
    }
    @Test public void preservesFencedAndInlineCodeVerbatim(){
        String code="```python\nprint('$x$ \\dots')\n```\n`\\(x\\) \\dots`，正文\\dots";
        assertEquals(code.substring(0,code.length()-5)+"…",MathMarkdown.normalize(code));
        String tilde="~~~text\n$x$ \\dots\n~~~";assertEquals(tilde,MathMarkdown.normalize(tilde));
    }
    @Test public void preservesPartialStreamAndEscapedDollars(){
        for(String partial:new String[]{"推导 $x^2","\\[\\frac{1}{", "```java\n$x$", "`$x$"})assertEquals(partial,MathMarkdown.normalize(partial));
        assertEquals("价格 \\$5 和 \\$10",MathMarkdown.normalize("价格 \\$5 和 \\$10"));
        assertEquals("$5 and $10",MathMarkdown.normalize("$5 and $10"));
    }
    @Test public void standaloneEllipsesHaveReadableFallbackWithoutChangingCommandPrefixes(){
        assertEquals("1,2,…; ⋯ ⋮ ⋱ \\dotsc",MathMarkdown.normalize("1,2,\\dots; \\cdots \\vdots \\ddots \\dotsc"));
    }
    @Test public void ordinaryMarkdownIsUnchanged(){String value="# 标题\n\n**答案**\n\n1. 第一步\n2. 第二步\n";assertEquals(value,MathMarkdown.normalize(value));}
}
