/* $Id$ */
/***************************************************************************
 *                   (C) Copyright 2003-2010 - Stendhal                    *
 ***************************************************************************
 ***************************************************************************
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 ***************************************************************************/
package games.stendhal.server.actions.equip;

import games.stendhal.common.EquipActionConsts;
import games.stendhal.common.MathHelper;
import games.stendhal.server.core.engine.ItemLogger;
import games.stendhal.server.core.engine.SingletonRepository;
import games.stendhal.server.core.events.EquipListener;
import games.stendhal.server.entity.Entity;
import games.stendhal.server.entity.item.Corpse;
import games.stendhal.server.entity.item.Item;
import games.stendhal.server.entity.item.Stackable;
import games.stendhal.server.entity.item.StackableItem;
import games.stendhal.server.entity.player.Player;
import games.stendhal.server.entity.slot.EntitySlot;

import java.util.Iterator;
import java.util.List;

import marauroa.common.game.RPAction;
import marauroa.common.game.RPObject;
import marauroa.common.game.RPSlot;

import org.apache.log4j.Logger;

/**
 * this encapsulates the equip/drop source.
 */
class SourceObject extends MoveableObject {
	private static InvalidSource invalidSource = new InvalidSource();
	private static Logger logger = Logger.getLogger(SourceObject.class);
	/** the item . */
	private Item item;

	private int quantity;

	private boolean isLootingRewardable = false;

	public static SourceObject createSourceObject(final RPAction action, final Player player) {

		if ((action == null) || (player == null)) {
			return invalidSource;
		}
 
		// source item must be there
		if (!action.has(EquipActionConsts.SOURCE_PATH) && !action.has(EquipActionConsts.BASE_ITEM)) {
			logger.warn("action does not have a base item. action: " + action);

			return invalidSource;
		}

		if (player.getZone() == null) {
			return invalidSource;
		}

		SourceObject source;
		/* TODO: disabled because of 
		 * if (action.has(EquipActionConsts.SOURCE_PATH)) {
			source = createSource(action, player);
			// Otherwise use compatibility mode
		} else*/
		if (action.has(EquipActionConsts.BASE_OBJECT)) {
			source = createSourceForContainedItem(action, player);
		} else {
			source = createSourceForNonContainedItem(action, player);

		}

		if ((source.getEntity() != null) && source.getEntity().hasSlot("content") && source.getEntity().getSlot("content").size() > 0) {
			player.sendPrivateText("Please empty your " + source.getEntityName() + " before moving it around");
			return invalidSource;
		}

		adjustAmountForStackables(action, source);
		return source;
	}

	private static SourceObject createSourceForContainedItem(final RPAction action, final Player player) {
		SourceObject source;
		final Entity parent = EquipUtil.getEntityFromId(player, action.getInt(EquipActionConsts.BASE_OBJECT));

		if (!isValidParent(parent, player)) {
			return invalidSource;
		}

		final String slotName = action.get(EquipActionConsts.BASE_SLOT);

		if (!parent.hasSlot(slotName)) {
			player.sendPrivateText("Source " + slotName + " does not exist");
			logger.error(player.getName() + " tried to use non existing slot " + slotName + " of " + parent
					+ " as source. player zone: " + player.getZone() + " object zone: " + parent.getZone());

			return invalidSource;
		}
		final RPSlot baseSlot = parent.getSlot(slotName);

		if (!isValidBaseSlot(player, baseSlot)) {
			return invalidSource;
		}
		final RPObject.ID baseItemId = new RPObject.ID(action.getInt(EquipActionConsts.BASE_ITEM), "");
		if (!baseSlot.has(baseItemId)) {
			logger.debug("Base item(" + parent + ") doesn't contain item(" + baseItemId + ") on given slot(" + slotName
					+ ")");
			// Remove message as discussed on #arianne 2010-04-25
			// player.sendPrivateText("There is no such item in the " + slotName + " of "
				//	+ parent.getDescriptionName(true));
			return invalidSource;
		}

		final Entity entity = (Entity) baseSlot.get(baseItemId);
		if (!(entity instanceof Item)) {
			player.sendPrivateText("Oh, that " + entity.getDescriptionName(true)
					+ " is not an item and therefore cannot be equipped");
			return invalidSource;
		}

		if (parent instanceof Corpse) {
			Corpse corpse = (Corpse) parent;
			if (!corpse.mayUse(player)) {
				logger.debug(player.getName() + " tried to access eCorpse owned by " + corpse.getCorpseOwner());
				player.sendPrivateText("Only " + corpse.getCorpseOwner() + " may access the corpse for now.");
				return invalidSource;
			}
		}

		source = new SourceObject(player, parent, slotName, (Item) entity);

		// handle logging of looting items
		if (parent instanceof Corpse) {
			Corpse corpse = (Corpse) parent;
			checkIfLootingIsRewardable(player, corpse, source, (Item) entity);
		}

		return source;
	}
	
