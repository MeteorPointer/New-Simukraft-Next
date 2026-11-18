package common.cn.nsimu.simukraft.virtualvein;

import com.mojang.datafixers.util.Pair;
import common.cn.nsimu.simukraft.SimuKraft;
import common.cn.nsimu.simukraft.mixin.MixinMultiNoiseBiomeSourceAccessor;
import common.cn.nsimu.simukraft.storage.SimuSqliteStorage;
import common.cn.nsimu.simukraft.util.SaveScopedCacheKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * VirtualVeinService: 解析矿区、生成档案并提供未来钻机查询。
 */

public final class VirtualVeinService {
    private static final int RESERVE_MIN = 4_000;
    private static final int RESERVE_MAX = 200_000;
    private static final int MAX_FIELD_CACHE_SIZE = 4096;
    // 区块建档改为限流：进世界时成批区块不能在同一帧各做一次气候采样和数据库往返。
    private static final int FIELD_INIT_CHUNK_BUDGET_PER_TICK = 8;
    private static final int MAX_PENDING_FIELD_INIT_CHUNKS = 4096;
    /** 按最近使用淘汰的矿区缓存；旧实现满 512 就整体清空，跑图时会反复回落数据库。 */
    private static final LinkedHashMap<CacheKey, VirtualVeinFieldProfile> FIELD_CACHE =
            new LinkedHashMap<>(256, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, VirtualVeinFieldProfile> eldest) {
                    return size() > MAX_FIELD_CACHE_SIZE;
                }
            };
    // 待建档区块队列（打包坐标），只在服务端主线程消费；超限丢弃，由玩家实际查询时按需建档兜底。
    private static final Queue<Long> PENDING_FIELD_INIT_CHUNKS = new ConcurrentLinkedQueue<>();
    private static final Set<Long> PENDING_FIELD_INIT_KEYS = ConcurrentHashMap.newKeySet();
    /** 气候采样器、群系解析器和气候参数表按存档加维度长期复用；26.3 每次 createClimateSampler 都是新建对象。 */
    private static final ConcurrentMap<String, ClimateAccess> CLIMATE_ACCESS_CACHE = new ConcurrentHashMap<>();

    private VirtualVeinService() {
    }

    /**
     * getOrCreateField: 查询或首次建立位置所属的矿区档案。
     */
    public static VirtualVeinLookupResult getOrCreateField(ServerLevel level, BlockPos position) {
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return new VirtualVeinLookupResult(VirtualVeinLookupStatus.NOT_OVERWORLD, null);
        }
        ClimateAccess access = climateAccess(level);
        if (access == null) {
            return new VirtualVeinLookupResult(VirtualVeinLookupStatus.UNSUPPORTED_WORLDGEN, null);
        }
        VirtualVeinFieldKey key = resolveFieldKey(level, position, access);
        if (key == null) {
            return new VirtualVeinLookupResult(VirtualVeinLookupStatus.UNSUPPORTED_WORLDGEN, null);
        }
        CacheKey cacheKey = cacheKeyOf(level, key);
        VirtualVeinFieldProfile cached = cachedField(cacheKey);
        if (cached != null) {
            return new VirtualVeinLookupResult(VirtualVeinLookupStatus.READY, cached);
        }
        Optional<VirtualVeinFieldProfile> existing = SimuSqliteStorage.findVirtualVeinField(level, key);
        if (existing.isPresent()) {
            VirtualVeinFieldProfile storedProfile = existing.get();
            List<VirtualVeinDefinition> definitions = VirtualVeinDefinitionLoader.INSTANCE.definitions();
            if (storedProfile.slots().isEmpty() && !definitions.isEmpty() && targetCount(level.getSeed(), key) > 0) {
                FieldClimate fieldClimate = sampleClimate(level, position, key, access);
                if (fieldClimate != null) {
                    Optional<VirtualVeinFieldProfile> repaired = SimuSqliteStorage.repairLegacyVirtualVeinField(
                            level,
                            generateProfile(level, key, fieldClimate, definitions)
                    );
                    if (repaired.isPresent()) {
                        storedProfile = repaired.get();
                    }
                }
            }
            cache(cacheKey, storedProfile);
            return new VirtualVeinLookupResult(VirtualVeinLookupStatus.READY, storedProfile);
        }
        List<VirtualVeinDefinition> definitions = VirtualVeinDefinitionLoader.INSTANCE.definitions();
        if (definitions.isEmpty()) {
            return new VirtualVeinLookupResult(VirtualVeinLookupStatus.DEFINITIONS_UNAVAILABLE, null);
        }
        FieldClimate fieldClimate = sampleClimate(level, position, key, access);
        if (fieldClimate == null) {
            return new VirtualVeinLookupResult(VirtualVeinLookupStatus.UNSUPPORTED_WORLDGEN, null);
        }
        VirtualVeinFieldProfile generated = generateProfile(level, key, fieldClimate, definitions);
        if (!SimuSqliteStorage.canPersistVirtualVeinField(level)) {
            return new VirtualVeinLookupResult(VirtualVeinLookupStatus.DATABASE_UNAVAILABLE, null);
        }
        // 建档只排进写队列：主线程不再阻塞等写线程。储量仍以数据库为准，扣减走 consume 的原子读改写。
        SimuSqliteStorage.persistVirtualVeinFieldIfAbsent(level, generated);
        cache(cacheKey, generated);
        return new VirtualVeinLookupResult(VirtualVeinLookupStatus.READY, generated);
    }

    /**
     * enqueueFieldInitChunk: 区块加载只排队建档，不在区块风暴那一帧做重活。
     */
    public static void enqueueFieldInitChunk(ServerLevel level, ChunkPos chunkPos) {
        if (level == null || chunkPos == null || !level.dimension().equals(Level.OVERWORLD)) {
            return;
        }
        // 去重集合的规模就是队列上限判据，ConcurrentHashMap.size() 是 O(1)，不能每次扫链表。
        if (PENDING_FIELD_INIT_KEYS.size() >= MAX_PENDING_FIELD_INIT_CHUNKS) {
            return;
        }
        long packed = chunkPos.pack();
        if (PENDING_FIELD_INIT_KEYS.add(packed)) {
            PENDING_FIELD_INIT_CHUNKS.add(packed);
        }
    }

    /**
     * tick: 每拍按固定预算补建待建档矿区，单块失败只记日志不能拖住整个 tick。
     */
    public static void tick(MinecraftServer server) {
        if (server == null || PENDING_FIELD_INIT_CHUNKS.isEmpty()) {
            return;
        }
        ServerLevel level = server.overworld();
        if (level == null) {
            clearFieldInitQueue();
            return;
        }
        for (int processed = 0; processed < FIELD_INIT_CHUNK_BUDGET_PER_TICK; processed++) {
            Long packed = PENDING_FIELD_INIT_CHUNKS.poll();
            if (packed == null) {
                return;
            }
            PENDING_FIELD_INIT_KEYS.remove(packed);
            int chunkX = ChunkPos.getX(packed);
            int chunkZ = ChunkPos.getZ(packed);
            try {
                getOrCreateField(level, new BlockPos(chunkX * 16 + 8, level.getSeaLevel(), chunkZ * 16 + 8));
            } catch (Exception exception) {
                SimuKraft.LOGGER.warn("Simukraft: virtual vein field init skipped for chunk ({}, {})", chunkX, chunkZ, exception);
            }
        }
    }

    private static void clearFieldInitQueue() {
        PENDING_FIELD_INIT_CHUNKS.clear();
        PENDING_FIELD_INIT_KEYS.clear();
    }

    /**
     * findVeinsAtY: 返回目标高度可供钻机选择的矿脉槽位。
     */
    public static List<VirtualVeinLocatedSlot> findVeinsAtY(ServerLevel level, BlockPos position) {
        VirtualVeinLookupResult lookup = getOrCreateField(level, position);
        if (!lookup.isReady()) {
            return List.of();
        }
        List<VirtualVeinLocatedSlot> matches = new ArrayList<>();
        List<VirtualVeinSlot> slots = lookup.profile().slots();
        for (int index = 0; index < slots.size(); index++) {
            VirtualVeinSlot slot = slots.get(index);
            if (slot.state() == VirtualVeinSlotState.ACTIVE && slot.acceptsY(position.getY())) {
                matches.add(new VirtualVeinLocatedSlot(index, slot));
            }
        }
        return List.copyOf(matches);
    }

    /**
     * consume: 原子扣减未来钻机指定矿脉的共享储量。
     */
    public static Optional<VirtualVeinConsumption> consume(ServerLevel level, BlockPos position, String veinId, int amount) {
        VirtualVeinLookupResult lookup = getOrCreateField(level, position);
        if (!lookup.isReady() || amount <= 0) {
            return Optional.empty();
        }
        List<VirtualVeinSlot> slots = lookup.profile().slots();
        for (int index = 0; index < slots.size(); index++) {
            VirtualVeinSlot slot = slots.get(index);
            if (slot.veinId().equals(veinId) && slot.acceptsY(position.getY())) {
                Optional<VirtualVeinConsumption> consumption = SimuSqliteStorage.consumeVirtualVein(level, lookup.profile().key(), index, amount);
                consumption.ifPresent(ignored -> invalidateField(cacheKeyOf(level, lookup.profile().key())));
                return consumption;
            }
        }
        return Optional.empty();
    }

    /**
     * cacheKeyOf: 组成当前存档、维度和矿区格子的缓存键。
     */
    private static CacheKey cacheKeyOf(ServerLevel level, VirtualVeinFieldKey key) {
        return new CacheKey(worldId(level.getServer()), level.dimension(), key.cellX(), key.cellZ(), key.biomeId());
    }

    /**
     * clearCachedFields: 数据包重载后清理矿区缓存和气候采样复用，群系参数表可能已经变了。
     */
    public static void clearCachedFields() {
        synchronized (FIELD_CACHE) {
            FIELD_CACHE.clear();
        }
        CLIMATE_ACCESS_CACHE.clear();
    }

    /**
     * clearServerCache: 服务端关闭时释放该存档的矿区缓存、气候采样复用和待建档队列。
     */
    public static void clearServerCache(MinecraftServer server) {
        clearFieldInitQueue();
        if (server == null) {
            return;
        }
        String worldId = worldId(server);
        synchronized (FIELD_CACHE) {
            FIELD_CACHE.keySet().removeIf(key -> key.worldId().equals(worldId));
        }
        // 复用键是“存档根目录|维度”，前缀就是该存档。
        CLIMATE_ACCESS_CACHE.keySet().removeIf(key -> key.startsWith(worldId + "|"));
    }

    @Nullable
    private static VirtualVeinFieldProfile cachedField(CacheKey key) {
        synchronized (FIELD_CACHE) {
            return FIELD_CACHE.get(key);
        }
    }

    private static void cache(CacheKey key, VirtualVeinFieldProfile profile) {
        synchronized (FIELD_CACHE) {
            FIELD_CACHE.put(key, profile);
        }
    }

    private static void invalidateField(CacheKey key) {
        synchronized (FIELD_CACHE) {
            FIELD_CACHE.remove(key);
        }
    }

    private static VirtualVeinFieldProfile generateProfile(ServerLevel level,
                                                           VirtualVeinFieldKey key,
                                                           FieldClimate fieldClimate,
                                                           List<VirtualVeinDefinition> definitions) {
        List<VirtualVeinDefinition> candidates = fieldClimate.parameterPoint() == null
                ? List.of()
                : selectCandidates(definitions, fieldClimate.parameterPoint());
        int targetCount = targetCount(level.getSeed(), key);
        int selectedCount = Math.min(targetCount, candidates.size());
        List<VirtualVeinSlot> slots = new ArrayList<>(selectedCount);
        for (int index = 0; index < selectedCount; index++) {
            VirtualVeinDefinition definition = candidates.get(index);
            int amount = VirtualVeinFieldResolver.inclusiveInt(
                    VirtualVeinFieldResolver.seededValue(level.getSeed(), key, definition.id() + ":amount"),
                    definition.minAmount(),
                    definition.maxAmount()
            );
            int reserve = VirtualVeinFieldResolver.inclusiveInt(
                    VirtualVeinFieldResolver.seededValue(level.getSeed(), key, definition.id() + ":reserve"),
                    RESERVE_MIN,
                    RESERVE_MAX
            );
            slots.add(new VirtualVeinSlot(
                    definition.id(),
                    definition.displayName(),
                    definition.productId(),
                    definition.minY(),
                    definition.maxY(),
                    amount,
                    definition.periodTicks(),
                    reserve,
                    reserve,
                    VirtualVeinSlotState.ACTIVE
            ));
        }
        return new VirtualVeinFieldProfile(
                level.dimension().identifier().toString(),
                key,
                key.biomeId(),
                level.getGameTime(),
                slots
        );
    }

    /**
     * targetCount: 按三成空、四成一条、三成两条的概率决定矿区矿脉数量。
     */
    static int targetCount(long worldSeed, VirtualVeinFieldKey key) {
        double value = VirtualVeinFieldResolver.unit(VirtualVeinFieldResolver.seededValue(worldSeed, key, "count"));
        if (value < 0.30D) {
            return 0;
        }
        return value < 0.70D ? 1 : 2;
    }

    /**
     * selectCandidates: 使用六项气候参数点、优先级和稳定 ID 筛选至多两种候选矿脉。
     */
    static List<VirtualVeinDefinition> selectCandidates(List<VirtualVeinDefinition> definitions, Climate.ParameterPoint parameterPoint) {
        return definitions.stream()
                .filter(definition -> definition.matches(parameterPoint))
                .sorted(Comparator.comparingInt(VirtualVeinDefinition::priority).reversed().thenComparing(VirtualVeinDefinition::id))
                .limit(2)
                .toList();
    }

    /**
     * sampleClimate: 用当前世界多重噪声表匹配群系自己的气候点，创建矿区快照。
     */
    @Nullable
    private static FieldClimate sampleClimate(ServerLevel level, BlockPos position, VirtualVeinFieldKey key, ClimateAccess access) {
        int quartX = QuartPos.fromBlock(position.getX());
        int quartY = QuartPos.fromBlock(level.getSeaLevel());
        int quartZ = QuartPos.fromBlock(position.getZ());
        Climate.TargetPoint target = access.sampler().sample(quartX, quartY, quartZ);
        List<Pair<Climate.ParameterPoint, String>> parameters = access.parameterEntries(level);
        Climate.ParameterPoint parameterPoint = findNearestUsableParameterPoint(target, key.biomeId(), parameters);
        if (parameterPoint == null) {
            // 海平面采到洞穴群系时，只在同一张世界参数表里找最近的可用点，不再换成原版预设。
            parameterPoint = findNearestUsableParameterPoint(target, null, parameters);
        }
        if (parameterPoint == null) {
            return null;
        }
        return new FieldClimate(parameterPoint);
    }

    /**
     * resolveFieldKey: 按当前位置噪声群系将空间矿区切分为独立档案。
     */
    @Nullable
    private static VirtualVeinFieldKey resolveFieldKey(ServerLevel level, BlockPos position, ClimateAccess access) {
        int quartX = QuartPos.fromBlock(position.getX());
        int quartY = QuartPos.fromBlock(level.getSeaLevel());
        int quartZ = QuartPos.fromBlock(position.getZ());
        String biomeId = biomeId(level.registryAccess(), access.resolver().getNoiseBiome(quartX, quartY, quartZ));
        return VirtualVeinFieldResolver.resolve(level.getSeed(), position.getX(), position.getZ(), biomeId);
    }

    /**
     * climateAccess: 按存档加维度长期复用气候采样器、群系解析器和参数表。
     * <p>26.3 的 createClimateSampler 每次调用都会新建采样器，且 EMPTY_UNCACHED 会关掉密度函数缓存，
     * 不能放回每区块一次的调用里；这里只在 RandomState 变化时重建一次。
     * <p>只在服务端主线程访问。
     */
    @Nullable
    private static ClimateAccess climateAccess(ServerLevel level) {
        RandomState randomState = level.getChunkSource().randomState();
        String key = SaveScopedCacheKey.levelKey(level);
        ClimateAccess cached = CLIMATE_ACCESS_CACHE.get(key);
        if (cached != null && cached.randomState() == randomState) {
            return cached;
        }
        try {
            SamplerContext samplerContext = SamplerContext.builder().enableCaches().build();
            Climate.Sampler sampler = randomState.createClimateSampler(samplerContext);
            BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
            BiomeResolver resolver = biomeSource.createResolver(sampler);
            ClimateAccess access = new ClimateAccess(randomState, sampler, resolver);
            CLIMATE_ACCESS_CACHE.put(key, access);
            return access;
        } catch (RuntimeException exception) {
            SimuKraft.LOGGER.warn("Simukraft: climate sampling unavailable for dimension {}", level.dimension().identifier(), exception);
            return null;
        }
    }

    /**
     * buildParameterEntries: 优先读取世界 MultiNoiseBiomeSource 的运行时表，否则回退原版预设。
     */
    private static List<Pair<Climate.ParameterPoint, String>> buildParameterEntries(ServerLevel level) {
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> parameters = worldClimateParameters(level);
        List<Pair<Climate.ParameterPoint, String>> named = new ArrayList<>(parameters.size());
        for (Pair<Climate.ParameterPoint, Holder<Biome>> parameter : parameters) {
            named.add(Pair.of(parameter.getFirst(), biomeId(level.registryAccess(), parameter.getSecond())));
        }
        return named;
    }

    /**
     * worldClimateParameters: 从当前维度群系源取出含模组群系的参数点列表。
     */
    private static List<Pair<Climate.ParameterPoint, Holder<Biome>>> worldClimateParameters(ServerLevel level) {
        var biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        if (biomeSource instanceof MixinMultiNoiseBiomeSourceAccessor accessor) {
            return accessor.simukraft$parameters().values();
        }
        return level.registryAccess()
                .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getValueOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)
                .parameters()
                .values();
    }

    /**
     * findNearestUsableParameterPoint: 在指定群系或全部可用参数点中按原版距离公式取最近点。
     */
    @Nullable
    static Climate.ParameterPoint findNearestUsableParameterPoint(Climate.TargetPoint target,
                                                                  @Nullable String requiredBiomeId,
                                                                  List<Pair<Climate.ParameterPoint, String>> parameters) {
        Climate.ParameterPoint nearest = null;
        long nearestFitness = Long.MAX_VALUE;
        for (Pair<Climate.ParameterPoint, String> parameter : parameters) {
            String biomeId = parameter.getSecond();
            if (requiredBiomeId != null && !requiredBiomeId.equals(biomeId)) {
                continue;
            }
            if (!isUsableVeinParameter(parameter.getFirst(), biomeId)) {
                continue;
            }
            long fitness = parameterPointFitness(parameter.getFirst(), target);
            if (fitness < nearestFitness) {
                nearestFitness = fitness;
                nearest = parameter.getFirst();
            }
        }
        return nearest;
    }

    /**
     * isUsableVeinParameter: 原版洞穴/深暗不可用；原版地表仍要求 Depth 0/1；模组群系接受其自身 Depth 区间。
     */
    static boolean isUsableVeinParameter(Climate.ParameterPoint parameterPoint, String biomeId) {
        if (isVanillaUndergroundBiomeId(biomeId)) {
            return false;
        }
        if (isSurfaceParameterPoint(parameterPoint)) {
            return true;
        }
        return biomeId != null && !biomeId.startsWith("minecraft:");
    }

    /**
     * isVanillaUndergroundBiomeId: 原版洞穴与深暗之域不单独建矿区。
     */
    static boolean isVanillaUndergroundBiomeId(String biomeId) {
        return "minecraft:lush_caves".equals(biomeId)
                || "minecraft:dripstone_caves".equals(biomeId)
                || "minecraft:deep_dark".equals(biomeId);
    }

    /**
     * isSurfaceParameterPoint: 识别原版地表 Depth 单点（0 或 1）。
     */
    static boolean isSurfaceParameterPoint(Climate.ParameterPoint parameterPoint) {
        long depth = parameterPoint.depth().min();
        return depth == parameterPoint.depth().max()
                && (depth == Climate.quantizeCoord(0.0F) || depth == Climate.quantizeCoord(1.0F));
    }

    private static String biomeId(RegistryAccess registryAccess, Holder<Biome> biome) {
        return biome.unwrapKey()
                .map(ResourceKey::identifier)
                .map(Object::toString)
                .orElseGet(() -> Optional.ofNullable(registryAccess.lookupOrThrow(Registries.BIOME).getKey(biome.value()))
                        .map(Object::toString)
                        .orElse("minecraft:unknown"));
    }

    /**
     * parameterPointFitness: 使用原版 Climate.ParameterPoint 的距离公式比较候选参数点。
     */
    private static long parameterPointFitness(Climate.ParameterPoint parameterPoint, Climate.TargetPoint target) {
        return square(parameterPoint.temperature().distance(target.temperature()))
                + square(parameterPoint.humidity().distance(target.humidity()))
                + square(parameterPoint.continentalness().distance(target.continentalness()))
                + square(parameterPoint.erosion().distance(target.erosion()))
                + square(parameterPoint.depth().distance(target.depth()))
                + square(parameterPoint.weirdness().distance(target.weirdness()))
                + square(parameterPoint.offset());
    }

    private static long square(long value) {
        return value * value;
    }

    private static String worldId(MinecraftServer server) {
        return SaveScopedCacheKey.serverKey(server);
    }

    private record CacheKey(String worldId, ResourceKey<Level> dimension, int cellX, int cellZ, String biomeId) {
    }

    private record FieldClimate(Climate.ParameterPoint parameterPoint) {
    }

    /**
     * ClimateAccess: 同一个存档同一维度内长期复用的气候采样入口。
     * <p>只在服务端主线程访问，因此参数表采用惰性填充，不再每区块重建。
     */
    private static final class ClimateAccess {
        private final RandomState randomState;
        private final Climate.Sampler sampler;
        private final BiomeResolver resolver;
        @Nullable
        private List<Pair<Climate.ParameterPoint, String>> parameterEntries;

        private ClimateAccess(RandomState randomState, Climate.Sampler sampler, BiomeResolver resolver) {
            this.randomState = randomState;
            this.sampler = sampler;
            this.resolver = resolver;
        }

        private RandomState randomState() {
            return randomState;
        }

        private Climate.Sampler sampler() {
            return sampler;
        }

        private BiomeResolver resolver() {
            return resolver;
        }

        private List<Pair<Climate.ParameterPoint, String>> parameterEntries(ServerLevel level) {
            if (parameterEntries == null) {
                parameterEntries = buildParameterEntries(level);
            }
            return parameterEntries;
        }
    }
}
