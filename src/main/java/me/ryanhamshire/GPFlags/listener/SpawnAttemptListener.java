package me.ryanhamshire.GPFlags.listener;

import com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent;
import me.ryanhamshire.GPFlags.Flag;
import me.ryanhamshire.GPFlags.FlagManager;
import me.ryanhamshire.GPFlags.GPFlags;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

/**
 * Cancels natural spawns in NoMobSpawns/NoMonsterSpawns areas before the server builds
 * the entity, instead of after.
 *
 * The CreatureSpawnEvent handlers on the flag definitions fire at the very end of the
 * spawn pipeline: the server has already picked a position, checked blocks and light,
 * constructed the mob and run finalizeSpawn before the event is cancelled and the mob
 * thrown away. Because a cancelled spawn never counts toward the mob cap, the spawner
 * retries the same area at full rate forever. Measured on a test server this is around
 * 60 wasted spawn attempts per second around a single player.
 *
 * This listener intercepts Paper's PreCreatureSpawnEvent, which fires before the entity
 * is constructed. On top of the cheaper cancel, when the whole chunk lies inside the
 * flagged claim (or the flag is world wide) it also sets shouldAbortSpawn, which stops
 * the remaining spawn attempts for that chunk in this cycle. Claims are arbitrary
 * rectangles, not chunk aligned, so chunks on a claim edge only get the per-position
 * cancel and keep vanilla behaviour for the part outside the claim.
 *
 * Only NATURAL spawns are handled here. Everything else (spawners, breeding, slime
 * splits and so on) still goes through the existing CreatureSpawnEvent handlers, so
 * flag semantics for those reasons are unchanged. The type specific flags such as
 * NoMobSpawnsType are also left to the existing handlers.
 */
public class SpawnAttemptListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreSpawn(PreCreatureSpawnEvent event) {
        if (event.getReason() != SpawnReason.NATURAL) return;

        Location location = event.getSpawnLocation();
        FlagManager flagManager = GPFlags.getInstance().getFlagManager();
        Claim claim = GriefPrevention.instance.dataStore.getClaimAt(location, false, false, null);

        Flag flag = flagManager.getEffectiveFlag(location, "NoMobSpawns", claim);
        if (flag == null && isMonsterType(event.getType())) {
            flag = flagManager.getEffectiveFlag(location, "NoMonsterSpawns", claim);
        }
        if (flag == null) return;

        event.setCancelled(true);

        // No claim means the flag is set world wide or as a server default, so every
        // chunk is fully covered. Otherwise only abort when the whole chunk is inside
        // the claim, so spawns just outside a claim border behave exactly as before.
        if (claim == null || containsWholeChunk(claim, location)) {
            event.setShouldAbortSpawn(true);
        }
    }

    /** Mirrors Util.isMonster, but for an EntityType with no entity constructed yet. */
    private static boolean isMonsterType(EntityType type) {
        Class<?> entityClass = type.getEntityClass();
        if (entityClass != null && Monster.class.isAssignableFrom(entityClass)) return true;
        return type == EntityType.GHAST || type == EntityType.MAGMA_CUBE || type == EntityType.SHULKER
                || type == EntityType.PHANTOM || type == EntityType.SLIME || type == EntityType.HOGLIN;
    }

    private static boolean containsWholeChunk(Claim claim, Location location) {
        World world = location.getWorld();
        int minX = (location.getBlockX() >> 4) << 4;
        int minZ = (location.getBlockZ() >> 4) << 4;
        int y = location.getBlockY();
        return claim.contains(new Location(world, minX, y, minZ), true, false)
                && claim.contains(new Location(world, minX + 15, y, minZ), true, false)
                && claim.contains(new Location(world, minX, y, minZ + 15), true, false)
                && claim.contains(new Location(world, minX + 15, y, minZ + 15), true, false);
    }
}
