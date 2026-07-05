package ru.dvolk.roulettechest.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent cooldown storage.
 *
 * File layout (plugins/RouletteChest/cooldowns.yml):
 *
 *   server:
 *     "<poolId>@<world>;<x>;<y>;<z>": <expiresAtEpochMillis>
 *   player:
 *     "<poolId>.<uuid>": <expiresAtEpochMillis>
 *
 * Entries whose timestamp is already in the past are discarded on load, so the file never
 * grows unbounded. Every write hits disk immediately — cooldowns.yml is tiny (dozens of bytes
 * per entry), so cost is negligible even at hundreds of rolls per minute.
 */
public final class CooldownStore {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Long> serverCooldowns = new HashMap<>();
    private final Map<String, Map<UUID, Long>> playerCooldowns = new HashMap<>();

    public CooldownStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "cooldowns.yml");
    }

    public void load() {
        serverCooldowns.clear();
        playerCooldowns.clear();
        if (!file.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        long now = System.currentTimeMillis();
        int purged = 0;

        ConfigurationSection serverSection = yaml.getConfigurationSection("server");
        if (serverSection != null) {
            for (String key : serverSection.getKeys(false)) {
                long expiresAt = serverSection.getLong(key);
                if (expiresAt > now) {
                    serverCooldowns.put(key, expiresAt);
                } else {
                    purged++;
                }
            }
        }

        ConfigurationSection playerSection = yaml.getConfigurationSection("player");
        if (playerSection != null) {
            for (String key : playerSection.getKeys(false)) {
                long expiresAt = playerSection.getLong(key);
                if (expiresAt <= now) { purged++; continue; }
                int dot = key.lastIndexOf('.');
                if (dot <= 0 || dot == key.length() - 1) continue;
                String poolId = key.substring(0, dot);
                UUID uuid;
                try {
                    uuid = UUID.fromString(key.substring(dot + 1));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                playerCooldowns.computeIfAbsent(poolId, k -> new HashMap<>()).put(uuid, expiresAt);
            }
        }

        if (purged > 0) {
            plugin.getLogger().info("Purged " + purged + " expired cooldown entries.");
            save();
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        serverCooldowns.forEach((k, v) -> yaml.set("server." + k, v));
        playerCooldowns.forEach((poolId, map) ->
                map.forEach((uuid, v) -> yaml.set("player." + poolId + "." + uuid, v)));
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save cooldowns.yml: " + e.getMessage());
        }
    }

    public long getServer(String key) {
        return serverCooldowns.getOrDefault(key, 0L);
    }

    public long getPlayer(String poolId, UUID uuid) {
        Map<UUID, Long> map = playerCooldowns.get(poolId);
        return map == null ? 0L : map.getOrDefault(uuid, 0L);
    }

    public void putServer(String key, long expiresAt) {
        serverCooldowns.put(key, expiresAt);
        save();
    }

    public void putPlayer(String poolId, UUID uuid, long expiresAt) {
        playerCooldowns.computeIfAbsent(poolId, k -> new HashMap<>()).put(uuid, expiresAt);
        save();
    }
}
