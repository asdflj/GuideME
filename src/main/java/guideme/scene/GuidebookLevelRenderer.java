package guideme.scene;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import guideme.color.LightDarkMode;
import guideme.internal.scene.FakeRenderEnvironment;
import guideme.scene.annotation.InWorldAnnotation;
import guideme.scene.annotation.InWorldAnnotationRenderer;
import guideme.scene.level.GuidebookLevel;
import java.util.ArrayList;
import java.util.Collection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import org.joml.Matrix4f;
import org.joml.Vector4f;

public class GuidebookLevelRenderer {

    private static GuidebookLevelRenderer instance;

    private final GuidebookLightmap lightmap = new GuidebookLightmap();

    public static GuidebookLevelRenderer getInstance() {
        RenderSystem.assertOnRenderThread();
        if (instance == null) {
            instance = new GuidebookLevelRenderer();
        }
        return instance;
    }

    public void render(GuidebookLevel level,
            CameraSettings cameraSettings,
            Collection<InWorldAnnotation> annotations,
            LightDarkMode lightDarkMode) {
        lightmap.update(level);

        level.onRenderFrame();

        RenderSystem.setShaderGameTime(level.getGameTime(), level.getPartialTick());

        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        render(level, cameraSettings, buffers, annotations, lightDarkMode);
        buffers.endBatch();

    }

    public void render(GuidebookLevel level,
            CameraSettings cameraSettings,
            MultiBufferSource.BufferSource buffers,
            Collection<InWorldAnnotation> annotations,
            LightDarkMode lightDarkMode) {
        lightmap.update(level);

        var lightEngine = level.getLightEngine();
        while (lightEngine.hasLightWork()) {
            lightEngine.runLightUpdates();
        }

        var projectionMatrix = cameraSettings.getProjectionMatrix();
        var viewMatrix = cameraSettings.getViewMatrix();

        // Essentially disable level fog
        RenderSystem.setShaderFog(FogParameters.NO_FOG);

        var modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.identity();
        modelViewStack.mul(viewMatrix);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projectionMatrix, ProjectionType.ORTHOGRAPHIC);

        var lightDirection = new Vector4f(15 / 90f, .35f, 1, 0);
        var lightTransform = new Matrix4f(viewMatrix);
        lightTransform.invert();
        lightTransform.transform(lightDirection);

        Lighting.setupLevel();

        renderContent(level, buffers);

        InWorldAnnotationRenderer.render(buffers, annotations, lightDarkMode);

        modelViewStack.popMatrix();
        RenderSystem.restoreProjectionMatrix();

