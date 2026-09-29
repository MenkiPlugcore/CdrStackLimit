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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;

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
            ItemStack hand = player.getInventory().getItemInMainHand();
            stacks.showAmount(player, hand, limit);
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
        int requested = Math.max(1, dropped.getAmount());
        ItemStack template = stacks.toVanillaTemplate(dropped);

        event.setCancelled(true);

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            PlayerInventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
                ItemStack current = inventory.getItem(slot);
                if (current == null || !stacks.canMerge(current, dropped) || !stacks.isVirtual(current)) {
                    continue;
                }

                int logical = stacks.getLogicalAmount(current);
                int toDrop = Math.min(requested, logical);
                inventory.setItem(slot, stacks.withLogicalAmount(current, logical - toDrop));

                ItemStack vanillaDrop = template.clone();
                vanillaDrop.setAmount(toDrop);
                player.getWorld().dropItemNaturally(player.getLocation(), vanillaDrop);
                stacks.showAmount(player, inventory.getItem(slot), limits.getLimit(player));
                return;
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!plugin.getConfig().getBoolean("behavior.block-container-transfer", true)) {
            return;
        }

        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        ItemStack hotbar = null;
        if (event.getHotbarButton() >= 0) {
            hotbar = player.getInventory().getItem(event.getHotbarButton());
        }

        boolean currentVirtual = stacks.isVirtual(current);
        boolean cursorVirtual = stacks.isVirtual(cursor);
        boolean hotbarVirtual = stacks.isVirtual(hotbar);
        boolean hasVirtual = currentVirtual || cursorVirtual || hotbarVirtual;
        if (!hasVirtual) {
            return;
        }

        Inventory clicked = event.getClickedInventory();
        Inventory top = event.getView().getTopInventory();
        boolean clickedNonPlayer = clicked != null && !(clicked instanceof PlayerInventory);
        boolean shiftIntoTop = event.isShiftClick() && clicked instanceof PlayerInventory;
        boolean topIsPlayerCraftingOnly = top.getHolder() instanceof Player;

        if (clickedNonPlayer || (shiftIntoTop && !topIsPlayerCraftingOnly)) {
            event.setCancelled(true);
            player.sendMessage(plugin.message("container-blocked"));
            return;
        }

        boolean sameMergeTarget = current != null
                && cursor != null
                && !current.getType().isAir()
                && !cursor.getType().isAir()
                && stacks.canMerge(current, cursor);

        boolean unsafe = event.isShiftClick()
                || event.isRightClick()
                || event.getClick() == ClickType.DOUBLE_CLICK
                || event.getClick() == ClickType.MIDDLE
                || event.getClickedInventory() == null
                || sameMergeTarget;

        if (unsafe) {
            event.setCancelled(true);
            player.sendMessage(plugin.message("unsafe-click"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!plugin.getConfig().getBoolean("behavior.block-container-transfer", true)) {
            return;
        }
        if (!stacks.isVirtual(event.getOldCursor())) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(plugin.message("unsafe-click"));
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

    @EventHandler
    public void onHeldSlot(PlayerItemHeldEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            ItemStack item = player.getInventory().getItem(event.getNewSlot());
            stacks.showAmount(player, item, limits.getLimit(player));
        });
    }
}
