package guideme.scene.export;

import com.google.flatbuffers.FlatBufferBuilder;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import guideme.flatbuffers.scene.ExpAnimatedTexturePart;
import guideme.flatbuffers.scene.ExpAnimatedTexturePartFrame;
import guideme.flatbuffers.scene.ExpCameraSettings;
import guideme.flatbuffers.scene.ExpDepthTest;
import guideme.flatbuffers.scene.ExpIndexElementType;
import guideme.flatbuffers.scene.ExpMaterial;
import guideme.flatbuffers.scene.ExpMesh;
import guideme.flatbuffers.scene.ExpPrimitiveType;
import guideme.flatbuffers.scene.ExpSampler;
import guideme.flatbuffers.scene.ExpScene;
import guideme.flatbuffers.scene.ExpTransparency;
import guideme.flatbuffers.scene.ExpVertexElementType;
import guideme.flatbuffers.scene.ExpVertexElementUsage;
import guideme.flatbuffers.scene.ExpVertexFormat;
import guideme.flatbuffers.scene.ExpVertexFormatElement;
import guideme.internal.siteexport.CacheBusting;
import guideme.internal.util.Platform;
import guideme.scene.CameraSettings;
import guideme.scene.GuidebookLevelRenderer;
import guideme.scene.GuidebookScene;
import guideme.siteexport.ResourceExporter;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exports a game scene 3d rendering to a custom 3d format for rendering it using WebGL in the browser. See scene.fbs
 * (we use FlatBuffers to encode the actual data).
 */
public class SceneExporter {
    private static final Logger LOG = LoggerFactory.getLogger(SceneExporter.class);

    private final ResourceExporter resourceExporter;

    public SceneExporter(ResourceExporter resourceExporter) {
        this.resourceExporter = resourceExporter;
    }

    public static boolean isAnimated(GuidebookScene scene) {
        return getSprites(scene)
                .stream()
                .anyMatch(sprite -> sprite.contents().animatedTexture != null);
    }

    private static Set<TextureAtlasSprite> getSprites(GuidebookScene scene) {
        var level = scene.getLevel();
        var bufferSource = new MeshBuildingBufferSource();
        GuidebookLevelRenderer.getInstance().renderContent(level, bufferSource);

        return bufferSource.getMeshes().stream()
                .flatMap(Mesh::getSprites)
                .collect(Collectors.toSet());
    }

    public byte[] export(GuidebookScene scene) {
        var level = scene.getLevel();

        List<Mesh> meshes;
        try (var bufferSource = new MeshBuildingBufferSource()) {

            // To avoid baking in the projection and camera, we need to reset these here
            var modelViewStack = RenderSystem.getModelViewStack();
            modelViewStack.pushMatrix();
            modelViewStack.identity();
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f(), ProjectionType.ORTHOGRAPHIC);

            GuidebookLevelRenderer.getInstance().renderContent(level, bufferSource);

            modelViewStack.popMatrix();
            RenderSystem.restoreProjectionMatrix();
            meshes = bufferSource.getMeshes();
        }

        // Concat all vertex buffers
        var builder = new FlatBufferBuilder(1024);

        int animatedTexturesOffset = writeAnimations(builder, meshes);

        var vertexFormats = writeVertexFormats(meshes, builder);
        var materials = writeMaterials(meshes, builder);
        var meshesOffset = writeMeshes(meshes, builder, vertexFormats, materials);

        ExpScene.startExpScene(builder);
        ExpScene.addMeshes(builder, meshesOffset);
        var cameraOffset = createCameraModel(scene.getCameraSettings(), builder);
        ExpScene.addCamera(builder, cameraOffset);
        ExpScene.addAnimatedTextures(builder, animatedTexturesOffset);

        builder.finish(ExpScene.endExpScene(builder));

