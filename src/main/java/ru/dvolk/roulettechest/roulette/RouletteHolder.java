package ru.dvolk.roulettechest.roulette;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class RouletteHolder implements InventoryHolder {

    @Override
    public @NotNull Inventory getInventory() {
        return Bukkit.createInventory(this, RouletteService.SIZE);
    }
}
