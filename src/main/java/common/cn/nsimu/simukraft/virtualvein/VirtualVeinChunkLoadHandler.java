package common.cn.nsimu.simukraft.virtualvein;

import common.cn.nsimu.simukraft.SimuKraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * VirtualVeinChunkLoadHandler: 在区块首次服务端加载时排队建立矿区档案。
 */
@EventBusSubscriber(modid = SimuKraft.MOD_ID)
public final class VirtualVeinChunkLoadHandler {
    private VirtualVeinChunkLoadHandler() {
    }

    /**
     * onChunkLoad: 只记录待建档区块，不在本帧采样和读写数据库。
     * <p>26.3 的 ChunkEvent.Load 仍由主线程成批投递，进世界时一帧可以有上百个区块到达。
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.dimension().equals(Level.OVERWORLD)) {
            return;
        }
        ChunkAccess chunk = event.getChunk();
        VirtualVeinService.enqueueFieldInitChunk(level, chunk.getPos());
    }
}
