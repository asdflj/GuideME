package guideme.document.interaction;

import guideme.document.LytRect;
import guideme.document.block.LytBlock;
import guideme.layout.LayoutContext;
import guideme.layout.MinecraftFontMetrics;
import guideme.render.SimpleRenderContext;
import guideme.siteexport.ExportableResourceProvider;
import guideme.siteexport.ResourceExporter;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.MultiBufferSource;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * A {@link GuideTooltip} that renders a {@link LytBlock} as the tooltip content.
 */
public class ContentTooltip implements GuideTooltip {
    private final List<ClientTooltipComponent> components;

    // The window size for which we performed layout
    @Nullable
    private LytRect layoutViewport;
    @Nullable
    private LytRect layoutBox;

    private final LytBlock content;

    public ContentTooltip(LytBlock content) {
        this.content = content;

        this.components = List.of(
                new ClientTooltipComponent() {
                    @Override
                    public int getHeight(Font font) {
                        return getLayoutBox().height();
                    }

                    @Override
                    public int getWidth(Font font) {
                        return getLayoutBox().width();
                    }

                    @Override
                    public void renderText(Font font, int x, int y, Matrix4f matrix,
                            MultiBufferSource.BufferSource bufferSource) {
                        getLayoutBox(); // Updates layout

                        var guiGraphics = new GuiGraphics(Minecraft.getInstance(), bufferSource);
                        var poseStack = guiGraphics.pose();
                        poseStack.mulPose(matrix);
                        poseStack.translate(x, y, 0);

                        var ctx = new SimpleRenderContext(layoutViewport, guiGraphics);
                        content.renderBatch(ctx, bufferSource);
                    }

                    @Override
                    public void renderImage(Font font, int x, int y, int width, int height, GuiGraphics guiGraphics) {
                        getLayoutBox(); // Updates layout

                        var pose = guiGraphics.pose();
                        pose.pushPose();
                        pose.translate(x, y, 0);
                        var ctx = new SimpleRenderContext(layoutViewport, guiGraphics);
                        content.render(ctx);
                        pose.popPose();
                    }
                });
    }

    @Override
    public List<ClientTooltipComponent> getLines() {
        return components;
    }

    public LytBlock getContent() {
        return content;
    }

    private LytRect getLayoutBox() {
        var screen = Minecraft.getInstance().screen;
        var currentViewport = new LytRect(0, 0, screen.width, screen.height);
        if (layoutBox == null || !currentViewport.equals(layoutViewport)) {
            layoutViewport = currentViewport;
            var layoutContext = new LayoutContext(new MinecraftFontMetrics());
            layoutBox = content.layout(layoutContext, 0, 0, screen.width / 2);
        }
        return layoutBox;
    }

    @Override
    public void exportResources(ResourceExporter exporter) {
        ExportableResourceProvider.visit(content, exporter);
    }
}
