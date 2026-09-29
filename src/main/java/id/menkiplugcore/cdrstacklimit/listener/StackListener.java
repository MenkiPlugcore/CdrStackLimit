package id.menkiplugcore.cdrstacklimit.listener;

import id.menkiplugcore.cdrstacklimit.CdrStackLimitPlugin;
import id.menkiplugcore.cdrstacklimit.StackLimitService;
import id.menkiplugcore.cdrstacklimit.VirtualStackService;
import org.bukkit.GameMode;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.DragType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class StackListener implements Listener {
    private final CdrStackLimitPlugin plugin;
    private final StackLimitService limits;
    private final VirtualStackService stacks;

    public StackListener(CdrStackLimitPlugin plugin, StackLimitService limits, VirtualStackService stacks) {
        this.plugin = plugin;
        this.limits = limits;
        this.stacks = stacks;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        Item entity = event.getItem();
        ItemStack source = entity.getItemStack();
        if (!stacks.isEligible(source)) {
            return;
        }

        int logicalAmount = stacks.getLogicalAmount(source);
        int limit = limits.getLimit(player);
        if (limit <= source.getType().getMaxStackSize()) {
            return;
        }

        event.setCancelled(true);
        int remaining = stacks.addToPlayer(player, source, logicalAmount, limit);
        int moved = logicalAmount - remaining;

        if (remaining <= 0) {
            entity.remove();
        } else if (remaining != logicalAmount) {
            entity.setItemStack(stacks.withLogicalAmount(source, remaining));
        }

        if (moved > 0) {
            stacks.showAmount(player, player.getInventory().getItemInMainHand(), limit);
            sync(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        ItemStack placedFrom = event.getItemInHand();
        if (!stacks.isVirtual(placedFrom)) {
            return;
        }

        int before = stacks.getLogicalAmount(placedFrom);
        if (before <= 0) {
            return;
        }

        ItemStack template = placedFrom.clone();
        EquipmentSlot hand = event.getHand();
        int heldSlot = player.getInventory().getHeldItemSlot();

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            int after = before - 1;
            ItemStack replacement = stacks.withLogicalAmount(template, after);
            if (hand == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(replacement);
            } else {
                player.getInventory().setItem(heldSlot, replacement);
            }
            stacks.showAmount(player, replacement, limits.getLimit(player));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (!stacks.isVirtual(dropped)) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack template = dropped.clone();
        boolean dropWholeStack = dropped.getAmount() >= dropped.getType().getMaxStackSize();
        event.setCancelled(true);

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            int slot = player.getInventory().getHeldItemSlot();
            ItemStack current = player.getInventory().getItem(slot);
            if (current == null || !stacks.isVirtual(current) || !stacks.canMerge(current, template)) {
                return;
            }

            int logical = stacks.getLogicalAmount(current);
            int toDrop = dropWholeStack ? logical : Math.min(1, logical);
            player.getInventory().setItem(slot, stacks.withLogicalAmount(current, logical - toDrop));
            stacks.dropVanilla(player, current, toDrop);
            stacks.showAmount(player, player.getInventory().getItem(slot), limits.getLimit(player));
            sync(player);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory clicked = event.getClickedInventory();
        Inventory top = event.getView().getTopInventory();
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        int limit = limits.getLimit(player);

        boolean clickedPlayer = clicked instanceof PlayerInventory;
        boolean clickedTop = clicked != null && clicked == top;
        boolean personalView = isPersonalView(top);
        boolean supportedContainer = !personalView && stacks.isSupportedContainer(top);

        if (clicked == null) {
            handleOutsideClick(event, player, cursor, limit);
            return;
        }

        if (clickedPlayer && isStorageSlot(player, event.getSlot())
                && (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP)
                && shouldHandle(player, current)) {
            event.setCancelled(true);
            int logical = stacks.getLogicalAmount(current);
            int toDrop = event.getClick() == ClickType.CONTROL_DROP ? logical : Math.min(1, logical);
            clicked.setItem(event.getSlot(), stacks.withLogicalAmount(current, logical - toDrop));
            stacks.dropVanilla(player, current, toDrop);
            sync(player);
            return;
        }

        if (event.getClick() == ClickType.DOUBLE_CLICK && shouldHandle(player, cursor)) {
            handleDoubleCollect(event, player, top, supportedContainer, cursor, limit);
            return;
        }

        if (event.isShiftClick()) {
            if (supportedContainer && clickedPlayer && isStorageSlot(player, event.getSlot()) && shouldHandle(player, current)) {
                event.setCancelled(true);
                int logical = stacks.getLogicalAmount(current);
                int remaining = stacks.addToContainer(top, current, logical);
                clicked.setItem(event.getSlot(), stacks.withLogicalAmount(current, remaining));
                sync(player);
                return;
            }

            if (supportedContainer && clickedTop && current != null && stacks.isEligible(current)
                    && !stacks.isVirtual(current) && isExtended(player, current)) {
                event.setCancelled(true);
                int logical = current.getAmount();
                int remaining = stacks.addToPlayer(player, current, logical, limit);
                top.setItem(event.getSlot(), vanillaAmount(current, remaining));
                sync(player);
                return;
            }

            if (personalView && clickedPlayer && isStorageSlot(player, event.getSlot()) && shouldHandle(player, current)) {
                event.setCancelled(true);
                handleInternalShift(player, event.getSlot(), current, limit);
                sync(player);
                return;
            }

            if (!personalView && !supportedContainer && clickedPlayer && stacks.isVirtual(current)) {
                event.setCancelled(true);
                player.sendMessage(plugin.message("unsupported-container"));
                return;
            }
        }

        ItemStack hotbar = null;
        if (event.getHotbarButton() >= 0) {
            hotbar = player.getInventory().getItem(event.getHotbarButton());
        }

        if (clickedTop) {
            if (supportedContainer) {
                if (stacks.isVirtual(current)) {
                    event.setCancelled(true);
                    player.sendMessage(plugin.message("container-corrupt"));
                    return;
                }

                if (stacks.isVirtual(hotbar)) {
                    event.setCancelled(true);
                    player.sendMessage(plugin.message("container-unpack"));
                    return;
                }

                if (stacks.isVirtual(cursor)) {
                    if (event.isLeftClick() || event.isRightClick()) {
                        if (current == null || current.getType().isAir() || stacks.isSameVanillaItem(current, cursor)) {
                            event.setCancelled(true);
                            int logical = stacks.getLogicalAmount(cursor);
                            int requested = event.isRightClick() ? 1 : logical;
                            int moved = stacks.depositIntoContainerSlot(top, event.getSlot(), cursor, requested);
                            event.setCursor(stacks.withLogicalAmount(cursor, logical - moved));
                            sync(player);
                            return;
                        }
                    }

                    event.setCancelled(true);
                    player.sendMessage(plugin.message("container-unpack"));
                    return;
                }
                return;
            }

            if (stacks.isVirtual(current) || stacks.isVirtual(cursor) || stacks.isVirtual(hotbar)) {
                event.setCancelled(true);
                player.sendMessage(plugin.message("unsupported-container"));
            }
            return;
        }

        if (clickedPlayer && isStorageSlot(player, event.getSlot())) {
            handlePlayerInventoryClick(event, player, current, cursor, limit);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack cursor = event.getOldCursor();
        if (!shouldHandle(player, cursor)) {
            return;
        }

        Inventory top = event.getView().getTopInventory();
        boolean personalView = isPersonalView(top);
        boolean supportedContainer = !personalView && stacks.isSupportedContainer(top);
        int topSize = top.getSize();

        Set<Integer> topSlots = new LinkedHashSet<>();
        Set<Integer> playerSlots = new LinkedHashSet<>();

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                topSlots.add(rawSlot);
            } else {
                int converted = event.getView().convertSlot(rawSlot);
                if (isStorageSlot(player, converted)) {
                    playerSlots.add(converted);
                }
            }
        }

        if (!topSlots.isEmpty() && !playerSlots.isEmpty()) {
            event.setCancelled(true);
            player.sendMessage(plugin.message("mixed-drag-blocked"));
            return;
        }

        if (!topSlots.isEmpty()) {
            if (!supportedContainer || stacks.isVirtual(cursor) && !stacks.isEligible(cursor)) {
                event.setCancelled(true);
                player.sendMessage(plugin.message("unsupported-container"));
                return;
            }
            event.setCancelled(true);
            distributeToContainer(event, top, cursor, topSlots);
            sync(player);
            return;
        }

        if (!playerSlots.isEmpty()) {
            event.setCancelled(true);
            distributeToPlayer(event, player, cursor, playerSlots, limits.getLimit(player));
            sync(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) {
            return;
        }

        List<ItemStack> additions = new ArrayList<>();
        event.getDrops().removeIf(item -> {
            if (!stacks.isVirtual(item)) {
                return false;
            }

            int logical = stacks.getLogicalAmount(item);
            ItemStack template = stacks.toVanillaTemplate(item);
            int max = template.getType().getMaxStackSize();
            int remaining = logical;
            while (remaining > 0) {
                int move = Math.min(max, remaining);
                ItemStack split = template.clone();
                split.setAmount(move);
                additions.add(split);
                remaining -= move;
            }
            return true;
        });
        event.getDrops().addAll(additions);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            stacks.normalizeInventory(player, limits.getLimit(player));
            stacks.showAmount(player, player.getInventory().getItemInMainHand(), limits.getLimit(player));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        ItemStack cursor = player.getOpenInventory().getCursor();
        if (!stacks.isVirtual(cursor)) {
            return;
        }

        player.getOpenInventory().setCursor(null);
        int logical = stacks.getLogicalAmount(cursor);
        int remaining = stacks.addToPlayer(player, cursor, logical, limits.getLimit(player));
        if (remaining > 0) {
            stacks.dropVanilla(player, cursor, remaining);
        }
    }

    @EventHandler
    public void onHeldSlot(PlayerItemHeldEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            ItemStack item = player.getInventory().getItem(event.getNewSlot());
            stacks.showAmount(player, item, limits.getLimit(player));
        });
    }

    private void handleOutsideClick(InventoryClickEvent event, Player player, ItemStack cursor, int limit) {
        if (!stacks.isVirtual(cursor)) {
            return;
        }
        if (!event.isLeftClick() && !event.isRightClick()) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        int logical = stacks.getLogicalAmount(cursor);
        int toDrop = event.isRightClick() ? Math.min(1, logical) : logical;
        stacks.dropVanilla(player, cursor, toDrop);
        event.setCursor(stacks.withLogicalAmount(cursor, logical - toDrop));
        stacks.showAmount(player, event.getCursor(), limit);
        sync(player);
    }

    private void handlePlayerInventoryClick(InventoryClickEvent event, Player player,
                                            ItemStack current, ItemStack cursor, int limit) {
        if (event.getClick() == ClickType.MIDDLE || event.getClick() == ClickType.CREATIVE) {
            if (stacks.isVirtual(current) || stacks.isVirtual(cursor)) {
                event.setCancelled(true);
            }
            return;
        }

        if (event.isShiftClick()) {
            if (shouldHandle(player, current)) {
                event.setCancelled(true);
                handleInternalShift(player, event.getSlot(), current, limit);
                sync(player);
            }
            return;
        }

        if (event.isRightClick()) {
            boolean currentHandled = shouldHandle(player, current);
            boolean cursorHandled = shouldHandle(player, cursor);
            if (!currentHandled && !cursorHandled) {
                return;
            }

            event.setCancelled(true);

            if (isEmpty(cursor) && !isEmpty(current)) {
                int logical = stacks.getLogicalAmount(current);
                int pickedUp = (logical + 1) / 2;
                int left = logical - pickedUp;
                event.setCurrentItem(stacks.withLogicalAmount(current, left));
                event.setCursor(stacks.withLogicalAmount(current, pickedUp));
                sync(player);
                return;
            }

            if (isEmpty(current) && !isEmpty(cursor)) {
                int logical = stacks.getLogicalAmount(cursor);
                event.setCurrentItem(stacks.withLogicalAmount(cursor, 1));
                event.setCursor(stacks.withLogicalAmount(cursor, logical - 1));
                sync(player);
                return;
            }

            if (!isEmpty(current) && !isEmpty(cursor) && stacks.canMerge(current, cursor)) {
                int currentLogical = stacks.getLogicalAmount(current);
                int cursorLogical = stacks.getLogicalAmount(cursor);
                if (currentLogical < limit && cursorLogical > 0) {
                    event.setCurrentItem(stacks.withLogicalAmount(current, currentLogical + 1));
                    event.setCursor(stacks.withLogicalAmount(cursor, cursorLogical - 1));
                }
                sync(player);
                return;
            }

            player.sendMessage(plugin.message("unsafe-click"));
            return;
        }

        if (event.isLeftClick() && !isEmpty(current) && !isEmpty(cursor)
                && stacks.canMerge(current, cursor)
                && (shouldHandle(player, current) || shouldHandle(player, cursor))) {
            event.setCancelled(true);
            int currentLogical = stacks.getLogicalAmount(current);
            int cursorLogical = stacks.getLogicalAmount(cursor);
            int move = Math.min(Math.max(0, limit - currentLogical), cursorLogical);
            if (move > 0) {
                event.setCurrentItem(stacks.withLogicalAmount(current, currentLogical + move));
                event.setCursor(stacks.withLogicalAmount(cursor, cursorLogical - move));
            }
            sync(player);
        }
    }

    private void handleInternalShift(Player player, int sourceSlot, ItemStack source, int limit) {
        if (!isStorageSlot(player, sourceSlot) || source == null) {
            return;
        }

        int[] targets;
        if (sourceSlot >= 0 && sourceSlot <= 8) {
            targets = range(9, 35);
        } else {
            targets = range(0, 8);
        }

        int logical = stacks.getLogicalAmount(source);
        int remaining = stacks.addToPlayerSlots(player, source, logical, limit, targets);
        player.getInventory().setItem(sourceSlot, stacks.withLogicalAmount(source, remaining));
        stacks.showAmount(player, player.getInventory().getItemInMainHand(), limit);
    }

    private void handleDoubleCollect(InventoryClickEvent event, Player player, Inventory top,
                                     boolean supportedContainer, ItemStack cursor, int limit) {
        if (isEmpty(cursor) || !stacks.isEligible(cursor)) {
            return;
        }

        event.setCancelled(true);
        int total = stacks.getLogicalAmount(cursor);
        int capacity = Math.max(0, limit - total);

        if (capacity > 0) {
            PlayerInventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getStorageContents().length && capacity > 0; slot++) {
                ItemStack item = inventory.getItem(slot);
                if (isEmpty(item) || !stacks.canMerge(item, cursor)) {
                    continue;
                }

                int logical = stacks.getLogicalAmount(item);
                int move = Math.min(capacity, logical);
                inventory.setItem(slot, stacks.withLogicalAmount(item, logical - move));
                total += move;
                capacity -= move;
            }
        }

        if (supportedContainer && capacity > 0) {
            for (int slot = 0; slot < top.getSize() && capacity > 0; slot++) {
                ItemStack item = top.getItem(slot);
                if (isEmpty(item) || stacks.isVirtual(item) || !stacks.canMerge(item, cursor)) {
                    continue;
                }

                int move = Math.min(capacity, item.getAmount());
                top.setItem(slot, vanillaAmount(item, item.getAmount() - move));
                total += move;
                capacity -= move;
            }
        }

        event.setCursor(stacks.withLogicalAmount(cursor, total));
        stacks.showAmount(player, event.getCursor(), limit);
        sync(player);
    }

    private void distributeToPlayer(InventoryDragEvent event, Player player, ItemStack cursor,
                                    Set<Integer> slots, int limit) {
        int remaining = stacks.getLogicalAmount(cursor);
        int targetsLeft = slots.size();

        for (int slot : slots) {
            if (remaining <= 0 || targetsLeft <= 0) {
                break;
            }

            int requested = event.getType() == DragType.SINGLE
                    ? 1
                    : (int) Math.ceil((double) remaining / targetsLeft);
            int moved = stacks.mergeIntoPlayerSlot(player, slot, cursor, requested, limit);
            remaining -= moved;
            targetsLeft--;
        }

        event.setCursor(stacks.withLogicalAmount(cursor, remaining));
        stacks.showAmount(player, event.getCursor(), limit);
    }

    private void distributeToContainer(InventoryDragEvent event, Inventory container, ItemStack cursor,
                                       Set<Integer> slots) {
        int remaining = stacks.getLogicalAmount(cursor);
        int targetsLeft = slots.size();

        for (int slot : slots) {
            if (remaining <= 0 || targetsLeft <= 0) {
                break;
            }

            int requested = event.getType() == DragType.SINGLE
                    ? 1
                    : (int) Math.ceil((double) remaining / targetsLeft);
            int moved = stacks.depositIntoContainerSlot(container, slot, cursor, requested);
            remaining -= moved;
            targetsLeft--;
        }

        event.setCursor(stacks.withLogicalAmount(cursor, remaining));
    }

    private boolean shouldHandle(Player player, ItemStack item) {
        return stacks.isVirtual(item) || isExtended(player, item);
    }

    private boolean isExtended(Player player, ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && stacks.isEligible(item)
                && limits.getLimit(player) > item.getType().getMaxStackSize();
    }

    private boolean isPersonalView(Inventory top) {
        return top.getHolder() instanceof Player || top.getType().name().equals("CRAFTING");
    }

    private boolean isStorageSlot(Player player, int slot) {
        return slot >= 0 && slot < player.getInventory().getStorageContents().length;
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private ItemStack vanillaAmount(ItemStack source, int amount) {
        if (source == null || amount <= 0) {
            return null;
        }
        ItemStack result = stacks.toVanillaTemplate(source);
        result.setAmount(amount);
        return result;
    }

    private int[] range(int startInclusive, int endInclusive) {
        int[] result = new int[endInclusive - startInclusive + 1];
        for (int i = 0; i < result.length; i++) {
            result[i] = startInclusive + i;
        }
        return result;
    }

    private void sync(Player player) {
        if (!plugin.getConfig().getBoolean("behavior.force-inventory-sync", true)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, player::updateInventory);
    }
}
