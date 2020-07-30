package net.sacredlabyrinth.Phaed.PreciousStones.listeners;

import net.sacredlabyrinth.Phaed.PreciousStones.PreciousStones;
import net.sacredlabyrinth.Phaed.PreciousStones.field.Field;
import net.sacredlabyrinth.Phaed.PreciousStones.field.FieldFlag;
import net.sacredlabyrinth.Phaed.PreciousStones.helpers.Helper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

import java.util.List;

/**
 * @author phaed
 */
public class PSVehicleListener implements Listener {
    private final PreciousStones plugin;

    /**
     *
     */
    public PSVehicleListener() {
        plugin = PreciousStones.getInstance();
    }

    /**
     * @param event
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleCreate(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && item != null && Helper.isBoat(item.getType())) {

            Location loc = event.getClickedBlock().getLocation();
            Field field = plugin.getForceFieldManager().getEnabledSourceField(loc, FieldFlag.PREVENT_VEHICLE_CREATE);

            if (field != null) {

                Player player = event.getPlayer();

                if (FieldFlag.PREVENT_VEHICLE_CREATE.applies(field, player)) {
                    if (plugin.getPermissionsManager().has(player, "preciousstones.bypass.use")) {
                        plugin.getCommunicationManager().notifyVehicleBypassCreate(player, loc, field);
                    } else {

                        event.setCancelled(true);
                        plugin.getCommunicationManager().warnCreateVehicle(player, loc, field);
                        return;
                    }
                }
            }
        }
    }

    /**
     * @param event
     */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onVehicleDestroy(VehicleDestroyEvent event) {
		Vehicle vehicle = event.getVehicle();

		if (event.getAttacker() instanceof Player) {

			Field field = plugin.getForceFieldManager().getEnabledSourceField(vehicle.getLocation(), FieldFlag.PREVENT_VEHICLE_DESTROY);

			if (field != null) {
				Player player = (Player) event.getAttacker();

				if (FieldFlag.PREVENT_VEHICLE_DESTROY.applies(field, player)) {
					if (plugin.getPermissionsManager().has(player, "preciousstones.bypass.destroy")) {
						plugin.getCommunicationManager().notifyBypassDestroyVehicle(player, vehicle, field);
					} else {
						event.setCancelled(true);
						plugin.getCommunicationManager().warnDestroyVehicle(player, vehicle, field);
						return;
					}
				}
			}
		}
	}
    
    /**
     * @param event
     */
	@EventHandler
	public void onVehicleDamage(VehicleDamageEvent event) {
		Vehicle vehicle = event.getVehicle();

		if (event.getAttacker() instanceof Player) {
			Field field = plugin.getForceFieldManager().getEnabledSourceField(vehicle.getLocation(), FieldFlag.PREVENT_VEHICLE_DESTROY);

			if (field != null) {
				Player player = (Player) event.getAttacker();
				

				if (FieldFlag.PREVENT_VEHICLE_DESTROY.applies(field, player)) {
					if (event.getVehicle() instanceof Minecart) {
						if (player.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.KNOCKBACK) > 0) {
							player.sendMessage("Level: " + player.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.KNOCKBACK));
							if (plugin.getPermissionsManager().has(player, "preciousstones.bypass.destroy")) {
								plugin.getCommunicationManager().notifyBypassDestroyVehicle(player, vehicle, field);
							} else {
								Bukkit.getScheduler().runTaskLater(plugin, () -> {
									event.getVehicle().setVelocity(new Vector(0, 0, 0));
								}, 1);
								event.setCancelled(true);
								plugin.getCommunicationManager().warnDestroyVehicle(player, vehicle, field);
								return;
							}
						}
					}
				}
			}
		}
	}

    /**
     * @param event
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleMove(VehicleMoveEvent event) {
        Vehicle vehicle = event.getVehicle();
        if (plugin.getSettingsManager().isBlacklistedWorld(vehicle.getLocation().getWorld())) {
            return;
        }

        List<Entity> passengers = vehicle.getPassengers();
        for (Entity passenger : passengers) {
            if (passenger instanceof Player) {
                plugin.getPlayerListener().onPlayerMove(new PlayerMoveEvent((Player) passenger, event.getFrom(), event.getTo()));
            }
        }
    }

    /**
     * @param event
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleEnter(VehicleEnterEvent event) {
        Vehicle vehicle = event.getVehicle();
        if (plugin.getSettingsManager().isBlacklistedWorld(vehicle.getLocation().getWorld())) {
            return;
        }
        List<Entity> passengers = vehicle.getPassengers();

        for (Entity passenger : passengers) {
            if (passenger instanceof Player) {
                Player player = (Player) passenger;

                Field field = plugin.getForceFieldManager().getEnabledSourceField(player.getLocation(), FieldFlag.PREVENT_VEHICLE_ENTER);

                if (field != null) {
                    if (FieldFlag.PREVENT_VEHICLE_ENTER.applies(field, player)) {
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    /**
     * @param event
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleExit(VehicleExitEvent event) {
        Vehicle vehicle = event.getVehicle();
        List<Entity> passengers = vehicle.getPassengers();

        for (Entity passenger : passengers) {
            if (passenger instanceof Player) {
                Player player = (Player) passenger;

                Field field = plugin.getForceFieldManager().getEnabledSourceField(player.getLocation(), FieldFlag.PREVENT_VEHICLE_EXIT);

                if (field != null) {
                    if (FieldFlag.PREVENT_VEHICLE_EXIT.applies(field, player)) {
                        event.setCancelled(true);
                    }
                }
            }
        }
    }
}
