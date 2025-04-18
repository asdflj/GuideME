package guideme.internal.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

public class GuideScrollbar extends AbstractWidget {
    private static final int WIDTH = 8;
    private int contentHeight;
    private int scrollAmount;
    private Double thumbHeldAt;

    public GuideScrollbar() {
        super(0, 0, WIDTH, 0, Component.empty());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    }

    protected int getMaxScrollAmount() {
        return Math.max(0, contentHeight - (this.height - 4));
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!visible) {
            return;
        }

        var maxScrollAmount = getMaxScrollAmount();
        if (maxScrollAmount <= 0) {
            return;
        }

        int thumbHeight = getThumbHeight();
        int left = getX();
        int right = left + 8;
        int top = getY() + getThumbTop();
        int bottom = top + thumbHeight;

        var pose = guiGraphics.pose().last().pose();
        var min = new Vector3f();
        pose.transformPosition(left, top, 0, min);
        var max = new Vector3f();
        pose.transformPosition(right, bottom, 0, max);

        var buffer = guiGraphics.bufferSource.getBuffer(RenderType.gui());
        buffer.addVertex(min.x, max.y, 0.0f).setColor(128, 128, 128, 255);
        buffer.addVertex(max.x, max.y, 0.0f).setColor(128, 128, 128, 255);
        buffer.addVertex(max.x, min.y, 0.0f).setColor(128, 128, 128, 255);
        buffer.addVertex(min.x, min.y, 0.0f).setColor(128, 128, 128, 255);
        buffer.addVertex(min.x, max.y - 1, 0.0f).setColor(192, 192, 192, 255);
        buffer.addVertex(max.x - 1, max.y - 1, 0.0f).setColor(192, 192, 192, 255);
        buffer.addVertex(max.x - 1, min.y, 0.0f).setColor(192, 192, 192, 255);
        buffer.addVertex(min.x, min.y, 0.0f).setColor(192, 192, 192, 255);
        guiGraphics.bufferSource.endBatch(RenderType.gui());
    }

    /**
     * The thumb is the draggable rectangle representing the current viewport being manipulated by the scrollbar.
     */
    private int getThumbTop() {
        if (getMaxScrollAmount() == 0) {
            return 0;
        }
        return Math.max(0, scrollAmount * (height - getThumbHeight()) / getMaxScrollAmount());
    }

    private int getThumbHeight() {
        if (contentHeight <= 0) {
            return 0;
        }
        return Mth.clamp((int) ((float) (this.height * this.height) / (float) contentHeight), 32, this.height);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.visible || button != 0) {
            return false;
        }

        var thumbTop = getY() + getThumbTop();
        var thumbBottom = thumbTop + getThumbHeight();

        boolean thumbHit = mouseX >= getX()
                && mouseX <= getX() + WIDTH
                && mouseY >= thumbTop
                && mouseY < thumbBottom;
        if (thumbHit) {
            this.thumbHeldAt = mouseY - thumbTop;
            return true;
        } else {
            this.thumbHeldAt = null;
            return false;
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseReleased(mouseX, mouseY, button);
        }

        this.thumbHeldAt = null;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.visible && this.thumbHeldAt != null) {

            var thumbY = (int) Math.round(mouseY - getY() - thumbHeldAt);
            var maxThumbY = height - getThumbHeight();
            var scrollAmount = (int) Math.round(thumbY / (double) maxThumbY * getMaxScrollAmount());
            setScrollAmount(scrollAmount);

            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (this.visible) {
            this.setScrollAmount((int) (this.scrollAmount - deltaY * 10));
            return true;
        } else {
            return false;
        }
    }

    public void move(int x, int y, int height) {
        setX(x);
        setY(y);
        this.height = height;
    }

    public void setContentHeight(int contentHeight) {
        this.contentHeight = contentHeight;
        if (this.scrollAmount > getMaxScrollAmount()) {
            this.scrollAmount = getMaxScrollAmount();
        }
    }

    public int getScrollAmount() {
        return scrollAmount;
    }

    public void setScrollAmount(int scrollAmount) {
        this.scrollAmount = Mth.clamp(scrollAmount, 0, getMaxScrollAmount());
    }
}
