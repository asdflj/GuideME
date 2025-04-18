package guideme.compiler.tags;

import guideme.compiler.IdUtils;
import guideme.compiler.IndexingContext;
import guideme.compiler.IndexingSink;
import guideme.compiler.PageCompiler;
import guideme.document.block.LytImage;
import guideme.document.flow.InlineBlockAlignment;
import guideme.document.flow.LytFlowInlineBlock;
import guideme.document.flow.LytFlowParent;
import guideme.libs.mdast.mdx.model.MdxJsxElementFields;
import java.util.Set;
import net.minecraft.ResourceLocationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FloatingImageCompiler extends FlowTagCompiler {
    public static final String TAG_NAME = "FloatingImage";
    private static final Logger LOG = LoggerFactory.getLogger(FloatingImageCompiler.class);

    @Override
    public Set<String> getTagNames() {
        return Set.of(TAG_NAME);
    }

    @Override
    protected void compile(PageCompiler compiler, LytFlowParent parent, MdxJsxElementFields el) {
        var src = el.getAttributeString("src", null);
        var align = el.getAttributeString("align", "left");
        var title = el.getAttributeString("title", null);

        var image = new LytImage();
        if (title != null) {
            image.setTitle(title);
        }
        try {
            var imageId = IdUtils.resolveLink(src, compiler.getPageId());
            var imageContent = compiler.loadAsset(imageId);
            if (imageContent == null) {
                LOG.error("Couldn't find image {}", src);
                image.setTitle("Missing image: " + src);
            }
            image.setImage(imageId, imageContent);
        } catch (ResourceLocationException e) {
            LOG.error("Invalid image id: {}", src);
            image.setTitle("Invalid image URL: " + src);
        }

        // Wrap it in a flow content inline block
        var inlineBlock = new LytFlowInlineBlock();
        inlineBlock.setBlock(image);
        switch (align) {
            case "left" -> {
                inlineBlock.setAlignment(InlineBlockAlignment.FLOAT_LEFT);
                image.setMarginRight(5);
                image.setMarginBottom(5);
            }
            case "right" -> {
                inlineBlock.setAlignment(InlineBlockAlignment.FLOAT_RIGHT);
                image.setMarginLeft(5);
                image.setMarginBottom(5);
            }
            default -> {
                parent.append(compiler.createErrorFlowContent("Invalid align. Must be left or right.", el));
                return;
            }
        }

        parent.append(inlineBlock);
    }

    @Override
    public void index(IndexingContext indexer, MdxJsxElementFields el, IndexingSink sink) {
        var title = el.getAttributeString("title", null);
        if (title != null) {
            sink.appendText(el, title);
        }
    }
}
