package guideme.compiler.tags;

import guideme.compiler.IndexingContext;
import guideme.compiler.IndexingSink;
import guideme.compiler.PageCompiler;
import guideme.document.block.LytBlockContainer;
import guideme.document.block.LytItemGrid;
import guideme.libs.mdast.mdx.model.MdxJsxElementFields;
import java.util.Set;

public class ItemGridCompiler extends BlockTagCompiler {
    @Override
    public Set<String> getTagNames() {
        return Set.of("ItemGrid");
    }

    @Override
    protected void compile(PageCompiler compiler, LytBlockContainer parent, MdxJsxElementFields el) {
        var itemGrid = new LytItemGrid();

        // We expect children to only contain ItemIcon elements
        for (var childNode : el.children()) {
            if (childNode instanceof MdxJsxElementFields jsxChild && "ItemIcon".equals(jsxChild.name())) {
                var item = MdxAttrs.getRequiredItem(compiler, parent, jsxChild, "id");
                if (item != null) {
                    itemGrid.addItem(item);
                }

                continue;
            }
            parent.appendError(compiler, "Unsupported child-element in ItemGrid", childNode);
        }

        parent.append(itemGrid);
    }

    @Override
    public void index(IndexingContext indexer, MdxJsxElementFields el, IndexingSink sink) {
    }
}
