package ru.dvolk.roulettechest;

import org.bukkit.plugin.java.JavaPlugin;
import ru.dvolk.roulettechest.command.RouletteChestCommand;
import ru.dvolk.roulettechest.data.CooldownStore;
import ru.dvolk.roulettechest.data.MarkerStore;
import ru.dvolk.roulettechest.data.PoolRegistry;
import ru.dvolk.roulettechest.listener.ChestInteractListener;
import ru.dvolk.roulettechest.roulette.RouletteService;

public final class RouletteChestPlugin extends JavaPlugin {

    private PoolRegistry poolRegistry;
    private MarkerStore markerStore;
    private CooldownStore cooldownStore;
    private RouletteService rouletteService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.poolRegistry = new PoolRegistry(this);
        this.poolRegistry.load();

        this.markerStore = new MarkerStore(this);
        this.markerStore.load();

        this.cooldownStore = new CooldownStore(this);
        this.cooldownStore.load();

        this.rouletteService = new RouletteService(this, poolRegistry, cooldownStore);

        getServer().getPluginManager().registerEvents(
                new ChestInteractListener(this, markerStore, rouletteService), this);

        RouletteChestCommand command = new RouletteChestCommand(this, markerStore, poolRegistry);
        getCommand("rchest").setExecutor(command);
        getCommand("rchest").setTabCompleter(command);
    }

    @Override
    public void onDisable() {
        if (markerStore != null) markerStore.save();
        if (cooldownStore != null) cooldownStore.save();
        if (rouletteService != null) rouletteService.cancelAll();
    }

    public PoolRegistry getPoolRegistry() {
        return poolRegistry;
    }

    public MarkerStore getMarkerStore() {
        return markerStore;
    }
}
