package com.example.serverstore;

import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The mailbox. Every purchase, payout and refund goes through here.
 * Items are handed over right away if they fit. Anything that does not fit (full inventory) or whose
 * owner is offline is held in store.json and delivered automatically as soon as the player is online
 * AND has room. Nothing is ever dropped on the floor.
 */
public final class Mail {
    private static final long REMIND_EVERY_MS = 30_000;
    private static final Map<UUID, Long> LAST_REMINDER = new HashMap<>();

    private Mail() {}

    private static String nice(String id) { return id.replace("minecraft:", "").replace('_', ' '); }

    /** Adds to the player's waiting items (merged per item type). Caller saves. */
    private static void hold(String uuid, String itemId, int count) {
        if (count <= 0) return;
        List<Data.Pending> list = Data.store.pending.computeIfAbsent(uuid, k -> new ArrayList<>());
        for (Data.Pending d : list) {
            if (d.item.equals(itemId)) { d.count += count; return; }
        }
        list.add(new Data.Pending(itemId, count));
    }

    /** Items waiting for a player (a copy, safe to loop over). */
    public static List<Data.Pending> waiting(String uuid) {
        List<Data.Pending> l = Data.store.pending.get(uuid);
        return l == null ? new ArrayList<>() : new ArrayList<>(l);
    }

    /** Total number of waiting items that can actually be delivered (unknown items are ignored). */
    public static int waitingCount(String uuid) {
        int c = 0;
        for (Data.Pending d : waiting(uuid)) if (Util.item(d.item) != Items.AIR) c += Math.max(0, d.count);
        return c;
    }

    // ---------------------------------------------------------------- sending

    /** Send items to a player, online or not, and tell them about it. */
    public static void send(String uuid, String itemId, int count, String from) {
        handOver(uuid, Auctions.online(uuid), itemId, count, from);
    }

    /** Give items to an online player when the caller already reports the action (buy, cancel...). */
    public static void give(ServerPlayer p, String itemId, int count) {
        handOver(p.getUUID().toString(), p, itemId, count, null);
    }

    private static void handOver(String uuid, ServerPlayer p, String itemId, int count, String from) {
        if (count <= 0) return;
        Item item = Util.item(itemId);
        int left = count;
        if (p != null && item != Items.AIR) {
            try {
                left = Util.giveFit(p, item, count);
            } catch (Exception e) {
                e.printStackTrace();
                left = count;                 // something went wrong: keep it safe in the mailbox
            }
            int given = count - left;
            if (given > 0 && from != null) {
                Util.msg(p, "You received " + given + "x " + nice(itemId) + " from " + from + ".", ChatFormatting.GREEN);
            }
        }
        if (left > 0) {
            hold(uuid, itemId, left);
            Data.save();
            if (p != null && item != Items.AIR) {
                Util.msg(p, "Your inventory is full! " + left + "x " + nice(itemId) + " is waiting in your /mailbox.", ChatFormatting.RED);
                Util.msg(p, "Clear some space and it will be delivered to you automatically.", ChatFormatting.YELLOW);
                LAST_REMINDER.put(p.getUUID(), System.currentTimeMillis());
            }
        }
    }

    // ---------------------------------------------------------------- delivering what is waiting

    /** Hands over everything that fits right now. Returns true if anything changed. */
    private static boolean drain(ServerPlayer p) {
        String uuid = p.getUUID().toString();
        List<Data.Pending> list = Data.store.pending.get(uuid);
        if (list == null) return false;
        boolean changed = false;
        for (Data.Pending d : new ArrayList<>(list)) {
            if (d.count <= 0) { list.remove(d); changed = true; continue; }
            Item item = Util.item(d.item);
            if (item == Items.AIR) continue;
            int left;
            try {
                left = Util.giveFit(p, item, d.count);
            } catch (Exception e) {
                e.printStackTrace();
                continue;
            }
            int given = d.count - left;
            if (given <= 0) continue;
            changed = true;
            d.count = left;
            if (left <= 0) list.remove(d);
            Util.msg(p, "Delivered " + given + "x " + nice(d.item) + " from your mailbox.", ChatFormatting.GREEN);
        }
        if (list.isEmpty()) { Data.store.pending.remove(uuid); changed = true; }
        return changed;
    }

    /** Runs once a second: delivers waiting items to online players as soon as they have room. */
    public static void tick() {
        if (Data.store.pending.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (String uuid : new ArrayList<>(Data.store.pending.keySet())) {
            ServerPlayer p = Auctions.online(uuid);
            if (p == null) continue;
            if (drain(p)) changed = true;
            int left = waitingCount(uuid);
            if (left <= 0) { LAST_REMINDER.remove(p.getUUID()); continue; }
            Long last = LAST_REMINDER.get(p.getUUID());
            if (last == null || now - last >= REMIND_EVERY_MS) {
                Util.msg(p, "You still have " + left + " item(s) waiting in your /mailbox - make some room in your inventory!", ChatFormatting.YELLOW);
                LAST_REMINDER.put(p.getUUID(), now);
            }
        }
        if (changed) Data.save();
    }

    /** When a player joins: deliver what piled up while they were away. */
    public static void onLogin(ServerPlayer p) {
        String uuid = p.getUUID().toString();
        if (waitingCount(uuid) <= 0) return;
        Util.msg(p, "You have items waiting from while you were away!", ChatFormatting.GOLD);
        if (drain(p)) Data.save();
        int left = waitingCount(uuid);
        if (left > 0) {
            Util.msg(p, "Your inventory is full! " + left + " item(s) are still waiting in your /mailbox.", ChatFormatting.RED);
            Util.msg(p, "Clear some space and they will be delivered automatically.", ChatFormatting.YELLOW);
            LAST_REMINDER.put(p.getUUID(), System.currentTimeMillis());
        }
    }

    // ---------------------------------------------------------------- manual collecting (mailbox menu)

    public static void collect(ServerPlayer p, String itemId) {
        String uuid = p.getUUID().toString();
        List<Data.Pending> list = Data.store.pending.get(uuid);
        if (list == null) return;
        for (Data.Pending d : new ArrayList<>(list)) {
            if (!d.item.equals(itemId)) continue;
            Item item = Util.item(d.item);
            if (item == Items.AIR) return;
            int left = Util.giveFit(p, item, d.count);
            int given = d.count - left;
            if (given <= 0) {
                Util.msg(p, "Your inventory is full - clear some space first.", ChatFormatting.RED);
                return;
            }
            d.count = left;
            if (left <= 0) list.remove(d);
            if (list.isEmpty()) Data.store.pending.remove(uuid);
            Data.save();
            Util.msg(p, "Collected " + given + "x " + nice(d.item) + ".", ChatFormatting.GREEN);
            return;
        }
    }

    public static void collectAll(ServerPlayer p) {
        boolean changed = drain(p);
        if (changed) Data.save();
        else if (waitingCount(p.getUUID().toString()) > 0) {
            Util.msg(p, "Your inventory is full - clear some space first.", ChatFormatting.RED);
        }
    }
}
