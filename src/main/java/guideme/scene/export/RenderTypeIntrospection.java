package guideme.scene.export;

import java.util.List;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RenderTypeIntrospection {
    private static final Logger LOG = LoggerFactory.getLogger(RenderTypeIntrospection.class);

    private RenderTypeIntrospection() {
    }

    public static List<Sampler> getSamplers(RenderType type) {
        if (!(type instanceof RenderType.CompositeRenderType compositeRenderType)) {
            return List.of();
        }
        var state = compositeRenderType.state;
        if (state.textureState instanceof RenderStateShard.TextureStateShard textureShard) {
            if (textureShard.texture.isPresent()) {
                var texture = textureShard.texture.get();

                return List.of(new Sampler(texture, textureShard.blur.toBoolean(false), textureShard.mipmap));
            } else {
                LOG.warn("Render type {} is using dynamic texture", type);
            }
        } else if (state.textureState != RenderStateShard.NO_TEXTURE) {
            LOG.warn("Cannot handle texturing of render-type {}", type);
        }

        return List.of();
    }

    public record Sampler(ResourceLocation texture, boolean blur, boolean mipmap) {
    }
}
