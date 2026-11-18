package client.cn.nsimu.simukraft.client.city.map;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * 地图渲染器。
 * 把 {@link SimuMapRegionData} 的颜色和高度烘到 {@link NativeImage}。
 * 只重烘标脏的 chunk，并且西北坡度明暗按邻格高度取值，避免一个区块变化就重画整幅 26 万像素。
 */
public class SimuMapRenderer {

    private static float shadowStrength = 1.0f;
    private static float noiseStrength = 0.03f;
    private static boolean drawChunkGrid = false;
    private static final int GRID_COLOR = 0x28464646;
    /**
     * 烘一格时需要读到西侧和北侧的邻格，所以取样窗口每边多带一格。
     */
    private static final int HALO_SIDE = 16 + 2;
    private static final int HALO_AREA = HALO_SIDE * HALO_SIDE;

    private SimuMapRenderer() {
    }

    public static void setShadowStrength(float v) {
        shadowStrength = v;
    }

    public static void setNoiseStrength(float v) {
        noiseStrength = v;
    }

    public static void setDrawChunkGrid(boolean v) {
        drawChunkGrid = v;
    }

    /**
     * 渲染一个 region 到 NativeImage。
     * 该方法在后台渲染线程调用，结果写入 region 的图像缓存。
     */
    public static void renderRegion(SimuMapRegion region) {
        SimuMapRegionData data = region.peekData();
        if (data == null || data.isEmpty()) {
            return;
        }

        NativeImage image = region.getOrCreateImage();
        int[] dirtyChunks = data.takeDirtyChunks();
        if (region.takeFullBakeRequest()) {
            // 图像刚重建，只烘脏 chunk 会留下透明空洞。
            dirtyChunks = null;
        }

        ScratchBuffer scratch = new ScratchBuffer();
        if (dirtyChunks == null) {
            for (int chunkZ = 0; chunkZ < SimuMapRegionData.CHUNK_SIDE; chunkZ++) {
                for (int chunkX = 0; chunkX < SimuMapRegionData.CHUNK_SIDE; chunkX++) {
                    bakeChunk(region, data, image, chunkX, chunkZ, scratch);
                }
            }
        } else {
            for (int chunk : dirtyChunks) {
                bakeChunk(region, data, image, chunk % SimuMapRegionData.CHUNK_SIDE,
                        chunk / SimuMapRegionData.CHUNK_SIDE, scratch);
            }
        }

        region.markTextureNeedsUpload();
    }

    /**
     * bakeChunk: 只重烘 region 内一个 16x16 的 chunk 矩形。
     */
    private static void bakeChunk(SimuMapRegion region, SimuMapRegionData data, NativeImage image,
                                  int chunkX, int chunkZ, ScratchBuffer scratch) {
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        if (baseX >= SimuMapRegionData.SIZE || baseZ >= SimuMapRegionData.SIZE) {
            return;
        }

        // 临界区只覆盖 18x18 的小窗口拷贝，主线程采样不会被整幅数组复制挡住。
        synchronized (data) {
            for (int haloZ = 0; haloZ < HALO_SIDE; haloZ++) {
                int localZ = clamp(baseZ + haloZ - 1);
                for (int haloX = 0; haloX < HALO_SIDE; haloX++) {
                    int localX = clamp(baseX + haloX - 1);
                    int source = localX + localZ * SimuMapRegionData.SIZE;
                    int target = haloX + haloZ * HALO_SIDE;
                    scratch.heights[target] = data.height[source];
                    scratch.colors[target] = data.color[source];
                    scratch.flags[target] = data.flags[source];
                }
            }
        }

        int regWX = region.regionX * SimuMapRegionData.SIZE;
        int regWZ = region.regionZ * SimuMapRegionData.SIZE;
        int written = 0;
        for (int localZ = 0; localZ < 16; localZ++) {
            int worldZ = baseZ + localZ;
            if (worldZ >= SimuMapRegionData.SIZE) {
                break;
            }
            for (int localX = 0; localX < 16; localX++) {
                int worldX = baseX + localX;
                if (worldX >= SimuMapRegionData.SIZE) {
                    break;
                }
                int self = (localZ + 1) * HALO_SIDE + (localX + 1);
                scratch.pixels[written++] = bakePixel(region, scratch, self, worldX, worldZ);
            }
        }

        synchronized (region) {
            if (image.isClosed()) {
                return;
            }
            int index = 0;
            for (int localZ = 0; localZ < 16; localZ++) {
                if (baseZ + localZ >= SimuMapRegionData.SIZE) {
                    break;
                }
                for (int localX = 0; localX < 16; localX++) {
                    if (baseX + localX >= SimuMapRegionData.SIZE) {
                        break;
                    }
                    image.setPixel(baseX + localX, baseZ + localZ, scratch.pixels[index++]);
                }
            }
        }
    }

