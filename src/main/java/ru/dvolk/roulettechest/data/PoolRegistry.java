package ru.dvolk.roulettechest.data;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import ru.dvolk.roulettechest.model.Pool;
import ru.dvolk.roulettechest.model.Prize;
import ru.dvolk.roulettechest.model.Reward;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PoolRegistry {

    private static final LegacyComponentSerializer LEGACY_AMPERSAND =
            LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private final Map<String, Pool> pools = new HashMap<>();

    private long durationTicks = 60L;
    private long startIntervalTicks = 1L;
    private long endIntervalTicks = 8L;
    private Sound tickSound = Sound.BLOCK_NOTE_BLOCK_HAT;
    private Sound winSound = Sound.ENTITY_PLAYER_LEVELUP;

    public PoolRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.reloadConfig();
        pools.clear();

        var config = plugin.getConfig();
        var anim = config.getConfigurationSection("animation");
        if (anim != null) {
            durationTicks = anim.getLong("duration-ticks", durationTicks);
            startIntervalTicks = Math.max(1, anim.getLong("start-interval-ticks", startIntervalTicks));
            endIntervalTicks = Math.max(startIntervalTicks, anim.getLong("end-interval-ticks", endIntervalTicks));
            tickSound = parseSound(anim.getString("tick-sound"), tickSound);
            winSound = parseSound(anim.getString("win-sound"), winSound);
        }

        var poolsSection = config.getConfigurationSection("pools");
        if (poolsSection == null) {
            plugin.getLogger().warning("No 'pools' section in config.yml — plugin will do nothing.");
            return;
        }

        for (String id : poolsSection.getKeys(false)) {
            var section = poolsSection.getConfigurationSection(id);
            if (section == null) continue;
            try {
                Pool pool = readPool(id, section);
                pools.put(id.toLowerCase(), pool);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Skipping pool '" + id + "': " + e.getMessage());
            }
        }

        plugin.getLogger().info("Loaded " + pools.size() + " roulette pool(s).");
    }

    public Pool get(String id) {
        return id == null ? null : pools.get(id.toLowerCase());
    }

    public boolean has(String id) {
        return get(id) != null;
    }

    public java.util.Collection<String> ids() {
        return Collections.unmodifiableCollection(pools.keySet());
    }

    public long durationTicks() { return durationTicks; }
    public long startIntervalTicks() { return startIntervalTicks; }
    public long endIntervalTicks() { return endIntervalTicks; }
    public Sound tickSound() { return tickSound; }
    public Sound winSound() { return winSound; }

    private Pool readPool(String id, ConfigurationSection section) {
        String title = section.getString("title", "&6Рулетка");
        List<Prize> prizes = new ArrayList<>();
        List<Map<?, ?>> prizeList = section.getMapList("prizes");
        for (Map<?, ?> raw : prizeList) {
            prizes.add(readPrize(raw));
        }
        if (prizes.isEmpty()) {
            throw new IllegalArgumentException("no prizes defined");
        }

        long cooldownSeconds = section.getLong("cooldown-seconds", 0L);
        Pool.CooldownScope scope;
        try {
            scope = Pool.CooldownScope.valueOf(
                    section.getString("cooldown-scope", "PLAYER").toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Pool '" + id + "': unknown cooldown-scope, using PLAYER.");
            scope = Pool.CooldownScope.PLAYER;
        }

        Pool.Concurrency concurrency;
        try {
            concurrency = Pool.Concurrency.valueOf(
                    section.getString("concurrency", "SPECTATE").toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Pool '" + id + "': unknown concurrency, using SPECTATE.");
            concurrency = Pool.Concurrency.SPECTATE;
        }

        String winMessage = section.getString("win-message",
                "&aВы выиграли &f{prize}&a!");
        String cooldownMessage = section.getString("cooldown-message",
                "&cПодождите &e{time}&c перед следующей попыткой.");
        String broadcastMessage = section.getString("broadcast-message", "");
        String busyMessage = section.getString("busy-message",
                "&cСундук занят: &e{player}&c уже крутит рулетку.");

        return new Pool(id, title, prizes, cooldownSeconds, scope, concurrency,
                winMessage, cooldownMessage, broadcastMessage, busyMessage);
    }

    @SuppressWarnings("unchecked")
    private Prize readPrize(Map<?, ?> raw) {
        int weight = raw.get("weight") instanceof Number n ? n.intValue() : 1;

        Map<String, Object> displayMap = (Map<String, Object>) raw.get("display");
        if (displayMap == null) {
            throw new IllegalArgumentException("prize missing 'display'");
        }
        ItemStack display = buildDisplayItem(displayMap);

        Reward reward = parseReward(raw.get("reward"));

        String label = displayMap.get("name") instanceof String s
                ? s
                : display.getType().name().toLowerCase().replace('_', ' ');

        return new Prize(weight, display, reward, label);
    }

    /**
     * Accepts either a single reward map, a list of reward maps, or null.
     * Nested lists are flattened.
     */
    @SuppressWarnings("unchecked")
    private Reward parseReward(Object node) {
        if (node == null) return new Reward.NoneReward();
        if (node instanceof List<?> list) {
            List<Reward> rewards = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Reward r = buildReward((Map<String, Object>) m);
                    if (r instanceof Reward.MultiReward multi) rewards.addAll(multi.rewards());
                    else if (!(r instanceof Reward.NoneReward)) rewards.add(r);
                }
            }
            if (rewards.isEmpty()) return new Reward.NoneReward();
            if (rewards.size() == 1) return rewards.get(0);
            return new Reward.MultiReward(List.copyOf(rewards));
        }
        if (node instanceof Map<?, ?> map) {
            return buildReward((Map<String, Object>) map);
        }
        return new Reward.NoneReward();
    }

    private ItemStack buildDisplayItem(Map<String, Object> map) {
        Material material = Material.matchMaterial(String.valueOf(map.getOrDefault("material", "STONE")));
        if (material == null || material.isAir()) material = Material.STONE;
        int amount = map.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1;
        ItemStack stack = new ItemStack(material, amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String name = map.get("name") instanceof String s ? s : null;
            if (name != null) {
                meta.displayName(colorize(name).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            }
            Object loreObj = map.get("lore");
            if (loreObj instanceof List<?> loreList) {
                List<Component> lore = new ArrayList<>();
                for (Object line : loreList) {
                    lore.add(colorize(String.valueOf(line))
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                }
                meta.lore(lore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    @SuppressWarnings("unchecked")
    private Reward buildReward(Map<String, Object> map) {
        if (map == null) return new Reward.NoneReward();
        String type = String.valueOf(map.getOrDefault("type", "NONE")).toUpperCase();
        return switch (type) {
            case "ITEM" -> new Reward.ItemReward(buildDisplayItem(map));
            case "COMMAND" -> new Reward.CommandReward(String.valueOf(map.getOrDefault("command", "")));
            case "ITEMS" -> {
                List<Reward> list = new ArrayList<>();
                Object itemsNode = map.get("items");
                if (itemsNode instanceof List<?> items) {
                    for (Object entry : items) {
                        if (entry instanceof Map<?, ?> m) {
                            list.add(new Reward.ItemReward(buildDisplayItem((Map<String, Object>) m)));
                        }
                    }
                }
                yield wrapMulti(list);
            }
            case "COMMANDS" -> {
                List<Reward> list = new ArrayList<>();
                Object cmdsNode = map.get("commands");
                if (cmdsNode instanceof List<?> cmds) {
                    for (Object entry : cmds) {
                        list.add(new Reward.CommandReward(String.valueOf(entry)));
                    }
                }
                yield wrapMulti(list);
            }
            default -> new Reward.NoneReward();
        };
    }

    private Reward wrapMulti(List<Reward> list) {
        if (list.isEmpty()) return new Reward.NoneReward();
        if (list.size() == 1) return list.get(0);
        return new Reward.MultiReward(List.copyOf(list));
    }

    public Component colorize(String text) {
        if (text == null) return Component.empty();
        if (text.contains("<") && text.contains(">")) {
            try {
                return MiniMessage.miniMessage().deserialize(text);
            } catch (RuntimeException ignored) {
            }
        }
        return LEGACY_AMPERSAND.deserialize(text);
    }

    private Sound parseSound(String name, Sound fallback) {
        if (name == null) return fallback;
        try {
            return Sound.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            Bukkit.getLogger().warning("[RouletteChest] Unknown sound: " + name);
            return fallback;
        }
    }
}
