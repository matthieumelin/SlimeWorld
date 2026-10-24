package fr.geeklegend.slimeworld.capture;

import io.papermc.paper.block.TileStateInventoryHolder;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;

public class InventoryAccess {

    private InventoryAccess() {
    }

    public static Inventory of(BlockState state) {
        if (state instanceof Chest chest) return chest.getBlockInventory();
        if (state instanceof Container container) return container.getInventory();
        // lectern, jukebox, decorated pot, chiseled bookshelf, shelf
        if (state instanceof TileStateInventoryHolder holder) return holder.getInventory();
        return null;
    }
}