	/**
	 * Create a SourceObject for an item path.
	 * 
	 * @param action
	 * @param player
	 * @return source object
	 */
	private static SourceObject createSource(RPAction action, final Player player) {
		List<String> path = action.getList(EquipActionConsts.SOURCE_PATH);
		Iterator<String> it = path.iterator();
		// The ultimate parent object
		Entity parent = EquipUtil.getEntityFromId(player, MathHelper.parseInt(it.next()));
		if (parent == null) {
			return invalidSource;
		}
		
		/*
		 * Corpses are always top level objects, so this check can be done
		 * outside the path loop.
		 */
		if (parent instanceof Corpse) {
			Corpse corpse = (Corpse) parent;
			if (!corpse.mayUse(player)) {
				logger.debug(player.getName() + " tried to access eCorpse owned by " + corpse.getCorpseOwner());
				player.sendPrivateText("Only " + corpse.getCorpseOwner() + " may access the corpse for now.");
				return invalidSource;
			}
		}
		
		/*
		 * Top level items need to be checked for players standing on them. We
		 * do not need to worry about access to containers below other players
		 * as items do not have the content slot when they are on the ground.
		 */
		if (parent instanceof Item) {
			if (isItemBelowOtherPlayer(player, (Item) parent)) {
				return invalidSource;
			}
		}
		
		// Walk the slot path
		Entity entity = parent;
		String slotName = null;
		while (it.hasNext()) {
			slotName = it.next();
			if (!entity.hasSlot(slotName)) {
				player.sendPrivateText("Source " + slotName + " does not exist");
				logger.error(player.getName() + " tried to use non existing slot " + slotName + " of " + entity
						+ " as source. player zone: " + player.getZone() + " object zone: " + parent.getZone());
				return invalidSource;
			}
			
			final RPSlot slot = entity.getSlot(slotName);
			if (!isValidBaseSlot(player, slot)) {
				return invalidSource;
			}
			
			if (!it.hasNext()) {
				logger.error("Missing item id");
				return invalidSource;
			}
			final RPObject.ID itemId = new RPObject.ID(MathHelper.parseInt(it.next()), "");
			if (!slot.has(itemId)) {
				logger.debug("Base item(" + entity + ") doesn't contain item(" + itemId + ") on given slot(" + slotName
						+ ")");
				return invalidSource;
			}
			
			entity = (Entity) slot.get(itemId);
			if (!(entity instanceof Item)) {
				player.sendPrivateText("Oh, that " + entity.getDescriptionName(true)
						+ " is not an item and therefore cannot be equipped");
				return invalidSource;
			}
		}
		// wipe parent, if the item is not contained
		if (parent == entity) {
			parent = null;
		}
		
		SourceObject source = new SourceObject(player, parent, slotName, (Item) entity);
		
		// handle logging of looting items
		if (parent instanceof Corpse) {
			Corpse corpse = (Corpse) parent;
			checkIfLootingIsRewardable(player, corpse, source, (Item) entity);
		}
		
		return source;
	}

