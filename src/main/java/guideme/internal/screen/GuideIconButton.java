package guideme.internal.screen;

import guideme.color.SymbolicColor;
import guideme.internal.GuideME;
import guideme.internal.GuideMEClient;
import guideme.internal.GuidebookText;
import guideme.internal.util.Blitter;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Button found in the toolbar at the top of {@link GuideScreen}.
 */
public class GuideIconButton extends Button {
    public static final int WIDTH = 16;
    public static final int HEIGHT = 16;

    private Role role;

    public GuideIconButton(int x, int y, Role role, Runnable callback) {
        this(x, y, role, btn -> callback.run());
    }

    public GuideIconButton(int x, int y, Role role, Consumer<GuideIconButton> callback) {
        super(
                x,
                y,
                WIDTH,
                HEIGHT,
                role.actionText,
                btn -> callback.accept((GuideIconButton) btn),
                Supplier::get);
        this.role = role;
        setTooltip(Tooltip.create(getMessage()));
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
        setMessage(role.actionText);
        setTooltip(Tooltip.create(getMessage()));
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int i, int j, float f) {
        var color = SymbolicColor.ICON_BUTTON_NORMAL;

        if (!isActive()) {
            color = SymbolicColor.ICON_BUTTON_DISABLED;
        } else if (isHovered()) {
            color = SymbolicColor.ICON_BUTTON_HOVER;
        }

        var resolved = color.resolve(GuideMEClient.currentLightDarkMode());

        Blitter.texture(GuideME.makeId("textures/guide/buttons.png"), 64, 64)
                .src(role.iconSrcX, role.iconSrcY, 16, 16)
                .dest(getX(), getY(), 16, 16)
                .colorArgb(resolved)
                .blit(guiGraphics);
    }

    public enum Role {
        BACK(GuidebookText.HistoryGoBack.text(), 0, 0),
        FORWARD(GuidebookText.HistoryGoForward.text(), 16, 0),
        CLOSE(GuidebookText.Close.text(), 32, 0),
        SEARCH(GuidebookText.Search.text(), 48, 0),
        HIDE_ANNOTATIONS(GuidebookText.HideAnnotations.text(), 0, 16),
        SHOW_ANNOTATIONS(GuidebookText.ShowAnnotations.text(), 16, 16),
        ZOOM_OUT(GuidebookText.ZoomOut.text(), 32, 16),
        ZOOM_IN(GuidebookText.ZoomIn.text(), 48, 16),
        RESET_VIEW(GuidebookText.ResetView.text(), 0, 32),
        OPEN_FULL_WIDTH_VIEW(GuidebookText.FullWidthView.text(), 16, 32),
        CLOSE_FULL_WIDTH_VIEW(GuidebookText.CloseFullWidthView.text(), 32, 32);

        final Component actionText;

        final int iconSrcX;
        final int iconSrcY;

        Role(Component actionText, int iconSrcX, int iconSrcY) {
            this.actionText = actionText;
            this.iconSrcX = iconSrcX;
            this.iconSrcY = iconSrcY;
        }
    }
}
