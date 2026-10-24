# SlimeWorld

A lightweight Paper library to store small worlds (islands, plots, dungeons, arenas…) **entirely in a
database or a single file**, instead of region folders. Worlds load in a few milliseconds, are saved as one
compressed blob, and can be created from templates in one call.

Built for skyblock-like servers where thousands of player worlds exist but only a few are loaded at a time.

## Features

- **One blob per world**: blocks, biomes, inventories, block entity data and entities, compressed with zstd.
- **Pluggable storage**: files or MySQL/MariaDB out of the box, or your own backend.
- **Templates**: export any area of any world, then create as many copies as you want.
- **Resizable world border**: grow a world ("island upgrade") and the new area is saved automatically.
- **Async and safe**: storage and compression run off the main thread, operations on the same world are queued,
  writes are atomic, and an emergency copy is written to disk if the storage is down.
- **No region files**: a loaded world uses a temporary folder that is deleted on unload (and on the next start
  after a crash).

## Requirements

- Paper **26.2+**
- Java **25**

## Installation

The library is not published to a Maven repository yet. Build and install it locally:

```sh
git clone https://github.com/matthieumelin/SlimeWorld.git
cd SlimeWorld/SlimeWorld
mvn clean install
```

Then add it to your plugin:

```xml
<dependency>
    <groupId>fr.geeklegend</groupId>
    <artifactId>SlimeWorld</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

Shade it into your plugin jar (`maven-shade-plugin`). To keep your jar small, you can let Paper download the
third-party libraries instead, by declaring them in your `plugin.yml` and marking them `provided`:

```yaml
libraries:
  - com.github.luben:zstd-jni:1.5.7-24
  - com.zaxxer:HikariCP:7.1.0          # only for MysqlStorage
  - com.mysql:mysql-connector-j:26.10.0 # only for MysqlStorage
```

## Quick start

```java
public class MyPlugin extends JavaPlugin {
    private SlimeWorldManager worlds;