	private static boolean isValidBaseSlot(final Player player, final RPSlot baseSlot) {
		if (! (baseSlot instanceof EntitySlot)) {
			return false;
		}
		EntitySlot slot = (EntitySlot) baseSlot;
		slot.clearErrorMessage();
		boolean res = slot.isReachableForTakingThingsOutOfBy(player);
		if (!res) {
			logger.debug("Unreachable slot");
			player.sendPrivateText(slot.getErrorMessage());
		}
		return res;
	}

	private static SourceObject createSourceForNonContainedItem(final RPAction action, final Player player) {
		final SourceObject source = new SourceObject(player);
		final RPObject.ID baseItemId = new RPObject.ID(action.getInt(EquipActionConsts.BASE_ITEM), player.getID().getZoneID());

		source.item = source.getNonContainedItem(baseItemId);
		if (source.item == null) {
			return invalidSource;
		}
		return source;
	}

	private static void adjustAmountForStackables(final RPAction action, final SourceObject source) {
		if ((source.item instanceof Stackable< ? >) && action.has(EquipActionConsts.QUANTITY)) {
			final int entityQuantity = ((Stackable< ? >) source.item).getQuantity();

			source.quantity = action.getInt(EquipActionConsts.QUANTITY);
			if ((entityQuantity < 1) || (source.quantity < 1) || (source.quantity >= entityQuantity)) {
				// quantity == 0 performs a regular move
				// of the entire item
				source.quantity = 0;
			}
		}
	}

	/**
	 * Represents the source of a movement of a contained Item as in Drop or Equip.
	 *
	 * @param player
	 *            who want to do action
	 * @param parent
	 *            who contains it right now
	 * @param slotName
	 *            where to get it from
	 * @param entity
	 *            the item to move
	 *
	 */
	private SourceObject(final Player player, final Entity parent, final String slotName, final Item entity) {
		super(player);
		this.parent = parent;
		this.slot = slotName;
		this.item = entity;
	}

	private static boolean isValidParent(final Entity parent, final Player player) {
		if (parent == null) {
			// Object doesn't exist.
			return false;
		}

		// TODO: Check that this code is not required because this check is
		// done in PlayerSlot
		// is the container a player and not the current one?
		if ((parent instanceof Player) && !parent.getID().equals(player.getID())) {
			// trying to remove an item from another player
			return false;
		}
		return true;
	}

	private Item getNonContainedItem(final RPObject.ID baseItemId) {
		Entity entity = null;
		if (SingletonRepository.getRPWorld().has(baseItemId)) {
			entity = (Entity) SingletonRepository.getRPWorld().get(baseItemId);
			if ((entity instanceof Item)) {
				if (isItemBelowOtherPlayer(player, (Item) entity)) {
					entity = null;
				}
			} else {
				entity = null;
			}
		}
		return (Item) entity;
	}

	public SourceObject(final Player player) {
		super(player);
	}

	/**
	 * moves this entity to the destination.
	 *
	 * @param dest
	 *            to move to
	 * @param player
	 *            who moves the Source
	 * @return true if successful
	 */
	public boolean moveTo(final DestinationObject dest, final Player player) {
		if (!((EquipListener) item).canBeEquippedIn(dest.getContentSlotName())) {
			// give some feedback
			player.sendPrivateText("You can't carry this " + item.getTitle() + " on your " + dest.getContentSlotName());
			logger.warn("tried to equip an entity into disallowed slot: " + item.getClass() + "; equip rejected");
			return false;
		}

		if (!dest.isValid() || !dest.preCheck(item, player)) {
			// no extra logger warning needed here as each is inside the methods called above, where necessary
			return false;
		}

		final String[] srcInfo = getLogInfo();
		final Item entity = removeFromWorld();
		logger.debug("item removed");
		dest.addToWorld(entity, player);
		logger.debug("item readded");

		new ItemLogger().equipAction(player, entity, srcInfo, dest.getLogInfo());

		return true;
	}

	/** returns true when this SourceObject is valid. */
	@Override
	public boolean isValid() {
		return (item != null);
	}

