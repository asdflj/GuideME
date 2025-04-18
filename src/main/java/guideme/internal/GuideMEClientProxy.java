package guideme.internal;

import guideme.Guide;
import guideme.Guides;
import guideme.PageAnchor;
import guideme.compiler.ParsedGuidePage;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import org.jetbrains.annotations.Nullable;

class GuideMEClientProxy extends GuideMEServerProxy {
    @Override
    public void addGuideTooltip(ResourceLocation guideId, Item.TooltipContext context, TooltipDisplay tooltipDisplay,
            Consumer<Component> lineConsumer,
            TooltipFlag tooltipFlag) {
        var guide = GuideRegistry.getById(guideId);
        if (guide == null) {
            lineConsumer.accept(GuidebookText.ItemInvalidGuideId.text().withStyle(ChatFormatting.RED));
            return;
        }

        guide.getItemSettings().tooltipLines().forEach(lineConsumer);
    }

    @Override
    public @Nullable Component getGuideDisplayName(ResourceLocation guideId) {
        var guide = GuideRegistry.getById(guideId);
        if (guide != null) {
            return guide.getItemSettings().displayName().orElse(null);
        }

        return null;
    }

    @Override
    public boolean openGuide(Player player, ResourceLocation id) {
        if (player == Minecraft.getInstance().player) {
            var guide = Guides.getById(id);
            if (guide == null) {
                Minecraft.getInstance().gui.setOverlayMessage(GuidebookText.ItemInvalidGuideId.text(id.toString()),
                        false);
                return false;
            } else {
                return GuideMEClient.openGuideAtPreviousPage(guide, guide.getStartPage());
            }
        }

        return super.openGuide(player, id);
    }

    @Override
    public boolean openGuide(Player player, ResourceLocation id, PageAnchor anchor) {
        if (player == Minecraft.getInstance().player) {
            var guide = Guides.getById(id);
            if (guide == null) {
                Minecraft.getInstance().gui.setOverlayMessage(GuidebookText.ItemInvalidGuideId.text(id.toString()),
                        false);
                return false;
            } else {
                if (anchor == null) {
                    return GuideMEClient.openGuideAtPreviousPage(guide, guide.getStartPage());
                }
                return GuideMEClient.openGuideAtAnchor(guide, anchor);
            }
        }

        return super.openGuide(player, id, anchor);
    }

    @Override
    public Stream<ResourceLocation> getAvailableGuides() {
        return Guides.getAll().stream().map(Guide::getId);
    }

    @Override
    public Stream<ResourceLocation> getAvailablePages(ResourceLocation guideId) {
        var guide = Guides.getById(guideId);
        if (guide == null) {
            return Stream.empty();
        }

        return guide.getPages().stream().map(ParsedGuidePage::getId);
    }
}
