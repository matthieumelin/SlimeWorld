package fr.geeklegend.slimeworld.world;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

import java.util.List;

public class MemoryBiomeProvider extends BiomeProvider {
    private final ResolvedWorld world;

    public MemoryBiomeProvider(ResolvedWorld world) {
        this.world = world;
    }

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        return world.biomeAt(x, y, z);
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        return world.allBiomes();
    }
}
