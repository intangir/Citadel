package com.untamedears.citadel.listener;

import static com.untamedears.citadel.Utility.createNaturalReinforcement;
import static com.untamedears.citadel.Utility.createPlayerReinforcement;
import static com.untamedears.citadel.Utility.maybeReinforcementDamaged;
import static com.untamedears.citadel.Utility.reinforcementBroken;
import static com.untamedears.citadel.Utility.reinforcementDamaged;
import static com.untamedears.citadel.Utility.sendMessage;
import static com.untamedears.citadel.Utility.sendThrottledMessage;
import static com.untamedears.citadel.Utility.damagePlayerTool;

import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.material.MaterialData;
import org.bukkit.material.Openable;
import org.bukkit.material.PistonBaseMaterial;
import org.bukkit.Effect;
import org.bukkit.World;

import com.untamedears.citadel.Citadel;
import com.untamedears.citadel.SecurityLevel;
import com.untamedears.citadel.access.AccessDelegate;
import com.untamedears.citadel.entity.PlayerState;
import com.untamedears.citadel.entity.IReinforcement;
import com.untamedears.citadel.entity.PlayerReinforcement;
import com.untamedears.citadel.entity.ReinforcementMaterial;

import net.sacredlabyrinth.Phaed.PreciousStones.PreciousStones;

public class BlockListener implements Listener {

	public static final List<BlockFace> planar_sides = Arrays.asList(
	        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST);
	
