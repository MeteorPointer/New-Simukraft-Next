package client.cn.nsimu.simukraft.client.city;

import client.cn.nsimu.simukraft.client.city.map.SimuMapStorage;
import common.cn.nsimu.simukraft.network.city.chunk.CityChunkSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientCityChunkCache {
    private static final ClientCityChunkCache INSTANCE = new ClientCityChunkCache();
    private final Map<CacheScope, ScopeCache> scopedCaches = new ConcurrentHashMap<>();

    private ClientCityChunkCache() {
    }

    public static ClientCityChunkCache getInstance() {
        return INSTANCE;
    }

    public synchronized void updateCurrentCity(UUID cityId, Set<Long> chunks, BlockPos corePos, String cityName) {
        ScopeCache cache = currentScopeCache();
        cache.currentCityId = cityId;
        cache.allCityChunks.put(cityId, Set.copyOf(chunks));
        cache.allCityCores.put(cityId, new CityCoreEntry(corePos.immutable(), cityName));
        cache.rebuildChunkOwnerIndex();
    }

    public synchronized void updateAllCityChunks(UUID cityId, Map<UUID, Set<Long>> chunks) {
        ScopeCache cache = currentScopeCache();
        cache.currentCityId = cityId;
        cache.allCityChunks.clear();
        chunks.forEach((id, cityChunks) -> cache.allCityChunks.put(id, Set.copyOf(cityChunks)));
        cache.rebuildChunkOwnerIndex();
    }

    public synchronized void updateAllCityCores(Map<UUID, CityCoreEntry> cores) {
        ScopeCache cache = currentScopeCache();
        cache.allCityCores.clear();
        cache.allCityCores.putAll(cores);
    }

    /**
     * updateDistricts: 用服务端分区区块重建当前维度的分区索引。
     */
    public synchronized void updateDistricts(List<CityChunkSyncPacket.DistrictEntry> districts) {
        ScopeCache cache = currentScopeCache();
        cache.chunkDistricts.clear();
        if (districts == null) {
            return;
        }
        for (CityChunkSyncPacket.DistrictEntry district : districts) {
            if (district == null || district.districtId() == null || district.chunks() == null) {
                continue;
            }
            DistrictLabel label = new DistrictLabel(district.districtId(), district.cityId(),
                    district.name() == null ? "" : district.name());
            district.chunks().forEach(chunk -> cache.chunkDistricts.put(chunk, label));
        }
    }

    public Set<Long> getCurrentCityChunks() {
        ScopeCache cache = currentScopeCache();
        UUID cityId = cache.currentCityId;
        if (cityId == null) {
            return Collections.emptySet();
        }
        return cache.allCityChunks.getOrDefault(cityId, Collections.emptySet());
    }

    /**
     * getAllCityChunks: 返回全量快照拷贝，仅供 UI 一次性读取；不得放进每帧或每 region 哈希路径，否则每次调用会拷贝所有城市的 chunk 集合。
     * 逐 region 遍历请改走 {@link #getRegionChunkOwners(int, int)}，chunk 反查请走 {@link #getChunkOwner(long)}。
     */
    public Map<UUID, Set<Long>> getAllCityChunks() {
        return snapshotChunks(currentScopeCache().allCityChunks);
    }

    public Map<UUID, CityCoreEntry> getAllCityCores() {
        return Map.copyOf(currentScopeCache().allCityCores);
    }

    public UUID getCurrentCityId() {
        return currentScopeCache().currentCityId;
    }

    public boolean isChunkInCurrentCity(long chunkLong) {
        ScopeCache cache = currentScopeCache();
        UUID cityId = cache.currentCityId;
        return cityId != null && cache.allCityChunks.getOrDefault(cityId, Collections.emptySet()).contains(chunkLong);
    }

    public boolean isChunkOwned(long chunkLong) {
        return currentScopeCache().chunkOwnerIndex.containsKey(chunkLong);
    }

    public UUID getChunkOwner(long chunkLong) {
        return currentScopeCache().chunkOwnerIndex.get(chunkLong);
    }

    /**
     * getChunkDistrict: 返回区块所属分区，没有分区时为空。
     */
    public DistrictLabel getChunkDistrict(long chunkLong) {
        return currentScopeCache().chunkDistricts.get(chunkLong);
    }

    /**
     * getChunkOwners: 返回当前世界和维度领地权属的不可变快照，供客户端渲染批量读取；单次调用即完成，不要放进每帧循环。
     */
    public Map<Long, UUID> getChunkOwners() {
        return Map.copyOf(currentScopeCache().chunkOwnerIndex);
    }

    /**
     * getRegionChunkOwners: 返回指定 region 内已归属的 chunk→city 只读视图，用于 Xaero 高亮器等逐 region 哈希；调用方不得修改。
     * <p>该视图直接引用内部索引，dataVersion 变更时会被整表替换；同一版本内稳定。
     */
    public Map<Long, UUID> getRegionChunkOwners(int regionX, int regionZ) {
        long key = (long) regionX << 32 | Integer.toUnsignedLong(regionZ);
        return currentScopeCache().regionOwnerIndex.getOrDefault(key, Map.of());
    }

    public int getDataVersion() {
        return currentScopeCache().dataVersion;
    }

    public boolean regionHasOwnedChunks(int regionX, int regionZ) {
        long key = (long) regionX << 32 | Integer.toUnsignedLong(regionZ);
        return currentScopeCache().regionWithChunks.contains(key);
    }

    public synchronized void clear() {
        scopedCaches.remove(currentScope());
    }

    public synchronized void clearAllWorlds() {
        scopedCaches.clear();
    }

    private ScopeCache currentScopeCache() {
        return scopedCaches.computeIfAbsent(currentScope(), scope -> new ScopeCache());
    }

    private CacheScope currentScope() {
        Minecraft minecraft = Minecraft.getInstance();
        String worldId = SimuMapStorage.getCurrentWorldId();
        Level level = minecraft.level;
        String dimensionId = level == null ? "unknown" : dimensionToId(level.dimension());
        return new CacheScope(worldId, dimensionId);
    }

    private static String dimensionToId(ResourceKey<Level> dimension) {
        return dimension.identifier().getNamespace() + ":" + dimension.identifier().getPath();
    }

    private static Map<UUID, Set<Long>> snapshotChunks(Map<UUID, Set<Long>> chunks) {
        Map<UUID, Set<Long>> snapshot = new HashMap<>();
        chunks.forEach((cityId, cityChunks) -> snapshot.put(cityId, Set.copyOf(new HashSet<>(cityChunks))));
        return Map.copyOf(snapshot);
    }

    private record CacheScope(String worldId, String dimensionId) {
    }

    private static final class ScopeCache {
        private final Map<UUID, Set<Long>> allCityChunks = new ConcurrentHashMap<>();
        private final Map<Long, UUID> chunkOwnerIndex = new ConcurrentHashMap<>();
        /**
         * regionKey → (chunkLong → cityId)。regionKey 与 {@link #regionHasOwnedChunks} 的编码一致。
         * 由 {@link #rebuildChunkOwnerIndex} 整表重建，避免调用方做全量 snapshot 引发 O(regions × totalChunks) 拷贝。
         */
        private final Map<Long, Map<Long, UUID>> regionOwnerIndex = new ConcurrentHashMap<>();
        private final Map<UUID, CityCoreEntry> allCityCores = new ConcurrentHashMap<>();
        private final Map<Long, DistrictLabel> chunkDistricts = new ConcurrentHashMap<>();
        private volatile UUID currentCityId;
        private volatile int dataVersion;
        private volatile Set<Long> regionWithChunks = Set.of();

        private void rebuildChunkOwnerIndex() {
            chunkOwnerIndex.clear();
            allCityChunks.forEach((cityId, chunks) -> chunks.forEach(chunk -> chunkOwnerIndex.put(chunk, cityId)));
            // 逐 region 索引和 has-region 集合一次遍历完成，避免 Xaero 高亮器每 region 再走全量 snapshot。
            Map<Long, Map<Long, UUID>> nextRegionOwners = new HashMap<>();
            Set<Long> regions = new HashSet<>();
            chunkOwnerIndex.forEach((chunk, cityId) -> {
                long regionKey = (long) (ChunkPos.getX(chunk) >> 5) << 32 | Integer.toUnsignedLong(ChunkPos.getZ(chunk) >> 5);
                nextRegionOwners.computeIfAbsent(regionKey, key -> new ConcurrentHashMap<>()).put(chunk, cityId);
                regions.add(regionKey);
            });
            regionOwnerIndex.clear();
            nextRegionOwners.forEach((regionKey, owners) -> regionOwnerIndex.put(regionKey, Map.copyOf(owners)));
            regionWithChunks = Set.copyOf(regions);
            dataVersion++;
        }
    }

    public record CityCoreEntry(BlockPos pos, String cityName) {
    }

    /**
     * DistrictLabel: 客户端区块上的分区名称。
     */
    public record DistrictLabel(UUID districtId, UUID cityId, String name) {
    }
}
