package fr.geeklegend.slimeworld.capture;

import fr.geeklegend.slimeworld.format.BitStorage;
import fr.geeklegend.slimeworld.format.WorldData;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class WorldCapture {
    private record CapturedChunk(int x, int z, ChunkSnapshot snapshot, List<WorldData.TileData> tiles,
                                 List<WorldData.EntityData> entities) {
    }

    private final int radius;
    private final double borderSize;
    private final int minY;
    private final int maxY;
    private final WorldData.Spawn spawn;
    private final String defaultBiome;
    private final List<CapturedChunk> chunks;

    private WorldCapture(int radius, double borderSize, int minY, int maxY, WorldData.Spawn spawn, String defaultBiome, List<CapturedChunk> chunks) {
        this.radius = radius;
        this.borderSize = borderSize;
        this.minY = minY;
        this.maxY = maxY;
        this.spawn = spawn;
        this.defaultBiome = defaultBiome;
        this.chunks = chunks;
    }

    public static WorldCapture capture(World world, int radius, String defaultBiome, boolean loadMissingChunks) {
        return capture(world, 0, 0, radius, world.getWorldBorder().getSize(), world.getSpawnLocation(), defaultBiome, loadMissingChunks);
    }

    public static WorldCapture capture(World world, int centerChunkX, int centerChunkZ, int radius, double borderSize, Location spawnLocation, String defaultBiome, boolean loadMissingChunks) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Capture must be done on the main thread");
        }
        int offsetX = centerChunkX << 4;
        int offsetZ = centerChunkZ << 4;
        WorldData.Spawn spawn = new WorldData.Spawn(spawnLocation.getX() - offsetX, spawnLocation.getY(), spawnLocation.getZ() - offsetZ, spawnLocation.getYaw(), spawnLocation.getPitch());

        List<CapturedChunk> captured = new ArrayList<>();
        for (int cx = -radius; cx <= radius; cx++) {
            for (int cz = -radius; cz <= radius; cz++) {
                int sourceX = centerChunkX + cx, sourceZ = centerChunkZ + cz;
                if (!loadMissingChunks && !world.isChunkLoaded(sourceX, sourceZ)) continue;
                Chunk chunk = world.getChunkAt(sourceX, sourceZ);
                captured.add(new CapturedChunk(cx, cz, chunk.getChunkSnapshot(false, true, false), captureTiles(chunk, offsetX, offsetZ), captureEntities(chunk, offsetX, offsetZ)));
            }
        }

        return new WorldCapture(radius, borderSize, world.getMinHeight(), world.getMaxHeight(), spawn, defaultBiome, captured);
    }

    private static List<WorldData.TileData> captureTiles(Chunk chunk, int offsetX, int offsetZ) {
        List<WorldData.TileData> tiles = new ArrayList<>();
        for (BlockState snapshot : chunk.getTileEntities()) {
            byte[][] items = captureItems(InventoryAccess.of(snapshot.getBlock().getState(false)));
            String data = TileDataAccess.capture(snapshot);
            if (items.length > 0 || data != null)
                tiles.add(new WorldData.TileData(snapshot.getX() - offsetX, snapshot.getY(), snapshot.getZ() - offsetZ, items, data));
        }
        return tiles;
    }

    /**
     * Empty array when there is no inventory or it holds nothing.
     */
    private static byte[][] captureItems(Inventory inventory) {
        if (inventory == null) return new byte[0][];
        ItemStack[] contents = inventory.getContents();
        byte[][] items = new byte[contents.length][];
        boolean hasItems = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir()) {
                items[i] = new byte[0];
            } else {
                items[i] = item.serializeAsBytes();
                hasItems = true;
            }
        }
        return hasItems ? items : new byte[0][];
    }

    @SuppressWarnings("deprecation") // Bukkit.getUnsafe()
    private static List<WorldData.EntityData> captureEntities(Chunk chunk, int offsetX, int offsetZ) {
        List<WorldData.EntityData> entities = new ArrayList<>();
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Player || entity.isInsideVehicle() || !entity.isPersistent()) continue;
            try {
                byte[] nbt = Bukkit.getUnsafe().serializeEntity(entity);
                Location location = entity.getLocation();
                entities.add(new WorldData.EntityData(location.getX() - offsetX, location.getY(), location.getZ() - offsetZ, location.getYaw(), location.getPitch(), nbt));
            } catch (RuntimeException ignored) {
                // Entity no serializable, skipping
            }
        }
        return entities;
    }

    public WorldData toWorldData() {
        Map<Long, WorldData.ChunkData> out = new HashMap<>();
        for (CapturedChunk capturedChunk : chunks) {
            List<WorldData.SectionData> sections = encodeSections(capturedChunk.snapshot());
            if (sections.isEmpty() && capturedChunk.tiles().isEmpty() && capturedChunk.entities().isEmpty()) continue;
            out.put(WorldData.chunkKey(capturedChunk.x(), capturedChunk.z()), new WorldData.ChunkData(capturedChunk.x(), capturedChunk.z(), sections, capturedChunk.tiles(), capturedChunk.entities()));
        }
        return new WorldData(radius, borderSize, spawn, defaultBiome, out);
    }

    private List<WorldData.SectionData> encodeSections(ChunkSnapshot snap) {
        List<WorldData.SectionData> result = new ArrayList<>();
        int[] blockIds = new int[4096];
        int[] biomeIds = new int[64];

        for (int sy = minY >> 4; sy <= (maxY - 1) >> 4; sy++) {
            int baseY = sy << 4;

            Map<BlockData, Integer> blockPalette = new LinkedHashMap<>();
            for (int i = 0; i < 4096; i++) {
                BlockData data = snap.getBlockData(i & 15, baseY + (i >> 8), (i >> 4) & 15);
                Integer id = blockPalette.computeIfAbsent(data, k -> blockPalette.size());
                blockIds[i] = id;
            }
            if (blockPalette.size() == 1 && blockPalette.keySet().iterator().next().getMaterial().isAir()) continue;

            Map<String, Integer> biomePalette = new LinkedHashMap<>();
            for (int i = 0; i < 64; i++) {
                int bx = i & 3, bz = (i >> 2) & 3, by = i >> 4;
                Biome biome = snap.getBiome(bx << 2, baseY + (by << 2), bz << 2);
                String key = biome.key().asString();
                Integer id = biomePalette.computeIfAbsent(key, k -> biomePalette.size());
                biomeIds[i] = id;
            }

            String[] blocks = blockPalette.keySet().stream().map(BlockData::getAsString).toArray(String[]::new);
            String[] biomes = biomePalette.keySet().toArray(String[]::new);
            result.add(new WorldData.SectionData(sy, blocks, BitStorage.pack(blockIds, BitStorage.bitsFor(blocks.length)),
                    biomes, BitStorage.pack(biomeIds, BitStorage.bitsFor(biomes.length))));
        }
        return result;
    }
}
