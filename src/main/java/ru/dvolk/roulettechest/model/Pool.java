package ru.dvolk.roulettechest.model;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class Pool {

    public enum CooldownScope { SERVER, PLAYER, NONE }

    /**
     * Who can interact with a chest while someone else is already spinning it:
     *   SOLO     - nobody else can open or watch. Late-comer gets the busy message.
     *   SPECTATE - other players see the same rolling GUI (read-only) but cannot start their own.
     *   FREE     - each player gets an independent, parallel roulette.
     */
    public enum Concurrency { SOLO, SPECTATE, FREE }

    private final String id;
    private final String title;
    private final List<Prize> prizes;
    private final int totalWeight;
    private final long cooldownSeconds;
    private final CooldownScope cooldownScope;
    private final Concurrency concurrency;
    private final String winMessage;
    private final String cooldownMessage;
    private final String broadcastMessage;
    private final String busyMessage;

    public Pool(String id,
                String title,
                List<Prize> prizes,
                long cooldownSeconds,
                CooldownScope cooldownScope,
                Concurrency concurrency,
                String winMessage,
                String cooldownMessage,
                String broadcastMessage,
                String busyMessage) {
        if (prizes.isEmpty()) {
            throw new IllegalArgumentException("Pool " + id + " has no prizes");
        }
        this.id = id;
        this.title = title;
        this.prizes = List.copyOf(prizes);
        this.totalWeight = prizes.stream().mapToInt(Prize::weight).sum();
        this.cooldownSeconds = Math.max(0, cooldownSeconds);
        this.cooldownScope = cooldownSeconds <= 0 ? CooldownScope.NONE : cooldownScope;
        this.concurrency = concurrency;
        this.winMessage = winMessage;
        this.cooldownMessage = cooldownMessage;
        this.broadcastMessage = broadcastMessage;
        this.busyMessage = busyMessage;
    }

    public String id() { return id; }
    public String title() { return title; }
    public List<Prize> prizes() { return prizes; }
    public long cooldownSeconds() { return cooldownSeconds; }
    public CooldownScope cooldownScope() { return cooldownScope; }
    public Concurrency concurrency() { return concurrency; }
    public String winMessage() { return winMessage; }
    public String cooldownMessage() { return cooldownMessage; }
    public String broadcastMessage() { return broadcastMessage; }
    public String busyMessage() { return busyMessage; }

    public Prize rollWeighted() {
        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        int cursor = 0;
        for (Prize prize : prizes) {
            cursor += prize.weight();
            if (roll < cursor) return prize;
        }
        return prizes.get(prizes.size() - 1);
    }

    public Prize randomAny() {
        return prizes.get(ThreadLocalRandom.current().nextInt(prizes.size()));
    }
}
