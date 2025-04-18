package guideme.document.block;

import guideme.document.LytRect;
import guideme.document.interaction.GuideTooltip;
import guideme.document.interaction.InteractiveElement;
import guideme.document.interaction.TextTooltip;
import guideme.layout.LayoutContext;
import guideme.render.GuiAssets;
import guideme.render.GuidePageTexture;
import guideme.render.RenderContext;
import java.util.Optional;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public class LytImage extends LytBlock implements InteractiveElement {

    private ResourceLocation imageId;
    private GuidePageTexture texture = GuidePageTexture.missing();
    private String title;
    private String alt;

    public ResourceLocation getImageId() {
        return imageId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAlt() {
        return alt;
    }

    public void setAlt(String alt) {
        this.alt = alt;
    }

    public void setImage(ResourceLocation id, byte @Nullable [] imageData) {
        this.imageId = id;
        if (imageData != null) {
            this.texture = GuidePageTexture.load(id, imageData);
        } else {
            this.texture = GuidePageTexture.missing();
        }
    }

    @Override
    protected LytRect computeLayout(LayoutContext context, int x, int y, int availableWidth) {
        if (texture == null) {
            return new LytRect(x, y, 32, 32);
        }

        var size = texture.getSize();
        var width = size.width();
        var height = size.height();

        width /= 4;
        height /= 4;

        if (width > availableWidth) {
            var f = availableWidth / (float) width;
            width *= f;
            height *= f;
        }

        return new LytRect(x, y, width, height);
    }

    @Override
    protected void onLayoutMoved(int deltaX, int deltaY) {
    }

    @Override
    public void renderBatch(RenderContext context, MultiBufferSource buffers) {
    }

    @Override
    public void render(RenderContext context) {
        if (texture == null) {
            context.fillIcon(getBounds(), GuiAssets.MISSING_TEXTURE);
        } else {
            context.fillTexturedRect(getBounds(), texture);
        }
    }

    @Override
    public Optional<GuideTooltip> getTooltip(float x, float y) {
        if (title != null) {
            return Optional.of(new TextTooltip(Component.literal(title)));
        }
        return Optional.empty();
    }
}
