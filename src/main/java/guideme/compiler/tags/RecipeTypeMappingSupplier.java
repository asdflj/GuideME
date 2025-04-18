package guideme.compiler.tags;

import guideme.document.block.LytBlock;
import guideme.extensions.Extension;
import guideme.extensions.ExtensionPoint;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.Nullable;

/**
 * Allows mods to register mappings between recipe type and their custom recipe blocks for use in {@code <RecipeFor/>}
 * and similar tags.
 * <p/>
 * **NOTE:** In addition to being an extension point, implementations of this interface are also retrieved through the
 * Java Service-Loader to enable use of mod recipes cross-guide. Specific instances registered through
 * {@link guideme.GuideBuilder#extension} will have higher priority than instances discovered through service-loader.
 */
public interface RecipeTypeMappingSupplier extends Extension {
    ExtensionPoint<RecipeTypeMappingSupplier> EXTENSION_POINT = new ExtensionPoint<>(RecipeTypeMappingSupplier.class);

    void collect(RecipeTypeMappings mappings);

    interface RecipeTypeMappings {
        /**
         * Adds a factory that can produce a display element (or none) for recipes of the given type.
         */
        default <T extends Recipe<C>, C extends RecipeInput> void add(
                RecipeType<T> recipeType,
                Function<RecipeHolder<T>, @Nullable LytBlock> factory) {
            addStreamFactory(recipeType, holder -> Optional.ofNullable(factory.apply(holder)).stream());
        }

        /**
         * Adds a factory that can produce 0 or more display elements for recipes of the given type.
         */
        <T extends Recipe<C>, C extends RecipeInput> void addStreamFactory(
                RecipeType<T> recipeType,
                Function<RecipeHolder<T>, Stream<? extends LytBlock>> factory);
    }
}