	/**
	 * returns true when this entity and the other is within the given distance.
	 */
	@Override
	public boolean checkDistance(final Entity other, final double distance) {
		final Entity checker;
		if (parent != null) {
			checker = parent;
		} else {
			checker = item;
		}
		if (other.nextTo(checker, distance)) {
			return true;
		}
		logger.debug("distance check failed " + other.squaredDistance(checker));
		player.sendPrivateText("You cannot reach that far.");
		return false;
	}

	/**
	 * removes the entity from the world and returns it (so it may be added
	 * again). In case of splitted StackableItem the only item is reduced and a
	 * new StackableItem with the splitted off amount is returned.
	 *
	 * @return Entity to place somewhere else in the world
	 */
	public Item removeFromWorld() {
		if (quantity != 0) {
			final StackableItem newItem = ((StackableItem) item).splitOff(quantity);
			new ItemLogger().splitOff(player, item, newItem, quantity);
			return newItem;
		} else {
			item.removeFromWorld();
			return item;
		}
	}

	/**
	 * Gets the entity that should be equipped.
	 *
	 * @return entity
	 */
	public Entity getEntity() {
		return item;
	}

	/**
	 * @return  the amount of objects.
	 */
	public int getQuantity() {

		int temp = quantity;
		if (quantity == 0) {
			// everything
			temp = 1;
			if (item instanceof StackableItem) {
				temp = ((StackableItem) item).getQuantity();
			}

		}
		return temp;
	}

	/**
	 * Sets the quantity.
	 *
	 * @param amount
	 */
	public void setQuantity(final int amount) {
		quantity = amount;
	}

	/**
	 * Checks whether the item is below <b>another</b> player.
	 *
	 * @param player Player trying to access the item
	 * @param sourceItem
	 *            to check
	 *
	 * @return true, if it cannot be taken; false otherwise
	 */
	private static boolean isItemBelowOtherPlayer(final Player player, final Item sourceItem) {
		final List<Player> players = player.getZone().getPlayers();
		for (final Player otherPlayer : players) {
			if (player.equals(otherPlayer)) {
				continue;
			}
			// Allow players always pick up their own items
			if (!player.getName().equals(sourceItem.getBoundTo())) {
				if (otherPlayer.getArea().intersects(sourceItem.getArea())) {
					player.sendPrivateText("You cannot take items which are below other players");
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Checks if looting the item is rewardable as looted item
	 *
	 * @return true iff the player deserves it
	 */
	public boolean isLootingRewardable() {
		return isLootingRewardable;
	}

	/**
	 * Determines if looting should be logged for the player
	 *
	 * @param player
	 * @param corpse
	 * @param source
	 * @param item
	 */
	private static void checkIfLootingIsRewardable(Player player, Corpse corpse, SourceObject source, Item item) {
		if (item.isFromCorpse()) {
			if (corpse.isItemLootingRewardable()) {
				source.isLootingRewardable = true;
			} else {
				if (player.getName().equals(corpse.getKiller())) {
					source.isLootingRewardable = true;
				}
			}
		}
	}

	@Override
	public String[] getLogInfo() {
		final String[] res = new String[3];
		if (parent != null) {
			res[0] = "slot";
			if (parent.has("name")) {
				res[1] = parent.get("name");
			} else {
				res[1] = parent.getDescriptionName(false);
			}
			res[2] = slot;
		} else {
			res[0] = "ground";
			res[1] = item.getZone().getName();
			res[2] = item.getX() + " " + item.getY();
		}
		return res;
	}

	String getEntityName() {
		Entity entity1 = getEntity();
		final String itemName;
		if (entity1.has("name")) {
			itemName = entity1.get("name");
		} else if (entity1 instanceof Item) {
			itemName = "item";
		} else {
			itemName = "entity";
		}
		return itemName;
	}

	private static class InvalidSource extends SourceObject {
		/**
		 * Constructor.
		 */
		public InvalidSource() {
			super(null);

		}

		/**
		 *
		 * @return false
		 */
		@Override
		public boolean isValid() {
			return false;
		}
	}
}
