package ru.dvolk.roulettechest.listener;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import ru.dvolk.roulettechest.RouletteChestPlugin;
import ru.dvolk.roulettechest.data.MarkerStore;
import ru.dvolk.roulettechest.model.Pool;
import ru.dvolk.roulettechest.roulette.RouletteHolder;
import ru.dvolk.roulettechest.roulette.RouletteService;

public final class ChestInteractListener implements Listener {

    private final RouletteChestPlugin plugin;
    private final MarkerStore markers;
    private final RouletteService roulette;

    public ChestInteractListener(RouletteChestPlugin plugin, MarkerStore markers, RouletteService roulette) {
        this.plugin = plugin;
        this.markers = markers;
        this.roulette = roulette;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || !isSupported(block.getType())) return;
        if (event.getPlayer().isSneaking()) return;

        Block marker = resolveMarkerBlock(block);
        String poolId = markers.poolFor(marker);
        if (poolId == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (!player.hasPermission("roulettechest.use")) {
            player.sendMessage("§cУ вас нет доступа к рулетке.");
            return;
        }

        Pool pool = plugin.getPoolRegistry().get(poolId);
        if (pool == null) {
            player.sendMessage("§cПул '" + poolId + "' не найден в config.yml.");
            return;
        }

        if (roulette.isSpinning(player.getUniqueId())) return;

        boolean bypass = player.hasPermission("roulettechest.cooldown.bypass");
        if (!bypass) {
            long waitMs = roulette.remainingCooldownMillis(pool, player.getUniqueId(), marker);
            if (waitMs > 0) {
                String template = pool.cooldownMessage();
                if (template != null && !template.isBlank()) {
                    String msg = template
                            .replace("{time}", RouletteService.formatDuration(waitMs))
                            .replace("{pool}", pool.id())
                            .replace("{player}", player.getName());
                    player.sendMessage(plugin.getPoolRegistry().colorize(msg));
                }
                return;
            }
        }

        RouletteService.StartResult result = roulette.tryStart(player, pool, marker, bypass);
        if (result == RouletteService.StartResult.BUSY_SOLO) {
            String template = pool.busyMessage();
            if (template != null && !template.isBlank()) {
                String ownerName = roulette.ownerNameOf(marker);
                String msg = template
                        .replace("{pool}", pool.id())
                        .replace("{player}", ownerName == null ? "?" : ownerName);
                player.sendMessage(plugin.getPoolRegistry().colorize(msg));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof RouletteHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof RouletteHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof RouletteHolder) {
            roulette.handleClose(event.getPlayer().getUniqueId());
        }
    }

    private boolean isSupported(Material type) {
        return type == Material.CHEST
                || type == Material.TRAPPED_CHEST
                || type == Material.ENDER_CHEST
                || Tag.SHULKER_BOXES.isTagged(type);
    }

    /**
     * If the clicked block is one half of a double chest, mark storage on either half counts.
     */
    private Block resolveMarkerBlock(Block block) {
        if (block.getState() instanceof Chest chestState) {
            var holder = chestState.getInventory().getHolder();
            if (holder instanceof DoubleChest dc) {
                if (markers.isMarked(block)) return block;
                Chest left = (Chest) dc.getLeftSide();
                Chest right = (Chest) dc.getRightSide();
                if (left != null && markers.isMarked(left.getBlock())) return left.getBlock();
                if (right != null && markers.isMarked(right.getBlock())) return right.getBlock();
            }
        }
        return block;
    }
}
