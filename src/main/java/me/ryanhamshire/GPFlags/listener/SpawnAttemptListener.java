package me.ryanhamshire.GPFlags.listener;

import com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent;
import me.ryanhamshire.GPFlags.Flag;
import me.ryanhamshire.GPFlags.FlagManager;
import me.ryanhamshire.GPFlags.GPFlags;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

import java.util.Locale;

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
 * is constructed. Cancelling at that stage also makes the server end the remaining
 * spawn attempts for the chunk in that cycle, so the retry pressure disappears as
 * well: measured on a test server with a world wide flag, the attempt rate collapsed
 * from about 75000 attempts per second to about 60 per second, with no entities
 * constructed at all.
 *
 * Only NATURAL spawns are handled here. Everything else (spawners, breeding, slime
 * splits and so on) still goes through the existing CreatureSpawnEvent handlers, so
 * flag semantics for those reasons are unchanged.
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

        event.setCancelled(true);
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
