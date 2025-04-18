package guideme.internal.util;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import guideme.document.LytRect;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;

/**
 * Rendering helper for rendering a rectangle with a dashed outline.
 */
public final class DashedRectangle {
    private DashedRectangle() {
    }

    public static void render(PoseStack stack, LytRect bounds, DashPattern pattern, float z) {
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        var t = 0f;
        if (pattern.animationCycleMs() > 0) {
            t = (System.currentTimeMillis() % (int) pattern.animationCycleMs()) / pattern.animationCycleMs();
        }

        buildHorizontalDashedLine(builder, stack, t, bounds.x(), bounds.right(), bounds.y(), z, pattern, false);
        buildHorizontalDashedLine(builder, stack, t, bounds.x(), bounds.right(), bounds.bottom() - pattern.width(), z,
                pattern, true);

        buildVerticalDashedLine(builder, stack, t, bounds.x(), bounds.y(), bounds.bottom(), z, pattern, true);
        buildVerticalDashedLine(builder, stack, t, bounds.right() - pattern.width(), bounds.y(), bounds.bottom(), z,
                pattern, false);

        RenderType.gui().draw(builder.buildOrThrow());
    }

    private static void buildHorizontalDashedLine(BufferBuilder builder, PoseStack stack,
            float t, float x1, float x2, float y, float z,
            DashPattern pattern, boolean reverse) {
        if (!reverse) {
            t = 1 - t;
        }
        var phase = t * pattern.length();

        var pose = stack.last().pose();
        var color = pattern.color();

        for (float x = x1 - phase; x < x2; x += pattern.length()) {
            builder.addVertex(pose, Mth.clamp(x + pattern.onLength(), x1, x2), y, z).setColor(color);
            builder.addVertex(pose, Mth.clamp(x, x1, x2), y, z).setColor(color);
            builder.addVertex(pose, Mth.clamp(x, x1, x2), y + pattern.width(), z).setColor(color);
            builder.addVertex(pose, Mth.clamp(x + pattern.onLength(), x1, x2), y + pattern.width(), z).setColor(color);
        }
    }

    private static void buildVerticalDashedLine(BufferBuilder builder, PoseStack stack,
            float t, float x, float y1, float y2, float z,
            DashPattern pattern, boolean reverse) {
        if (!reverse) {
            t = 1 - t;
        }
        var phase = t * pattern.length();

        var pose = stack.last().pose();
        var color = pattern.color();

        for (float y = y1 - phase; y < y2; y += pattern.length()) {
            builder.addVertex(pose, x + pattern.width(), Mth.clamp(y, y1, y2), z).setColor(color);
            builder.addVertex(pose, x, Mth.clamp(y, y1, y2), z).setColor(color);
            builder.addVertex(pose, x, Mth.clamp(y + pattern.onLength(), y1, y2), z).setColor(color);
            builder.addVertex(pose, x + pattern.width(), Mth.clamp(y + pattern.onLength(), y1, y2), z).setColor(color);
        }
    }

}
