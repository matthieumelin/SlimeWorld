package fr.geeklegend.slimeworld.capture;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.block.sign.Side;
import org.bukkit.block.spawner.SpawnRule;
import org.bukkit.block.spawner.SpawnerEntry;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.block.structure.UsageMode;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.spawner.BaseSpawner;
import org.bukkit.spawner.TrialSpawnerConfiguration;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Block entity data that is not an inventory (sign text, banner patterns, skull owner, spawner...),
 * stored as one JSON object per block. Inventories are handled by {@link InventoryAccess}.
 * <p>
 * Not kept (no API): jigsaw, test and test instance blocks, a jukebox's playing state, a creaking heart's link.
 * Game-time timers (trial spawner/vault cooldowns) restart, the island world has its own clock.
 */
public class TileDataAccess {

    private TileDataAccess() {
    }

    /**
     * Returns null when the block has nothing worth saving.
     */
    @SuppressWarnings("deprecation") // Lockable#getLock
    public static String capture(BlockState state) {
        JsonObject json = new JsonObject();

        if (state instanceof Nameable nameable && nameable.customName() != null) {
            json.addProperty("name", component(nameable.customName()));
        }
        if (state instanceof TileState tile && !tile.getPersistentDataContainer().isEmpty()) {
            try {
                json.addProperty("pdc", Base64.getEncoder().encodeToString(tile.getPersistentDataContainer().serializeToBytes()));
            } catch (IOException ignored) {
                // plugin data not serializable, skipping
            }
        }
        if (state instanceof Lootable lootable && lootable.getLootTable() != null) {
            JsonObject data = new JsonObject();
            data.addProperty("table", lootable.getLootTable().getKey().toString());
            data.addProperty("seed", lootable.getSeed());
            json.add("loot", data);
        }
        if (state instanceof Lockable lockable && lockable.isLocked()) json.addProperty("lock", lockable.getLock());

        switch (state) {
            case Sign sign -> {
                JsonObject data = new JsonObject();
                data.add("front", captureSide(sign.getSide(Side.FRONT)));
                data.add("back", captureSide(sign.getSide(Side.BACK)));
                data.addProperty("waxed", sign.isWaxed());
                json.add("sign", data);
            }
            case Banner banner -> {
                JsonArray patterns = new JsonArray();
                for (Pattern pattern : banner.getPatterns()) {
                    JsonObject data = new JsonObject();
                    data.addProperty("color", pattern.getColor().name());
                    data.addProperty("type", RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN).getKeyOrThrow(pattern.getPattern()).toString());
                    patterns.add(data);
                }
                if (!patterns.isEmpty()) json.add("banner", patterns);
            }
            case Skull skull -> {
                JsonObject data = new JsonObject();
                PlayerProfile profile = skull.getPlayerProfile();
                if (profile != null) {
                    if (profile.getId() != null) data.addProperty("id", profile.getId().toString());
                    if (profile.getName() != null) data.addProperty("name", profile.getName());
                    JsonArray properties = new JsonArray();
                    for (ProfileProperty property : profile.getProperties()) {
                        JsonObject p = new JsonObject();
                        p.addProperty("name", property.getName());
                        p.addProperty("value", property.getValue());
                        if (property.getSignature() != null) p.addProperty("signature", property.getSignature());
                        properties.add(p);
                    }
                    data.add("properties", properties);
                }
                if (skull.getNoteBlockSound() != null) data.addProperty("sound", skull.getNoteBlockSound().toString());
                if (skull.customName() != null) data.addProperty("customName", component(skull.customName()));
                if (!data.isEmpty()) json.add("skull", data);
            }
            case CreatureSpawner spawner -> {
                JsonObject data = captureSpawner(spawner);
                data.addProperty("minDelay", spawner.getMinSpawnDelay());
                data.addProperty("maxDelay", spawner.getMaxSpawnDelay());
                data.addProperty("count", spawner.getSpawnCount());
                data.addProperty("maxNearby", spawner.getMaxNearbyEntities());
                json.add("spawner", data);
            }
            case TrialSpawner trial -> {
                JsonObject data = new JsonObject();
                data.addProperty("cooldown", trial.getCooldownLength());
                data.addProperty("playerRange", trial.getRequiredPlayerRange());
                data.addProperty("ominous", trial.isOminous());
                data.add("normal", captureTrialConfig(trial.getNormalConfiguration()));
                data.add("ominousConfig", captureTrialConfig(trial.getOminousConfiguration()));
                json.add("trialSpawner", data);
            }
            case Vault vault -> {
                JsonObject data = new JsonObject();
                data.addProperty("activation", vault.getActivationRange());
                data.addProperty("deactivation", vault.getDeactivationRange());
                if (!vault.getKeyItem().isEmpty()) data.addProperty("key", item(vault.getKeyItem()));
                if (!vault.getDisplayedItem().isEmpty()) data.addProperty("displayed", item(vault.getDisplayedItem()));
                if (vault.getLootTable() != null) data.addProperty("loot", vault.getLootTable().getKey().toString());
                if (vault.getDisplayedLootTable() != null) data.addProperty("displayedLoot", vault.getDisplayedLootTable().getKey().toString());
                JsonArray rewarded = new JsonArray();
                for (UUID player : vault.getRewardedPlayers()) rewarded.add(player.toString());
                data.add("rewarded", rewarded);
                json.add("vault", data);
            }
            case Structure structure -> {
                JsonObject data = new JsonObject();
                data.addProperty("name", structure.getStructureName());
                data.addProperty("author", structure.getAuthor());
                data.add("position", vector(structure.getRelativePosition()));
                data.add("size", vector(structure.getStructureSize()));
                data.addProperty("mirror", structure.getMirror().name());
                data.addProperty("rotation", structure.getRotation().name());
                data.addProperty("mode", structure.getUsageMode().name());
                data.addProperty("ignoreEntities", structure.isIgnoreEntities());
                data.addProperty("showAir", structure.isShowAir());
                data.addProperty("boundingBox", structure.isBoundingBoxVisible());
                data.addProperty("integrity", structure.getIntegrity());
                data.addProperty("seed", structure.getSeed());
                data.addProperty("metadata", structure.getMetadata());
                json.add("structure", data);
            }
            case EndGateway gateway -> {
                JsonObject data = new JsonObject();
                Location exit = gateway.getExitLocation();
                // relative to the gateway, so it follows the island's offset
                if (exit != null) data.add("exit", vector(exit.toVector().subtract(gateway.getLocation().toVector())));
                data.addProperty("exact", gateway.isExactTeleport());
                data.addProperty("age", gateway.getAge());
                json.add("gateway", data);
            }
            case SculkSensor sensor -> {
                JsonObject data = new JsonObject();
                data.addProperty("frequency", sensor.getLastVibrationFrequency());
                data.addProperty("range", sensor.getListenerRange());
                json.add("sculkSensor", data);
            }
            case SculkShrieker shrieker -> json.addProperty("shrieker", shrieker.getWarningLevel());
            case Hopper hopper -> json.addProperty("hopper", hopper.getTransferCooldown());
            case Beacon beacon -> {
                JsonObject data = new JsonObject();
                if (beacon.getPrimaryEffect() != null) data.addProperty("primary", effectKey(beacon.getPrimaryEffect()));
                if (beacon.getSecondaryEffect() != null) data.addProperty("secondary", effectKey(beacon.getSecondaryEffect()));
                if (!data.isEmpty()) json.add("beacon", data);
            }
            case Beehive hive -> {
                if (hive.getEntityCount() > 0) json.add("bees", captureBees(hive));
                if (hive.getFlower() != null) {
                    json.add("flower", vector(hive.getFlower().toVector().subtract(hive.getLocation().toVector())));
                }
            }
            case Lectern lectern -> json.addProperty("lectern", lectern.getPage());
            case DecoratedPot pot -> {
                JsonObject data = new JsonObject();
                pot.getSherds().forEach((side, material) -> data.addProperty(side.name(), material.key().toString()));
                json.add("sherds", data);
            }
            case Crafter crafter -> {
                JsonArray disabled = new JsonArray();
                for (int slot = 0; slot < 9; slot++) if (crafter.isSlotDisabled(slot)) disabled.add(slot);
                JsonObject data = new JsonObject();
                data.add("disabled", disabled);
                data.addProperty("triggered", crafter.isTriggered());
                json.add("crafter", data);
            }
            case Campfire campfire -> {
                JsonArray slots = new JsonArray();
                for (int i = 0; i < campfire.getSize(); i++) {
                    JsonObject slot = new JsonObject();
                    ItemStack item = campfire.getItem(i);
                    if (item != null && !item.isEmpty()) {
                        slot.addProperty("item", item(item));
                        slot.addProperty("cook", campfire.getCookTime(i));
                        slot.addProperty("total", campfire.getCookTimeTotal(i));
                    }
                    slots.add(slot);
                }
                json.add("campfire", slots);
            }
            case BrushableBlock brushable -> {
                if (!brushable.getItem().isEmpty()) json.addProperty("brushable", item(brushable.getItem()));
            }
            case CommandBlock command -> json.addProperty("command", command.getCommand());
            case Furnace furnace -> {
                JsonObject data = new JsonObject();
                data.addProperty("burn", furnace.getBurnTime());
                data.addProperty("cook", furnace.getCookTime());
                data.addProperty("total", furnace.getCookTimeTotal());
                JsonObject recipes = new JsonObject();
                furnace.getRecipesUsed().forEach((recipe, count) -> recipes.addProperty(recipe.getKey().toString(), count));
                data.add("recipes", recipes);
                json.add("furnace", data);
            }
            case BrewingStand brewing -> {
                JsonObject data = new JsonObject();
                data.addProperty("time", brewing.getBrewingTime());
                data.addProperty("fuel", brewing.getFuelLevel());
                json.add("brewing", data);
            }
            default -> {
            }
        }
        return json.isEmpty() ? null : json.toString();
    }

    /**
     * Applies the data to a snapshot; the caller must {@code update()} it afterwards.
     */
    @SuppressWarnings("deprecation") // Bukkit.getUnsafe()
    public static void apply(BlockState state, String raw) {
        JsonObject json = JsonParser.parseString(raw).getAsJsonObject();

        if (state instanceof Nameable nameable && json.has("name")) {
            nameable.customName(component(json.get("name").getAsString()));
        }
        if (state instanceof TileState tile && json.has("pdc")) {
            try {
                tile.getPersistentDataContainer().readFromBytes(Base64.getDecoder().decode(json.get("pdc").getAsString()), true);
            } catch (IOException ignored) {
                // corrupted plugin data, skipping
            }
        }
        if (state instanceof Lootable lootable && json.has("loot")) {
            JsonObject data = json.getAsJsonObject("loot");
            LootTable table = lootTable(data.get("table"));
            if (table != null) lootable.setLootTable(table, data.get("seed").getAsLong());
        }
        if (state instanceof Lockable lockable && json.has("lock")) lockable.setLock(json.get("lock").getAsString());

        switch (state) {
            case Sign sign when json.has("sign") -> {
                JsonObject data = json.getAsJsonObject("sign");
                applySide(sign.getSide(Side.FRONT), data.getAsJsonObject("front"));
                applySide(sign.getSide(Side.BACK), data.getAsJsonObject("back"));
                sign.setWaxed(data.get("waxed").getAsBoolean());
            }
            case Banner banner when json.has("banner") -> {
                List<Pattern> patterns = new ArrayList<>();
                for (JsonElement element : json.getAsJsonArray("banner")) {
                    JsonObject data = element.getAsJsonObject();
                    PatternType type = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN).get(key(data.get("type")));
                    DyeColor color = dyeColor(data.get("color").getAsString());
                    if (type != null && color != null) patterns.add(new Pattern(color, type));
                }
                banner.setPatterns(patterns);
            }
            case Skull skull when json.has("skull") -> {
                JsonObject data = json.getAsJsonObject("skull");
                if (data.has("id") || data.has("name")) {
                    PlayerProfile profile = Bukkit.createProfile(
                            data.has("id") ? UUID.fromString(data.get("id").getAsString()) : null,
                            data.has("name") ? data.get("name").getAsString() : null);
                    for (JsonElement element : data.getAsJsonArray("properties")) {
                        JsonObject p = element.getAsJsonObject();
                        profile.setProperty(new ProfileProperty(p.get("name").getAsString(), p.get("value").getAsString(),
                                p.has("signature") ? p.get("signature").getAsString() : null));
                    }
                    skull.setPlayerProfile(profile);
                }
                if (data.has("sound")) skull.setNoteBlockSound(key(data.get("sound")));
                if (data.has("customName")) skull.customName(component(data.get("customName").getAsString()));
            }
            case CreatureSpawner spawner when json.has("spawner") -> {
                JsonObject data = json.getAsJsonObject("spawner");
                applySpawner(spawner, data);
                // Paper rejects min > max at every step, so the order depends on the direction
                int min = data.get("minDelay").getAsInt(), max = data.get("maxDelay").getAsInt();
                if (max >= spawner.getMinSpawnDelay()) {
                    spawner.setMaxSpawnDelay(max);
                    spawner.setMinSpawnDelay(min);
                } else {
                    spawner.setMinSpawnDelay(min);
                    spawner.setMaxSpawnDelay(max);
                }
                spawner.setDelay(data.get("delay").getAsInt());
                spawner.setSpawnCount(data.get("count").getAsInt());
                spawner.setMaxNearbyEntities(data.get("maxNearby").getAsInt());
            }
            case TrialSpawner trial when json.has("trialSpawner") -> {
                JsonObject data = json.getAsJsonObject("trialSpawner");
                trial.setCooldownLength(data.get("cooldown").getAsInt());
                trial.setRequiredPlayerRange(data.get("playerRange").getAsInt());
                applyTrialConfig(trial.getNormalConfiguration(), data.getAsJsonObject("normal"));
                applyTrialConfig(trial.getOminousConfiguration(), data.getAsJsonObject("ominousConfig"));
                trial.setOminous(data.get("ominous").getAsBoolean());
            }
            case Vault vault when json.has("vault") -> {
                JsonObject data = json.getAsJsonObject("vault");
                double activation = data.get("activation").getAsDouble(), deactivation = data.get("deactivation").getAsDouble();
                // activation must stay <= deactivation at every step
                if (activation <= vault.getDeactivationRange()) {
                    vault.setActivationRange(activation);
                    vault.setDeactivationRange(deactivation);
                } else {
                    vault.setDeactivationRange(deactivation);
                    vault.setActivationRange(activation);
                }
                if (data.has("key")) vault.setKeyItem(item(data.get("key").getAsString()));
                if (data.has("displayed")) vault.setDisplayedItem(item(data.get("displayed").getAsString()));
                if (data.has("loot")) {
                    LootTable table = lootTable(data.get("loot"));
                    if (table != null) vault.setLootTable(table);
                }
                if (data.has("displayedLoot")) vault.setDisplayedLootTable(lootTable(data.get("displayedLoot")));
                for (JsonElement player : data.getAsJsonArray("rewarded")) vault.addRewardedPlayer(UUID.fromString(player.getAsString()));
            }
            case Structure structure when json.has("structure") -> {
                JsonObject data = json.getAsJsonObject("structure");
                structure.setStructureName(data.get("name").getAsString());
                structure.setAuthor(data.get("author").getAsString());
                structure.setRelativePosition(blockVector(data.getAsJsonObject("position")));
                structure.setStructureSize(blockVector(data.getAsJsonObject("size")));
                structure.setMirror(Mirror.valueOf(data.get("mirror").getAsString()));
                structure.setRotation(StructureRotation.valueOf(data.get("rotation").getAsString()));
                structure.setUsageMode(UsageMode.valueOf(data.get("mode").getAsString()));
                structure.setIgnoreEntities(data.get("ignoreEntities").getAsBoolean());
                structure.setShowAir(data.get("showAir").getAsBoolean());
                structure.setBoundingBoxVisible(data.get("boundingBox").getAsBoolean());
                structure.setIntegrity(data.get("integrity").getAsFloat());
                structure.setSeed(data.get("seed").getAsLong());
                structure.setMetadata(data.get("metadata").getAsString());
            }
            case EndGateway gateway when json.has("gateway") -> {
                JsonObject data = json.getAsJsonObject("gateway");
                if (data.has("exit")) gateway.setExitLocation(gateway.getLocation().add(blockVector(data.getAsJsonObject("exit"))));
                gateway.setExactTeleport(data.get("exact").getAsBoolean());
                gateway.setAge(data.get("age").getAsLong());
            }
            case SculkSensor sensor when json.has("sculkSensor") -> {
                JsonObject data = json.getAsJsonObject("sculkSensor");
                sensor.setLastVibrationFrequency(data.get("frequency").getAsInt());
                sensor.setListenerRange(data.get("range").getAsInt());
            }
            case SculkShrieker shrieker when json.has("shrieker") -> shrieker.setWarningLevel(json.get("shrieker").getAsInt());
            case Hopper hopper when json.has("hopper") -> hopper.setTransferCooldown(json.get("hopper").getAsInt());
            case Beacon beacon when json.has("beacon") -> {
                JsonObject data = json.getAsJsonObject("beacon");
                if (data.has("primary")) beacon.setPrimaryEffect(effect(data.get("primary")));
                if (data.has("secondary")) beacon.setSecondaryEffect(effect(data.get("secondary")));
            }
            case Beehive hive when json.has("bees") || json.has("flower") -> {
                Location location = hive.getLocation().toCenterLocation();
                for (JsonElement element : json.has("bees") ? json.getAsJsonArray("bees") : new JsonArray()) {
                    Entity entity = Bukkit.getUnsafe().deserializeEntity(Base64.getDecoder().decode(element.getAsString()), hive.getWorld());
                    if (!(entity instanceof Bee bee)) continue;
                    bee.spawnAt(location);
                    hive.addEntity(bee); // stored in the snapshot, written by the caller's update()
                }
                if (json.has("flower")) hive.setFlower(hive.getLocation().add(blockVector(json.getAsJsonObject("flower"))));
            }
            case Lectern lectern when json.has("lectern") -> lectern.setPage(json.get("lectern").getAsInt());
            case DecoratedPot pot when json.has("sherds") -> {
                for (var entry : json.getAsJsonObject("sherds").entrySet()) {
                    Material material = Registry.MATERIAL.get(key(entry.getValue()));
                    if (material != null) pot.setSherd(DecoratedPot.Side.valueOf(entry.getKey()), material);
                }
            }
            case Crafter crafter when json.has("crafter") -> {
                JsonObject data = json.getAsJsonObject("crafter");
                for (JsonElement slot : data.getAsJsonArray("disabled")) crafter.setSlotDisabled(slot.getAsInt(), true);
                crafter.setTriggered(data.get("triggered").getAsBoolean());
            }
            case Campfire campfire when json.has("campfire") -> {
                JsonArray slots = json.getAsJsonArray("campfire");
                for (int i = 0; i < Math.min(campfire.getSize(), slots.size()); i++) {
                    JsonObject slot = slots.get(i).getAsJsonObject();
                    if (!slot.has("item")) continue;
                    campfire.setItem(i, item(slot.get("item").getAsString()));
                    campfire.setCookTime(i, slot.get("cook").getAsInt());
                    campfire.setCookTimeTotal(i, slot.get("total").getAsInt());
                }
            }
            case BrushableBlock brushable when json.has("brushable") -> brushable.setItem(item(json.get("brushable").getAsString()));
            case CommandBlock command when json.has("command") -> command.setCommand(json.get("command").getAsString());
            case Furnace furnace when json.has("furnace") -> {
                JsonObject data = json.getAsJsonObject("furnace");
                furnace.setBurnTime(data.get("burn").getAsShort());
                furnace.setCookTime(data.get("cook").getAsShort());
                furnace.setCookTimeTotal(data.get("total").getAsInt());
                if (data.has("recipes")) {
                    for (var entry : data.getAsJsonObject("recipes").entrySet()) {
                        NamespacedKey recipeKey = NamespacedKey.fromString(entry.getKey());
                        Recipe recipe = recipeKey == null ? null : Bukkit.getRecipe(recipeKey);
                        if (recipe instanceof CookingRecipe<?> cooking) furnace.setRecipeUsedCount(cooking, entry.getValue().getAsInt());
                    }
                }
            }
            case BrewingStand brewing when json.has("brewing") -> {
                JsonObject data = json.getAsJsonObject("brewing");
                brewing.setBrewingTime(data.get("time").getAsInt());
                brewing.setFuelLevel(data.get("fuel").getAsInt());
            }
            default -> {
            }
        }
    }

    /**
     * The API has no read access to the bees: they are released, serialized, then put back in the
     * same tick. Their time spent in the hive restarts at 0.
     */
    @SuppressWarnings("deprecation") // Bukkit.getUnsafe()
    private static JsonArray captureBees(Beehive snapshot) {
        // live state: on a snapshot, addEntity would put the bees back in the copy only
        Beehive hive = (Beehive) snapshot.getBlock().getState(false);
        JsonArray bees = new JsonArray();
        for (Bee bee : hive.releaseEntities()) {
            try {
                bees.add(Base64.getEncoder().encodeToString(Bukkit.getUnsafe().serializeEntity(bee)));
            } catch (RuntimeException ignored) {
                // bee not serializable, it stays in the world
            }
            if (bee.isValid()) hive.addEntity(bee);
        }
        return bees;
    }

    private static JsonObject captureSpawner(BaseSpawner spawner) {
        JsonObject data = new JsonObject();
        EntitySnapshot entity = spawner.getSpawnedEntity();
        if (entity != null) data.addProperty("entity", entity.getAsString());
        else if (spawner.getSpawnedType() != null) data.addProperty("type", spawner.getSpawnedType().getKey().toString());
        data.addProperty("delay", spawner.getDelay());
        data.addProperty("playerRange", spawner.getRequiredPlayerRange());
        data.addProperty("spawnRange", spawner.getSpawnRange());

        JsonArray potentials = new JsonArray();
        for (SpawnerEntry entry : spawner.getPotentialSpawns()) {
            JsonObject p = new JsonObject();
            p.addProperty("entity", entry.getSnapshot().getAsString());
            p.addProperty("weight", entry.getSpawnWeight());
            SpawnRule rule = entry.getSpawnRule();
            if (rule != null) {
                JsonArray light = new JsonArray();
                light.add(rule.getMinBlockLight());
                light.add(rule.getMaxBlockLight());
                light.add(rule.getMinSkyLight());
                light.add(rule.getMaxSkyLight());
                p.add("rule", light);
            }
            SpawnerEntry.Equipment equipment = entry.getEquipment();
            if (equipment != null) {
                JsonObject e = new JsonObject();
                e.addProperty("table", equipment.getEquipmentLootTable().getKey().toString());
                JsonObject drops = new JsonObject();
                equipment.getDropChances().forEach((slot, chance) -> drops.addProperty(slot.name(), chance));
                e.add("drops", drops);
                p.add("equipment", e);
            }
            potentials.add(p);
        }
        if (!potentials.isEmpty()) data.add("potentials", potentials);
        return data;
    }

    private static void applySpawner(BaseSpawner spawner, JsonObject data) {
        try {
            if (data.has("entity")) {
                spawner.setSpawnedEntity(Bukkit.getEntityFactory().createEntitySnapshot(data.get("entity").getAsString()));
            } else if (data.has("type")) {
                spawner.setSpawnedType(RegistryAccess.registryAccess().getRegistry(RegistryKey.ENTITY_TYPE).get(key(data.get("type"))));
            }
        } catch (IllegalArgumentException ignored) {
            // unknown entity, keep the empty spawner
        }
        spawner.setDelay(data.get("delay").getAsInt());
        spawner.setRequiredPlayerRange(data.get("playerRange").getAsInt());
        spawner.setSpawnRange(data.get("spawnRange").getAsInt());

        if (!data.has("potentials")) return;
        List<SpawnerEntry> entries = new ArrayList<>();
        for (JsonElement element : data.getAsJsonArray("potentials")) {
            JsonObject p = element.getAsJsonObject();
            try {
                EntitySnapshot snapshot = Bukkit.getEntityFactory().createEntitySnapshot(p.get("entity").getAsString());
                SpawnRule rule = null;
                if (p.has("rule")) {
                    JsonArray light = p.getAsJsonArray("rule");
                    rule = new SpawnRule(light.get(0).getAsInt(), light.get(1).getAsInt(), light.get(2).getAsInt(), light.get(3).getAsInt());
                }
                SpawnerEntry.Equipment equipment = null;
                if (p.has("equipment")) {
                    JsonObject e = p.getAsJsonObject("equipment");
                    LootTable table = lootTable(e.get("table"));
                    if (table != null) {
                        Map<EquipmentSlot, Float> drops = new HashMap<>();
                        for (var drop : e.getAsJsonObject("drops").entrySet()) {
                            drops.put(EquipmentSlot.valueOf(drop.getKey()), drop.getValue().getAsFloat());
                        }
                        equipment = new SpawnerEntry.Equipment(table, drops);
                    }
                }
                entries.add(new SpawnerEntry(snapshot, p.get("weight").getAsInt(), rule, equipment));
            } catch (IllegalArgumentException ignored) {
                // unknown entity, skipping this entry
            }
        }
        spawner.setPotentialSpawns(entries);
    }

    private static JsonObject captureTrialConfig(TrialSpawnerConfiguration config) {
        JsonObject data = captureSpawner(config);
        data.addProperty("baseSpawns", config.getBaseSpawnsBeforeCooldown());
        data.addProperty("baseSimultaneous", config.getBaseSimultaneousEntities());
        data.addProperty("additionalSpawns", config.getAdditionalSpawnsBeforeCooldown());
        data.addProperty("additionalSimultaneous", config.getAdditionalSimultaneousEntities());
        JsonObject rewards = new JsonObject();
        config.getPossibleRewards().forEach((table, weight) -> rewards.addProperty(table.getKey().toString(), weight));
        data.add("rewards", rewards);
        return data;
    }

    private static void applyTrialConfig(TrialSpawnerConfiguration config, JsonObject data) {
        applySpawner(config, data);
        config.setBaseSpawnsBeforeCooldown(data.get("baseSpawns").getAsFloat());
        config.setBaseSimultaneousEntities(data.get("baseSimultaneous").getAsFloat());
        config.setAdditionalSpawnsBeforeCooldown(data.get("additionalSpawns").getAsFloat());
        config.setAdditionalSimultaneousEntities(data.get("additionalSimultaneous").getAsFloat());
        Map<LootTable, Integer> rewards = new HashMap<>();
        for (var entry : data.getAsJsonObject("rewards").entrySet()) {
            LootTable table = lootTable(new com.google.gson.JsonPrimitive(entry.getKey()));
            if (table != null) rewards.put(table, entry.getValue().getAsInt());
        }
        config.setPossibleRewards(rewards);
    }

    private static JsonObject vector(Vector vector) {
        JsonObject data = new JsonObject();
        data.addProperty("x", vector.getBlockX());
        data.addProperty("y", vector.getBlockY());
        data.addProperty("z", vector.getBlockZ());
        return data;
    }

    private static BlockVector blockVector(JsonObject data) {
        return new BlockVector(data.get("x").getAsInt(), data.get("y").getAsInt(), data.get("z").getAsInt());
    }

    private static LootTable lootTable(JsonElement key) {
        NamespacedKey namespacedKey = key(key);
        return namespacedKey == null ? null : Bukkit.getLootTable(namespacedKey);
    }

    private static JsonObject captureSide(SignSide side) {
        JsonArray lines = new JsonArray();
        for (Component line : side.lines()) lines.add(component(line));
        JsonObject data = new JsonObject();
        data.add("lines", lines);
        DyeColor color = side.getColor();
        data.addProperty("color", color == null ? DyeColor.BLACK.name() : color.name());
        data.addProperty("glowing", side.isGlowingText());
        return data;
    }

    private static void applySide(SignSide side, JsonObject data) {
        JsonArray lines = data.getAsJsonArray("lines");
        for (int i = 0; i < Math.min(4, lines.size()); i++) side.line(i, component(lines.get(i).getAsString()));
        DyeColor color = dyeColor(data.get("color").getAsString());
        if (color != null) side.setColor(color);
        side.setGlowingText(data.get("glowing").getAsBoolean());
    }

    private static String component(Component component) {
        return GsonComponentSerializer.gson().serialize(component);
    }

    private static Component component(String json) {
        return GsonComponentSerializer.gson().deserialize(json);
    }

    private static String item(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private static ItemStack item(String base64) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(base64));
    }

    private static String effectKey(PotionEffect effect) {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).getKeyOrThrow(effect.getType()).toString();
    }

    private static PotionEffectType effect(JsonElement key) {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).get(key(key));
    }

    private static NamespacedKey key(JsonElement element) {
        return NamespacedKey.fromString(element.getAsString());
    }

    private static DyeColor dyeColor(String name) {
        try {
            return DyeColor.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
