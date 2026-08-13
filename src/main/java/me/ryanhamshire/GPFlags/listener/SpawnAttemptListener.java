package me.ryanhamshire.GPFlags.listener;

import com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent;
import me.ryanhamshire.GPFlags.Flag;
import me.ryanhamshire.GPFlags.FlagManager;
import me.ryanhamshire.GPFlags.GPFlags;
import me.ryanhamshire.GPFlags.GPFlagsConfig;
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

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cancels natural spawns in flagged areas before the server builds the entity,
 * instead of after. Covers NoMobSpawns, NoMonsterSpawns, NoMonsters, NoMobSpawnsType
 * and SpawnReasonWhitelist.
 *
 * The CreatureSpawnEvent handlers on the flag definitions fire at the very end of the
 * spawn pipeline: the server has already picked a position, checked blocks and light,
 * constructed the mob and run finalizeSpawn before the event is cancelled and the mob
 * thrown away. Because a cancelled spawn never counts toward the mob cap, the spawner
 * retries the same area at full rate forever. Measured on a test server this is around
 * 60 wasted spawn attempts per second around a single player.
 *
 * This listener intercepts Paper's PreCreatureSpawnEvent, which fires before the entity
 * is constructed, so the placement checks, the entity construction and finalizeSpawn
 * are all skipped for a spawn that was going to be refused anyway.
 *
 * Cancelling does NOT end the chunk's remaining attempts on its own. Only
 * setShouldAbortSpawn(true) makes the spawner return early; a plain cancel falls
 * through to the next candidate position exactly like a failed placement check.
 *
 * Note for anyone tuning this: on Paper with per-player-mob-spawns enabled (the
 * default) every cancelled pre spawn is charged to a per player mob backoff counter
 * that is added to the mob cap of every player within tick view distance and bleeds
 * off one per spawn cycle. A large flagged area can therefore hold that budget down
 * and suppress spawning in nearby chunks that allow mobs. Set
 * "Cancel Natural Spawns Before Entity Creation" to false to turn this listener off
 * on servers where that matters.
 *
 * Only NATURAL spawns are handled here. Everything else (spawners, breeding, slime
 * splits and so on) still goes through the existing CreatureSpawnEvent handlers, so
 * flag semantics for those reasons are unchanged.
 */
public class SpawnAttemptListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreSpawn(PreCreatureSpawnEvent event) {
        if (!GPFlagsConfig.PRE_SPAWN_CANCEL) return;
        if (event.getReason() != SpawnReason.NATURAL) return;

        Location location = event.getSpawnLocation();
        FlagManager flagManager = GPFlags.getInstance().getFlagManager();
        // Paper fires this event for every candidate position, before the light,
        // block and collision checks, so it runs far more often than
        // CreatureSpawnEvent did. Skip the claim lookup entirely where claims cannot
        // apply: getEffectiveFlag ignores claim, parent and default scope in a world
        // with claims disabled and consults only world and server scope, which do not
        // need a claim.
        Claim claim = GriefPrevention.instance.claimsEnabledForWorld(location.getWorld())
                ? GriefPrevention.instance.dataStore.getClaimAt(location, false, false, null)
                : null;

        Flag flag = flagManager.getEffectiveFlag(location, "NoMobSpawns", claim);
        if (flag == null && isMonsterType(event.getType())) {
            flag = flagManager.getEffectiveFlag(location, "NoMonsterSpawns", claim);
            if (flag == null) {
                flag = flagManager.getEffectiveFlag(location, "NoMonsters", claim);
            }
        }
        if (flag == null) {
            Flag typeFlag = flagManager.getEffectiveFlag(location, "NoMobSpawnsType", claim);
            if (typeFlag != null && isListedType(event.getType(), typeFlag)) {
                flag = typeFlag;
            }
        }
        if (flag == null) {
            Flag whitelist = flagManager.getEffectiveFlag(location, "SpawnReasonWhitelist", claim);
            if (whitelist != null && whitelistBlocksNatural(whitelist)) {
                flag = whitelist;
            }
        }
        if (flag == null) return;

