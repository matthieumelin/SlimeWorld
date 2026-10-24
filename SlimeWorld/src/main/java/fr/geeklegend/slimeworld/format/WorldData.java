package fr.geeklegend.slimeworld.format;

import java.util.List;
import java.util.Map;

public record WorldData(int radius, double borderSize, Spawn spawn, String defaultBiome, Map<Long, ChunkData> chunks) {

    public record Spawn(double x, double y, double z, float yaw, float pitch) {
    }

    public record ChunkData(int x, int z, List<SectionData> sections, List<TileData> tiles, List<EntityData> entities) {
    }

    public record SectionData(int sectionY, String[] blockPalette, long[] blocks, String[] biomePalette,
                              long[] biomes) {
    }

    /**
     * {@code items} is empty when the block has no inventory, {@code data} is the JSON from
     * TileDataAccess or null when there is nothing else to keep.
     */
    public record TileData(int x, int y, int z, byte[][] items, String data) {
    }

    public record EntityData(double x, double y, double z, float yaw, float pitch, byte[] nbt) {
    }

    public static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
