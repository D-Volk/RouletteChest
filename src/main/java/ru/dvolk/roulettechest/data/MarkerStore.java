package ru.dvolk.roulettechest.data;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class MarkerStore {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, String> markers = new HashMap<>();

    public MarkerStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "markers.yml");
    }

    public void load() {
        markers.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            String pool = yaml.getString(key);
            if (pool != null && !pool.isBlank()) {
                markers.put(key, pool);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        markers.forEach(yaml::set);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save markers.yml: " + e.getMessage());
        }
    }

    public String poolFor(Block block) {
        return markers.get(key(block));
    }

    public boolean isMarked(Block block) {
        return markers.containsKey(key(block));
    }

    public void mark(Block block, String pool) {
        markers.put(key(block), pool);
        save();
    }

    public boolean unmark(Block block) {
        boolean removed = markers.remove(key(block)) != null;
        if (removed) save();
        return removed;
    }

    public Map<String, String> all() {
        return Collections.unmodifiableMap(markers);
    }

    public static Location parseKey(String key) {
        String[] parts = key.split(";");
        if (parts.length != 4) return null;
        World world = org.bukkit.Bukkit.getWorld(parts[0]);
        if (world == null) return null;
        try {
            return new Location(world,
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String key(Block block) {
        return block.getWorld().getName() + ";" + block.getX() + ";" + block.getY() + ";" + block.getZ();
    }
}
