package ru.dvolk.roulettechest.model;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public sealed interface Reward
        permits Reward.ItemReward, Reward.CommandReward, Reward.NoneReward, Reward.MultiReward {

    void grant(Player player);

    record ItemReward(ItemStack stack) implements Reward {
        @Override
        public void grant(Player player) {
            var overflow = player.getInventory().addItem(stack.clone());
            overflow.values().forEach(remaining ->
                    player.getWorld().dropItemNaturally(player.getLocation(), remaining));
        }
    }

    record CommandReward(String command) implements Reward {
        @Override
        public void grant(Player player) {
            String resolved = command.replace("{player}", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
        }
    }

    record NoneReward() implements Reward {
        @Override
        public void grant(Player player) {
        }
    }

    /**
     * Runs a list of rewards in order. Any nested MultiReward is flattened by the parser,
     * so grant() sees only leaf rewards.
     */
    record MultiReward(List<Reward> rewards) implements Reward {
        @Override
        public void grant(Player player) {
            for (Reward r : rewards) r.grant(player);
        }
    }
}