        // Only pre cancel when the WHOLE chunk is covered, and then abort it.
        //
        // Paper charges every cancelled PreCreatureSpawnEvent to a per player mob
        // backoff counter (see the per player mob count backoff patch): the count is
        // added to the mob cap of every player within tick view distance and bleeds
        // off at one per category per spawn cycle. That cap is per PLAYER, not per
        // area, so cancelling here suppresses spawning in every chunk near that
        // player, including neighbouring claims that allow mobs.
        //
        // Aborting is charged once and then ends the chunk's remaining attempts for
        // this cycle, where a plain cancel is charged again for every candidate
        // position in the chunk. A chunk that lies entirely inside the flagged area
        // has nothing legal to spawn anyway, so ending it early costs nothing.
        //
        // A chunk that only partly overlaps the claim keeps the old behaviour and is
        // left to the CreatureSpawnEvent handlers on the flag definitions. That is
        // more expensive per spawn, but it charges no backoff, so the positions in
        // that chunk which ARE allowed keep spawning normally, and so do the
        // player's other chunks.
        if (GPFlagsConfig.PRE_SPAWN_ABORT_CHUNK) {
            if (claim != null && !containsWholeChunk(claim, location)) return;
            event.setCancelled(true);
            event.setShouldAbortSpawn(true);
        } else {
            event.setCancelled(true);
        }
        if (GPFlagsConfig.LOG_PRE_SPAWN_CANCELS) logCancel(flag, claim, event, location);
    }

    // Diagnostics. One aggregated line per 10 seconds, because this path runs
    // tens of thousands of times a second and a line per cancel would drown the
    // console and cost more than the spawn it prevented.
    private static final Map<String, Long> COUNTS = new ConcurrentHashMap<>();
    private static volatile long nextReport;

    private static void logCancel(Flag flag, Claim claim, PreCreatureSpawnEvent event, Location location) {
        String key = flag.getFlagDefinition().getName()
                + " claim=" + (claim == null ? "none(world/server flag)" : String.valueOf(claim.getID()))
                + " world=" + (location.getWorld() == null ? "?" : location.getWorld().getName())
                + " type=" + event.getType();
        COUNTS.merge(key, 1L, Long::sum);

        long now = System.currentTimeMillis();
        if (now < nextReport) return;
        nextReport = now + 10_000L;
        StringBuilder sb = new StringBuilder("[GPFlags] pre-spawn cancels (last 10s):");
        COUNTS.forEach((k, v) -> sb.append("\n  ").append(v).append("x ").append(k));
        COUNTS.clear();
        GPFlags.getInstance().getLogger().info(sb.toString());
    }

    /**
     * Whether the 16x16 chunk containing this location lies entirely inside the claim.
     * Claims are arbitrary rectangles rather than chunk aligned, so a chunk on a claim
     * border contains positions the flag does not cover.
     */
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

    /** Mirrors FlagDef_NoMobSpawnsType.isNotAllowed. */
    private static boolean isListedType(EntityType type, Flag flag) {
        for (String t : flag.parameters.split(";")) {
            if (t.equalsIgnoreCase(type.toString())) return true;
        }
        return false;
    }

    /** Mirrors the loop in FlagDef_SpawnReasonWhitelist for a NATURAL spawn. */
    private static boolean whitelistBlocksNatural(Flag flag) {
        for (String string : flag.getParametersArray()) {
            SpawnReason reason;
            try {
                reason = SpawnReason.valueOf(string.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return false;
            }
            if (reason != SpawnReason.NATURAL) return true;
        }
        return false;
    }

    /** Mirrors Util.isMonster, but for an EntityType with no entity constructed yet. */
    private static boolean isMonsterType(EntityType type) {
        Class<?> entityClass = type.getEntityClass();
        if (entityClass != null && Monster.class.isAssignableFrom(entityClass)) return true;
        return type == EntityType.GHAST || type == EntityType.MAGMA_CUBE || type == EntityType.SHULKER
                || type == EntityType.PHANTOM || type == EntityType.SLIME || type == EntityType.HOGLIN;
    }

}
