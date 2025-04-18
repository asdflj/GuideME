package guideme.scene.level;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

class GuidebookChunk extends LevelChunk {
    public GuidebookChunk(GuidebookLevel level, ChunkPos pos) {
        super(level, pos);
    }

    private GuidebookLevel getGuidebookLevel() {
        return (GuidebookLevel) getLevel();
    }

    @Override
    @Nullable
    public BlockState setBlockState(BlockPos pos, BlockState state, int flags) {
        getGuidebookLevel().prepareLighting(pos);

        var result = super.setBlockState(pos, state, flags);
        if (state.isAir()) {
            getGuidebookLevel().removeFilledBlock(pos);
        } else {
            getGuidebookLevel().addFilledBlock(pos);
        }
        return result;
    }

    public FullChunkStatus getFullStatus() {
        return FullChunkStatus.FULL;
    }

}
