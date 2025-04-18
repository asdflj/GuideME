package guideme.internal.siteexport;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import guideme.hooks.RenderToTextureHooks;
import guideme.internal.util.Platform;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class OffScreenRenderer implements AutoCloseable {
    private final NativeImage nativeImage;
    private final TextureTarget fb;
    private final int width;
    private final int height;
    private final GpuDevice device;
    private final CommandEncoder commandEncoder;
    private final GpuTexture colorTexture;
    private final GpuTexture depthTexture;

    public OffScreenRenderer(int width, int height) {
        this.width = width;
        this.height = height;
        nativeImage = new NativeImage(width, height, false);
        fb = new TextureTarget("GuideME OSR", width, height, true /* with depth */, false /* with stencil */);

        device = RenderSystem.getDevice();
        commandEncoder = device.createCommandEncoder();

        colorTexture = Objects.requireNonNull(fb.getColorTexture(), "colorTexture");
        depthTexture = Objects.requireNonNull(fb.getDepthTexture(), "depthTexture");
        commandEncoder.createRenderPass(colorTexture, OptionalInt.of(0), depthTexture, OptionalDouble.of(1.0)).close();
    }

    @Override
    public void close() {
        nativeImage.close();
        fb.destroyBuffers();
    }

    public byte[] captureAsPng(Runnable r) {
        renderToBuffer(r);

        try {
            return Platform.exportAsPng(nativeImage);
        } catch (IOException e) {
            throw new RuntimeException("failed to encode image as PNG", e);
        }
    }

    public void captureAsPng(Runnable r, Path path) throws IOException {
        renderToBuffer(r);

        nativeImage.writeToFile(path);
    }

    public boolean isAnimated(Collection<TextureAtlasSprite> sprites) {
        return sprites.stream().anyMatch(s -> s.contents().animatedTexture != null);
    }

    public byte[] captureAsWebp(Runnable r, Collection<TextureAtlasSprite> sprites, WebPExporter.Format format) {
        var animatedSprites = sprites.stream()
                .filter(sprite -> sprite.contents().animatedTexture != null)
                .toList();

        // Not animated
        if (animatedSprites.isEmpty()) {
            return captureAsPng(r);
        }

        // This is an oversimplification. Not all animated textures may have the same loop frequency
        // But the greatest common divisor could be so inconvenient that we're essentially looping forever.
        var maxTime = animatedSprites.stream()
                .mapToInt(s -> s.contents().animatedTexture.frames.stream().mapToInt(SpriteContents.FrameInfo::time)
                        .sum())
                .max()
                .orElse(0);

        var textureManager = Minecraft.getInstance().getTextureManager();

        var tickers = animatedSprites.stream()
                .collect(Collectors.groupingBy(TextureAtlasSprite::atlasLocation))
                .entrySet().stream().collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                e -> e.getValue().stream().map(TextureAtlasSprite::createTicker).toList()));
        for (var sprite : animatedSprites) {
            var atlas = textureManager.getTexture(sprite.atlasLocation());
            sprite.uploadFirstFrame(atlas.getTexture());
        }

        int width = nativeImage.getWidth();
        int height = nativeImage.getHeight();

        try (var webpWriter = new WebPExporter(width, height, format)) {
            for (var i = 0; i < maxTime; i++) {
                // Bind all animated textures to their corresponding frames
                for (var entry : tickers.entrySet()) {
                    var texture = textureManager.getTexture(entry.getKey());
                    for (var ticker : entry.getValue()) {
                        ticker.tickAndUpload(texture.getTexture());
                    }
                }

                renderToBuffer(r);

                webpWriter.writeFrame(i, nativeImage);
            }

            return webpWriter.finish();
        }
    }

    private void renderToBuffer(Runnable r) {
        commandEncoder.clearColorAndDepthTextures(colorTexture, 0, depthTexture, 1.0);
        RenderToTextureHooks.targetOverride = fb;
        try {
            r.run();
        } finally {
            RenderToTextureHooks.targetOverride = null;
        }
        TextureDownloader.downloadTexture(colorTexture, 0, IntUnaryOperator.identity(), nativeImage, true);
    }

    public void setupItemRendering() {
        // See GameRenderer
        // Set up GL state for GUI rendering where the 16x16 item will fill the entire framebuffer
        var matrix4f = new Matrix4f().setOrtho(
                0.0f, 16,
                16, 0.0f,
                1000.0f, 21000.0f);
        RenderSystem.setProjectionMatrix(matrix4f, ProjectionType.ORTHOGRAPHIC);

        var poseStack = RenderSystem.getModelViewStack();
        poseStack.identity();
        poseStack.translate(0.0f, 0.0f, -11000.0f);
        Lighting.setupFor3DItems();
        RenderSystem.setShaderFog(FogParameters.NO_FOG);
    }

    public void setupOrtographicRendering() {
        float angle = 36;
        float renderHeight = 0;
        float renderScale = 100;
        float rotation = 45;

        // Set up GL state for GUI rendering where the 16x16 item will fill the entire framebuffer
        RenderSystem.setProjectionMatrix(
                new Matrix4f().ortho(-1, 1, 1, -1, 1000, 3000),
                ProjectionType.ORTHOGRAPHIC);

        var poseStack = RenderSystem.getModelViewStack();
        poseStack.identity();
        poseStack.translate(0.0F, 0.0F, -2000.0F);

        RenderSystem.setShaderFog(FogParameters.NO_FOG);

        poseStack.scale(1, -1, -1);
        poseStack.rotate(new Quaternionf().rotationY(Mth.DEG_TO_RAD * -180));

        Quaternionf flip = new Quaternionf().rotationZ(Mth.DEG_TO_RAD * 180);
        flip.mul(new Quaternionf().rotationX(Mth.DEG_TO_RAD * angle));

        poseStack.translate(0, (renderHeight / -300f), 0);
        poseStack.scale(renderScale * 0.004f, renderScale * 0.004f, 1f);

        Quaternionf rotate = new Quaternionf().rotationY(Mth.DEG_TO_RAD * rotation);
        poseStack.rotate(flip);
        poseStack.rotate(rotate);

        Lighting.setupLevel();
    }

    public void setupPerspectiveRendering(float zoom, float fov, Vector3f eyePos, Vector3f lookAt) {
        float aspectRatio = (float) width / height;

        Matrix4fStack projMat = new Matrix4fStack();
        if (zoom != 1.0F) {
            projMat.scale(zoom, zoom, 1.0F);
        }

        projMat.mul(new Matrix4f().perspective(fov, aspectRatio, 0.05F, 16));
        RenderSystem.setProjectionMatrix(projMat, ProjectionType.PERSPECTIVE);

        var poseStack = RenderSystem.getModelViewStack();
        poseStack.identity();
        var vm = createViewMatrix(eyePos, lookAt);
        poseStack.mul(vm);

        Lighting.setupLevel();
    }

    /**
     * This is in essence the same code as in gluLookAt, but it returns the resulting transformation matrix instead of
     * applying it to the deprecated OpenGL transformation stack.
     */
    private static Matrix4f createViewMatrix(Vector3f eyePos, Vector3f lookAt) {
        Vector3f dir = new Vector3f(lookAt);
        dir.sub(eyePos);

        Vector3f up = new Vector3f(0, 1f, 0);
        dir.normalize();

        var right = new Vector3f(dir);
        right.cross(up);
        right.normalize();

        up = new Vector3f(right);
        up.cross(dir);
        up.normalize();

        var viewMatrix = new Matrix4f();
        viewMatrix.setTransposed(FloatBuffer.wrap(new float[] {
                right.x(),
                right.y(),
                right.z(),
                0.0f,

                up.x(),
                up.y(),
                up.z(),
                0.0f,

                -dir.x(),
                -dir.y(),
                -dir.z(),
                0.0f,

                0.0f,
                0.0f,
                0.0f,
                1.0f,
        }));

        viewMatrix.translate(-eyePos.x(), -eyePos.y(), -eyePos.z());
        return viewMatrix;
    }

}
