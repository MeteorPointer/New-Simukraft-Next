package client.cn.nsimu.simukraft.client.city.map;

import java.util.Arrays;

/**
 * 单个地图 region 的地形数据。
 * 一个 region 覆盖 512x512 方块，即 32x32 个 chunk。
 */
public class SimuMapRegionData {

    public static final int SIZE = 512;
    public static final int AREA = SIZE * SIZE;
    /**
     * 一个 region 边长上的 chunk 数量。
     */
    public static final int CHUNK_SIDE = SIZE / 16;
    public static final int CHUNKS = CHUNK_SIDE * CHUNK_SIDE;
    public static final short HEIGHT_UNKNOWN = Short.MIN_VALUE;

    /**
     * 最高非空气方块高度。
     */
    public final short[] height = new short[AREA];

    /**
     * 每个方块位置对应的 ARGB 渲染颜色。
     */
    public final int[] color = new int[AREA];

    /**
     * 标志位：bit0=水面。
     * 光照位已停用：烘图只用高度和颜色，但昼夜光照会让整块 region 持续标脏重烘，白耗 CPU 和显存上传。
     */
    public final short[] flags = new short[AREA];

    /**
     * 每个 chunk 是否需要重新烘图。旧实现只有一个 region 级脏标记，一个区块变化就重画 26 万像素。
     */
    private final boolean[] chunkDirty = new boolean[CHUNKS];

    private int dirtyChunkCount;

    /**
     * 整块重绘标记，用于首次烘图、方块图集重建和磁盘加载后的补烘。
     */
    private boolean fullDirty = true;

    /**
     * 数据是否需要写回当前存档的磁盘缓存。
     */
    private volatile boolean saveDirty = true;

    /**
     * 已采样的非空列数量，避免 isEmpty 每次扫 26 万格。
     */
    private int filled;

    /**
     * 该数据所属 region 坐标。
     */
    public final int regionX;
    public final int regionZ;

    public SimuMapRegionData(int regionX, int regionZ) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        Arrays.fill(height, HEIGHT_UNKNOWN);
    }

    /**
     * 获取指定 region 内方块位置的数组索引。
     */
    public static int index(int localX, int localZ) {
        return (localX & 0x1FF) + (localZ & 0x1FF) * SIZE;
    }

    /**
     * 设置指定位置的地图采样数据。与烘图线程按同一把锁互斥，只在像素真的变化时标脏。
     */
    public synchronized void setData(int localX, int localZ, short h, int argbColor, boolean water) {
        int idx = index(localX, localZ);
        short f = (short) (water ? 1 : 0);
        if (height[idx] == h && color[idx] == argbColor && flags[idx] == f) {
            return;
        }
        if (height[idx] == HEIGHT_UNKNOWN && h != HEIGHT_UNKNOWN) {
            filled++;
        } else if (height[idx] != HEIGHT_UNKNOWN && h == HEIGHT_UNKNOWN) {
            filled--;
        }
        height[idx] = h;
        color[idx] = argbColor;
        flags[idx] = f;
        markChunkDirty(localX, localZ);
        saveDirty = true;
    }

    /**
     * markChunkDirty: 标出像素所属 chunk 需要重烘。
     */
    private void markChunkDirty(int localX, int localZ) {
        int chunkIndex = (localZ >> 4) * CHUNK_SIDE + (localX >> 4);
        if (!chunkDirty[chunkIndex]) {
            chunkDirty[chunkIndex] = true;
            dirtyChunkCount++;
        }
    }

    /**
     * 判断指定位置的高度。
     */
    public short getHeight(int localX, int localZ) {
        return height[index(localX, localZ)];
    }

    /**
     * 获取指定位置的颜色。
     */
    public int getColor(int localX, int localZ) {
        return color[index(localX, localZ)];
    }

    /**
     * 判断指定位置是否为水面。
     */
    public boolean isWater(int localX, int localZ) {
        return (flags[index(localX, localZ)] & 1) != 0;
    }

    /**
     * mergeUnknownFrom: 用磁盘快照填补本地还没采到的格子，不覆盖已经采到的地形。
     * <p>区块可能比磁盘数据先一步被扫描建好 region，这时候只能填空不能回退。
     */
    public synchronized void mergeUnknownFrom(SimuMapRegionData loaded) {
        boolean merged = false;
        for (int index = 0; index < AREA; index++) {
            if (height[index] != HEIGHT_UNKNOWN || loaded.height[index] == HEIGHT_UNKNOWN) {
                continue;
            }
            height[index] = loaded.height[index];
            color[index] = loaded.color[index];
            flags[index] = loaded.flags[index];
            filled++;
            merged = true;
        }
        if (merged) {
            fullDirty = true;
            saveDirty = true;
        }
    }

    /**
     * 判断是否有未渲染的修改。
     */
    public synchronized boolean isDirty() {
        return fullDirty || dirtyChunkCount > 0;
    }

    /**
     * 标记整块 region 需要重新渲染。
     */
    public synchronized void markDirty() {
        fullDirty = true;
        Arrays.fill(chunkDirty, false);
        dirtyChunkCount = 0;
    }

    /**
     * takeDirtyChunks: 取走本 region 待重烘的 chunk 列表并清脏。返回 null 表示需要整幅重烘。
     */
    public synchronized int[] takeDirtyChunks() {
        if (fullDirty) {
            fullDirty = false;
            // 整幅重烘已经覆盖所有 chunk，不要把零散脏块留到下一轮。
            Arrays.fill(chunkDirty, false);
            dirtyChunkCount = 0;
            return null;
        }
        if (dirtyChunkCount <= 0) {
            return new int[0];
        }
        int[] chunks = new int[dirtyChunkCount];
        int written = 0;
        for (int index = 0; index < CHUNKS && written < chunks.length; index++) {
            if (chunkDirty[index]) {
                chunkDirty[index] = false;
                chunks[written++] = index;
            }
        }
        dirtyChunkCount = 0;
        return chunks;
    }

    /**
     * 检查该 region 是否完全空白。
     */
    public synchronized boolean isEmpty() {
        return filled <= 0;
    }

    /**
     * isFullySampled: 本地已经采满整块，不需要再从磁盘补数据。
     */
    public synchronized boolean isFullySampled() {
        return filled >= AREA;
    }

    /**
     * needsSave: 是否有尚未写入当前存档目录的修改。
     */
    public boolean needsSave() {
        return saveDirty;
    }

    /**
     * markSaved: 磁盘写入成功后清除存盘脏标记。
     */
    public void markSaved() {
        saveDirty = false;
    }

    /**
     * setFilledCount: 从磁盘加载后设置已填充列数。
     */
    public synchronized void setFilledCount(int filledCount) {
        filled = Math.max(0, filledCount);
    }

    /**
     * 获取该 region 起点的世界 X 坐标。
     */
    public int worldBlockX() {
        return regionX * SIZE;
    }

    /**
     * 获取该 region 起点的世界 Z 坐标。
     */
    public int worldBlockZ() {
        return regionZ * SIZE;
    }
}
