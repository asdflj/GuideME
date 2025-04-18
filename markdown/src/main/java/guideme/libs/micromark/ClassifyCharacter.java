package guideme.libs.micromark;

import guideme.libs.micromark.symbol.Codes;
import guideme.libs.micromark.symbol.Constants;

public final class ClassifyCharacter {
    private ClassifyCharacter() {
    }

    /**
     * Classify whether a character code represents whitespace, punctuation, or something else.
     * <p>
     * Used for attention (emphasis, strong), whose sequences can open or close based on the class of surrounding
     * characters.
     * <p>
     * Note that eof (`null`) is seen as whitespace.
     */
    public static int classifyCharacter(int code) {
        if (code == Codes.eof ||
                CharUtil.markdownLineEndingOrSpace(code) ||
                CharUtil.unicodeWhitespace(code)) {
            return Constants.characterGroupWhitespace;
        }

        if (CharUtil.unicodePunctuation(code)) {
            return Constants.characterGroupPunctuation;
        }

        return 0;
    }

}
