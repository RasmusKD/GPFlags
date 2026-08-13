package me.ryanhamshire.GPFlags.listener;

import me.ryanhamshire.GPFlags.GPFlags;
import me.ryanhamshire.GPFlags.GPFlagsConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;

/**
 * Clears Paper's per player mob spawn backoff.
 *
 * Paper adds one point to this counter for every cancelled PreCreatureSpawnEvent, for
 * every player within simulation distance of the chunk, and getMobCountNear returns
 * mobCounts + mobBackoffCounts. The counter knows nothing about whose land refused the
 * spawn, so denying spawns on one claim spends the mob cap of every player nearby,
 * including players standing on land that allows mobs.
 *
 * The counter bleeds off one per chunk tick, which is far slower than a flagged area
 * fills it, so on a server with a low spawn-limits.monsters it stays saturated and
 * spawning stops entirely for those players.
 *
 * This runs once per tick and zeroes it. That gives back the spawn rate the server had
 * before the pre spawn listener existed while keeping what the listener was written for:
 * the expensive part of a refused spawn was never the attempt, it was constructing an
 * entity and finalizing it before throwing it away, and that still does not happen.
 */
public class MobBackoffClearer implements Runnable {

    private static Field backoffField;
    private static boolean unavailable;

    /** Starts the task if the config asks for it and the field can be found. */
    public static void startIfEnabled(GPFlags plugin) {
        if (!GPFlagsConfig.CLEAR_MOB_SPAWN_BACKOFF) return;
        Bukkit.getScheduler().runTaskTimer(plugin, new MobBackoffClearer(), 1L, 1L);
    }

    @Override
    public void run() {
        if (!GPFlagsConfig.CLEAR_MOB_SPAWN_BACKOFF || unavailable) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            int[] backoff = read(player);
            if (backoff == null) return;
            for (int i = 0; i < backoff.length; i++) backoff[i] = 0;
        }
    }

    private static int[] read(Player player) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            if (backoffField == null) {
                for (Class<?> c = handle.getClass(); c != null && backoffField == null; c = c.getSuperclass()) {
                    try {
                        Field f = c.getDeclaredField("mobBackoffCounts");
                        f.setAccessible(true);
                        backoffField = f;
                    } catch (NoSuchFieldException ignored) {}
                }
            }
            if (backoffField == null) {
                // A mapping change, or a server that does not carry the patch. Say so once
                // rather than failing silently every tick.
                unavailable = true;
                GPFlags.getInstance().getLogger().warning(
                        "Clear Paper Mob Spawn Backoff is on, but this server has no mobBackoffCounts field. Doing nothing.");
                return null;
            }
            return (int[]) backoffField.get(handle);
        } catch (Throwable t) {
            unavailable = true;
            GPFlags.getInstance().getLogger().warning("Could not read Paper's mob spawn backoff: " + t);
            return null;
        }
    }
}
