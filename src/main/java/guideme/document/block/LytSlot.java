package guideme.document.block;

import guideme.document.LytRect;
import guideme.document.interaction.GuideTooltip;
import guideme.document.interaction.InteractiveElement;
import guideme.document.interaction.ItemTooltip;
import guideme.internal.util.Platform;
import guideme.layout.LayoutContext;
import guideme.render.GuiAssets;
import guideme.render.GuiSprite;
import guideme.render.RenderContext;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.SlotDisplay;

/**
 * Renders a standard Minecraft GUI slot.
 */
public class LytSlot extends LytBlock implements InteractiveElement {

    private static final int ITEM_SIZE = 16;
    private static final int PADDING = 1;
    private static final int LARGE_PADDING = 5;
    public static final int OUTER_SIZE = ITEM_SIZE + 2 * PADDING;
    public static final int OUTER_SIZE_LARGE = ITEM_SIZE + 2 * LARGE_PADDING;
    private static final int CYCLE_TIME = 2000;

    private boolean largeSlot;

    private final List<ItemStack> stacks;

    public LytSlot(SlotDisplay display) {
        this.stacks = display.resolveForStacks(Platform.getSlotDisplayContext());
    }

    public LytSlot(Ingredient ingredient) {
        this.stacks = ingredient.items().map(Holder::value).map(Item::getDefaultInstance).toList();
    }

    public LytSlot(Optional<Ingredient> ingredient) {
        if (ingredient.isPresent()) {
            this.stacks = ingredient.get().items().map(Holder::value).map(Item::getDefaultInstance).toList();
        } else {
            this.stacks = List.of();
        }
    }

    public LytSlot(ItemStack stack) {
        this.stacks = stack.isEmpty() ? List.of() : List.of(stack);
    }

    public boolean isLargeSlot() {
        return largeSlot;
    }

    public void setLargeSlot(boolean largeSlot) {
        this.largeSlot = largeSlot;
    }

    @Override
    protected LytRect computeLayout(LayoutContext context, int x, int y, int availableWidth) {
        if (largeSlot) {
            return new LytRect(x, y, OUTER_SIZE_LARGE, OUTER_SIZE_LARGE);
        } else {
            return new LytRect(x, y, OUTER_SIZE, OUTER_SIZE);
        }
    }

    @Override
    protected void onLayoutMoved(int deltaX, int deltaY) {
    }

    @Override
    public void renderBatch(RenderContext context, MultiBufferSource buffers) {

    }

    @Override
    public void render(RenderContext context) {
        var x = bounds.x();
        var y = bounds.y();

        GuiSprite texture;
        if (largeSlot) {
            texture = GuiAssets.LARGE_SLOT;
        } else {
            texture = GuiAssets.SLOT;
        }
        context.fillIcon(bounds, texture);

        var padding = largeSlot ? LARGE_PADDING : PADDING;

        var stack = getDisplayedStack();
        if (!stack.isEmpty()) {
            context.renderItem(stack, x + padding, y + padding, 1, ITEM_SIZE, ITEM_SIZE);
        }
    }

    @Override
    public Optional<GuideTooltip> getTooltip(float x, float y) {
        var stack = getDisplayedStack();
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ItemTooltip(stack));
    }

    private ItemStack getDisplayedStack() {
        if (stacks.isEmpty()) {
            return ItemStack.EMPTY;
        }

        var cycle = System.nanoTime() / TimeUnit.MILLISECONDS.toNanos(CYCLE_TIME);
        return stacks.get((int) (cycle % stacks.size()));
    }
}
