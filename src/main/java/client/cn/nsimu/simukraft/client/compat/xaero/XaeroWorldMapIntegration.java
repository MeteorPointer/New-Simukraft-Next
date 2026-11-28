package client.cn.nsimu.simukraft.client.compat.xaero;

import client.cn.nsimu.simukraft.client.city.ClientCityChunkCache;
import common.cn.nsimu.simukraft.SimuKraft;
import net.minecraft.client.Minecraft;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.highlight.AbstractHighlighter;
import xaero.map.region.LeveledRegion;
import xaero.map.region.MapRegion;
import xaero.map.world.MapDimension;
import xaero.map.world.MapWorld;

import java.util.ArrayList;
import java.util.List;

public final class XaeroWorldMapIntegration {
    private static volatile boolean registeredOnce;

    private XaeroWorldMapIntegration() {
    }

    /**
     * registerHighlighter: 向 Xaero World Map 注册城市区块高亮器。
     */
    public static void registerHighlighter(List<AbstractHighlighter> highlighters) {
        if (highlighters == null) {
            return;
        }
        if (containsSimuKraftHighlighter(highlighters)) {
            refreshCityHighlights();
            return;
        }
        highlighters.add(new SimuKraftCityHighlighter());
        if (!registeredOnce) {
            registeredOnce = true;
            SimuKraft.LOGGER.info("Simukraft: Registered city chunk highlighter for Xaero's World Map.");
        }
        refreshCityHighlights();
    }

    /**
     * refreshCityHighlights: 城市区块同步后刷新 Xaero 已缓存的高亮区域。
     */
    public static void refreshCityHighlights() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(XaeroWorldMapIntegration::refreshCityHighlights);
            return;
        }
        try {
            WorldMapSession session = WorldMapSession.getCurrentSession();
            if (session == null || !session.isUsable()) {
                return;
            }
            MapProcessor processor = session.getMapProcessor();
            if (processor == null) {
                return;
            }
            MapWorld mapWorld = processor.getMapWorld();
            if (mapWorld == null) {
                return;
            }
            refreshLoadedRegions(processor, mapWorld.getCurrentDimension());
        } catch (RuntimeException exception) {
            SimuKraft.LOGGER.warn("Simukraft: Failed to refresh Xaero city chunk highlights.", exception);
        }
    }

    /**
     * refreshLoadedRegions: 仅将含城市领地的叶子地图区域重新加入刷新队列。
     * <p>无领地的 region 重刷只会触发 Xaero 内部逐 chunk getColors 回调，而我们的 getColors 对无主 chunk 直接返回 null，白白重绘。
     */
    private static void refreshLoadedRegions(MapProcessor processor, MapDimension dimension) {
        if (processor == null || dimension == null) {
            return;
        }
        ClientCityChunkCache cache = ClientCityChunkCache.getInstance();
        List<MapRegion> loadedMapRegions = new ArrayList<>();
        List<LeveledRegion<?>> loadedRegions = dimension.getLayeredMapRegions().getLoadedListUnsynced();
        synchronized (loadedRegions) {
            for (LeveledRegion<?> region : loadedRegions) {
                if (region instanceof MapRegion mapRegion
                        && cache.regionHasOwnedChunks(mapRegion.getRegionX(), mapRegion.getRegionZ())) {
                    loadedMapRegions.add(mapRegion);
                }
            }
        }
        for (MapRegion mapRegion : loadedMapRegions) {
            mapRegion.requestRefresh(processor);
        }
    }

    /**
     * containsSimuKraftHighlighter: 防止 Xaero 会话重复初始化时重复添加高亮器。
     */
    private static boolean containsSimuKraftHighlighter(List<AbstractHighlighter> highlighters) {
        for (AbstractHighlighter highlighter : highlighters) {
            if (highlighter instanceof SimuKraftCityHighlighter) {
                return true;
            }
        }
        return false;
    }
}
