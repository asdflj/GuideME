package guideme.layout.flow;

import static org.assertj.core.api.Assertions.assertThat;

import guideme.document.flow.LytFlowSpan;
import guideme.document.flow.LytFlowText;
import guideme.layout.FontMetrics;
import guideme.layout.LayoutContext;
import guideme.style.ResolvedTextStyle;
import guideme.style.TextAlignment;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class LineBuilderTest {

    @Test
    void breakAtStartOfChunkAfterWhitespaceInPreviousChunk() {
        var lines = getLines(3, "A ", "BC");

        assertThat(lines).extracting(this::getTextContent).containsExactly(
                "A ",
                "BC");
    }

    /**
     * When not necessary, don't break in the middle of a word.
     */
    @Test
    void dontBreakInWords() {
        var lines = getLines(3, "A BC");

        assertThat(lines).extracting(this::getTextContent).containsExactly(
                "A ",
                "BC");
    }

    /**
     * When a white-space character causes a line-break, it is removed.
     */
    @Test
    void testWhitespaceCausingLineBreakGetsRemoved() {
        var lines = getLines(1, "A B");

        assertThat(lines).extracting(this::getTextContent).containsExactly(
                "A",
                "B");
    }

    /**
     * Test white-space collapsing.
     */
    @Test
    void testWhitespaceCollapsing() {
        var lines = getLines(3, "A  B");

        assertThat(lines).extracting(this::getTextContent).containsExactly(
                "A B");
    }

    /**
     * Consider CJK characters as break opportunities and don't fall back to the last whitespace in mixed language text.
     */
    @Test
    void testBreakBetweenCJKCharacters() {
        var lines = getLines(5, "A Bを変更す");

        assertThat(lines).extracting(this::getTextContent).containsExactly(
                "A Bを変", "更す");
    }

    private static ArrayList<Line> getLines(int charsPerLine, String... textChunks) {
        var lines = new ArrayList<Line>();
        var floats = new ArrayList<LineBlock>();
        var context = new LayoutContext(new MockFontMetrics());
        var lineBuilder = new LineBuilder(context, 0, 0, charsPerLine * 5, lines, floats, TextAlignment.LEFT);

        for (String textChunk : textChunks) {
            var flowContent = new LytFlowText();
            flowContent.setText(textChunk);
            flowContent.setParent(new LytFlowSpan());
            lineBuilder.accept(flowContent);
        }

        lineBuilder.end();
        return lines;
    }

    private String getTextContent(Line line) {
        var result = new StringBuilder();
        for (var el = line.firstElement(); el != null; el = el.next) {
            if (el instanceof LineTextRun run) {
                result.append(run.text);
            }
        }
        return result.toString();
    }

    // Every character is 5 pixels wide
    record MockFontMetrics() implements FontMetrics {
        @Override
        public float getAdvance(int codePoint, ResolvedTextStyle style) {
            return 5;
        }

        @Override
        public int getLineHeight(ResolvedTextStyle style) {
            return 10;
        }
    }
}
