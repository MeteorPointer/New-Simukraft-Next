package client.cn.nsimu.simukraft.client.buildbox;

import common.cn.nsimu.simukraft.SimuKraft;
import common.cn.nsimu.simukraft.building.BuildingCatalog;
import common.cn.nsimu.simukraft.building.BuildingPackageCatalog;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class BuildingCacheService {
    private static final Map<String, List<BuildingMeta>> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean initialized;
    private static volatile boolean prewarmScheduled;

    private BuildingCacheService() {
    }

    public static void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (BuildingCacheService.class) {
            if (initialized) {
                return;
            }
            reload();
        }
    }

    /**
     * prewarmAsync: 将首次扫描 zip 建筑包提交到后台线程，避免 LoggingIn 在 Render 线程同步 reload 卡住画面。
     * <p>后续 getBuildings() 会走 ensureInitialized，同锁下等后台完成；UI 打开时能接受短暂等待，而不是进世界瞬间卡。
     */
    public static void prewarmAsync() {
        if (initialized || prewarmScheduled) {
            return;
        }
        prewarmScheduled = true;
        CompletableFuture.runAsync(() -> {
            try {
                ensureInitialized();
            } catch (RuntimeException exception) {
                prewarmScheduled = false;
                SimuKraft.LOGGER.warn("Simukraft: Async building cache prewarm failed", exception);
            }
        });
    }

    /**
     * reload: 重新扫描 simukraftbuilding 下的 zip 建筑包。
     */
    public static void reload() {
        synchronized (BuildingCacheService.class) {
            BuildingCatalog.reload();
            CACHE.clear();
            for (String category : BuildingPackageCatalog.categories()) {
                CACHE.put(category, BuildingCatalog.listBuildings(category).stream()
                        .map(BuildingCacheService::toMeta)
                        .toList());
            }
            initialized = true;
            SimuKraft.LOGGER.info("Simukraft: Reloaded building cache from {}", rootDirectory());
        }
    }

    public static List<BuildingMeta> getBuildings(String category) {
        ensureInitialized();
        return CACHE.getOrDefault(BuildingPackageCatalog.normalizeCategory(category), List.of());
    }

    public static Path rootDirectory() {
        return BuildingCatalog.rootDirectory();
    }

    public static String rootDirectoryText() {
        return BuildingCatalog.rootDirectoryText();
    }

    private static BuildingMeta toMeta(BuildingCatalog.BuildingDefinition definition) {
        return new BuildingMeta(
                definition.category(),
                definition.localizedDisplayName(),
                definition.size(),
                definition.localizedAmount(),
                definition.author(),
                definition.localizedDescription(),
                definition.unlockLevel(),
                definition.metaFileName(),
                definition.structureFileName(),
                definition.packageName()
        );
    }

    public record BuildingMeta(String category,
                               String name,
                               String size,
                               String amount,
                               String author,
                               String description,
                               int unlockLevel,
                               String metaFileName,
                               String structureFileName,
                               String packageName) {
    }
}
