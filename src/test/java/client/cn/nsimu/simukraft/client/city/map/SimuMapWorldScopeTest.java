package client.cn.nsimu.simukraft.client.city.map;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SimuMapWorldScopeTest {
    @Test
    void sanitizeForPath_keepsChineseWorldNamesDistinct() {
        assertEquals("新的世界", SimuMapStorage.sanitizeForPath("新的世界"));
        assertEquals("我的世界", SimuMapStorage.sanitizeForPath("我的世界"));
        assertNotEquals(
                SimuMapStorage.sanitizeForPath("新的世界"),
                SimuMapStorage.sanitizeForPath("我的世界"));
    }

    @Test
    void sanitizeForPath_onlyReplacesIllegalPathCharacters() {
        assertEquals("a_b", SimuMapStorage.sanitizeForPath("a/b"));
        assertEquals("host_25565", SimuMapStorage.sanitizeForPath("host:25565"));
    }

    @Test
    void singlePlayerWorldIds_differByFolderAndPath() {
        String first = SimuMapStorage.createSinglePlayerWorldId(Path.of("run", "saves", "新的世界"));
        String second = SimuMapStorage.createSinglePlayerWorldId(Path.of("run", "saves", "我的世界"));
        String copy = SimuMapStorage.createSinglePlayerWorldId(Path.of("other", "saves", "新的世界"));

        assertTrue(first.startsWith("sp_新的世界_"));
        assertNotEquals(first, second);
        assertNotEquals(first, copy);
    }

    @Test
    void cacheIdentity_rejectsForeignWorldOrDimension() {
        assertTrue(SimuMapStorage.matchesCacheIdentity(
                "sp_world_1", "minecraft_overworld", "sp_world_1", "minecraft_overworld"));
        assertFalse(SimuMapStorage.matchesCacheIdentity(
                "sp_world_1", "minecraft_overworld", "sp_world_2", "minecraft_overworld"));
        assertFalse(SimuMapStorage.matchesCacheIdentity(
                "sp_world_1", "minecraft_overworld", "sp_world_1", "minecraft_the_nether"));
        assertFalse(SimuMapStorage.matchesCacheIdentity(
                "sp_world_1", "minecraft_overworld", SimuMapStorage.UNRESOLVED_WORLD_ID, "minecraft_overworld"));
    }

    @Test
    void unresolvedWorldId_isNotWritable() {
        assertFalse(SimuMapStorage.isResolvedWorldId(SimuMapStorage.UNRESOLVED_WORLD_ID));
        assertFalse(SimuMapStorage.isResolvedWorldId(null));
        assertTrue(SimuMapStorage.isResolvedWorldId("sp_新的世界_abc"));
    }

    @Test
    void regionData_tracksFilledCountAndSaveDirty() {
        SimuMapRegionData data = new SimuMapRegionData(0, 0);
        assertTrue(data.isEmpty());
        assertTrue(data.needsSave());

        data.setData(1, 1, (short) 64, 0xFF112233, false);
        assertFalse(data.isEmpty());
        assertTrue(data.needsSave());

        data.markSaved();
        assertFalse(data.needsSave());

        data.setData(1, 1, (short) 64, 0xFF112233, false);
        assertFalse(data.needsSave());

        data.setData(2, 2, (short) 70, 0xFF445566, false);
        assertTrue(data.needsSave());
    }

    @Test
    void regionData_dirtyChunksAreTakenOnceAndOnlyCoverChangedChunks() {
        SimuMapRegionData data = new SimuMapRegionData(0, 0);
        // 首次取脏要求整幅重烘，之后只烘真正变化的 chunk。
        assertNull(data.takeDirtyChunks());
        assertEquals(0, data.takeDirtyChunks().length);

        data.setData(0, 0, (short) 70, 0xFF000001, false);
        data.setData(8, 4, (short) 71, 0xFF000002, false);
        assertArrayEquals(new int[]{0}, data.takeDirtyChunks());

        // 同样的值再采一次不产生新脏块，不会再触发重烘和纹理上传。
        data.setData(0, 0, (short) 70, 0xFF000001, false);
        assertEquals(0, data.takeDirtyChunks().length);

        // 跳过一个 chunk 边界就计入下一个 chunk。
        data.setData(17, 0, (short) 60, 0xFF000003, false);
        assertArrayEquals(new int[]{1}, data.takeDirtyChunks());
    }

    @Test
    void regionData_markDirtyRequestsFullBakeAgain() {
        SimuMapRegionData data = new SimuMapRegionData(0, 0);
        assertNull(data.takeDirtyChunks());
        data.setData(0, 0, (short) 70, 0xFF000001, false);
        data.markDirty();
        assertNull(data.takeDirtyChunks());
    }

    @Test
    void regionData_mergeUnknownOnlyFillsHoles() {
        SimuMapRegionData local = new SimuMapRegionData(0, 0);
        SimuMapRegionData disk = new SimuMapRegionData(0, 0);
        disk.setData(0, 0, (short) 80, 0xFFEEEEEE, true);
        disk.setData(1, 0, (short) 90, 0xFFDDDDDD, false);
        local.setData(1, 0, (short) 70, 0xFF111111, false);

        local.mergeUnknownFrom(disk);

        assertEquals((short) 80, local.getHeight(0, 0));
        assertEquals(0xFFEEEEEE, local.getColor(0, 0));
        assertTrue(local.isWater(0, 0));
        // 已经采到的格子不能被磁盘快照回退。
        assertEquals((short) 70, local.getHeight(1, 0));
        assertEquals(0xFF111111, local.getColor(1, 0));
        assertFalse(local.isWater(1, 0));
    }
}