        var bout = new ByteArrayOutputStream();
        try (var out = new GZIPOutputStream(bout)) {
            out.write(builder.sizedByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return bout.toByteArray();
    }

    private int writeAnimations(FlatBufferBuilder builder, List<Mesh> meshes) {
        // Find all animations we also need to export
        var animSprites = meshes.stream().flatMap(Mesh::getSprites)
                .filter(s -> s.contents().animatedTexture != null)
                .distinct()
                .mapToInt(sprite -> writeAnimatedTextureSprite(builder, sprite))
                .toArray();

        return ExpScene.createAnimatedTexturesVector(builder, animSprites);
    }

    private int writeAnimatedTextureSprite(FlatBufferBuilder builder, TextureAtlasSprite sprite) {
        // Get the original name, export it there to reuse if possible
        var contents = sprite.contents();
        var animatedTexture = contents.animatedTexture;
        var name = contents.name();

        byte[] image;
        long frameCount;
        int framesOffset;
        int frameRowSize;
        if (animatedTexture.interpolateFrames) {
            // For textures that have interpolation enabled, we pre-interpolate all frames
            // since this is hard to do on the browser-side
            var interpResult = InterpolatedSpriteBuilder.interpolate(
                    contents.originalImage,
                    contents.width(),
                    contents.height(),
                    animatedTexture.frameRowSize,
                    animatedTexture.frames);
            try (var interpFrames = interpResult.frames()) {
                image = Platform.exportAsPng(interpFrames);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            frameRowSize = interpResult.frameRowSize();
            frameCount = interpResult.frameCount();

            // We've simplified frames here. They all have frame time 1
            ExpAnimatedTexturePart.startFramesVector(builder, interpResult.indices().length);
            for (var frameIndex : interpResult.indices()) {
                ExpAnimatedTexturePartFrame.createExpAnimatedTexturePartFrame(
                        builder,
                        frameIndex,
                        1);
            }
            framesOffset = builder.endVector();
        } else {
            try {
                image = Platform.exportAsPng(contents.originalImage);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            frameCount = animatedTexture.getUniqueFrames().count();
            frameRowSize = animatedTexture.frameRowSize;

            ExpAnimatedTexturePart.startFramesVector(builder, animatedTexture.frames.size());
            for (var frame : animatedTexture.frames) {
                ExpAnimatedTexturePartFrame.createExpAnimatedTexturePartFrame(
                        builder,
                        frame.index(),
                        frame.time());
            }
            framesOffset = builder.endVector();
        }

        var path = resourceExporter.getOutputFolder()
                .resolve("!anims")
                .resolve(name.getNamespace())
                .resolve(name.getPath() + ".png");
        try {
            path = CacheBusting.writeAsset(path, image);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var relativePath = resourceExporter.getPathRelativeFromOutputFolder(path);

        var textureIdOffset = builder.createSharedString(sprite.atlasLocation().toString());
        var spritePath = builder.createString(relativePath);

        return ExpAnimatedTexturePart.createExpAnimatedTexturePart(
                builder,
                textureIdOffset,
                sprite.getX(),
                sprite.getY(),
                contents.width(),
                contents.height(),
                spritePath,
                frameCount,
                frameRowSize,
                framesOffset);
    }

    private Map<VertexFormat, Integer> writeVertexFormats(List<Mesh> meshes, FlatBufferBuilder builder) {
        var result = new IdentityHashMap<VertexFormat, Integer>();

        for (var mesh : meshes) {
            result.computeIfAbsent(mesh.drawState().format(), format -> writeVertexFormat(format, builder));
        }

        return result;
    }

    private int writeVertexFormat(VertexFormat format, FlatBufferBuilder builder) {

        // Count relevant vertex formats
        var count = (int) format.getElements().stream()
                .filter(SceneExporter::isRelevant)
                .count();

        ExpVertexFormat.startElementsVector(builder, count);

        // Vectors are written in reverse-order
        var elements = format.getElements();
        for (int i = elements.size() - 1; i >= 0; i--) {
            var offset = 0;
            for (int j = 0; j < i; j++) {
                offset += elements.get(j).byteSize();
            }

            var element = elements.get(i);
            if (isRelevant(element)) {
                var normalized = element.usage() == VertexFormatElement.Usage.NORMAL
                        || element.usage() == VertexFormatElement.Usage.COLOR;

                ExpVertexFormatElement.createExpVertexFormatElement(
                        builder,
                        element.index(),
                        mapType(element.type()),
                        mapUsage(element.usage()),
                        element.count(),
                        offset,
                        element.byteSize(),
                        normalized);
            }
        }
        var elementsOffset = builder.endVector();

        ExpVertexFormat.startExpVertexFormat(builder);
        ExpVertexFormat.addElements(builder, elementsOffset);
        ExpVertexFormat.addVertexSize(builder, format.getVertexSize());

        return ExpVertexFormat.endExpVertexFormat(builder);

    }

    private static boolean isRelevant(VertexFormatElement element) {
        return element.usage() != VertexFormatElement.Usage.GENERIC;
    }

    private Map<RenderType, Integer> writeMaterials(List<Mesh> meshes, FlatBufferBuilder builder) {
        var result = new IdentityHashMap<RenderType, Integer>();

        for (var mesh : meshes) {
            result.computeIfAbsent(mesh.renderType(), type -> writeMaterial(type, builder));
        }

        return result;
    }

    private int writeMaterial(RenderType type, FlatBufferBuilder builder) {

        var state = ((RenderType.CompositeRenderType) type).state;
        var pipeline = type.getRenderPipeline();

        var shaderNameOffset = builder.createSharedString(pipeline.getLocation().toString());

        var nameOffset = builder.createSharedString(type.name);

        var disableCulling = !pipeline.isCull();

        // Handle transparency
        var transparencyState = pipeline.getBlendFunction().orElse(null);
        int transparency;
        if (transparencyState == null) {
            transparency = ExpTransparency.DISABLED;
        } else if (transparencyState.equals(BlendFunction.ADDITIVE)) {
            transparency = ExpTransparency.ADDITIVE;
        } else if (transparencyState.equals(BlendFunction.LIGHTNING)) {
            transparency = ExpTransparency.LIGHTNING;
        } else if (transparencyState.equals(BlendFunction.GLINT)) {
            transparency = ExpTransparency.GLINT;
        } else if (transparencyState.equals(RenderPipelines.CRUMBLING.getBlendFunction().orElse(null))) {
            transparency = ExpTransparency.CRUMBLING;
        } else if (transparencyState.equals(BlendFunction.TRANSLUCENT)) {
            transparency = ExpTransparency.TRANSLUCENT;
        } else {
            LOG.warn("Cannot handle transparency state {} of render type {}", transparencyState, type);
            transparency = ExpTransparency.DISABLED;
        }

        // Handle depth-testing
        int depthTest;
        var depthTestShard = pipeline.getDepthTestFunction();
        if (depthTestShard == DepthTestFunction.NO_DEPTH_TEST) {
            depthTest = ExpDepthTest.DISABLED;
        } else if (depthTestShard == DepthTestFunction.EQUAL_DEPTH_TEST) {
            depthTest = ExpDepthTest.EQUAL;
        } else if (depthTestShard == DepthTestFunction.LEQUAL_DEPTH_TEST) {
            depthTest = ExpDepthTest.LEQUAL;
        } else if (depthTestShard == DepthTestFunction.GREATER_DEPTH_TEST) {
            depthTest = ExpDepthTest.GREATER;
        } else {
            LOG.warn("Cannot handle depth-test state {} of render type {}", depthTestShard, type);
            depthTest = ExpDepthTest.DISABLED;
        }

        var samplersOffset = 0;
        var samplers = RenderTypeIntrospection.getSamplers(type);
        if (!samplers.isEmpty()) {
            var sampler = samplers.get(0);

            var texturePath = resourceExporter.exportTexture(sampler.texture());
            var textureOffset = builder.createSharedString(texturePath);
            var textureIdOffset = builder.createSharedString(sampler.texture().toString());

            var samplerOffset = ExpSampler.createExpSampler(builder, textureIdOffset, textureOffset, sampler.blur(),
                    sampler.blur());
            samplersOffset = ExpMaterial.createSamplersVector(builder, new int[] { samplerOffset });
        }

        return ExpMaterial.createExpMaterial(
                builder,
                nameOffset,
                shaderNameOffset,
                disableCulling,
                transparency,
                depthTest,
                samplersOffset);

    }

    private static int mapMode(VertexFormat.Mode mode) {
        return switch (mode) {
            case LINES -> ExpPrimitiveType.LINES;
            case LINE_STRIP -> ExpPrimitiveType.LINE_STRIP;
            case DEBUG_LINES -> ExpPrimitiveType.DEBUG_LINES;
            case DEBUG_LINE_STRIP -> ExpPrimitiveType.DEBUG_LINE_STRIP;
            case QUADS, TRIANGLES -> ExpPrimitiveType.TRIANGLES;
            case TRIANGLE_STRIP -> ExpPrimitiveType.TRIANGLE_STRIP;
            case TRIANGLE_FAN -> ExpPrimitiveType.TRIANGLE_FAN;
        };
    }

    private static int mapUsage(VertexFormatElement.Usage usage) {
        return switch (usage) {
            case POSITION -> ExpVertexElementUsage.POSITION;
            case NORMAL -> ExpVertexElementUsage.NORMAL;
            case COLOR -> ExpVertexElementUsage.COLOR;
            case UV -> ExpVertexElementUsage.UV;
            case GENERIC -> throw new IllegalStateException("Should have been skipped");
        };
    }

    private static int mapType(VertexFormatElement.Type type) {
        return switch (type) {
            case FLOAT -> ExpVertexElementType.FLOAT;
            case UBYTE -> ExpVertexElementType.UBYTE;
            case BYTE -> ExpVertexElementType.BYTE;
            case USHORT -> ExpVertexElementType.USHORT;
            case SHORT -> ExpVertexElementType.SHORT;
            case UINT -> ExpVertexElementType.UINT;
            case INT -> ExpVertexElementType.INT;
        };
    }

    private int writeMeshes(List<Mesh> meshes,
            FlatBufferBuilder builder,
            Map<VertexFormat, Integer> vertexFormats,
            Map<RenderType, Integer> materials) {
        var writtenMeshes = new IntArrayList(meshes.size());

        for (var mesh : meshes) {
            int vb = ExpMesh.createVertexBufferVector(builder, mesh.vertexBuffer());
            var ibData = createIndexBuffer(mesh.drawState(), mesh.indexBuffer());
            int ib = ExpMesh.createIndexBufferVector(builder, ibData.data);

            ExpMesh.startExpMesh(builder);
            ExpMesh.addVertexBuffer(builder, vb);
            ExpMesh.addIndexBuffer(builder, ib);
            ExpMesh.addIndexType(builder, mapIndexType(ibData.indexType));
            ExpMesh.addIndexCount(builder, ibData.indexCount);
            ExpMesh.addMaterial(builder, materials.get(mesh.renderType()));
            ExpMesh.addVertexFormat(builder, vertexFormats.get(mesh.drawState().format()));
            ExpMesh.addPrimitiveType(builder, mapMode(mesh.drawState().mode()));
            writtenMeshes.add(ExpMesh.endExpMesh(builder));
        }

        return ExpScene.createMeshesVector(builder, writtenMeshes.elements());
    }

    private int mapIndexType(VertexFormat.IndexType indexType) {
        return switch (indexType) {
            case INT -> ExpIndexElementType.UINT;
            case SHORT -> ExpIndexElementType.USHORT;
        };
    }

    record IndexBufferAttributes(
            ByteBuffer data,
            VertexFormat.IndexType indexType,
            int indexCount) {
    }

    private IndexBufferAttributes createIndexBuffer(MeshData.DrawState drawState, ByteBuffer idxBuffer) {
        // Handle index buffer
        ByteBuffer effectiveIndices;
        var indexType = drawState.indexType();
        var indexCount = drawState.indexCount();
        var mode = drawState.mode();

        // Auto-generated indices
        if (idxBuffer == null) {
            var generated = generateSequentialIndices(
                    mode,
                    drawState.vertexCount(),
                    drawState.indexCount());
            effectiveIndices = generated.data;
            indexType = generated.type;
            indexCount = generated.indexCount();
        } else if (indexType == VertexFormat.IndexType.SHORT) {
            // Convert quads -> triangles
            if (mode == VertexFormat.Mode.QUADS) {
                var idxShortBuffer = idxBuffer.asShortBuffer();
                var triIndices = ShortBuffer.allocate(idxShortBuffer.remaining() * 2);
                while (idxShortBuffer.hasRemaining()) {
                    short one = idxShortBuffer.get();
                    short two = idxShortBuffer.get();
                    short three = idxShortBuffer.get();
                    short four = idxShortBuffer.get();

                    triIndices.put(one);
                    triIndices.put(two);
                    triIndices.put(three);

                    triIndices.put(three);
                    triIndices.put(four);
                    triIndices.put(one);
                }
                triIndices.flip();

                effectiveIndices = ByteBuffer.allocate(triIndices.remaining() * 2)
                        .order(ByteOrder.nativeOrder());
                while (triIndices.hasRemaining()) {
                    effectiveIndices.putShort(triIndices.get());
                }
            } else {
                effectiveIndices = idxBuffer;
            }
        } else if (indexType == VertexFormat.IndexType.INT) {
            // Convert quads -> triangles
            if (mode == VertexFormat.Mode.QUADS) {
                var idxIntBuffer = idxBuffer.asIntBuffer();
                var triIndices = IntBuffer.allocate(idxIntBuffer.remaining() * 2);
                while (idxIntBuffer.hasRemaining()) {
                    var one = idxIntBuffer.get();
                    var two = idxIntBuffer.get();
                    var three = idxIntBuffer.get();
                    var four = idxIntBuffer.get();

                    triIndices.put(one);
                    triIndices.put(two);
                    triIndices.put(three);

                    triIndices.put(three);
                    triIndices.put(four);
                    triIndices.put(one);
                }
                triIndices.flip();

                effectiveIndices = ByteBuffer.allocate(triIndices.remaining() * 4)
                        .order(ByteOrder.nativeOrder());
                while (triIndices.hasRemaining()) {
                    effectiveIndices.putInt(triIndices.get());
                }
            } else {
                effectiveIndices = idxBuffer;
            }
        } else {
            throw new RuntimeException("Unknown index type: " + indexType);
        }

        // Add raw buffer data for indices
        return new IndexBufferAttributes(effectiveIndices, indexType, indexCount);
    }

    private GeneratedIndexBuffer generateSequentialIndices(VertexFormat.Mode mode, int vertexCount,
            int expectedIndexCount) {
        var indicesPerPrimitive = switch (mode) {
            case LINES -> 2;
            case DEBUG_LINES -> 2;
            case TRIANGLES -> 3;
            case QUADS -> 6;
            default -> throw new UnsupportedOperationException();
        };
        var verticesPerPrimitive = switch (mode) {
            case LINES -> 2;
            case DEBUG_LINES -> 2;
            case TRIANGLES -> 3;
            case QUADS -> 4;
            default -> throw new UnsupportedOperationException();
        };
        var primitives = vertexCount / verticesPerPrimitive;
        var indexCount = primitives * indicesPerPrimitive;
        if (indexCount != expectedIndexCount) {
            throw new RuntimeException("Would generate " + indexCount + " but MC expected " + expectedIndexCount);
        }

        var indexType = VertexFormat.IndexType.least(indexCount);
        var buffer = ByteBuffer.allocate(indexType.bytes * indexCount).order(ByteOrder.nativeOrder());

        IntConsumer indexConsumer;
        if (indexType == VertexFormat.IndexType.SHORT) {
            indexConsumer = value -> buffer.putShort((short) value);
        } else {
            indexConsumer = buffer::putInt;
        }

        for (var i = 0; i < vertexCount; i += verticesPerPrimitive) {
            switch (mode) {
                case QUADS -> {
                    indexConsumer.accept(i + 0);
                    indexConsumer.accept(i + 1);
                    indexConsumer.accept(i + 2);
                    indexConsumer.accept(i + 2);
                    indexConsumer.accept(i + 3);
                    indexConsumer.accept(i + 0);
                }
                default -> IntStream.range(0, indexCount).forEach(indexConsumer);
            }
        }

        buffer.flip();

        return new GeneratedIndexBuffer(indexType, buffer, indexCount);
    }

    record GeneratedIndexBuffer(VertexFormat.IndexType type, ByteBuffer data, int indexCount) {
    }

    private int createCameraModel(CameraSettings cameraSettings, FlatBufferBuilder builder) {
        return ExpCameraSettings.createExpCameraSettings(
                builder,
                cameraSettings.getRotationY(),
                cameraSettings.getRotationX(),
                cameraSettings.getRotationZ(),
                cameraSettings.getZoom());
    }

}
