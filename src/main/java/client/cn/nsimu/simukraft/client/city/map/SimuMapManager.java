package client.cn.nsimu.simukraft.client.city.map;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 客户端地图管理器。
 * 只扫描客户端已经加载的 FULL chunk，并将结果写入本地 region 缓存。
 */
public class SimuMapManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MAX_SCAN_RADIUS = 32;
    private static final int DEFAULT_SCAN_RADIUS = 12;
    private static final int ACTIVE_SCAN_INTERVAL_TICKS = 1;
    private static final int ACTIVE_SCAN_BUDGET = 8;
    private static final int PASSIVE_SCAN_INTERVAL_TICKS = 6;
    private static final int PASSIVE_SCAN_BUDGET = 1;
    private static final int DIRTY_RENDER_INTERVAL_TICKS = 5;
    private static final int AUTO_SAVE_INTERVAL_TICKS = 1200;
    private static final int STALE_RELEASE_INTERVAL_TICKS = 600;
    private static final long STALE_REGION_MAX_AGE_MS = 60_000L;
    private static final int ACTIVE_REFRESH_INTERVAL_TICKS = 200;
    // 区块加载改为限流补采：避免进入世界时成批区块在主线程同步全量采样导致严重卡顿。
    private static final int CHUNK_LOAD_SCAN_BUDGET_PER_TICK = 4;
    private static final int MAX_PENDING_CHUNK_SCANS = 4096;
    /**
     * 每拍最多提交几个脏 region 给后台烘图，旧实现一次性把全部脏 region 灌进无界队列。
     */
    private static final int REGION_BAKE_BUDGET_PER_TICK = 2;
    private static final int MAX_REGION_BAKES_IN_FLIGHT = 6;
    /**
     * 一次绘制窗口内允许同步上传的 region 纹理数，剩下的帧继续用旧纹理。
     */
    private static final int TEXTURE_UPLOAD_BUDGET_PER_FRAME = 2;
    /**
     * 磁盘 region 按需加载的并发上限，每个文件是 512x512 的地形数据。
     */
    private static final int MAX_REGION_LOADS_IN_FLIGHT = 3;
    private static final int REGION_LIST_REFRESH_TICKS = 1200;
    /**
     * 常驻 region 上限。一个 region 的三个数组约 2MB，长期跑图不能只增不减。
     */
    private static final int MAX_RESIDENT_REGIONS = 128;
    private static final long EVICT_MIN_AGE_MS = 120_000L;
    private static final int EVICT_MIN_DISTANCE_BLOCKS = 4096;
    /**
     * 视口补载每次最多扫的 region 边长。
     */
    private static final int MAX_VIEW_REGION_SPAN = 32;

    private static SimuMapManager instance;

    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SimuMap-Render");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<Long, SimuMapRegion> regions = new ConcurrentHashMap<>();
    private final Set<Long> renderingKeys = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> scannedAtTick = new ConcurrentHashMap<>();
    // 待补采区块队列（打包坐标），仅由每拍限流消费，用并发容器兜住区块加载与 tick 的时序。
    private final Queue<Long> pendingChunkScans = new ConcurrentLinkedQueue<>();
    private final Set<Long> pendingChunkKeys = ConcurrentHashMap.newKeySet();
    // 磁盘缓存里已有的 region 键，只列目录不读数据；旧实现进世界时把全部 region 一次性读进内存。
    private volatile Set<Long> availableRegionKeys = Set.of();
    private final Set<Long> loadingRegionKeys = ConcurrentHashMap.newKeySet();
    private volatile long regionListTick = Long.MIN_VALUE;

    private int scanRadius = DEFAULT_SCAN_RADIUS;
    private long tickCount = 0L;

    private int scanCursorDX = -DEFAULT_SCAN_RADIUS;
    private int scanCursorDZ = -DEFAULT_SCAN_RADIUS;
    private int scanCursorRadius = DEFAULT_SCAN_RADIUS;

    private volatile boolean initialized = false;
    private volatile int loadGeneration = 0;

    @Nullable
    private volatile ResourceKey<Level> currentDimension = null;

    @Nullable
    private volatile String currentWorldId = null;

    private int activeConsumers = 0;

    /**
     * 本帧还能同步上传几个 region 纹理，只在渲染线程读写。
     */
    private int uploadWindowRemaining = 0;

    /**
     * 脏区烘图排序焦点（世界坐标）。地图界面远程看别处时不能按玩家位置排队。
     */
    private volatile double bakeFocusX = Double.NaN;
    private volatile double bakeFocusZ = Double.NaN;

    private SimuMapManager() {
    }

    public static SimuMapManager getInstance() {
        if (instance == null) {
            instance = new SimuMapManager();
        }
        return instance;
    }

    public static boolean isAvailable() {
        return instance != null && instance.initialized;
    }

    public static void shutdownIfPresent() {
        if (instance != null) {
            instance.shutdown();
        }
    }

    public void acquireConsumer() {
        activeConsumers++;
    }

    public void releaseConsumer() {
        activeConsumers = Math.max(0, activeConsumers - 1);
        if (activeConsumers == 0) {
            bakeFocusX = Double.NaN;
            bakeFocusZ = Double.NaN;
        }
    }

    /**
     * setBakeFocus: 地图界面把视口中心告知这里，脏区烘图按它排序。
     */
    public void setBakeFocus(double blockX, double blockZ) {
        bakeFocusX = blockX;
        bakeFocusZ = blockZ;
    }

    /**
     * bakeFocusDistanceSquared: region 到排序焦点的距离平方，没设焦点时回到玩家位置。
     */
    private double bakeFocusDistanceSquared(SimuMapRegion region) {
        double focusX = bakeFocusX;
        double focusZ = bakeFocusZ;
        if (Double.isNaN(focusX) || Double.isNaN(focusZ)) {
            return region.distToPlayer();
        }
        double centerX = region.regionX * 512.0 + 256.0;
        double centerZ = region.regionZ * 512.0 + 256.0;
        double dx = centerX - focusX;
        double dz = centerZ - focusZ;
        return dx * dx + dz * dz;
    }

    public boolean hasActiveConsumer() {
        return activeConsumers > 0;
    }

    public void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        SimuBlockColors.getInstance().init();
        resetScanCursor();

        Minecraft minecraft = Minecraft.getInstance();
        currentWorldId = SimuMapStorage.getCurrentWorldId();
        currentDimension = minecraft.level == null ? null : minecraft.level.dimension();

        if (currentDimension != null && SimuMapStorage.isResolvedWorldId(currentWorldId)) {
            refreshAvailableRegions(currentWorldId, currentDimension);
            LOGGER.info("Simukraft: Map system initialization queued for world={} dim={}.",
                    currentWorldId, SimuMapStorage.dimensionToDir(currentDimension));
        } else {
            LOGGER.info("Simukraft: Map system initialized (world identity not yet resolved).");
        }
    }

    /**
     * onBlockAtlasReloaded: 方块图集重建后清空贴图色缓存并重扫已加载区块。
     */

    public static void onBlockAtlasReloaded() {
        SimuBlockTextureColors.clear();
        if (instance == null || !instance.initialized) {
            return;
        }
        instance.scannedAtTick.clear();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            instance.forceScanArea(
                    minecraft.player.chunkPosition().x(),
                    minecraft.player.chunkPosition().z(),
                    instance.getEffectiveScanRadius());
        }
        instance.forceRenderAll();
    }

    public void shutdown() {
        if (!initialized) {
            return;
        }
        initialized = false;
        SimuBlockTextureColors.clear();

        persistRegionsAsync(currentWorldId, currentDimension, List.copyOf(regions.values()), "shutdown");

        currentWorldId = null;
        currentDimension = null;
        loadGeneration++;

        regions.clear();
        renderingKeys.clear();
        scannedAtTick.clear();
        pendingChunkScans.clear();
        pendingChunkKeys.clear();
        clearRegionScopeCache();
        renderExecutor.shutdownNow();

        instance = null;
        LOGGER.info("Simukraft: Map rendering system shut down.");
    }

    public SimuMapRegion getOrCreateRegion(int regionX, int regionZ) {
        return regions.computeIfAbsent(regionKey(regionX, regionZ), key -> new SimuMapRegion(regionX, regionZ));
    }

    @Nullable
    public SimuMapRegion getRegion(int regionX, int regionZ) {
        return regions.get(regionKey(regionX, regionZ));
    }

    public Collection<SimuMapRegion> getAllRegions() {
        return regions.values();
    }

    public void tick() {
        if (!initialized) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null) {
            return;
        }

        updateScope(SimuMapStorage.getCurrentWorldId(), level.dimension());

        tickCount++;
        boolean active = hasActiveConsumer();
        tickRegionScope(player);

        if (shouldScanThisTick(active)) {
            incrementalScan(active ? ACTIVE_SCAN_BUDGET : PASSIVE_SCAN_BUDGET);
        }
        drainPendingChunkScans(level);
        if (active && tickCount % DIRTY_RENDER_INTERVAL_TICKS == 0) {
            renderDirtyRegions();
        }
        if (tickCount % AUTO_SAVE_INTERVAL_TICKS == 0) {
            autoSaveRegions();
        }
        if (tickCount % STALE_RELEASE_INTERVAL_TICKS == 0) {
            releaseStaleRegions(STALE_REGION_MAX_AGE_MS);
        }
    }

    /**
     * forceScanArea：只在主线程补中心几块，其余交给每拍 8 块的增量扫描。
     */
    public void forceScanArea(int centerChunkX, int centerChunkZ, int radius) {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) {
            return;
        }

        int clampedRadius = Math.max(0, Math.min(radius, getEffectiveScanRadius()));
        resetScanCursor(clampedRadius);
        int scanned = 0;
        for (int ring = 0; ring <= clampedRadius && scanned < ACTIVE_SCAN_BUDGET; ring++) {
            for (int dz = -ring; dz <= ring && scanned < ACTIVE_SCAN_BUDGET; dz++) {
                for (int dx = -ring; dx <= ring && scanned < ACTIVE_SCAN_BUDGET; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    if (scanLoadedChunk(level, centerChunkX + dx, centerChunkZ + dz, false)) {
                        scanned++;
                    }
                }
            }
        }
    }

    public void forceRenderAll() {
        for (SimuMapRegion region : regions.values()) {
            SimuMapRegionData data = region.getData();
            if (data != null) {
                data.markDirty();
            }
        }
        renderDirtyRegions();
    }

    public void setScanRadius(int radius) {
        scanRadius = Math.max(1, Math.min(radius, MAX_SCAN_RADIUS));
    }

    public int getScanRadius() {
        return scanRadius;
    }

    public int getEffectiveScanRadius() {
        Minecraft minecraft = Minecraft.getInstance();
        int clientRadius = minecraft.options == null ? scanRadius : minecraft.options.getEffectiveRenderDistance() + 1;
        return Math.max(scanRadius, Math.min(clientRadius, MAX_SCAN_RADIUS));
    }

    /**
     * onClientChunkLoaded: 新加载区块只排队，交由每拍限流补采，避免进入世界时区块风暴在主线程同步全量采样造成严重卡顿。
     */
    public void onClientChunkLoaded(Level level, ChunkAccess chunk) {
        if (!initialized || level == null || chunk == null) {
            return;
        }

        updateScope(SimuMapStorage.getCurrentWorldId(), level.dimension());
        ChunkPos pos = chunk.getPos();
        queueChunkScan(pos.x(), pos.z());
    }

    // queueChunkScan: 记录待补采区块；超上限直接丢弃，螺旋增量扫描仍能兜底覆盖。
    private void queueChunkScan(int chunkX, int chunkZ) {
        // 上限判据用去重集合的规模，ConcurrentHashMap.size() 是 O(1)，不能每次扫链表。
        if (pendingChunkKeys.size() >= MAX_PENDING_CHUNK_SCANS) {
            return;
        }
        long packed = ChunkPos.pack(chunkX, chunkZ);
        if (pendingChunkKeys.add(packed)) {
            pendingChunkScans.add(packed);
        }
    }

    // drainPendingChunkScans: 每拍最多补采固定数量区块，把区块加载的采样开销摊平到多拍执行。
    private void drainPendingChunkScans(Level level) {
        if (level == null) {
            return;
        }
        for (int drained = 0; drained < CHUNK_LOAD_SCAN_BUDGET_PER_TICK; drained++) {
            Long packed = pendingChunkScans.poll();
            if (packed == null) {
                return;
            }
            pendingChunkKeys.remove(packed);
            scanLoadedChunk(level, ChunkPos.getX(packed), ChunkPos.getZ(packed), true);
        }
    }

    /**
     * onClientChunkUnloaded: 卸载后允许下次加载重新采样，建筑变化能反映到地图。
     */
    public void onClientChunkUnloaded(ChunkPos chunkPos) {
        if (chunkPos == null) {
            return;
        }
        scannedAtTick.remove(chunkPos.pack());
        pendingChunkKeys.remove(chunkPos.pack());
    }

    private void updateScope(String worldId, ResourceKey<Level> dimension) {
        String previousWorldId = currentWorldId;
        ResourceKey<Level> previousDimension = currentDimension;
        if (Objects.equals(worldId, previousWorldId) && Objects.equals(dimension, previousDimension)) {
            return;
        }

        if (previousWorldId != null && previousDimension != null) {
            boolean sameDimension = previousDimension.equals(dimension);
            if (SimuMapStorage.isResolvedWorldId(previousWorldId) || !sameDimension) {
                persistRegionsAsync(previousWorldId, previousDimension, List.copyOf(regions.values()), "world_or_dimension_change");
                LOGGER.info("Simukraft: Map scope changed from world={} dim={} to world={} dim={}, queued async save for {} regions.",
                        previousWorldId, SimuMapStorage.dimensionToDir(previousDimension),
                        worldId, SimuMapStorage.dimensionToDir(dimension), regions.size());
                regions.clear();
                renderingKeys.clear();
                scannedAtTick.clear();
                pendingChunkScans.clear();
                pendingChunkKeys.clear();
                clearRegionScopeCache();
                resetScanCursor();
            }
        } else if (previousDimension == null) {
            LOGGER.info("Simukraft: First dimension acquired: {}.", SimuMapStorage.dimensionToDir(dimension));
        }

        currentWorldId = worldId;
        currentDimension = dimension;
        if (SimuMapStorage.isResolvedWorldId(worldId)) {
            refreshAvailableRegions(worldId, dimension);
        }
    }

    private boolean shouldScanThisTick(boolean active) {
        int interval = active ? ACTIVE_SCAN_INTERVAL_TICKS : PASSIVE_SCAN_INTERVAL_TICKS;
        return tickCount % interval == 0;
    }

    private void autoSaveRegions() {
        if (currentWorldId == null || currentDimension == null || regions.isEmpty()) {
            return;
        }

        SimuMapStorage.saveAllAsync(currentWorldId, currentDimension,
                List.copyOf(regions.values()), "periodic_cache", false);
    }

    private void incrementalScan(int maxChunks) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null || maxChunks <= 0) {
            return;
        }

        int playerChunkX = player.chunkPosition().x();
        int playerChunkZ = player.chunkPosition().z();
        int scanRadiusNow = getEffectiveScanRadius();
        if (scanCursorRadius != scanRadiusNow) {
            resetScanCursor(scanRadiusNow);
        }

        int scanned = 0;
        int attempts = 0;
        int side = scanRadiusNow * 2 + 1;
        int maxAttempts = side * side;
        while (scanned < maxChunks && attempts < maxAttempts) {
            int chunkX = playerChunkX + scanCursorDX;
            int chunkZ = playerChunkZ + scanCursorDZ;

            advanceScanCursor(scanRadiusNow);
            attempts++;

            if (!SimuChunkScanner.isChunkLoaded(level, chunkX, chunkZ)) {
                continue;
            }

            if (scanLoadedChunk(level, chunkX, chunkZ, false)) {
                scanned++;
            }
        }
    }

    private boolean scanLoadedChunk(Level level, int chunkX, int chunkZ, boolean force) {
        ChunkAccess chunk = SimuChunkScanner.getLoadedChunk(level, chunkX, chunkZ);
        if (chunk == null) {
            return false;
        }
        return scanLoadedChunk(level, chunk, chunkX, chunkZ, force);
    }

    private boolean scanLoadedChunk(Level level, ChunkAccess chunk, int chunkX, int chunkZ, boolean force) {
        long chunkLong = ChunkPos.pack(chunkX, chunkZ);
        if (!force && !shouldScanChunk(chunkLong)) {
            return false;
        }
        SimuMapRegion region = getOrCreateRegion(chunkX >> 5, chunkZ >> 5);
        try {
            boolean scanned = SimuChunkScanner.scanChunk(level, chunk, chunkX, chunkZ, region);
            if (scanned) {
                scannedAtTick.put(chunkLong, tickCount);
            }
            return scanned;
        } catch (Exception e) {
            LOGGER.debug("Simukraft: Chunk scan failed for ({}, {}): {}", chunkX, chunkZ, e.getMessage());
            return false;
        }
    }

    /**
     * shouldScanChunk: 未扫描过的区块立刻采；地图打开时隔一段时间才重扫。
     */
    private boolean shouldScanChunk(long chunkLong) {
        Long lastScanTick = scannedAtTick.get(chunkLong);
        if (lastScanTick == null) {
            return true;
        }
        if (!hasActiveConsumer()) {
            return false;
        }
        return tickCount - lastScanTick >= ACTIVE_REFRESH_INTERVAL_TICKS;
    }

    private void resetScanCursor() {
        resetScanCursor(getEffectiveScanRadius());
    }

    private void resetScanCursor(int radius) {
        scanCursorRadius = radius;
        scanCursorDX = -radius;
        scanCursorDZ = -radius;
    }

    private void advanceScanCursor(int radius) {
        scanCursorDX++;
        if (scanCursorDX > radius) {
            scanCursorDX = -radius;
            scanCursorDZ++;
            if (scanCursorDZ > radius) {
                scanCursorDZ = -radius;
            }
        }
    }

    private void renderDirtyRegions() {
        int inFlight = MAX_REGION_BAKES_IN_FLIGHT - renderingKeys.size();
        if (inFlight <= 0) {
            return;
        }

        List<SimuMapRegion> dirty = new ArrayList<>();
        for (SimuMapRegion region : regions.values()) {
            // peekData 不刷新访问时间，否则每拍查脏会把闲置 region 一直钉在常驻集里。
            SimuMapRegionData data = region.peekData();
            if (data != null && data.isDirty()) {
                dirty.add(region);
            }
        }
        if (dirty.isEmpty()) {
            return;
        }

        int budget = Math.min(REGION_BAKE_BUDGET_PER_TICK, inFlight);
        if (dirty.size() > budget) {
            // 先烘排序焦点附近的，远处的留下一拍，避免整片脏区挤满后台队列。
            dirty.sort(Comparator.comparingDouble(this::bakeFocusDistanceSquared));
        }
        for (int index = 0; index < dirty.size() && index < budget; index++) {
            SimuMapRegion region = dirty.get(index);
            long key = regionKey(region.regionX, region.regionZ);
            if (!renderingKeys.add(key)) {
                continue;
            }

            renderExecutor.execute(() -> {
                try {
                    SimuMapRenderer.renderRegion(region);
                } catch (Exception e) {
                    LOGGER.error("Simukraft: Failed to render region {}", region, e);
                } finally {
                    renderingKeys.remove(key);
                }
            });
        }
    }

    private void releaseStaleRegions(long maxAge) {
        long now = System.currentTimeMillis();
        regions.entrySet().removeIf(entry -> {
            SimuMapRegion region = entry.getValue();
            if (now - region.getLastAccessTime() <= maxAge) {
                return false;
            }

            SimuMapRegionData data = region.peekData();
            if (data == null && !region.isImageLoaded()) {
                return true;
            }
            if (region.isImageLoaded()) {
                region.releaseTexture();
                if (data != null) {
                    data.markDirty();
                }
            }
            return false;
        });
        evictOverCapacity();
    }

    /**
     * evictOverCapacity: 常驻 region 超上限时，丢掉远处、久未使用且已经落盘的 region。
     * <p>没落盘的不碰，交给周期缓存写完后下一轮再淘汰；被丢的 region 下次进入扫描范围或视口时按需重读磁盘。
     */
    private void evictOverCapacity() {
        int excess = regions.size() - MAX_RESIDENT_REGIONS;
        if (excess <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        double minDistanceSquared = (double) EVICT_MIN_DISTANCE_BLOCKS * EVICT_MIN_DISTANCE_BLOCKS;
        Iterator<Map.Entry<Long, SimuMapRegion>> iterator = regions.entrySet().iterator();
        while (iterator.hasNext() && excess > 0) {
            Map.Entry<Long, SimuMapRegion> entry = iterator.next();
            SimuMapRegion region = entry.getValue();
            SimuMapRegionData data = region.peekData();
            if (data == null || data.needsSave()
                    || now - region.getLastAccessTime() < EVICT_MIN_AGE_MS
                    || region.distToPlayer() < minDistanceSquared) {
                continue;
            }
            region.releaseTexture();
            region.discardData();
            iterator.remove();
            scannedAtTick.keySet().removeIf(chunkKey ->
                    ChunkPos.getX(chunkKey) >> 5 == region.regionX && ChunkPos.getZ(chunkKey) >> 5 == region.regionZ);
            excess--;
        }
    }

    private void persistRegionsAsync(@Nullable String worldId, @Nullable ResourceKey<Level> dimension,
                                     List<SimuMapRegion> regionSnapshot, String reason) {
        if (regionSnapshot.isEmpty()) {
            return;
        }

        for (SimuMapRegion region : regionSnapshot) {
            region.releaseTexture();
        }

        if (SimuMapStorage.isResolvedWorldId(worldId) && dimension != null) {
            SimuMapStorage.saveAllAsync(worldId, dimension, regionSnapshot, reason, true);
            return;
        }

        for (SimuMapRegion region : regionSnapshot) {
            region.discardData();
        }
    }

    /**
     * refreshAvailableRegions: 只列一次目录，拿到磁盘上已有的 region 键；地形数据按需加载。
     * <p>旧实现进世界就把全部 region 读进内存，存档探索得越大，加入世界的 IO 和堆压力越离谱。
     */
    private void refreshAvailableRegions(@Nullable String worldId, @Nullable ResourceKey<Level> dimension) {
        if (!SimuMapStorage.isResolvedWorldId(worldId) || dimension == null) {
            return;
        }
        int scopeGeneration = loadGeneration;
        regionListTick = tickCount;
        SimuMapStorage.listRegionsAsync(worldId, dimension, keys -> Minecraft.getInstance().execute(() -> {
            if (!initialized || scopeGeneration != loadGeneration
                    || !Objects.equals(worldId, currentWorldId) || !Objects.equals(dimension, currentDimension)) {
                return;
            }
            availableRegionKeys = keys;
            LOGGER.info("Simukraft: Found {} cached map regions for world={} dim={}.",
                    keys.size(), worldId, SimuMapStorage.dimensionToDir(dimension));
        }));
    }

    /**
     * tickRegionScope: 目录到期重列，并补载玩家扫描范围内已经存在的磁盘 region。
     */
    private void tickRegionScope(LocalPlayer player) {
        if (regionListTick != Long.MIN_VALUE && tickCount - regionListTick >= REGION_LIST_REFRESH_TICKS) {
            refreshAvailableRegions(currentWorldId, currentDimension);
        }
        int chunkX = player.chunkPosition().x();
        int chunkZ = player.chunkPosition().z();
        int radius = getEffectiveScanRadius();
        requestRegionLoads((chunkX - radius) >> 5, (chunkX + radius) >> 5, (chunkZ - radius) >> 5, (chunkZ + radius) >> 5);
    }

    /**
     * requestRegionsInView: 地图界面把视口内的世界坐标矩形告知这里，缺的 region 按需从磁盘补载。
     * <p>每轴最多 32 个 region，超出部分不补，避免缩到极远时每帧扫几万格。
     */
    public void requestRegionsInView(int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ) {
        int minRegionX = minBlockX >> 9;
        int maxRegionX = maxBlockX >> 9;
        int minRegionZ = minBlockZ >> 9;
        int maxRegionZ = maxBlockZ >> 9;
        int centerRegionX = Math.floorDiv(minRegionX + maxRegionX, 2);
        int centerRegionZ = Math.floorDiv(minRegionZ + maxRegionZ, 2);
        int halfSpan = MAX_VIEW_REGION_SPAN / 2;
        requestRegionLoads(Math.max(minRegionX, centerRegionX - halfSpan),
                Math.min(maxRegionX, centerRegionX + halfSpan),
                Math.max(minRegionZ, centerRegionZ - halfSpan),
                Math.min(maxRegionZ, centerRegionZ + halfSpan));
    }

    private void requestRegionLoads(int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ) {
        String worldId = currentWorldId;
        ResourceKey<Level> dimension = currentDimension;
        if (!SimuMapStorage.isResolvedWorldId(worldId) || dimension == null) {
            return;
        }
        Set<Long> available = availableRegionKeys;
        if (available.isEmpty()) {
            return;
        }
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                long key = regionKey(regionX, regionZ);
                if (!available.contains(key)) {
                    continue;
                }
                SimuMapRegion resident = regions.get(key);
                if (resident != null) {
                    SimuMapRegionData residentData = resident.peekData();
                    // 已经采满的块不需要再读磁盘，否则跑图时每拍都会白读 2MB 文件。
                    if (residentData == null || residentData.isFullySampled()) {
                        continue;
                    }
                }
                // 满了就不再拉，等这拍淘汰出手位；请求下个 tick 或下一帧还会再来。
                if (regions.size() >= MAX_RESIDENT_REGIONS || loadingRegionKeys.size() >= MAX_REGION_LOADS_IN_FLIGHT) {
                    return;
                }
                if (loadingRegionKeys.add(key)) {
                    loadRegionFromFile(worldId, dimension, regionX, regionZ);
                }
            }
        }
    }

    private void loadRegionFromFile(String worldId, ResourceKey<Level> dimension, int regionX, int regionZ) {
        long key = regionKey(regionX, regionZ);
        int scopeGeneration = loadGeneration;
        SimuMapStorage.loadRegionAsync(worldId, dimension, regionX, regionZ, loaded -> {
            Minecraft.getInstance().execute(() -> {
                loadingRegionKeys.remove(key);
                if (!initialized || scopeGeneration != loadGeneration || loaded == null) {
                    return;
                }
                applyLoadedRegion(regionX, regionZ, loaded);
            });
        });
    }

    /**
     * applyLoadedRegion: 磁盘数据落地。扫描已经先把该 region 建过时只填还未知的格子，
     * 不能把玩家刚看到的新地形覆盖成旧快照。
     * <p>用 compute 保证与扫描里的 computeIfAbsent 互相不会吞掉对方。
     */
    private void applyLoadedRegion(int regionX, int regionZ, SimuMapRegionData loaded) {
        regions.compute(regionKey(regionX, regionZ), (key, existing) -> {
            if (existing != null) {
                SimuMapRegionData data = existing.peekData();
                if (data == null) {
                    existing.setData(loaded);
                    loaded.markDirty();
                } else {
                    data.mergeUnknownFrom(loaded);
                }
                return existing;
            }
            SimuMapRegion region = new SimuMapRegion(regionX, regionZ);
            region.setData(loaded);
            loaded.markDirty();
            return region;
        });
    }

    /**
     * clearRegionScopeCache: 切档、切维度和关服时释放目录快照与在途加载。
     */
    private void clearRegionScopeCache() {
        availableRegionKeys = Set.of();
        loadingRegionKeys.clear();
        regionListTick = Long.MIN_VALUE;
        loadGeneration++;
    }

    /**
     * beginTextureUploadWindow: 开启一次地图绘制窗口，限制本帧内同步上传的纹理数量。
     * <p>只在渲染线程调用。
     */
    public void beginTextureUploadWindow() {
        uploadWindowRemaining = TEXTURE_UPLOAD_BUDGET_PER_FRAME;
    }

    /**
     * tryAcquireTextureUploadSlot: 取一个本帧上传名额，只在渲染线程调用。
     */
    public boolean tryAcquireTextureUploadSlot() {
        if (uploadWindowRemaining > 0) {
            uploadWindowRemaining--;
            return true;
        }
        return false;
    }

    /**
     * regionKey: region 坐标到缓存键的打包。
     */
    private static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }
}
