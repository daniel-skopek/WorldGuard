/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.bukkit.listener;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.bukkit.util.PaperInterop;
import com.sk89q.worldguard.session.MoveType;
import com.sk89q.worldguard.session.Session;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerMoveListener extends AbstractListener {

    private final Map<UUID, Location> lastPositions = new ConcurrentHashMap<>();
    private BukkitTask moveCheckTask;
    private ScheduledTask foliaMoveCheckTask;

    public PlayerMoveListener(WorldGuardPlugin plugin) {
        super(plugin);
    }

    @Override
    public void registerEvents() {
        if (WorldGuard.getInstance().getPlatform().getGlobalStateManager().usePlayerMove) {
            PluginManager pm = getPlugin().getServer().getPluginManager();
            pm.registerEvents(this, getPlugin());

            startMoveCheckScheduler();
        }
    }

    private void startMoveCheckScheduler() {
        long checkInterval = 5L;

        if (getPlugin().isFolia()) {
            foliaMoveCheckTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(getPlugin(), scheduledTask -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.getScheduler().run(getPlugin(), ignored -> checkPlayerMovement(player), null);
                }
            }, checkInterval, checkInterval);
        } else {
            moveCheckTask = Bukkit.getScheduler().runTaskTimer(getPlugin(), () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    checkPlayerMovement(player);
                }
            }, checkInterval, checkInterval);
        }
    }

    private boolean isSameBlock(Location loc1, Location loc2) {
        return loc1.getBlockX() == loc2.getBlockX()
                && loc1.getBlockY() == loc2.getBlockY()
                && loc1.getBlockZ() == loc2.getBlockZ()
                && loc1.getWorld().getUID().equals(loc2.getWorld().getUID());
    }

    private void checkPlayerMovement(Player player) {
        if (!player.isOnline() || player.isDead()) {
            return;
        }

        Location currentLoc = player.getLocation();
        UUID playerUUID = player.getUniqueId();

        Location lastLoc = lastPositions.get(playerUUID);

        if (lastLoc != null && isSameBlock(lastLoc, currentLoc)) {
            return;
        }

        lastPositions.put(playerUUID, currentLoc.clone());

        if (lastLoc == null) {
            return;
        }

        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);
        Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(localPlayer);

        MoveType moveType = determineMoveType(player);
        com.sk89q.worldedit.util.Location weLocation = session.testMoveTo(
                localPlayer,
                BukkitAdapter.adapt(currentLoc),
                moveType
        );

        if (weLocation != null) {
            handleDeniedMovement(player, weLocation, currentLoc);
        }
    }

    private MoveType determineMoveType(Player player) {
        if (player.isGliding()) {
            return MoveType.GLIDE;
        } else if (player.isSwimming()) {
            return MoveType.SWIM;
        } else if (player.getVehicle() != null && player.getVehicle() instanceof AbstractHorse) {
            return MoveType.RIDE;
        }

        return MoveType.MOVE;
    }

    private void handleDeniedMovement(Player player, com.sk89q.worldedit.util.Location weLocation, Location originalTo) {
        final Location override = BukkitAdapter.adapt(weLocation);
        override.setX(override.getBlockX() + 0.5);
        override.setY(override.getBlockY());
        override.setZ(override.getBlockZ() + 0.5);
        override.setPitch(originalTo.getPitch());
        override.setYaw(originalTo.getYaw());

        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            vehicle.eject();

            Entity current = vehicle;
            while (current != null) {
                current.eject();
                vehicle.setVelocity(new Vector());
                if (vehicle instanceof LivingEntity) {
                    vehicle.teleport(override.clone());
                } else {
                    vehicle.teleport(override.clone().add(0, 1, 0));
                }
                current = current.getVehicle();
            }

            player.teleport(override.clone().add(0, 1, 0));
            if (getPlugin().isFolia()) {
                player.getScheduler().runDelayed(getPlugin(),
                        scheduledTask -> player.teleport(override.clone().add(0, 1, 0)), null, 1);
            } else {
                Bukkit.getScheduler().runTaskLater(getPlugin(), () ->
                        player.teleport(override.clone().add(0, 1, 0)), 1);
            }
        } else {
            player.teleport(override);
        }

        lastPositions.put(player.getUniqueId(), override.clone());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        LocalPlayer player = getPlugin().wrapPlayer(event.getPlayer());

        Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(player);
        session.testMoveTo(player, BukkitAdapter.adapt(event.getRespawnLocation()), MoveType.RESPAWN, true);

        lastPositions.put(event.getPlayer().getUniqueId(), event.getRespawnLocation().clone());
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        Entity entity = event.getEntered();
        if (entity instanceof Player) {
            LocalPlayer player = getPlugin().wrapPlayer((Player) entity);
            Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(player);
            if (null != session.testMoveTo(player, BukkitAdapter.adapt(event.getVehicle().getLocation()), MoveType.EMBARK, true)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        lastPositions.put(event.getPlayer().getUniqueId(), event.getPlayer().getLocation().clone());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        LocalPlayer localPlayer = getPlugin().wrapPlayer(player);

        Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(localPlayer);
        com.sk89q.worldedit.util.Location loc = session.testMoveTo(localPlayer,
            BukkitAdapter.adapt(event.getPlayer().getLocation()), MoveType.OTHER_CANCELLABLE); // white lie
        if (loc != null) {
            if (getPlugin().isFolia()) {
                PaperInterop.teleportAsync(player, BukkitAdapter.adapt(loc));
            } else {
                player.teleport(BukkitAdapter.adapt(loc));
            }
        }

        session.uninitialize(localPlayer);

        lastPositions.remove(player.getUniqueId());
    }

    /**
     * Small utility method to teleport via async or sync methods depending on platform.
     *
     * <p>Ideally we can make this use PaperLib in the future once it's better tested.</p>
     *
     * @param entity The entity to teleport
     * @param location The location to teleport to
     */
    private void teleport(Entity entity, Location location) {
        if (getPlugin().isFolia()) {
            PaperInterop.teleportAsync(entity, location);
        } else {
            entity.teleport(location);
        }
    }

    @EventHandler
    public void onEntityMount(EntityMountEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player) {
            LocalPlayer player = getPlugin().wrapPlayer((Player) entity);
            Session session = WorldGuard.getInstance().getPlatform().getSessionManager().get(player);
            if (null != session.testMoveTo(player, BukkitAdapter.adapt(event.getMount().getLocation()), MoveType.EMBARK, true)) {
                event.setCancelled(true);
            }
        }
    }

    public void shutdown() {
        if (foliaMoveCheckTask != null) {
            foliaMoveCheckTask.cancel();
        }
        if (moveCheckTask != null && !moveCheckTask.isCancelled()) {
            moveCheckTask.cancel();
        }

        lastPositions.clear();
    }
}
