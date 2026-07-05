package ru.dvolk.roulettechest.command;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;
import ru.dvolk.roulettechest.RouletteChestPlugin;
import ru.dvolk.roulettechest.data.MarkerStore;
import ru.dvolk.roulettechest.data.PoolRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class RouletteChestCommand implements TabExecutor {

    private final RouletteChestPlugin plugin;
    private final MarkerStore markers;
    private final PoolRegistry pools;

    public RouletteChestCommand(RouletteChestPlugin plugin, MarkerStore markers, PoolRegistry pools) {
        this.plugin = plugin;
        this.markers = markers;
        this.pools = pools;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e/rchest set <pool> | unset | list | reload");
            return true;
        }

        String sub = args[0].toLowerCase();
        return switch (sub) {
            case "set" -> handleSet(sender, args);
            case "unset" -> handleUnset(sender);
            case "list" -> handleList(sender);
            case "reload" -> handleReload(sender);
            default -> {
                sender.sendMessage("§cНеизвестная подкоманда: " + sub);
                yield true;
            }
        };
    }

    private boolean handleSet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игроков.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§eИспользование: /rchest set <pool>");
            return true;
        }
        String poolId = args[1];
        if (!pools.has(poolId)) {
            sender.sendMessage("§cПул '" + poolId + "' не найден. Известные: " + String.join(", ", pools.ids()));
            return true;
        }
        Block target = targetContainer(player);
        if (target == null) {
            sender.sendMessage("§cПосмотрите на сундук, эндер-сундук или шалкер.");
            return true;
        }
        markers.mark(target, poolId.toLowerCase());
        sender.sendMessage("§aБлок отмечен как рулетка (пул '" + poolId + "').");
        return true;
    }

    private boolean handleUnset(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игроков.");
            return true;
        }
        Block target = targetContainer(player);
        if (target == null) {
            sender.sendMessage("§cПосмотрите на сундук, эндер-сундук или шалкер.");
            return true;
        }
        if (markers.unmark(target)) {
            sender.sendMessage("§aМетка снята.");
        } else {
            sender.sendMessage("§7Этот блок не был отмечен.");
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        Map<String, String> all = markers.all();
        if (all.isEmpty()) {
            sender.sendMessage("§7Нет отмеченных блоков.");
            return true;
        }
        sender.sendMessage("§eОтмеченные блоки (" + all.size() + "):");
        all.forEach((key, poolId) -> {
            Location loc = MarkerStore.parseKey(key);
            String where = loc == null
                    ? key
                    : loc.getWorld().getName() + " " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
            sender.sendMessage("  §7" + where + " §8→ §f" + poolId);
        });
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        pools.load();
        sender.sendMessage("§aКонфиг перезагружен. Пулов: " + pools.ids().size());
        return true;
    }

    private Block targetContainer(Player player) {
        RayTraceResult trace = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                6.0,
                FluidCollisionMode.NEVER,
                true);
        if (trace == null || trace.getHitBlock() == null) return null;
        Block block = trace.getHitBlock();
        Material type = block.getType();
        if (type == Material.CHEST
                || type == Material.TRAPPED_CHEST
                || type == Material.ENDER_CHEST
                || Tag.SHULKER_BOXES.isTagged(type)) {
            return block;
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("set", "unset", "list", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return filter(new ArrayList<>(pools.ids()), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        return options.stream().filter(s -> s.toLowerCase().startsWith(p)).toList();
    }
}
