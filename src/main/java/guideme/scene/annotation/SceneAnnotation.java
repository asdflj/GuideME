package guideme.scene.annotation;

import guideme.document.block.LytBlock;
import guideme.document.interaction.ContentTooltip;
import guideme.document.interaction.GuideTooltip;
import guideme.document.interaction.TextTooltip;
import guideme.siteexport.ExportableResourceProvider;
import guideme.siteexport.ResourceExporter;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * An annotation to show additional information to the user about content in a {@link guideme.scene.GuidebookScene}.
 */
public abstract class SceneAnnotation implements ExportableResourceProvider {
    @Nullable
    private GuideTooltip tooltip;

    private boolean hovered;

    public @Nullable GuideTooltip getTooltip() {
        return tooltip;
    }

    public void setTooltip(@Nullable GuideTooltip tooltip) {
        this.tooltip = tooltip;
    }

    public void setTooltipContent(LytBlock block) {
        this.tooltip = new ContentTooltip(block);
    }

    public void setTooltipContent(Component component) {
        this.tooltip = new TextTooltip(component);
    }

    public boolean hasTooltip() {
        return tooltip != null;
    }

    public boolean isHovered() {
        return hovered;
    }

    public void setHovered(boolean hovered) {
        this.hovered = hovered;
    }

    @Override
    public void exportResources(ResourceExporter exporter) {
        if (tooltip != null) {
            tooltip.exportResources(exporter);
        }
    }
}
