package guideme.document.block;

import guideme.document.LytRect;
import guideme.document.flow.LytFlowContainer;
import guideme.document.flow.LytFlowContent;
import guideme.layout.LayoutContext;
import guideme.layout.flow.FlowBuilder;
import guideme.render.RenderContext;
import java.util.stream.Stream;
import net.minecraft.client.renderer.MultiBufferSource;
import org.jetbrains.annotations.Nullable;

public class LytParagraph extends LytBlock implements LytFlowContainer {
    protected final FlowBuilder content = new FlowBuilder();

    protected int paddingLeft;
    protected int paddingTop;
    protected int paddingRight;
    protected int paddingBottom;

    @Nullable
    protected LytFlowContent hoveredContent;

    @Override
    public void append(LytFlowContent child) {
        content.append(child);
        child.setParent(this);
    }

    @Override
    public boolean isCulled(LytRect viewport) {
        // If we have floating content, account for its bounding box exceeding our content box
        if (content.floatsIntersect(viewport)) {
            return false;
        }

        return super.isCulled(viewport);
    }

    @Override
    public LytRect computeLayout(LayoutContext context, int x, int y, int availableWidth) {
        // Apply padding to paragraph content
        x += paddingLeft;
        availableWidth -= paddingLeft + paddingRight;
        y += paddingTop;

        var style = resolveStyle();

        var bounds = content.computeLayout(context, x, y, availableWidth, style.alignment());

        if (paddingBottom != 0) {
            return bounds.withHeight(bounds.height() + paddingBottom);
        }
        return bounds;
    }

    @Override
    protected void onLayoutMoved(int deltaX, int deltaY) {
        content.move(deltaX, deltaY);
    }

    @Override
    public void onMouseEnter(@Nullable LytFlowContent hoveredContent) {
        super.onMouseEnter(hoveredContent);
        this.hoveredContent = hoveredContent;
    }

    @Override
    public void onMouseLeave() {
        super.onMouseLeave();
        this.hoveredContent = null;
    }

    @Override
    public @Nullable LytNode pickNode(int x, int y) {
        // If we are the host for any floating elements, those can exceed our own bounds
        var fl = content.pickFloatingElement(x, y);
        if (fl != null) {
            return this;
        }

        return super.pickNode(x, y);
    }

    @Override
    public void renderBatch(RenderContext context, MultiBufferSource buffers) {
        // Since we overwrite isCulled, we render even if our actual line content is culled, for floats
        if (context.intersectsViewport(bounds)) {
            content.renderBatch(context, buffers, hoveredContent);
        }

        content.renderFloatsBatch(context, buffers, hoveredContent);
    }

    @Override
    public void render(RenderContext context) {
        // Since we overwrite isCulled, we render even if our actual line content is culled, for floats
        if (context.intersectsViewport(bounds)) {
            content.render(context, hoveredContent);
        }

        content.renderFloats(context, hoveredContent);
    }

    @Override
    public @Nullable LytFlowContent pickContent(int x, int y) {
        var lineEl = content.pick(x, y);
        return lineEl != null ? lineEl.getFlowContent() : null;
    }

    @Override
    public Stream<LytRect> enumerateContentBounds(LytFlowContent content) {
        return this.content.enumerateContentBounds(content);
    }

    @Override
    protected LytVisitor.Result visitChildren(LytVisitor visitor, boolean includeOutOfTreeContent) {
        if (super.visitChildren(visitor, includeOutOfTreeContent) == LytVisitor.Result.STOP) {
            return LytVisitor.Result.STOP;
        }

        for (var flowContent : getContent()) {
            flowContent.visit(visitor);
        }

        return LytVisitor.Result.CONTINUE;
    }

    public Iterable<LytFlowContent> getContent() {
        return content.getContent();
    }

    public boolean isEmpty() {
        return content.isEmpty();
    }

    public void clearContent() {
        content.clear();
    }

    public int getPaddingLeft() {
        return paddingLeft;
    }

    public void setPaddingLeft(int paddingLeft) {
        this.paddingLeft = paddingLeft;
    }

    public int getPaddingTop() {
        return paddingTop;
    }

    public void setPaddingTop(int paddingTop) {
        this.paddingTop = paddingTop;
    }

    public int getPaddingRight() {
        return paddingRight;
    }

    public void setPaddingRight(int paddingRight) {
        this.paddingRight = paddingRight;
    }

    public int getPaddingBottom() {
        return paddingBottom;
    }

    public void setPaddingBottom(int paddingBottom) {
        this.paddingBottom = paddingBottom;
    }
}
