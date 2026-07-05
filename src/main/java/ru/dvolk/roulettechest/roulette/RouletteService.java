package ru.dvolk.roulettechest.roulette;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Lidded;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import ru.dvolk.roulettechest.data.CooldownStore;
import ru.dvolk.roulettechest.data.PoolRegistry;
import ru.dvolk.roulettechest.model.Pool;
import ru.dvolk.roulettechest.model.Prize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RouletteService {

    public enum StartResult { OK, ALREADY_SPINNING, BUSY_SOLO, SPECTATING }

    static final int REEL_START = 9;
    static final int REEL_END = 17;
    static final int WINNER_SLOT = 13;
    static final int REEL_LEN = REEL_END - REEL_START + 1;
    static final int SIZE = 27;

    private final JavaPlugin plugin;
    private final PoolRegistry registry;
    private final CooldownStore cooldowns;
    private final Map<UUID, RouletteSession> ownerSessions = new HashMap<>();
    private final Map<String, RouletteSession> occupiedBlocks = new HashMap<>();
    private final Map<UUID, RouletteSession> spectatorSessions = new HashMap<>();

    public RouletteService(JavaPlugin plugin, PoolRegistry registry, CooldownStore cooldowns) {
        this.plugin = plugin;
        this.registry = registry;
        this.cooldowns = cooldowns;
    }

    public boolean isSpinning(UUID playerId) {
        return ownerSessions.containsKey(playerId);
    }

    public long remainingCooldownMillis(Pool pool, UUID playerId, Block block) {
        if (pool.cooldownScope() == Pool.CooldownScope.NONE) return 0L;
        long now = System.currentTimeMillis();
        return switch (pool.cooldownScope()) {
            case SERVER -> Math.max(0L, cooldowns.getServer(serverKey(pool, block)) - now);
            case PLAYER -> Math.max(0L, cooldowns.getPlayer(pool.id().toLowerCase(), playerId) - now);
            default -> 0L;
        };
    }

    private void applyCooldown(Pool pool, UUID playerId, Block block) {
        if (pool.cooldownScope() == Pool.CooldownScope.NONE) return;
        long expiresAt = System.currentTimeMillis() + pool.cooldownSeconds() * 1000L;
        switch (pool.cooldownScope()) {
            case SERVER -> cooldowns.putServer(serverKey(pool, block), expiresAt);
            case PLAYER -> cooldowns.putPlayer(pool.id().toLowerCase(), playerId, expiresAt);
            default -> { }
        }
    }

    private String serverKey(Pool pool, Block block) {
        return pool.id().toLowerCase() + "@" + blockKey(block);
    }

    private String blockKey(Block block) {
        return block.getWorld().getName() + ";" + block.getX() + ";" + block.getY() + ";" + block.getZ();
    }

    /**
     * Called when a player clicks a marked container. Applies concurrency policy and either
     * starts a fresh spin, joins as spectator, or refuses.
     */
    public StartResult tryStart(Player player, Pool pool, Block sourceBlock, boolean bypassCooldown) {
        UUID id = player.getUniqueId();
        if (ownerSessions.containsKey(id) || spectatorSessions.containsKey(id)) {
            return StartResult.ALREADY_SPINNING;
        }

        RouletteSession existing = occupiedBlocks.get(blockKey(sourceBlock));
        if (existing != null) {
            return switch (pool.concurrency()) {
                case SOLO -> StartResult.BUSY_SOLO;
                case SPECTATE -> {
                    openAsSpectator(player, existing);
                    yield StartResult.SPECTATING;
                }
                case FREE -> beginSpin(player, pool, sourceBlock, bypassCooldown);
            };
        }
        return beginSpin(player, pool, sourceBlock, bypassCooldown);
    }

    private StartResult beginSpin(Player player, Pool pool, Block sourceBlock, boolean bypassCooldown) {
        Prize winner = pool.rollWeighted();

        Component title = registry.colorize(pool.title());
        Inventory inv = Bukkit.createInventory(new RouletteHolder(), SIZE, title);
        decorateFrame(inv);

        int totalShifts = estimateShifts();
        LinkedList<ItemStack> reel = buildReel(pool, winner, totalShifts);
        for (int i = 0; i < REEL_LEN; i++) {
            inv.setItem(REEL_START + i, reel.get(i));
        }

        RouletteSession session = new RouletteSession(
                player.getUniqueId(), pool, inv, winner, reel, sourceBlock, blockKey(sourceBlock));

        ownerSessions.put(player.getUniqueId(), session);
        if (pool.concurrency() != Pool.Concurrency.FREE) {
            occupiedBlocks.put(session.blockKey, session);
        }

        if (!bypassCooldown) {
            applyCooldown(pool, player.getUniqueId(), sourceBlock);
        }

        playOpenEffect(sourceBlock);
        player.openInventory(inv);
        session.task = new SpinTask(session).runTaskTimer(plugin, 2L, 1L);
        return StartResult.OK;
    }

    private void openAsSpectator(Player player, RouletteSession session) {
        session.spectators.add(player.getUniqueId());
        spectatorSessions.put(player.getUniqueId(), session);
        player.openInventory(session.inventory);
    }

    public void cancelAll() {
        for (RouletteSession s : new ArrayList<>(ownerSessions.values())) {
            if (s.task != null) s.task.cancel();
            releaseBlock(s);
            Player owner = Bukkit.getPlayer(s.playerId);
            if (owner != null) owner.closeInventory();
            for (UUID spectatorId : s.spectators) {
                Player sp = Bukkit.getPlayer(spectatorId);
                if (sp != null) sp.closeInventory();
                spectatorSessions.remove(spectatorId);
            }
        }
        ownerSessions.clear();
        spectatorSessions.clear();
        occupiedBlocks.clear();
    }

    /**
     * Called when any GUI viewer closes the inventory. Owner-close cancels the spin without
     * awarding a prize; spectator-close is a silent detach.
     */
    public void handleClose(UUID viewerId) {
        RouletteSession spectated = spectatorSessions.remove(viewerId);
        if (spectated != null) {
            spectated.spectators.remove(viewerId);
            return;
        }
        RouletteSession session = ownerSessions.remove(viewerId);
        if (session != null) {
            if (session.task != null) session.task.cancel();
            releaseBlock(session);
            evictSpectators(session);
        }
    }

    private void releaseBlock(RouletteSession session) {
        occupiedBlocks.remove(session.blockKey, session);
        playCloseEffect(session.sourceBlock);
    }

    private void evictSpectators(RouletteSession session) {
        for (UUID spectatorId : new ArrayList<>(session.spectators)) {
            spectatorSessions.remove(spectatorId);
            Player sp = Bukkit.getPlayer(spectatorId);
            if (sp != null) sp.closeInventory();
        }
        session.spectators.clear();
    }

    void finish(RouletteSession session) {
        ownerSessions.remove(session.playerId);
        evictSpectators(session);
        releaseBlock(session);

        Player player = Bukkit.getPlayer(session.playerId);
        if (player == null) return;
        session.winner.reward().grant(player);
        player.playSound(player.getLocation(), registry.winSound(), 1f, 1f);

        String prizeLabel = session.winner.label() == null ? "" : session.winner.label();

        String winTemplate = session.pool.winMessage();
        if (winTemplate != null && !winTemplate.isBlank()) {
            String msg = winTemplate
                    .replace("{prize}", prizeLabel)
                    .replace("{pool}", session.pool.id())
                    .replace("{player}", player.getName());
            player.sendMessage(registry.colorize(msg));
        }

        String broadcastTemplate = session.pool.broadcastMessage();
        if (broadcastTemplate != null && !broadcastTemplate.isBlank()) {
            String msg = broadcastTemplate
                    .replace("{prize}", prizeLabel)
                    .replace("{pool}", session.pool.id())
                    .replace("{player}", player.getName());
            Bukkit.getServer().broadcast(registry.colorize(msg));
        }
    }

    public String ownerNameOf(Block block) {
        RouletteSession session = occupiedBlocks.get(blockKey(block));
        if (session == null) return null;
        Player p = Bukkit.getPlayer(session.playerId);
        return p == null ? "?" : p.getName();
    }

    private void playOpenEffect(Block block) {
        if (block.getState() instanceof Lidded lidded) {
            try { lidded.open(); } catch (Throwable ignored) {}
        }
        Sound sound = openSoundFor(block.getType());
        if (sound != null) {
            block.getWorld().playSound(block.getLocation(), sound, 0.7f, 1f);
        }
    }

    private void playCloseEffect(Block block) {
        if (block.getState() instanceof Lidded lidded) {
            try { lidded.close(); } catch (Throwable ignored) {}
        }
        Sound sound = closeSoundFor(block.getType());
        if (sound != null) {
            block.getWorld().playSound(block.getLocation(), sound, 0.7f, 1f);
        }
    }

    private Sound openSoundFor(Material type) {
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST) return Sound.BLOCK_CHEST_OPEN;
        if (type == Material.ENDER_CHEST) return Sound.BLOCK_ENDER_CHEST_OPEN;
        if (type.name().endsWith("SHULKER_BOX")) return Sound.BLOCK_SHULKER_BOX_OPEN;
        return null;
    }

    private Sound closeSoundFor(Material type) {
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST) return Sound.BLOCK_CHEST_CLOSE;
        if (type == Material.ENDER_CHEST) return Sound.BLOCK_ENDER_CHEST_CLOSE;
        if (type.name().endsWith("SHULKER_BOX")) return Sound.BLOCK_SHULKER_BOX_CLOSE;
        return null;
    }

    /**
     * Build a reel exactly REEL_LEN + totalShifts long: first REEL_LEN items are the initial
     * on-screen frames, the remaining totalShifts items are the pending-shift queue. The winner
     * is planted at index (pointerIndex + totalShifts) — so after exactly totalShifts shifts it
     * sits under the pointer and the queue is empty.
     */
    private LinkedList<ItemStack> buildReel(Pool pool, Prize winner, int totalShifts) {
        int pointerIndex = WINNER_SLOT - REEL_START;
        int targetIndex = pointerIndex + totalShifts;
        int reelSize = REEL_LEN + totalShifts;

        LinkedList<ItemStack> reel = new LinkedList<>();
        for (int i = 0; i < reelSize; i++) {
            reel.add(pool.randomAny().display());
        }
        reel.set(targetIndex, winner.display());
        return reel;
    }

    /**
     * Simulate the same easing SpinTask uses to know exactly how many shifts will happen.
     * Must stay in sync with SpinTask.currentInterval().
     */
    private int estimateShifts() {
        long totalTicks = Math.max(20L, registry.durationTicks());
        long startInterval = registry.startIntervalTicks();
        long endInterval = registry.endIntervalTicks();
        int shifts = 0;
        long elapsed = 0;
        long nextShiftAt = 0;
        while (elapsed < totalTicks) {
            if (elapsed >= nextShiftAt) {
                shifts++;
                double progress = Math.min(1.0, (double) elapsed / totalTicks);
                double eased = progress * progress;
                long interval = startInterval + Math.round((endInterval - startInterval) * eased);
                nextShiftAt = elapsed + interval;
            }
            elapsed++;
        }
        return shifts;
    }

    private void decorateFrame(Inventory inv) {
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            pane.setItemMeta(meta);
        }
        int[] frame = {0, 1, 2, 3, 5, 6, 7, 8, 18, 19, 20, 21, 23, 24, 25, 26};
        for (int slot : frame) inv.setItem(slot, pane);

        ItemStack pointer = new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);
        ItemMeta pMeta = pointer.getItemMeta();
        if (pMeta != null) {
            pMeta.displayName(Component.empty());
            pointer.setItemMeta(pMeta);
        }
        inv.setItem(4, pointer);
        inv.setItem(22, pointer);
    }

    public static String formatDuration(long millis) {
        Duration d = Duration.ofMillis(millis);
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        if (h > 0) return h + "ч " + m + "м " + s + "с";
        if (m > 0) return m + "м " + s + "с";
        return s + "с";
    }

    private final class SpinTask extends BukkitRunnable {

        private static final long HOLD_TICKS_AFTER_LAST_SHIFT = 10L;

        private final RouletteSession session;
        private final long totalTicks;
        private long elapsed;
        private long nextShiftAt;
        private long holdRemaining = -1L;

        SpinTask(RouletteSession session) {
            this.session = session;
            this.totalTicks = Math.max(20L, registry.durationTicks());
            this.elapsed = 0;
            this.nextShiftAt = 0;
        }

        @Override
        public void run() {
            if (holdRemaining >= 0) {
                if (holdRemaining == 0) {
                    finish(session);
                    cancel();
                    return;
                }
                holdRemaining--;
                return;
            }

            if (elapsed >= nextShiftAt) {
                shiftReel();
                Player p = Bukkit.getPlayer(session.playerId);
                if (p != null) {
                    p.playSound(p.getLocation(), registry.tickSound(), 0.6f, 1f);
                }
                if (session.pendingQueue.isEmpty()) {
                    holdRemaining = HOLD_TICKS_AFTER_LAST_SHIFT;
                    return;
                }
                nextShiftAt = elapsed + currentInterval();
            }
            elapsed++;
        }

        private long currentInterval() {
            double progress = Math.min(1.0, (double) elapsed / totalTicks);
            double eased = progress * progress;
            long start = registry.startIntervalTicks();
            long end = registry.endIntervalTicks();
            return start + Math.round((end - start) * eased);
        }

        private void shiftReel() {
            if (session.pendingQueue.isEmpty()) return;
            ItemStack incoming = session.pendingQueue.pollFirst();
            for (int slot = REEL_START; slot < REEL_END; slot++) {
                session.inventory.setItem(slot, session.inventory.getItem(slot + 1));
            }
            session.inventory.setItem(REEL_END, incoming);
        }
    }

    static final class RouletteSession {
        final UUID playerId;
        final Pool pool;
        final Inventory inventory;
        final Prize winner;
        final LinkedList<ItemStack> pendingQueue;
        final Block sourceBlock;
        final String blockKey;
        final Set<UUID> spectators = new HashSet<>();
        BukkitTask task;

        RouletteSession(UUID playerId,
                        Pool pool,
                        Inventory inventory,
                        Prize winner,
                        LinkedList<ItemStack> reel,
                        Block sourceBlock,
                        String blockKey) {
            this.playerId = playerId;
            this.pool = pool;
            this.inventory = inventory;
            this.winner = winner;
            this.pendingQueue = new LinkedList<>(reel.subList(REEL_LEN, reel.size()));
            this.sourceBlock = sourceBlock;
            this.blockKey = blockKey;
        }
    }
}
