package net.trueog.coreprotecttntog;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

public class CoreProtectTNTOG extends JavaPlugin implements Listener {
    private final Cache<Object, String> probablyCache = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .concurrencyLevel(4) // Sync and Async threads
            .maximumSize(50000) // Drop objects if too much, because it will cost expensive lookup.
            .recordStats()
            .build();
    private CoreProtectAPI api;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        Plugin depend = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (depend == null) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        api = ((CoreProtect) depend).getAPI();
    }

    // Bed/RespawnAnchor explosion (tracing)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerInteractBedOrRespawnAnchorExplosion(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clickedBlock = e.getClickedBlock();
        Location locationHead = clickedBlock.getLocation();
        if (clickedBlock.getBlockData() instanceof Bed bed) {
            Location locationFoot =
                    locationHead.clone().subtract(bed.getFacing().getDirection());
            if (bed.getPart() == Bed.Part.FOOT) {
                locationHead.add(bed.getFacing().getDirection());
            }
            String reason = "#bed-" + e.getPlayer().getName();
            probablyCache.put(locationHead, reason);
            probablyCache.put(locationFoot, reason);
        }
        if (clickedBlock.getBlockData() instanceof RespawnAnchor) {
            probablyCache.put(
                    clickedBlock.getLocation(),
                    "#respawnanchor-" + e.getPlayer().getName());
        }
    }

    // Creeper ignite (tracing)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerInteractCreeper(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Creeper)) {
            return;
        }
        probablyCache.put(e.getRightClicked(), "#ignitecreeper-" + e.getPlayer().getName());
    }

    // Block explode (logger)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockExplode(BlockExplodeEvent e) {
        ConfigurationSection section = Companion.bakeConfigSection(getConfig(), "block-explosion");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        Location location = e.getBlock().getLocation();
        String probablyCauses = probablyCache.getIfPresent(e.getBlock());
        if (probablyCauses == null) {
            probablyCauses = probablyCache.getIfPresent(location);
        }
        if (probablyCauses == null) {
            if (section.getBoolean("disable-unknown", true)) {
                e.blockList().clear();
                Companion.broadcastNearPlayers(location, section.getString("alert"));
            }
        }
        // Found causes, let's begin for logging
        for (Block block : e.blockList()) {
            api.logRemoval(probablyCauses, block.getLocation(), block.getType(), block.getBlockData());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockPlaceOnHanging(BlockPlaceEvent event) {
        // We can't check the hanging in this event, may cause server lagging, just store it
        probablyCache.put(event.getBlock().getLocation(), event.getPlayer().getName());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockPlaceEvent event) {
        // We can't check the hanging in this event, may cause server lagging, just store it
        // Maybe a player break the tnt and a plugin igniting it?
        probablyCache.put(event.getBlock().getLocation(), event.getPlayer().getName());
    }

    // Player item put into ItemFrame / Rotate ItemFrame (logger)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onClickItemFrame(PlayerInteractEntityEvent e) { // Add item to item-frame or rotating
        if (!(e.getRightClicked() instanceof ItemFrame itemFrame)) {
            return;
        }
        ConfigurationSection section = Companion.bakeConfigSection(getConfig(), "itemframe");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        // Player interacted itemframe
        api.logInteraction(e.getPlayer().getName(), e.getRightClicked().getLocation());
        // Check item I/O
        if (itemFrame.getItem().getType().isAir()) { // Probably put item now
            ItemStack mainItem = e.getPlayer().getInventory().getItemInMainHand();
            ItemStack offItem = e.getPlayer().getInventory().getItemInOffHand();
            ItemStack putIn = mainItem.getType().isAir() ? offItem : mainItem;
            if (!putIn.getType().isAir()) {
                // Put in item
                api.logPlacement(
                        "#additem-" + e.getPlayer().getName(),
                        e.getRightClicked().getLocation(),
                        putIn.getType(),
                        null);
                return;
            }
        }
        // Probably rotating ItemFrame
        api.logRemoval(
                "#rotate-" + e.getPlayer().getName(),
                e.getRightClicked().getLocation(),
                itemFrame.getItem().getType(),
                null);
        api.logPlacement(
                "#rotate-" + e.getPlayer().getName(),
                e.getRightClicked().getLocation(),
                itemFrame.getItem().getType(),
                null);
    }

    // Any projectile shoot (listener)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onProjectileLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() == null) {
            return;
        }
        ProjectileSource projectileSource = e.getEntity().getShooter();
        String source = "";
        if (!(projectileSource instanceof Player)) {
            source += "#"; // We only hope non-player object use hashtag
        }
        source += e.getEntity().getName() + "-";
        if (projectileSource instanceof Entity entity) {
            if (projectileSource instanceof Mob mob && ((Mob) projectileSource).getTarget() != null) {
                source += mob.getTarget().getName();
            } else {
                source += entity.getName();
            }
        } else {
            if (projectileSource instanceof Block block) {
                source += block.getType().name();
            } else {
                source += projectileSource.getClass().getName();
            }
        }
        probablyCache.put(e.getEntity(), source);
        probablyCache.put(projectileSource, source);
    }

    // TNT ignites by Player (listener)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onIgniteTNT(EntitySpawnEvent e) {
        Entity tnt = e.getEntity();
        if (!(e.getEntity() instanceof TNTPrimed tntPrimed)) {
            return;
        }
        Entity source = tntPrimed.getSource();
        if (source != null) {
            // Bukkit has given the ignition source, track it directly.
            String sourceFromCache = probablyCache.getIfPresent(source);
            if (sourceFromCache != null) {
                probablyCache.put(tnt, sourceFromCache);
            }
            if (source.getType() == EntityType.PLAYER) {
                probablyCache.put(tntPrimed, source.getName());
                return;
            }
        }
        Location blockCorner = tnt.getLocation().clone().subtract(0.5, 0, 0.5);
        for (Map.Entry<Object, String> entry : probablyCache.asMap().entrySet()) {
            if (entry.getKey() instanceof Location loc) {
                if (loc.getWorld().equals(blockCorner.getWorld()) && loc.distance(blockCorner) < 0.5) {
                    probablyCache.put(tnt, entry.getValue());
                    break;
                }
            }
        }
    }

    // HangingBreak (logger)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHangingBreak(HangingBreakEvent e) {
        ConfigurationSection section = Companion.bakeConfigSection(getConfig(), "hanging");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        if (e.getCause() == HangingBreakEvent.RemoveCause.PHYSICS
                || e.getCause() == HangingBreakEvent.RemoveCause.DEFAULT) {
            return; // We can't track them tho.
        }

        Block hangingPosBlock = e.getEntity().getLocation().getBlock();
        String reason = probablyCache.getIfPresent(hangingPosBlock.getLocation());
        if (reason != null) {
            Material mat = Material.matchMaterial(e.getEntity().getType().name());
            if (mat != null) {
                api.logRemoval(
                        "#" + e.getCause().name() + "-" + reason,
                        hangingPosBlock.getLocation(),
                        Material.matchMaterial(e.getEntity().getType().name()),
                        null);
            } else {
                api.logInteraction("#" + e.getCause().name() + "-" + reason, hangingPosBlock.getLocation());
            }
        }
    }

    // EndCrystal rigged by entity (listener)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onEndCrystalHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof EnderCrystal)) {
            return;
        }
        if (e.getDamager() instanceof Player) {
            probablyCache.put(e.getEntity(), e.getDamager().getName());
        } else {
            String sourceFromCache = probablyCache.getIfPresent(e.getDamager());
            if (sourceFromCache != null) {
                probablyCache.put(e.getEntity(), sourceFromCache);
            } else if (e.getDamager() instanceof Projectile projectile) {
                if (projectile.getShooter() != null && projectile.getShooter() instanceof Player player) {
                    probablyCache.put(e.getEntity(), player.getName());
                }
            }
        }
    }

    // Haning hit by entity (logger)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onHangingHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Hanging)) {
            return;
        }
        ConfigurationSection section = Companion.bakeConfigSection(getConfig(), "itemframe");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        ItemFrame itemFrame = (ItemFrame) e.getEntity();
        if (itemFrame.getItem().getType().isAir() || itemFrame.isInvulnerable()) {
            return;
        }
        if (e.getDamager() instanceof Player) {
            probablyCache.put(e.getEntity(), e.getDamager().getName());
            api.logInteraction(e.getDamager().getName(), itemFrame.getLocation());
            api.logRemoval(
                    e.getDamager().getName(),
                    itemFrame.getLocation(),
                    itemFrame.getItem().getType(),
                    null);
        } else {
            String cause = probablyCache.getIfPresent(e.getDamager());
            if (cause != null) {
                String reason = "#" + e.getDamager().getName() + "-" + cause;
                probablyCache.put(e.getEntity(), reason);
                api.logRemoval(
                        reason, itemFrame.getLocation(), itemFrame.getItem().getType(), null);
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPaintingHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Painting)) {
            return;
        }
        ConfigurationSection section = Companion.bakeConfigSection(getConfig(), "painting");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        ItemFrame itemFrame = (ItemFrame) e.getEntity();
        if (itemFrame.getItem().getType().isAir() || itemFrame.isInvulnerable()) {
            return;
        }

        if (e.getDamager() instanceof Player) {
            api.logInteraction(e.getDamager().getName(), itemFrame.getLocation());
            api.logRemoval(
                    e.getDamager().getName(),
                    itemFrame.getLocation(),
                    itemFrame.getItem().getType(),
                    null);
        } else {
            String reason = probablyCache.getIfPresent(e.getDamager());
            if (reason != null) {
                api.logInteraction("#" + e.getDamager().getName() + "-" + reason, itemFrame.getLocation());
                api.logRemoval(
                        "#" + e.getDamager().getName() + "-" + reason,
                        itemFrame.getLocation(),
                        itemFrame.getItem().getType(),
                        null);
            } else {
                if (section.getBoolean("disable-unknown")) {
                    e.setCancelled(true);
                    e.setDamage(0.0d);
                    Companion.broadcastNearPlayers(e.getEntity().getLocation(), section.getString("alert"));
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onEntityHitByProjectile(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Player player) {
                probablyCache.put(e.getEntity(), player.getName());
                return;
            }
            String reason = probablyCache.getIfPresent(e.getDamager());
            if (reason != null) {
                probablyCache.put(e.getEntity(), reason);
                return;
            }
            probablyCache.put(e.getEntity(), e.getDamager().getName());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockIgnite(BlockIgniteEvent e) {
        if (e.getIgnitingEntity() != null) {
            if (e.getIgnitingEntity().getType() == EntityType.PLAYER) {
                probablyCache.put(e.getBlock().getLocation(), e.getPlayer().getName());
                // Don't add it to probablyIgnitedThisTick because it's the simplest case and is logged by Core Protect
                return;
            }
            String sourceFromCache = probablyCache.getIfPresent(e.getIgnitingEntity());
            if (sourceFromCache != null) {
                probablyCache.put(e.getBlock().getLocation(), sourceFromCache);
                return;
            } else if (e.getIgnitingEntity() instanceof Projectile projectile) {
                if (((Projectile) e.getIgnitingEntity()).getShooter() != null) {
                    ProjectileSource shooter = projectile.getShooter();
                    if (shooter instanceof Player player) {
                        probablyCache.put(e.getBlock().getLocation(), player.getName());
                        return;
                    }
                }
            }
        }
        if (e.getIgnitingBlock() != null) {
            String sourceFromCache =
                    probablyCache.getIfPresent(e.getIgnitingBlock().getLocation());
            if (sourceFromCache != null) {
                probablyCache.put(e.getBlock().getLocation(), sourceFromCache);
                return;
            }
        }
        ConfigurationSection section = Companion.bakeConfigSection(getConfig(), "fire");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        if (section.getBoolean("disable-unknown", true)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBurn(BlockBurnEvent e) {
        ConfigurationSection section = Companion.bakeConfigSection(getConfig(), "fire");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        if (e.getIgnitingBlock() != null) {
            String sourceFromCache =
                    probablyCache.getIfPresent(e.getIgnitingBlock().getLocation());
            if (sourceFromCache != null) {
                probablyCache.put(e.getBlock().getLocation(), sourceFromCache);
                api.logRemoval(
                        "#fire-"
                                + probablyCache.getIfPresent(
                                        e.getIgnitingBlock().getLocation()),
                        e.getBlock().getLocation(),
                        e.getBlock().getType(),
                        e.getBlock().getBlockData());
            } else if (section.getBoolean("disable-unknown", true)) {
                e.setCancelled(true);
                Companion.broadcastNearPlayers(e.getIgnitingBlock().getLocation(), section.getString("alert"));
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onBombHit(ProjectileHitEvent e) {
        if (e.getHitEntity() instanceof ExplosiveMinecart || e.getEntityType() == EntityType.ENDER_CRYSTAL) {
            if (e.getEntity().getShooter() != null && e.getEntity().getShooter() instanceof Player) {
                if (e.getHitEntity() != null) {
                    String sourceFromCache = probablyCache.getIfPresent(e.getEntity());
                    if (sourceFromCache != null) {
                        probablyCache.put(e.getHitEntity(), sourceFromCache);
                    } else {
                        if (e.getEntity().getShooter() != null
                                && e.getEntity().getShooter() instanceof Player shooter) {
                            probablyCache.put(e.getHitEntity(), shooter.getName());
                        }
                    }
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onExplode(EntityExplodeEvent e) {
        Entity entity = e.getEntity();
        List<Block> blockList = e.blockList();
        if (blockList.isEmpty()) {
            return;
        }
        List<Entity> pendingRemoval = new ArrayList<>();
        String entityName = e.getEntityType().name().toLowerCase(Locale.ROOT);
        ConfigurationSection section = Companion.bakeConfigSection(getConfig(), "entity-explosion");
        if (!section.getBoolean("enable", true)) {
            return;
        }
        String track = probablyCache.getIfPresent(entity);
        // TNT or EnderCrystal
        if (entity instanceof TNTPrimed || entity instanceof EnderCrystal) {
            if (track != null) {
                String reason = "#" + entityName + "-" + track;
                for (Block block : blockList) {
                    api.logRemoval(reason, block.getLocation(), block.getType(), block.getBlockData());
                    probablyCache.put(block.getLocation(), reason);
                }
                pendingRemoval.add(entity);
            } else {
                // Notify players this tnt or end crystal won't break any blocks
                if (!section.getBoolean("disable-unknown", true)) {
                    return;
                }
                e.blockList().clear();
                e.getEntity().remove();
                Companion.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
            }
            pendingRemoval.forEach(probablyCache::invalidate);
            return;
        }
        // Creeper... aww man
        if (entity instanceof Creeper creeper) {
            // New added: Player ignite creeper
            if (track != null) {
                for (Block block : blockList) {
                    api.logRemoval(track, block.getLocation(), block.getType(), block.getBlockData());
                }
            } else {
                LivingEntity creeperTarget = creeper.getTarget();
                if (creeperTarget != null) {
                    for (Block block : blockList) {
                        api.logRemoval(
                                "#creeper-" + creeperTarget.getName(),
                                block.getLocation(),
                                block.getType(),
                                block.getBlockData());
                        probablyCache.put(block.getLocation(), "#creeper-" + creeperTarget.getName());
                    }
                } else {
                    // Notify players this creeper won't break any blocks
                    if (!section.getBoolean("disable-unknown")) {
                        return;
                    }
                    e.blockList().clear();
                    e.getEntity().remove();
                    Companion.broadcastNearPlayers(e.getLocation(), section.getString("alert"));
                    return;
                }
            }
            return;
        }
        if (entity instanceof Fireball) {
            if (track != null) {
                String reason = "#fireball-" + track;
                for (Block block : blockList) {
                    api.logRemoval(reason, block.getLocation(), block.getType(), block.getBlockData());
                    probablyCache.put(block.getLocation(), reason);
                }
                pendingRemoval.add(entity);
            } else {
                if (section.getBoolean("disable-unknown")) {
                    e.blockList().clear();
                    e.getEntity().remove();
                    Companion.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
                }
            }
            pendingRemoval.forEach(probablyCache::invalidate);
            return;
        }
        if (entity instanceof ExplosiveMinecart) {
            boolean isLogged = false;
            Location blockCorner = entity.getLocation().clone().subtract(0.5, 0, 0.5);
            for (Map.Entry<Object, String> entry : probablyCache.asMap().entrySet()) {
                if (entry.getKey() instanceof Location loc) {
                    if (loc.getWorld().equals(blockCorner.getWorld()) && loc.distance(blockCorner) < 1) {
                        for (Block block : blockList) {
                            api.logRemoval(
                                    "#tntminecart-" + entry.getValue(),
                                    block.getLocation(),
                                    block.getType(),
                                    block.getBlockData());
                            probablyCache.put(block.getLocation(), "#tntminecart-" + entry.getValue());
                        }
                        isLogged = true;
                        break;
                    }
                }
            }
            if (!isLogged) {
                if (probablyCache.getIfPresent(entity) != null) {
                    String reason = "#tntminecart-" + probablyCache.getIfPresent(entity);
                    for (Block block : blockList) {
                        api.logRemoval(reason, block.getLocation(), block.getType(), block.getBlockData());
                        probablyCache.put(block.getLocation(), reason);
                    }
                    pendingRemoval.add(entity);
                } else if (section.getBoolean("disable-unknown")) {
                    e.blockList().clear();
                    Companion.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
                }
            }
            pendingRemoval.forEach(probablyCache::invalidate);
            return;
        }
        if (track == null || track.isEmpty()) {
            if (e.getEntity() instanceof Mob mob && ((Mob) e.getEntity()).getTarget() != null) {
                track = mob.getTarget().getName();
            }
        }
        // No matches, plugin explode or cannot to track?
        if (track == null || track.isEmpty()) {
            EntityDamageEvent cause = e.getEntity().getLastDamageCause();
            if (cause != null) {
                if (cause instanceof EntityDamageByEntityEvent entityDamageByEntityEvent) {
                    track = "#" + e.getEntity().getName() + "-"
                            + entityDamageByEntityEvent.getDamager().getName();
                }
            }
        }

        if (track != null && !track.isEmpty()) {
            for (Block block : e.blockList()) {
                api.logRemoval(track, block.getLocation(), block.getType(), block.getBlockData());
            }
        } else if (section.getBoolean("disable-unknown")) {
            e.blockList().clear();
            e.getEntity().remove();
            Companion.broadcastNearPlayers(entity.getLocation(), section.getString("alert"));
        }
    }
}
