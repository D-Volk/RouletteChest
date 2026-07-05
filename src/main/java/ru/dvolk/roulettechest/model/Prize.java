package ru.dvolk.roulettechest.model;

import org.bukkit.inventory.ItemStack;

public final class Prize {

    private final int weight;
    private final ItemStack display;
    private final Reward reward;
    private final String label;

    public Prize(int weight, ItemStack display, Reward reward, String label) {
        this.weight = Math.max(1, weight);
        this.display = display;
        this.reward = reward;
        this.label = label;
    }

    public int weight() { return weight; }
    public ItemStack display() { return display.clone(); }
    public Reward reward() { return reward; }
    public String label() { return label; }
}