    /**
     * bakePixel: 按西北高差算明暗，水面单独降对比，避免把草地画成亮绿色色块。
     */
    private static int bakePixel(SimuMapRegion region, ScratchBuffer scratch, int self, int worldX, int worldZ) {
        short height = scratch.heights[self];
        if (height == SimuMapRegionData.HEIGHT_UNKNOWN) {
            return 0;
        }

        int argb = scratch.colors[self];
        boolean isWater = (scratch.flags[self] & 1) != 0;

        if (shadowStrength > 0) {
            short heightN = scratch.heights[self - HALO_SIDE];
            short heightW = scratch.heights[self - 1];
            if (heightN == SimuMapRegionData.HEIGHT_UNKNOWN) {
                heightN = height;
            }
            if (heightW == SimuMapRegionData.HEIGHT_UNKNOWN) {
                heightW = height;
            }
            float brightness = SimuBlockColors.slopeBrightness(height, heightN, heightW, isWater)
                    * shadowStrength;
            if (noiseStrength > 0) {
                long seed = (long) (region.regionX * SimuMapRegionData.SIZE + worldX) * 31
                        + (region.regionZ * SimuMapRegionData.SIZE + worldZ);
                float noise = ((seed * 6364136223846793005L + 1442695040888963407L) >> 33 & 0xFF) / 255f;
                brightness += (noise - 0.5f) * noiseStrength;
            }
            if (brightness != 0) {
                argb = SimuBlockColors.adjustBrightness(argb, brightness);
            }
        }

        if (drawChunkGrid && (worldX % 16 == 0 || worldZ % 16 == 0)) {
            argb = SimuBlockColors.blendColors(argb, GRID_COLOR);
        }
        return argb;
    }

    /**
     * clamp: 把邻格索引折回 region 边界，边界外的明暗按原格处理。
     */
    private static int clamp(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, SimuMapRegionData.SIZE - 1);
    }

    /**
     * ScratchBuffer: 一次烘图复用的取样窗口，只在后台渲染线程使用。
     */
    private static final class ScratchBuffer {
        private final short[] heights = new short[HALO_AREA];
        private final int[] colors = new int[HALO_AREA];
        private final short[] flags = new short[HALO_AREA];
        private final int[] pixels = new int[16 * 16];
    }

    /**
     * 在 region 图像上绘制城市 chunk 边框叠加层。
     * 只绘制外框，不填充领地颜色。
     *
     * @param region          目标 region
     * @param chunkX          chunk X 坐标
     * @param chunkZ          chunk Z 坐标
     * @param borderColor     ARGB 边框颜色
     * @param borderThickness 边框厚度，单位为像素
     * @param drawTop         是否绘制上边框
     * @param drawBottom      是否绘制下边框
     * @param drawLeft        是否绘制左边框
     * @param drawRight       是否绘制右边框
     */
    public static void drawChunkBorder(SimuMapRegion region, int chunkX, int chunkZ,
                                       int borderColor, int borderThickness,
                                       boolean drawTop, boolean drawBottom,
                                       boolean drawLeft, boolean drawRight) {
        NativeImage image;
        int localX = (chunkX - region.regionX * 32) * 16;
        int localZ = (chunkZ - region.regionZ * 32) * 16;

        if (localX < 0 || localX + 16 > 512 || localZ < 0 || localZ + 16 > 512) return;

        // 与烘图和纹理上传共用 region 锁，避免读写到已经释放的图像。
        synchronized (region) {
            image = region.getOrCreateImage();
            if (image.isClosed()) {
                return;
            }
            for (int t = 0; t < borderThickness; t++) {
                if (drawTop) {
                    for (int bx = localX; bx < localX + 16; bx++) {
                        image.setPixel(bx, localZ + t,
                                SimuBlockColors.blendColors(image.getPixel(bx, localZ + t), borderColor));
                    }
                }
                if (drawBottom) {
                    for (int bx = localX; bx < localX + 16; bx++) {
                        image.setPixel(bx, localZ + 15 - t,
                                SimuBlockColors.blendColors(image.getPixel(bx, localZ + 15 - t), borderColor));
                    }
                }
                if (drawLeft) {
                    for (int bz = localZ; bz < localZ + 16; bz++) {
                        image.setPixel(localX + t, bz,
                                SimuBlockColors.blendColors(image.getPixel(localX + t, bz), borderColor));
                    }
                }
                if (drawRight) {
                    for (int bz = localZ; bz < localZ + 16; bz++) {
                        image.setPixel(localX + 15 - t, bz,
                                SimuBlockColors.blendColors(image.getPixel(localX + 15 - t, bz), borderColor));
                    }
                }
            }
        }
    }
}