        Lighting.setupFor3DItems(); // Reset to GUI lighting
    }

    /**
     * Render without any setup.
     */
    public void renderContent(GuidebookLevel level, MultiBufferSource.BufferSource buffers) {
        try (var fake = FakeRenderEnvironment.create(level)) {
            renderBlocks(level, buffers, false);
            renderBlockEntities(level, buffers, level.getPartialTick());
            renderEntities(level, buffers, level.getPartialTick());

            // The order comes from LevelRenderer#renderLevel
            buffers.endBatch(RenderType.entitySolid(TextureAtlas.LOCATION_BLOCKS));
            buffers.endBatch(RenderType.entityCutout(TextureAtlas.LOCATION_BLOCKS));
            buffers.endBatch(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
            buffers.endBatch(RenderType.entitySmoothCutout(TextureAtlas.LOCATION_BLOCKS));

            // These would normally be pre-baked, but they are not for us
            for (var layer : RenderType.chunkBufferLayers()) {
                if (layer != RenderType.translucent()) {
                    buffers.endBatch(layer);
                }
            }

            buffers.endBatch(RenderType.solid());
            buffers.endBatch(RenderType.endPortal());
            buffers.endBatch(RenderType.endGateway());
            buffers.endBatch(Sheets.solidBlockSheet());
            buffers.endBatch(Sheets.cutoutBlockSheet());
            buffers.endBatch(Sheets.bedSheet());
            buffers.endBatch(Sheets.shulkerBoxSheet());
            buffers.endBatch(Sheets.signSheet());
            buffers.endBatch(Sheets.hangingSignSheet());
            buffers.endBatch(Sheets.chestSheet());
            buffers.endLastBatch();

            renderBlocks(level, buffers, true);
            buffers.endBatch(RenderType.translucent());
        }
    }

    private void renderBlocks(GuidebookLevel level, MultiBufferSource buffers, boolean translucent) {
        var blockRenderDispatcher = Minecraft.getInstance().getBlockRenderer();
        var poseStack = new PoseStack();

        var randomSource = new SingleThreadedRandomSource(0L);
        var modelParts = new ArrayList<BlockModelPart>();

        var it = level.getFilledBlocks().iterator();
        while (it.hasNext()) {
            var pos = it.next();
            var blockState = level.getBlockState(pos);
            var fluidState = blockState.getFluidState();
            if (!fluidState.isEmpty()) {
                var renderType = ItemBlockRenderTypes.getRenderLayer(fluidState);
                if (renderType != RenderType.translucent() || translucent) {
                    var bufferBuilder = buffers.getBuffer(renderType);

                    var sectionPos = SectionPos.of(pos);
                    var liquidVertexConsumer = new LiquidVertexConsumer(bufferBuilder, sectionPos);
                    blockRenderDispatcher.renderLiquid(pos, level, liquidVertexConsumer, blockState, fluidState);

                    markFluidSpritesActive(fluidState);
                }
            }

            if (blockState.getRenderShape() == RenderShape.INVISIBLE) {
                continue;
            }

            var model = blockRenderDispatcher.getBlockModel(blockState);

            modelParts.clear();
            randomSource.setSeed(blockState.getSeed(pos));
            model.collectParts(level, pos, blockState, randomSource, modelParts);
            if (!translucent) {
                modelParts.removeIf(part -> {
                    return part.getRenderType(blockState).getRenderPipeline().getBlendFunction().isPresent();
                });
            }

            poseStack.pushPose();
            poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
            blockRenderDispatcher.renderBatched(blockState, pos, level, poseStack, buffers::getBuffer, true,
                    modelParts);
            poseStack.popPose();
        }
    }

    private void renderBlockEntities(GuidebookLevel level, MultiBufferSource buffers, float partialTick) {
        var poseStack = new PoseStack();

        var it = level.getFilledBlocks().iterator();
        while (it.hasNext()) {
            var pos = it.next();
            var blockState = level.getBlockState(pos);
            if (blockState.hasBlockEntity()) {
                var blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null) {
                    this.handleBlockEntity(poseStack, blockEntity, buffers, partialTick);
                }
            }
        }
    }

    private static void markFluidSpritesActive(FluidState fluidState) {
        // For Sodium compatibility, ensure the sprites actually animate even if no block is on-screen
        // that would cause them to, otherwise.
        var props = IClientFluidTypeExtensions.of(fluidState);
        var sprite1 = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(props.getStillTexture());
        SodiumCompat.markSpriteActive(sprite1);
        var sprite2 = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(props.getFlowingTexture());
        SodiumCompat.markSpriteActive(sprite2);
    }

    private <E extends BlockEntity> void handleBlockEntity(PoseStack stack,
            E blockEntity,
            MultiBufferSource buffers,
            float partialTicks) {
        var dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        var renderer = dispatcher.getRenderer(blockEntity);
        if (renderer != null && renderer.shouldRender(blockEntity, blockEntity.getBlockPos().getCenter())) {
            var pos = blockEntity.getBlockPos();
            stack.pushPose();
            stack.translate(pos.getX(), pos.getY(), pos.getZ());

            int packedLight = LevelRenderer.getLightColor(blockEntity.getLevel(), blockEntity.getBlockPos());
            renderer.render(blockEntity, partialTicks, stack, buffers, packedLight, OverlayTexture.NO_OVERLAY,
                    Vec3.ZERO);
            stack.popPose();
        }
    }

    private void renderEntities(GuidebookLevel level, MultiBufferSource.BufferSource buffers, float partialTick) {
        var poseStack = new PoseStack();

        for (var entity : level.getEntitiesForRendering()) {
            handleEntity(level, poseStack, entity, buffers, partialTick);
        }
    }

    private <E extends Entity> void handleEntity(GuidebookLevel level,
            PoseStack poseStack,
            E entity,
            MultiBufferSource buffers,
            float partialTicks) {
        var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        var renderer = dispatcher.getRenderer(entity);
        if (renderer == null) {
            return;
        }

        renderEntity(level, poseStack, entity, buffers, partialTicks, renderer);
    }

    private static <E extends Entity, S extends EntityRenderState> void renderEntity(GuidebookLevel level,
            PoseStack poseStack,
            E entity,
            MultiBufferSource buffers,
            float partialTicks,
            EntityRenderer<? super E, S> renderer) {
        var probePos = BlockPos.containing(entity.getLightProbePosition(partialTicks));
        int packedLight = LevelRenderer.getLightColor(level, probePos);

        var pos = entity.position();
        var state = renderer.createRenderState(entity, partialTicks);
        var offset = renderer.getRenderOffset(state);
        poseStack.pushPose();
        poseStack.translate(pos.x + offset.x(), pos.y + offset.y(), pos.z + offset.z());
        renderer.render(state, poseStack, buffers, packedLight);
        poseStack.popPose();
    }
}
