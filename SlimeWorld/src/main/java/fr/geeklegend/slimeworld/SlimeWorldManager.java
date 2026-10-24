package fr.geeklegend.slimeworld;

import fr.geeklegend.slimeworld.capture.InventoryAccess;
import fr.geeklegend.slimeworld.capture.TileDataAccess;
import fr.geeklegend.slimeworld.capture.WorldCapture;
import fr.geeklegend.slimeworld.format.WorldCodec;
import fr.geeklegend.slimeworld.format.WorldData;
import fr.geeklegend.slimeworld.storage.Storage;
import fr.geeklegend.slimeworld.world.MemoryChunkGenerator;
import fr.geeklegend.slimeworld.world.ResolvedWorld;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads, saves and exports small worlds (islands) kept entirely in a {@link Storage}
 * instead of region files.
 * <p>
 * A world is a square of chunks centered on chunk 0,0. While loaded it is a normal Bukkit world
 * named {@code worldPrefix + id}; its folder on disk is temporary and deleted on unload.
 * All methods returning a {@link CompletableFuture} can be called from any thread, run their
 * Bukkit work on the main thread and their storage work on dedicated IO threads.
 * Operations on the same id are queued and run in call order.
 */
public class SlimeWorldManager {

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,48}");

    private record LoadedWorld(String id, World world, int radius, String defaultBiome) {
    }

    private record Closed(WorldCapture capture, File folder) {
    }

    private final Plugin plugin;
    private final Storage storage;
    private final ExecutorService io;
    private final Executor mainThread;
    private final String worldPrefix;
    private final int compressionLevel;

    private final Map<String, LoadedWorld> loaded = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<?>> queues = new HashMap<>();

    /**
     * Creates a manager with the defaults: prefix {@code world_}, 2 IO threads, zstd level 6.
     *
     * @param plugin  plugin owning the worlds and the chunk tickets
     * @param storage where worlds are read from and written to
     */
    public SlimeWorldManager(Plugin plugin, Storage storage) {
        this(plugin, storage, "world_", 2, 6);
    }

    /**
     * Creates a manager. Must be called on the main thread (usually in {@code onEnable}):
     * leftover world folders with the prefix, from a crash, are deleted.
     *
     * @param plugin           plugin owning the worlds and the chunk tickets
     * @param storage          where worlds are read from and written to
     * @param worldPrefix      prefix of the Bukkit world names, must not match your other worlds
     * @param ioThreads        number of threads for compression and storage access
     * @param compressionLevel zstd level, 1 (fast) to 22 (small)
     */
    public SlimeWorldManager(Plugin plugin, Storage storage, String worldPrefix, int ioThreads, int compressionLevel) {
        this.plugin = plugin;
        this.storage = storage;
        this.worldPrefix = worldPrefix;
        this.compressionLevel = compressionLevel;

        AtomicInteger counter = new AtomicInteger();
        this.io = Executors.newFixedThreadPool(ioThreads, r -> {
            Thread thread = new Thread(r, "SlimeWorld-IO-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.mainThread = r -> {
            if (Bukkit.isPrimaryThread()) r.run();
            else Bukkit.getScheduler().runTask(plugin, r);
        };
        cleanupLeftoverWorlds();
    }

    /**
     * Loads a world from the storage, or returns it if it is already loaded.
     *
     * @param id world id, {@code [A-Za-z0-9_-]{1,48}}
     * @return the loaded world, once all its chunks and their contents are ready;
     * fails with {@link NoSuchElementException} if the world does not exist
     * @throws IllegalArgumentException if the id is invalid
     */
    public CompletableFuture<World> load(String id) {
        checkId(id);
        return enqueue(id, () -> {
            LoadedWorld already = loaded.get(id);
            if (already != null) return CompletableFuture.completedFuture(already.world());
            return CompletableFuture.supplyAsync(() -> readWorld(id), io)
                    .thenComposeAsync(data -> openWorld(id, data), mainThread);
        });
    }

    /**
     * Creates a new world as a copy of a template, then loads it.
     *
     * @param id         id of the new world
     * @param templateId id of an existing world to copy, usually made with an export method
     * @return the loaded world; fails with {@link IllegalStateException} if {@code id} already exists
     * or {@link NoSuchElementException} if the template does not exist
     * @throws IllegalArgumentException if an id is invalid
     */
    public CompletableFuture<World> create(String id, String templateId) {
        checkId(id);
        checkId(templateId);
        return enqueue(id, () -> CompletableFuture.runAsync(() -> {
            try {
                if (storage.exists(id)) throw new IllegalStateException("World " + id + " already exists");
                byte[] template = storage.load(templateId);
                if (template == null) throw new NoSuchElementException("Unknown template: " + templateId);
                storage.save(id, template);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, io)).thenCompose(v -> load(id));
    }

    /**
     * Saves a loaded world to the storage and keeps it loaded.
     *
     * @param id world id
     * @return completes once the data is written; fails with {@link IllegalStateException}
     * if the world is not loaded
     * @throws IllegalArgumentException if the id is invalid
     */
    public CompletableFuture<Void> save(String id) {
        checkId(id);
        return enqueue(id, () -> CompletableFuture.supplyAsync(() -> {
            LoadedWorld world = loaded.get(id);
            if (world == null) throw new IllegalStateException("World " + id + " is not loaded");
            return WorldCapture.capture(world.world(), world.radius(), world.defaultBiome(), false);
        }, mainThread).thenAcceptAsync(capture -> write(id, capture), io));
    }

    /**
     * Saves a world, then unloads it. Players inside are teleported to {@code fallback} first.
     * Does nothing if the world is not loaded.
     *
     * @param id       world id
     * @param fallback where players in the world are sent, must be in another world
     * @return completes once the data is written
     * @throws IllegalArgumentException if the id is invalid
     */
    public CompletableFuture<Void> unload(String id, Location fallback) {
        checkId(id);
        Objects.requireNonNull(fallback, "fallback");
        return enqueue(id, () -> CompletableFuture.supplyAsync(() -> closeWorld(id, fallback, true), mainThread)
                .thenAcceptAsync(closed -> {
                    if (closed == null) return;
                    try {
                        write(id, closed.capture());
                    } finally {
                        deleteRecursively(closed.folder());
                    }
                }, io));
    }

    /**
     * Unloads a world without saving it and deletes it from the storage. Cannot be undone.
     *
     * @param id       world id
     * @param fallback where players in the world are sent, must be in another world
     * @return completes once the world is removed from the storage
     * @throws IllegalArgumentException if the id is invalid
     */
    public CompletableFuture<Void> delete(String id, Location fallback) {
        checkId(id);
        Objects.requireNonNull(fallback, "fallback");
        return enqueue(id, () -> CompletableFuture.supplyAsync(() -> closeWorld(id, fallback, false), mainThread)
                .thenAcceptAsync(closed -> {
                    if (closed != null) deleteRecursively(closed.folder());
                    try {
                        storage.delete(id);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, io));
    }

    /**
     * Copies an area of any world into the storage, typically to make a template for {@link #create}.
     * The area is re-centered: chunk {@code centerChunkX, centerChunkZ} becomes chunk 0,0.
     * Missing chunks are generated. Blocks, biomes, inventories, block entity data and entities are kept.
     *
     * @param world        source world
     * @param centerChunkX center chunk X of the area
     * @param centerChunkZ center chunk Z of the area
     * @param radius       radius in chunks, the area is {@code (2 * radius + 1)} chunks wide
     * @param spawn        spawn of the exported world, in source world coordinates
     * @param id           id to save under, overwritten if it exists
     * @param defaultBiome biome key used outside the saved chunks, e.g. {@code minecraft:plains}
     * @return completes once the data is written
     * @throws IllegalArgumentException if the id is invalid
     */
    public CompletableFuture<Void> exportWorld(World world, int centerChunkX, int centerChunkZ, int radius,
                                               Location spawn, String id, String defaultBiome) {
        checkId(id);
        Location spawnCopy = spawn.clone();
        return enqueue(id, () -> CompletableFuture
                .supplyAsync(() -> WorldCapture.capture(world, centerChunkX, centerChunkZ, radius,
                        (2 * radius + 1) * 16, spawnCopy, defaultBiome, true), mainThread)
                .thenAcceptAsync(capture -> write(id, capture), io));
    }

    /**
     * Exports the area around a location, which also becomes the spawn.
     *
     * @param center center and spawn of the exported world
     * @param radius radius in chunks
     * @param id     id to save under, overwritten if it exists
     * @return completes once the data is written
     * @see #exportWorld(World, int, int, int, Location, String, String)
     */
    public CompletableFuture<Void> exportAround(Location center, int radius, String id) {
        return exportWorld(center.getWorld(), center.getBlockX() >> 4, center.getBlockZ() >> 4,
                radius, center, id, "minecraft:plains");
    }

    /**
     * Exports the area around chunk 0,0 of a world, keeping its spawn.
     *
     * @param world  source world
     * @param radius radius in chunks
     * @param id     id to save under, overwritten if it exists
     * @return completes once the data is written
     * @see #exportWorld(World, int, int, int, Location, String, String)
     */
    public CompletableFuture<Void> exportWorld(World world, int radius, String id) {
        return exportWorld(world, 0, 0, radius, world.getSpawnLocation(), id, "minecraft:plains");
    }

    /**
     * Resizes the world border of a loaded world. The size is saved with the world.
     * <p>
     * Growing it also extends the saved area, so blocks placed in the new space are kept.
     * Shrinking it never removes saved chunks: growing it again later brings them back.
     *
     * @param id   world id
     * @param size border width in blocks, centered on block 8, 8
     * @return completes once the new chunks are loaded and the border is set; fails with
     * {@link IllegalStateException} if the world is not loaded
     * @throws IllegalArgumentException if the id or the size is invalid
     */
    public CompletableFuture<Void> setBorderSize(String id, double size) {
        checkId(id);
        if (!(size >= 1 && size <= 59_999_968)) throw new IllegalArgumentException("Invalid border size: " + size);
        return enqueue(id, () -> CompletableFuture.supplyAsync(() -> {
            LoadedWorld current = loaded.get(id);
            if (current == null) throw new IllegalStateException("World " + id + " is not loaded");
            return current;
        }, mainThread).thenComposeAsync(current -> {
            int radius = Math.max(current.radius(), (int) Math.max(0, Math.ceil((size / 2 - 8) / 16)));
            return loadChunks(current.world(), current.radius(), radius).thenRunAsync(() -> {
                current.world().getWorldBorder().setSize(size);
                loaded.put(id, new LoadedWorld(id, current.world(), radius, current.defaultBiome()));
            }, mainThread);
        }, mainThread));
    }

    /**
     * @param id world id
     * @return whether the world is currently loaded
     */
    public boolean isLoaded(String id) {
        return loaded.containsKey(id);
    }

    /**
     * @param id world id
     * @return the Bukkit world if it is loaded
     */
    public Optional<World> getWorld(String id) {
        return Optional.ofNullable(loaded.get(id)).map(LoadedWorld::world);
    }

    /**
     * @param world a Bukkit world
     * @return its id if it is a world loaded by this manager
     */
    public Optional<String> getWorldId(World world) {
        return loaded.values().stream().filter(loadedWorld -> loadedWorld.world().equals(world)).map(LoadedWorld::id).findFirst();
    }

    /**
     * Saves and unloads every world, waits for pending writes (30 s max), then closes the storage.
     * Call it on the main thread in {@code onDisable}; the manager is unusable afterwards.
     */
    public void shutdown() {
        Location fallback = Bukkit.getWorlds().get(0).getSpawnLocation();
        Map<String, Closed> closed = new HashMap<>();
        for (String id : List.copyOf(loaded.keySet())) {
            try {
                Closed c = closeWorld(id, fallback, true);
                if (c != null) closed.put(id, c);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Impossible to unload the world " + id, e);
            }
        }

        io.shutdown();
        try {
            if (!io.awaitTermination(30, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("World backups could not be completed in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        closed.forEach((id, c) -> {
            try {
                write(id, c.capture());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save the world " + id, e);
            } finally {
                deleteRecursively(c.folder());
            }
        });
        storage.close();
    }

    private WorldData readWorld(String id) {
        try {
            byte[] bytes = storage.load(id);
            if (bytes == null) throw new NoSuchElementException("Unknown world: " + id);
            return WorldCodec.decode(bytes);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private CompletableFuture<World> openWorld(String id, WorldData data) {
        String name = worldPrefix + id;
        if (Bukkit.getWorld(name) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("World " + name + " is already loaded"));
        }
        deleteRecursively(new File(Bukkit.getWorldContainer(), name));

        ResolvedWorld resolved = ResolvedWorld.resolve(data, plugin.getLogger());
        MemoryChunkGenerator generator = new MemoryChunkGenerator(resolved);
        World world = new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .generateStructures(false)
                .generator(generator)
                .biomeProvider(generator.biomeProvider())
                .createWorld();
        if (world == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Impossible to create world " + name));
        }

        world.setAutoSave(false);
        WorldData.Spawn spawn = data.spawn();
        world.setSpawnLocation(new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch()));

        int radius = data.radius();
        world.getWorldBorder().setCenter(8, 8);
        world.getWorldBorder().setSize(data.borderSize());

        return loadChunks(world, -1, radius)
                .thenApplyAsync(v -> {
                    restoreContents(world, data);
                    loaded.put(id, new LoadedWorld(id, world, radius, data.defaultBiome()));
                    return world;
                }, mainThread)
                .whenComplete((w, error) -> {
                    if (error != null) {
                        mainThread.execute(() -> {
                            world.removePluginChunkTickets(plugin);
                            Bukkit.unloadWorld(world, false);
                            deleteRecursively(world.getWorldFolder());
                        });
                    }
                });
    }

    @SuppressWarnings("deprecation") // Bukkit.getUnsafe()
    private void restoreContents(World world, WorldData data) {
        for (WorldData.ChunkData chunk : data.chunks().values()) {
            for (WorldData.TileData tile : chunk.tiles()) {
                Block block = world.getBlockAt(tile.x(), tile.y(), tile.z());
                Inventory inventory = tile.items().length == 0 ? null : InventoryAccess.of(block.getState(false));
                if (inventory != null) {
                    ItemStack[] items = new ItemStack[Math.min(inventory.getSize(), tile.items().length)];
                    for (int i = 0; i < items.length; i++) {
                        byte[] bytes = tile.items()[i];
                        items[i] = bytes.length == 0 ? null : ItemStack.deserializeBytes(bytes);
                    }
                    inventory.setContents(items);
                }

                if (tile.data() != null) {
                    // snapshot + update: some states (banners...) only write their fields on update()
                    BlockState state = block.getState();
                    try {
                        TileDataAccess.apply(state, tile.data());
                        state.update(true, false);
                    } catch (RuntimeException ex) {
                        plugin.getLogger().log(Level.WARNING, "Block data ignored at " + tile.x() + " " + tile.y() + " " + tile.z() + " in " + world.getName(), ex);
                    }
                }
            }

            for (WorldData.EntityData e : chunk.entities()) {
                try {
                    Entity entity = Bukkit.getUnsafe().deserializeEntity(e.nbt(), world);
                    entity.spawnAt(new Location(world, e.x(), e.y(), e.z(), e.yaw(), e.pitch()));
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING, "Entity ignored in " + world.getName(), ex);
                }
            }
        }
    }

    /**
     * Loads and pins the chunks within {@code to} that are outside {@code from} (-1 = all).
     */
    private CompletableFuture<Void> loadChunks(World world, int from, int to) {
        List<CompletableFuture<Chunk>> futures = new ArrayList<>();
        for (int cx = -to; cx <= to; cx++) {
            for (int cz = -to; cz <= to; cz++) {
                if (Math.abs(cx) <= from && Math.abs(cz) <= from) continue;
                futures.add(world.getChunkAtAsync(cx, cz).thenApply(chunk -> {
                    chunk.addPluginChunkTicket(plugin);
                    return chunk;
                }));
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private Closed closeWorld(String id, Location fallback, boolean capture) {
        LoadedWorld loadedWorld = loaded.get(id);
        if (loadedWorld == null) return null;
        World world = loadedWorld.world();

        for (Player player : List.copyOf(world.getPlayers())) {
            player.teleport(fallback);
        }

        WorldCapture snapshot = capture ? WorldCapture.capture(world, loadedWorld.radius(), loadedWorld.defaultBiome(), false) : null;
        File folder = world.getWorldFolder();

        world.removePluginChunkTickets(plugin);
        if (!Bukkit.unloadWorld(world, false)) {
            for (int cx = -loadedWorld.radius(); cx <= loadedWorld.radius(); cx++) {
                for (int cz = -loadedWorld.radius(); cz <= loadedWorld.radius(); cz++) {
                    world.addPluginChunkTicket(cx, cz, plugin);
                }
            }
            throw new IllegalStateException("Impossible to unload the world " + world.getName());
        }
        loaded.remove(id);
        return new Closed(snapshot, folder);
    }

    private void write(String id, WorldCapture capture) {
        byte[] bytes;
        try {
            bytes = WorldCodec.encode(capture.toWorldData(), compressionLevel);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
        try {
            storage.save(id, bytes);
        } catch (IOException e) {
            Path emergency = plugin.getDataFolder().toPath()
                    .resolve("emergency")
                    .resolve(id + "-" + System.currentTimeMillis() + ".isl");
            try {
                Files.createDirectories(emergency.getParent());
                Files.write(emergency, bytes);
                plugin.getLogger().severe("Storage unavailable for island " + id + ", backup copy: " + emergency);
            } catch (IOException e2) {
                e.addSuppressed(e2);
            }
            throw new CompletionException(e);
        }
    }

    private <T> CompletableFuture<T> enqueue(String id, Supplier<CompletableFuture<T>> task) {
        synchronized (queues) {
            CompletableFuture<?> previous = queues.getOrDefault(id, CompletableFuture.completedFuture(null));
            CompletableFuture<T> next = previous.handle((r, e) -> null).thenCompose(ignored -> task.get());
            queues.put(id, next);
            next.whenComplete((r, e) -> {
                synchronized (queues) {
                    queues.remove(id, next);
                }
            });
            return next;
        }
    }

    private void cleanupLeftoverWorlds() {
        File[] leftovers = Bukkit.getWorldContainer().listFiles(file -> file.isDirectory() && file.getName().startsWith(worldPrefix));
        if (leftovers == null) return;
        for (File folder : leftovers) {
            if (Bukkit.getWorld(folder.getName()) == null) deleteRecursively(folder);
        }
    }

    private static void checkId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("World id invalid: " + id);
        }
    }

    private static void deleteRecursively(File folder) {
        if (folder == null || !folder.exists()) return;
        try (Stream<Path> paths = Files.walk(folder.toPath())) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // retried on next restart (cleanupLeftoverWorlds)
                }
            });
        } catch (IOException ignored) {
            // retried on next restart (cleanupLeftoverWorlds)
        }
    }
}
