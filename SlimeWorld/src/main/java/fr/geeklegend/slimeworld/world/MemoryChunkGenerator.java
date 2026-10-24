package fr.geeklegend.slimeworld.world;

import fr.geeklegend.slimeworld.format.BitStorage;
import fr.geeklegend.slimeworld.format.WorldData;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Map;
import java.util.Random;

public class MemoryChunkGenerator extends ChunkGenerator {
    private final ResolvedWorld world;
    private final MemoryBiomeProvider biomeProvider;

    public MemoryChunkGenerator(ResolvedWorld world) {
        this.world = world;
        this.biomeProvider = new MemoryBiomeProvider(world);
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        Map<Integer, ResolvedWorld.Section> sections = world.chunk(chunkX, chunkZ);
        if (sections == null) return;

        int minY = chunkData.getMinHeight();
        int maxY = chunkData.getMaxHeight();
        for (Map.Entry<Integer, ResolvedWorld.Section> entry : sections.entrySet()) {
            int baseY = entry.getKey() << 4;
            if (baseY < minY || baseY + 16 > maxY) continue;
            ResolvedWorld.Section section = entry.getValue();

            if (section.blockBits() == 0) {
                if (!section.air()[0]) chunkData.setRegion(0, baseY, 0, 16, baseY + 16, 16, section.palette()[0]);
                continue;
            }

            for (int i = 0; i < 4096; i++) {
                int id = BitStorage.get(section.blocks(), section.blockBits(), i);
                if (section.air()[id]) continue;
                chunkData.setBlock(i & 15, baseY + (i >> 8), (i >> 4) & 15, section.palette()[id]);
            }
        }
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return biomeProvider;
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of();
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        WorldData.Spawn spawn = this.world.spawn();
        return new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    public MemoryBiomeProvider biomeProvider() {
        return biomeProvider;
    }
}