    private boolean canPlace(Block block, String player_name) {
        Material block_mat = block.getType();

        // check that a hopper isn't placed under another persons protected chest
        if (block_mat == Material.HOPPER){
            Block above = block.getRelative(BlockFace.UP);
            if (above.getState() instanceof InventoryHolder) {
	            IReinforcement rein = AccessDelegate.getDelegate(above).getReinforcement();
	            if (null != rein && rein instanceof PlayerReinforcement) {
	                PlayerReinforcement pr = (PlayerReinforcement)rein;
	                if (!pr.isAccessible(player_name)) {
	                    return false;
	                }
	            }
            }
        }

        // check that a chest isn't placed next to another persons protected chest
        if (block_mat == Material.CHEST || block_mat == Material.TRAPPED_CHEST){
            for (BlockFace direction : planar_sides) {
                Block adjacent = block.getRelative(direction);
                if (!(adjacent.getState() instanceof InventoryHolder)) {
                    continue;
                }
                IReinforcement rein = AccessDelegate.getDelegate(adjacent).getReinforcement();
                if (null != rein && rein instanceof PlayerReinforcement) {
                    PlayerReinforcement pr = (PlayerReinforcement)rein;
                    if (!pr.isAccessible(player_name)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
	
    /**
     * This handles the BlockPlaceEvent for Fortification mode (all placed blocks are reinforced)
     *
     * @param bpe BlockPlaceEvent
     */
    @EventHandler(ignoreCancelled = true)
    public void placeFortifiedBlock(BlockPlaceEvent bpe) {
        Player player = bpe.getPlayer();
        Block block = bpe.getBlockPlaced();
        
        if(!canPlace(block, player.getName())) {
        	sendThrottledMessage(player, ChatColor.RED, "Cancelled block place, mismatched reinforcement.");
            bpe.setCancelled(true);
            return;
        }

        PlayerState state = PlayerState.get(player);

        switch (state.getMode())
        {
        case NORMAL:
        case INFO:
            if(Citadel.getConfigManager().getReinforceNormal() == true)
            {
                createPlayerReinforcement(player, block);
            }
            return;

        case FORTIFICATION:
            break;

        case REINFORCEMENT:
        case REINFORCEMENT_SINGLE_BLOCK:
            bpe.setCancelled(true);
            return;

       	default:
       	    return;
        }

        PlayerInventory inventory = player.getInventory();

        ReinforcementMaterial material = state.getReinforcementMaterial();
        ItemStack required = material.getRequiredMaterials();
        if (material.getMaterialId() == 0 || inventory.contains(material.getMaterial(), required.getAmount())) {
            if (createPlayerReinforcement(player, block) == null) {
                sendMessage(player, ChatColor.RED, "%s is not a reinforcible material", block.getType().name());
            } else {
            	state.checkResetMode();
            }
        } else {
            sendMessage(player, ChatColor.YELLOW, "%s depleted, left fortification mode", material.getMaterial().name());
            state.reset();
            bpe.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void blockBreak(BlockBreakEvent bbe) {
        Block block = bbe.getBlock();
        Player player = bbe.getPlayer();

        IReinforcement reinforcement = AccessDelegate.getDelegate(block).getReinforcement();
        if (reinforcement == null) {
            reinforcement = createNaturalReinforcement(block);
            if (reinforcement != null && reinforcementDamaged(reinforcement)) {
            	if (block != null && player != null) {
            		PreciousStones.getInstance().getSnitchManager().recordSnitchBlockBreak(player, block);
            	}
            	damagePlayerTool(player);
            	bbe.setCancelled(true);
                block.getDrops().clear();
            }
	        return;
	    }

        boolean is_cancelled = true;
        if (reinforcement instanceof PlayerReinforcement) {
            PlayerReinforcement pr = (PlayerReinforcement)reinforcement;
            PlayerState state = PlayerState.get(player);
            if (pr.isBypassable(player) && (state.isBypassMode() || pr.getMaterialId() == 0)) {
		    	Citadel.info(player.getDisplayName() + " bypassed reinforcement %s at " 
		    			+ pr.getBlock().getLocation().toString());
                is_cancelled = reinforcementBroken(reinforcement);
            } else {
                is_cancelled = reinforcementDamaged(reinforcement);
            }
            if (!is_cancelled) {
                // The player reinforcement broke. Now check for natural
                is_cancelled = createNaturalReinforcement(block) != null;
            }
        } else {
            is_cancelled = reinforcementDamaged(reinforcement);
        }

        if (is_cancelled) {
        	if (block != null && player != null) {
        		PreciousStones.getInstance().getSnitchManager().recordSnitchBlockBreak(player, block);
        	}
        	damagePlayerTool(player);
        	bbe.setCancelled(true);
            block.getDrops().clear();
        }
    }

    public PlayerReinforcement getReinforcement(InventoryHolder holder) {
        // Returns reinforcement of the inventory's holder or null if none exists
        Location loc;
        if (holder instanceof DoubleChest) {
            loc = ((DoubleChest)holder).getLocation();
        } else if (holder instanceof BlockState) {
            loc = ((BlockState)holder).getLocation();
        } else if (holder instanceof Minecart) {
            // Vehicle inventories (should get the track?)
        	loc = ((Minecart)holder).getLocation();
        } else {
        	return null;
        }
        IReinforcement reinforcement = AccessDelegate.getDelegate(loc.getBlock()).getReinforcement();
        if (null != reinforcement && reinforcement instanceof PlayerReinforcement) {
            PlayerReinforcement pr = (PlayerReinforcement)reinforcement;
            // Treat public reinforcements as if they don't exist
            if (!pr.getSecurityLevel().equals(SecurityLevel.PUBLIC)) {
                return pr;
            }
        }
        return null;
    }
    
    @EventHandler(ignoreCancelled = true)
    public void hopperTransfer(InventoryMoveItemEvent e) {

    	try {
	    	InventoryHolder taker = e.getDestination().getHolder();
	    	PlayerReinforcement takerRein = getReinforcement(taker);
	    	InventoryHolder giver = e.getSource().getHolder();
	    	PlayerReinforcement giverRein = getReinforcement(giver);
	    	
	    	if(giverRein == null) {
	    		// feeding from unowned block always allowed
	    		Citadel.info("allowing public giver");
	    		return;
	    	}
	    	
	    	if(takerRein == null) {
	    		// unowned takers never allowed
	    		Citadel.info("disallowing public taker");
	    		e.setCancelled(true);
	    		return;
	    	}

	    	if(takerRein.getOwner() == giverRein.getOwner()) {
	    		Citadel.info("allowing same owner named " + takerRein.getOwner().getName()); 
	    		return;
	    	}

	    	if(giverRein.isAccessible(takerRein.getOwner().getName())) {
	    		Citadel.info("allowing allowed taker named " + takerRein.getOwner().getName()); 
	    		return;
	    	}
    	
	    } catch(Exception er) {
	    	Citadel.warning("error handling hopperTransfer, disallowing");
	    	Citadel.printStackTrace(er);
	    }

    	// by default deny
    	Citadel.info("disallowing by default");
    	e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void pistonExtend(BlockPistonExtendEvent bpee)
    {
		for(Block block : bpee.getBlocks())
		{
			IReinforcement reinforcement = AccessDelegate.getDelegate(block).getReinforcement();
		
			if (reinforcement != null)
			{
				if(reinforcement.getMaterialId() == 0)
				{
					reinforcementBroken(reinforcement);
				}
				else
				{
					bpee.setCancelled(true);
					break;
				}
			}
		}
    }
    
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void pistonRetract(BlockPistonRetractEvent bpre) {
    	Block piston = bpre.getBlock();
		BlockState state = piston.getState();
		MaterialData data = state.getData();
		BlockFace direction = null;
		
		// Check the block it pushed directly
		if (data instanceof PistonBaseMaterial) {
			direction = ((PistonBaseMaterial) data).getFacing();
		}
	
		if (direction == null)
			return;
	
		// the block that the piston moved
		Block moved = piston.getRelative(direction, 2);
	
		AccessDelegate delegate = AccessDelegate.getDelegate(moved);
		IReinforcement reinforcement = delegate.getReinforcement();
	
		if (reinforcement != null)
		{
			if(reinforcement.getMaterialId() == 0)
			{
				reinforcementBroken(reinforcement);
			}
			else
			{
				bpre.setCancelled(true);
			}
		}

    }
    
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void blockBurn(BlockBurnEvent bbe) {
        boolean wasprotected = maybeReinforcementDamaged(bbe.getBlock());
    	if (wasprotected) {
            bbe.setCancelled(wasprotected);
            Block block = bbe.getBlock();
            Block rblock;
            // super aggressive fire protection! suppress all fire within a 5 block cube around the fire
            for(int x = -2; x <= 2; x++) {
            	for(int y = -2; y <= 2; y++) {
            		for(int z = -2; z <= 2; z++) {
            			rblock = block.getRelative(x,y,z);
            			switch(rblock.getType()) {
            				case FIRE:
            					rblock.setType(Material.AIR);
            					break;

            				case LAVA:
            				case STATIONARY_LAVA:
            					rblock.setType(Material.COBBLESTONE);
            					break;
            			}
            		}
            	}
            }
    	}
	}


    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void blockPhysics(BlockPhysicsEvent bpe) {
       Material changedType = bpe.getChangedType();
       if (Material.LAVA == changedType || Material.WATER == changedType) {
           Block block = bpe.getBlock();
           // Protection for reinforced rails types from lava and water. Similar to water/lava, transform surrounding blocks in cobblestone or obsidian to stop the lava/water effect.
           if (Material.RAILS == block.getType() || Material.POWERED_RAIL == block.getType() || Material.DETECTOR_RAIL == block.getType()) {
               boolean isReinforced = maybeReinforcementDamaged(block);
               if (isReinforced) {
                   for (final BlockFace blockFace : new BlockFace[]{BlockFace.DOWN, BlockFace.UP, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH}) {
                       Block otherBlock = block.getRelative(blockFace);
                       if (Material.LAVA == otherBlock.getType() || Material.WATER == otherBlock.getType()) {
                    	   otherBlock.setType(Material.COBBLESTONE);
                    	   otherBlock.getWorld().playEffect(otherBlock.getLocation(), Effect.EXTINGUISH, 0);
                       }
                   }
               }
           }
       }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void redstonePower(BlockRedstoneEvent bre) {
        // This currently only protects against reinforced openable objects,
        //  like doors, from being opened by unauthorizied players.
        try {
            // NewCurrent <= 0 means the redstone wire is turning off, so the
            //  container is closing. Closing is good so just return. This also
            //  shaves off some time when dealing with sand generators.
            // OldCurrent > 0 means that the wire was already on, thus the
            //  container was already open by an authorized player. Now it's
            //  either staying open or closing. Just return.
            if (bre.getNewCurrent() <= 0 || bre.getOldCurrent() > 0) {
                return;
            }
            Block block = bre.getBlock();
            MaterialData blockData = block.getState().getData();
            if (!(blockData instanceof Openable)) {
                return;
            }
            Openable openable = (Openable)blockData;
            if (openable.isOpen()) {
                return;
            }
            IReinforcement generic_reinforcement =
                Citadel.getReinforcementManager().getReinforcement(block);
            if (generic_reinforcement == null ||
                !(generic_reinforcement instanceof PlayerReinforcement)) {
                return;
            }
            PlayerReinforcement reinforcement =
                (PlayerReinforcement)generic_reinforcement;
            if (reinforcement.getSecurityLevel() == SecurityLevel.PUBLIC) {
                return;
            }
            double redstoneDistance = Citadel.getConfigManager().getRedstoneDistance();
            Location blockLocation = block.getLocation();
            double min_x = blockLocation.getX() - redstoneDistance;
            double min_z = blockLocation.getZ() - redstoneDistance;
            double max_x = blockLocation.getX() + redstoneDistance;
            double max_z = blockLocation.getZ() + redstoneDistance;
            World blockWorld = blockLocation.getWorld();
            //Set<Player> onlinePlayers = new HashSet<Player>(Citadel.getMemberManager().getOnlinePlayers());
            Collection<? extends Player> onlinePlayers = Citadel.getPlugin().getServer().getOnlinePlayers();
            boolean isAuthorizedPlayerNear = false;
            try {
                for (Player player : onlinePlayers) {
                    if (player.isDead()) {
                        continue;
                    }
                    Location playerLocation = player.getLocation();
                    double player_x = playerLocation.getX();
                    double player_z = playerLocation.getZ();
                    // Simple bounding box check to quickly rule out Players
                    //  before doing the more expensive playerLocation.distance
                    if (player_x < min_x || player_x > max_x ||
                        player_z < min_z || player_z > max_z) {
                        continue;
                    }
                    if (playerLocation.getWorld() != blockWorld) {
                        continue;
                    }
                    if (!reinforcement.isAccessible(player)) {
                        continue;
                    }
                    double distanceSquared =
                        playerLocation.distance(blockLocation);
                    if (distanceSquared <= redstoneDistance) {
                        isAuthorizedPlayerNear = true;
                        break;
                    }
                }
            } catch (ConcurrentModificationException e) {
                Citadel.warning("ConcurrentModificationException at redstonePower() in BlockListener");
            }
            if (!isAuthorizedPlayerNear) {
                Citadel.info("Prevented redstone from opening reinforcement at "
                        + reinforcement.getBlock().getLocation().toString());
                bre.setNewCurrent(bre.getOldCurrent());
            }
        } catch(Exception e) {
            Citadel.printStackTrace(e);
        }
    }
}
