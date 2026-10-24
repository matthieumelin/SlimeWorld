package fr.geeklegend.slimeworld.world;

import fr.geeklegend.slimeworld.format.BitStorage;
import fr.geeklegend.slimeworld.format.WorldData;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;

import java.util.*;
import java.util.logging.Logger;

public class ResolvedWorld {

    public record Section(BlockData[] palette, boolean[] air, long[] blocks, int blockBits, Biome[] biomePalette,
                          long[] biomes, int biomeBits) {
    }

    private final Map<Long, Map<Integer, Section>> chunks;
    private final Biome defaultBiome;
    private final List<Biome> allBiomes;
    private final WorldData.Spawn spawn;

    private ResolvedWorld(Map<Long, Map<Integer, Section>> chunks, Biome defaultBiome, List<Biome> allBiomes, WorldData.Spawn spawn) {
        this.chunks = chunks;
        this.defaultBiome = defaultBiome;
        this.allBiomes = allBiomes;
        this.spawn = spawn;
    }

    public static ResolvedWorld resolve(WorldData data, Logger logger) {
        Registry<Biome> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
        Biome found = lookupBiome(registry, data.defaultBiome());
        if (found == null) {
            logger.warning("Default biome unknown '" + data.defaultBiome() + "', uses plains");
            found = Biome.PLAINS;
        }
        final Biome fallback = found;

        Map<String, BlockData> blockCache = new HashMap<>();
        Map<String, Biome> biomeCache = new HashMap<>();
        Set<Biome> used = new LinkedHashSet<>();
        used.add(fallback);

        Map<Long, Map<Integer, Section>> chunks = new HashMap<>();
        for (WorldData.ChunkData chunk : data.chunks().values()) {
            Map<Integer, Section> sections = new HashMap<>();
            for (WorldData.SectionData section : chunk.sections()) {
                BlockData[] palette = new BlockData[section.blockPalette().length];
                boolean[] air = new boolean[palette.length];
                for (int i = 0; i < palette.length; i++) {
                    palette[i] = blockCache.computeIfAbsent(section.blockPalette()[i], k -> parseBlock(k, logger));
                    air[i] = palette[i].getMaterial().isAir();
                }

                Biome[] biomes = new Biome[section.biomePalette().length];
                for (int i = 0; i < biomes.length; i++) {
                    biomes[i] = biomeCache.computeIfAbsent(section.biomePalette()[i], k -> {
                        Biome biome = lookupBiome(registry, k);
                        if (biome == null) {
                            logger.warning("Unknown biome '" + k + "', replaced by default biome");
                            return fallback;
                        }
                        return biome;
                    });
                    used.add(biomes[i]);
                }

                sections.put(section.sectionY(), new Section(palette, air, section.blocks(), BitStorage.bitsFor(palette.length), biomes, section.biomes(), BitStorage.bitsFor(biomes.length)));
            }

            chunks.put(WorldData.chunkKey(chunk.x(), chunk.z()), sections);
        }

        return new ResolvedWorld(chunks, fallback, List.copyOf(used), data.spawn());
    }

    private static BlockData parseBlock(String value, Logger logger) {
        try {
            return Bukkit.createBlockData(value);
        } catch (IllegalArgumentException e) {
            logger.warning("Unknown block '" + value + "', replaced by AIR");
            return Material.AIR.createBlockData();
        }
    }

    private static Biome lookupBiome(Registry<Biome> registry, String key) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
        return namespacedKey == null ? null : registry.get(namespacedKey);
    }

    public Map<Integer, Section> chunk(int chunkX, int chunkZ) {
        return chunks.get(WorldData.chunkKey(chunkX, chunkZ));
    }

    public Biome biomeAt(int x, int y, int z) {
        Map<Integer, Section> chunk = chunk(x >> 4, z >> 4);
        if (chunk == null) return defaultBiome;
        Section section = chunk.get(y >> 4);
        if (section == null) return defaultBiome;
        int index = (((y & 15) >> 2) << 4) | (((z & 15) >> 2) << 2 | ((x & 15) >> 2));
        return section.biomePalette()[BitStorage.get(section.biomes(), section.biomeBits(), index)];
    }

    public List<Biome> allBiomes() {
        return allBiomes;
    }

    public WorldData.Spawn spawn() {
        return spawn;
    }
}