    @Override
    public void onEnable() {
        try {
            worlds = new SlimeWorldManager(this, new FileStorage(getDataFolder().toPath().resolve("worlds")));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onDisable() {
        worlds.shutdown(); // saves and unloads everything
    }

    public void openIsland(Player player) {
        String id = player.getUniqueId().toString().replace("-", "");
        worlds.load(id)
                // first visit: the island does not exist yet, create it from the template
                .exceptionallyCompose(error -> unwrap(error) instanceof NoSuchElementException
                        ? worlds.create(id, "island_template")
                        : CompletableFuture.failedFuture(error))
                .thenAccept(world -> player.teleportAsync(world.getSpawnLocation()))
                .exceptionally(error -> {
                    getLogger().log(Level.SEVERE, "Cannot open island " + id, error);
                    return null;
                });
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }
}
```

## How it works

A SlimeWorld world is a square of chunks centered on chunk `0,0`, with a radius `r` (so `2r + 1` chunks wide).

| Step | What happens |
|---|---|
| **Load** | The blob is read and decompressed on an IO thread. A Bukkit world is created with an in-memory chunk generator that rebuilds the saved chunks, then inventories, block entity data and entities are restored. Chunks stay loaded while the world is loaded. |
| **Save** | The chunks are captured on the main thread (fast snapshot), then encoded, compressed and written on an IO thread. |
| **Unload** | Players are sent to a fallback location, the world is saved, unloaded and its temporary folder deleted. |
| **Export** | Any area of any world is captured and re-centered on chunk `0,0`, which makes it a template. |
| **Create** | A template is copied under a new id, then loaded. |

Every method that returns a `CompletableFuture` can be called from any thread. Bukkit work always runs on the
main thread and storage work on IO threads:

- `load`, `create` and `setBorderSize` complete successfully **on the main thread** (or on the calling thread
  when there is nothing to do, e.g. `load` of an already loaded world): called from the main thread, you can use
  the world directly in the callback.
- `save`, `unload`, `delete` and the export methods complete **on an IO thread**: switch back with
  `Bukkit.getScheduler().runTask(...)` before touching Bukkit objects.

## API

### `SlimeWorldManager`

| Method | Description |
|---|---|
| `load(id)` | Loads a world (or returns it if already loaded). |
| `create(id, templateId)` | Copies a template under a new id and loads it. Fails if `id` already exists. |
| `save(id)` | Saves a loaded world and keeps it loaded. |
| `unload(id, fallback)` | Saves and unloads a world, players inside are sent to `fallback`. |
| `delete(id, fallback)` | Unloads without saving and deletes from the storage. Cannot be undone. |
| `exportAround(location, radius, id)` | Exports the area around a location, which becomes the spawn. |
| `exportWorld(world, radius, id)` | Exports the area around chunk `0,0` of a world. |
| `exportWorld(world, cx, cz, radius, spawn, id, defaultBiome)` | Full control over the exported area. |
| `setBorderSize(id, size)` | Resizes the border of a loaded world; the size is saved with it. |
| `isLoaded(id)`, `getWorld(id)`, `getWorldId(world)` | Lookups. |
| `shutdown()` | Saves and unloads everything, then closes the storage. Call it in `onDisable`. |

World ids must match `[A-Za-z0-9_-]{1,48}`. The loaded Bukkit world is named `world_<id>` by default; the prefix,
the number of IO threads and the zstd level can be changed with the second constructor.

### Making a template

1. Build your template in any world.
2. Export it: `worlds.exportAround(player.getLocation(), 2, "island_template")` (a radius of 2 chunks = 80×80 blocks).
3. Create worlds from it: `worlds.create("island_42", "island_template")`.

Exporting to an existing id overwrites it, so you can update a template at any time. Worlds already created
from it are not affected.

### Island upgrades

```java
worlds.setBorderSize(id, 160).thenRun(() -> player.sendMessage("Your island is now 160×160!"));
```

The border is centered on block `8, 8` (the middle of chunk `0,0`). Growing it loads and saves the new chunks;
shrinking it never deletes saved blocks. Always resize through `setBorderSize`: a border changed directly with
`world.getWorldBorder()` is saved, but blocks built outside the saved area are not.

### Storage

| Implementation | Use case |
|---|---|
| `FileStorage(directory)` | One `<id>.isl` file per world, atomic writes. Single server. |
| `MysqlStorage(jdbcUrl, user, password, table)` | MySQL/MariaDB table, created if missing. Share worlds between servers. |

Any backend works by implementing `Storage`:

```java
public interface Storage {
    byte[] load(String id) throws IOException;           // null if missing
    void save(String id, byte[] data) throws IOException; // should be atomic
    boolean exists(String id) throws IOException;
    void delete(String id) throws IOException;
    default void close() {}
}
```

> When sharing worlds between servers, make sure a world is loaded on only one server at a time. The library
> does not lock worlds across servers.

## What is saved

- Blocks and biomes of every non-empty chunk section.
- Inventories of all containers: chests, barrels, shulker boxes, furnaces, hoppers, droppers, dispensers,
  brewing stands, crafters, lecterns, jukeboxes, decorated pots, chiseled bookshelves, shelves.
- Block entity data: signs, banners, skulls, spawners (including potential spawns), trial spawners, vaults
  (including rewarded players), beacons, beehives (bees and flower), campfires, suspicious blocks, command blocks,
  structure blocks, end gateways, sculk sensors and shriekers, furnace stored XP, crafter slots, decorated pot
  sherds, lectern pages, hopper cooldowns, unopened loot tables, locks, custom names and plugin data
  (`PersistentDataContainer`).
- Persistent entities (mobs, item frames, armor stands, minecarts…). Players are never saved.

**Not saved** (no Paper API): jigsaw, test and test instance blocks, a jukebox's playing state, a creaking
heart's link to its creaking. Game-time timers (trial spawner and vault cooldowns) restart, since each world has
its own clock.

## File format

```
int    magic "ISLD"
short  version
int    uncompressed size
bytes  zstd(body)
```

The body holds the radius, border size, spawn, default biome, and for each chunk its sections (block and biome
palettes with packed indices), inventories, block entity data (JSON) and entities (NBT). Older versions are
always readable: the format is upgraded transparently on the next save.

## Test plugin

`SlimeWorldTest/` is a small plugin to try the library and measure timings. All commands require the
`islandworlds.test` permission.

| Command | Description |
|---|---|
| `/istest exporthere <radius> <id>` | Exports the area around you. |
| `/istest export <world> <radius> <id>` | Exports the area around chunk `0,0` of a world. |
| `/istest create <id> <template>` | Creates a world from a template and teleports you. |
| `/istest load <id>` | Loads a world and teleports you. |
| `/istest border <id> <size>` | Resizes the border. |
| `/istest save <id>` / `unload <id>` / `delete <id>` | Saves / unloads / deletes. |
| `/istest loadworld <world>` | Loads a regular world folder (handy to export from it). |

## Contributing

Contributions are welcome! Whether it is a bug report, an idea, a new storage backend or support for a block
that is not saved yet, feel free to jump in:

1. Open an [issue](https://github.com/matthieumelin/SlimeWorld/issues) to report a bug or discuss a change.
2. Fork the repository and create a branch from `main`.
3. Keep the code style of the surrounding code, and test your change in game with the test plugin.
4. Open a pull request describing what you changed and how you tested it.

If you change the file format, bump `WorldCodec.VERSION` and keep older versions readable.

## License

SlimeWorld is released under the [MIT License](LICENSE). By contributing, you agree that your contributions are licensed under it too.
