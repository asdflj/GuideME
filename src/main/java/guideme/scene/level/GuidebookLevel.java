package guideme.scene.level;

import guideme.internal.GuideME;
import guideme.internal.util.Platform;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.model.data.ModelData;
import net.neoforged.neoforge.model.data.ModelDataManager;
import org.jetbrains.annotations.Nullable;

public class GuidebookLevel extends Level {

    private static final ResourceKey<Level> LEVEL_ID = ResourceKey.create(Registries.DIMENSION,
            GuideME.makeId("guidebook"));

    private final TransientEntitySectionManager<Entity> entityStorage = new TransientEntitySectionManager<>(
            Entity.class, new EntityCallbacks());

    private final ChunkSource chunkSource = new GuidebookChunkSource(this);
    private final Holder<Biome> biome;
    private final RegistryAccess registryAccess;
    private final LongSet filledBlocks = new LongOpenHashSet();
    /**
     * Sections for which we prepared lighting.
     */
    private final LongSet litSections = new LongOpenHashSet();
    private final DataLayer defaultDataLayer;

    private final TickRateManager tickRateManager = new TickRateManager();
    private final ClientLevel.ClientLevelData clientLevelData;
    private final DeltaTracker.Timer tracker = new DeltaTracker.Timer(20.0F, 0L, def -> def);
    private float partialTick;
    private final ModelDataManager modelDataManager = new ModelDataManager(this);

    public GuidebookLevel() {
        this(Platform.getClientRegistryAccess());
    }

    public GuidebookLevel(RegistryAccess registryAccess) {
        this(createLevelData(), registryAccess);
    }

    private GuidebookLevel(ClientLevel.ClientLevelData levelData, RegistryAccess registryAccess) {
        super(
                levelData,
                LEVEL_ID,
                registryAccess,
                registryAccess.lookupOrThrow(Registries.DIMENSION_TYPE)
                        .getOrThrow(BuiltinDimensionTypes.OVERWORLD),
                true /* client-side */,
                false /* debug */,
                0 /* seed */,
                1000000 /* max neighbor updates */
        );
        this.clientLevelData = levelData;
        this.registryAccess = registryAccess;
        this.biome = registryAccess.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);

