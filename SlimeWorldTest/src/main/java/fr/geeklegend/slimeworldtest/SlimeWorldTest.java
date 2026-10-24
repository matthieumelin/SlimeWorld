package fr.geeklegend.slimeworldtest;

import fr.geeklegend.slimeworld.SlimeWorldManager;
import fr.geeklegend.slimeworld.storage.FileStorage;
import fr.geeklegend.slimeworld.storage.Storage;
import fr.geeklegend.slimeworldtest.command.IslandCommand;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

public class SlimeWorldTest extends JavaPlugin {
    private SlimeWorldManager slimeWorldManager;

    @Override
    public void onEnable() {
        try {
            Storage storage = new FileStorage(getDataFolder().toPath().resolve("islands"));
            slimeWorldManager = new SlimeWorldManager(this, storage);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(IslandCommand.build(slimeWorldManager), "Commandes des îles"));
    }

    @Override
    public void onDisable() {
        slimeWorldManager.shutdown();
    }
}