        var nibbles = new byte[DataLayer.SIZE];
        Arrays.fill(nibbles, (byte) 0xFF);
        defaultDataLayer = new DataLayer(nibbles);
    }

    public Bounds getBounds() {
        if (filledBlocks.isEmpty()) {
            return new Bounds(BlockPos.ZERO, BlockPos.ZERO);
        }

        var min = new BlockPos.MutableBlockPos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        var max = new BlockPos.MutableBlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        var cur = new BlockPos.MutableBlockPos();

        filledBlocks.forEach(packedPos -> {
            cur.set(packedPos);
            min.setX(Math.min(min.getX(), cur.getX()));
            min.setY(Math.min(min.getY(), cur.getY()));
            min.setZ(Math.min(min.getZ(), cur.getZ()));

            max.setX(Math.max(max.getX(), cur.getX() + 1));
            max.setY(Math.max(max.getY(), cur.getY() + 1));
            max.setZ(Math.max(max.getZ(), cur.getZ() + 1));
        });

        for (var entity : getEntitiesForRendering()) {
            var bounds = entity.getBoundingBox();

            min.setX(Math.min(min.getX(), (int) bounds.minX));
            min.setY(Math.min(min.getY(), (int) bounds.minY));
            min.setZ(Math.min(min.getZ(), (int) bounds.minZ));

            max.setX(Math.max(max.getX(), (int) Math.ceil(bounds.maxX)));
            max.setY(Math.max(max.getY(), (int) Math.ceil(bounds.maxY)));
            max.setZ(Math.max(max.getZ(), (int) Math.ceil(bounds.maxZ)));
        }

        return new Bounds(min, max);
    }

    public boolean isFilledBlock(BlockPos blockPos) {
        return filledBlocks.contains(blockPos.asLong());
    }

    void removeFilledBlock(BlockPos pos) {
        filledBlocks.remove(pos.asLong());
    }

    void addFilledBlock(BlockPos pos) {
        filledBlocks.add(pos.asLong());
    }

    /**
     * Ensures lighting is set to skylight level 15 in the entire chunk and adjacent chunks whenever a block is first
     * changed in that chunk.
     */
    public void prepareLighting(BlockPos pos) {
        var minChunk = new ChunkPos(pos.offset(-1, -1, -1));
        var maxChunk = new ChunkPos(pos.offset(1, 1, 1));
        ChunkPos.rangeClosed(minChunk, maxChunk).forEach(chunkPos -> {
            if (litSections.add(chunkPos.toLong())) {
                var lightEngine = getLightEngine();
                for (int i = 0; i < getSectionsCount(); ++i) {
                    int y = getSectionYFromSectionIndex(i);
                    var sectionPos = SectionPos.of(chunkPos, y);
                    lightEngine.updateSectionStatus(sectionPos, false);
                    lightEngine.queueSectionData(LightLayer.BLOCK, sectionPos, defaultDataLayer);
                    lightEngine.queueSectionData(LightLayer.SKY, sectionPos, defaultDataLayer);
                }

                lightEngine.setLightEnabled(chunkPos, true);
                lightEngine.propagateLightSources(chunkPos);
                lightEngine.retainData(chunkPos, false);
            }
        });
    }

    public record Bounds(BlockPos min, BlockPos max) {
    }

    private static ClientLevel.ClientLevelData createLevelData() {
        var levelData = new ClientLevel.ClientLevelData(Difficulty.PEACEFUL, false /* hardcore */, false /* flat */);

        // set time of day to noon (from TimeCommand noon)
        levelData.setDayTime(6000);

        return levelData;
    }

    public float getPartialTick() {
        return partialTick;
    }

    @Override
    public ModelData getModelData(BlockPos pos) {
        return modelDataManager.getAt(pos);
    }

    @Override
    public @Nullable ModelDataManager getModelDataManager() {
        return modelDataManager;
    }

    public void onRenderFrame() {
        var ticksElapsed = tracker.advanceTime(Util.getMillis(), true);
        if (ticksElapsed > 0) {
            clientLevelData.setGameTime(clientLevelData.getGameTime() + ticksElapsed);
        }

        partialTick = tracker.getGameTimeDeltaPartialTick(false);
    }

    public boolean hasFilledBlocks() {
        return !filledBlocks.isEmpty();
    }

    public Stream<BlockPos> getFilledBlocks() {
        var mutablePos = new BlockPos.MutableBlockPos();
        return filledBlocks.longStream()
                .sequential()
                .mapToObj(pos -> {
                    mutablePos.set(pos);
                    return mutablePos;
                });
    }

    /**
     * @return All block entities in the level.
     */
    public Set<BlockEntity> getBlockEntities() {
        return getFilledBlocks()
                .map(this::getBlockEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(() -> Collections.newSetFromMap(new IdentityHashMap<>())));
    }

    @Override
    protected LevelEntityGetter<Entity> getEntities() {
        return entityStorage.getEntityGetter();
    }

    public Iterable<Entity> getEntitiesForRendering() {
        return entityStorage.getEntityGetter().getAll();
    }

    public void addEntity(Entity entity) {
        this.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        this.entityStorage.addEntity(entity);
        entity.onAddedToLevel();
        prepareLighting(entity.getOnPos());
    }

    public void removeEntity(int entityId, Entity.RemovalReason reason) {
        Entity entity = getEntities().get(entityId);
        if (entity != null) {
            entity.setRemoved(reason);
            entity.onClientRemoval();
        }
    }

    @Nullable
    @Override
    public Entity getEntity(int id) {
        return getEntities().get(id);
    }

    @Override
    public TickRateManager tickRateManager() {
        return tickRateManager;
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
    }

    @Override
    public void playSeededSound(@Nullable Entity p_394382_, double p_220364_, double p_220365_, double p_220366_,
            Holder<SoundEvent> p_394088_, SoundSource p_220368_, float p_220369_, float p_220370_, long p_220371_) {

    }

    @Override
    public void playSeededSound(@Nullable Entity p_394455_, Entity p_393481_, Holder<SoundEvent> p_263359_,
            SoundSource p_263020_, float p_263055_, float p_262914_, long p_262991_) {

    }

    @Override
    public String gatherChunkSourceStats() {
        return "";
    }

    @Nullable
    @Override
    public MapItemSavedData getMapData(MapId mapId) {
        return null;
    }

    @Override
    public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
    }

    @Override
    public Scoreboard getScoreboard() {
        return new Scoreboard();
    }

    @Override
    public RecipeAccess recipeAccess() {
        if (Minecraft.getInstance().level != null) {
            return Minecraft.getInstance().level.recipeAccess();
        }
        return new RecipeAccess() {
            @Override
            public RecipePropertySet propertySet(ResourceKey<RecipePropertySet> propertySet) {
                return RecipePropertySet.EMPTY;
            }

            @Override
            public SelectableRecipe.SingleInputSet<StonecutterRecipe> stonecutterRecipes() {
                return SelectableRecipe.SingleInputSet.empty();
            }
        };
    }

    @Override
    public FuelValues fuelValues() {
        return Platform.fuelValues();
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public ChunkSource getChunkSource() {
        return chunkSource;
    }

    @Override
    public void levelEvent(@Nullable Entity player, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> gameEvent, Vec3 vec3, GameEvent.Context context) {
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        if (!shade) {
            return 1.0F;
        } else {
            return switch (direction) {
                case DOWN -> 0.5F;
                case NORTH, SOUTH -> 0.8F;
                case WEST, EAST -> 0.6F;
                default -> 1.0F;
            };
        }
    }

    @Override
    public List<? extends Player> players() {
        return List.of();
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int i, int j, int k) {
        return biome;
    }

    @Override
    public RegistryAccess registryAccess() {
        return registryAccess;
    }

    @Override
    public PotionBrewing potionBrewing() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setDayTimeFraction(float v) {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getDayTimeFraction() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getDayTimePerTick() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setDayTimePerTick(float v) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return FeatureFlags.DEFAULT_FLAGS;
    }

    @Override
    public void explode(@Nullable Entity source, @Nullable DamageSource damageSource,
            @Nullable ExplosionDamageCalculator damageCalculator, double x, double y, double z, float radius,
            boolean fire, ExplosionInteraction explosionInteraction, ParticleOptions smallExplosionParticles,
            ParticleOptions largeExplosionParticles, Holder<SoundEvent> explosionSound) {
    }

    @Override
    public Collection<PartEntity<?>> dragonParts() {
        return List.of();
    }

    @Override
    public int getSeaLevel() {
        return 0;
    }

    private static class EntityCallbacks implements LevelCallback<Entity> {
        @Override
        public void onCreated(Entity entity) {
        }

        @Override
        public void onDestroyed(Entity entity) {
        }

        @Override
        public void onTickingStart(Entity entity) {
        }

        @Override
        public void onTickingEnd(Entity entity) {
        }

        @Override
        public void onTrackingStart(Entity entity) {
        }

        @Override
        public void onTrackingEnd(Entity entity) {
        }

        @Override
        public void onSectionChange(Entity object) {
        }
    }
}
