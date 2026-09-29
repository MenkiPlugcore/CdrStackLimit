package id.menkiplugcore.cdrstacklimit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class VirtualStackService {
    private final CdrStackLimitPlugin plugin;
    private final NamespacedKey virtualAmountKey;
    private final NamespacedKey markerKey;
    private final Set<Material> blockedMaterials = new HashSet<>();

    public VirtualStackService(CdrStackLimitPlugin plugin) {
        this.plugin = plugin;
        this.virtualAmountKey = new NamespacedKey(plugin, "virtual_amount");
        this.markerKey = new NamespacedKey(plugin, "virtual_stack");
        reload();
    }

    public void reload() {
        blockedMaterials.clear();
        for (String raw : plugin.getConfig().getStringList("behavior.blocked-materials")) {
            Material material = Material.matchMaterial(raw);
            if (material != null) {
                blockedMaterials.add(material);
            }
        }
    }

    public boolean isVirtual(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        Integer amount = item.getItemMeta().getPersistentDataContainer()
                .get(virtualAmountKey, PersistentDataType.INTEGER);
        return amount != null && amount > 0;
    }

    public int getLogicalAmount(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }
        if (!isVirtual(item)) {
            return item.getAmount();
        }
        Integer amount = item.getItemMeta().getPersistentDataContainer()
                .get(virtualAmountKey, PersistentDataType.INTEGER);
        return amount == null ? item.getAmount() : Math.max(0, amount);
    }

    public boolean isEligible(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (isVirtual(item)) {
            return true;
        }

        Material material = item.getType();
        if (!material.isBlock() || material.getMaxStackSize() <= 1 || blockedMaterials.contains(material)) {
            return false;
        }

        if (!item.hasItemMeta()) {
            return true;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta) {
            return false;
        }
        if (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants() || meta.hasCustomModelData()) {
            return false;
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.getKeys().isEmpty();
    }

    public boolean canMerge(ItemStack first, ItemStack second) {
        return first != null
                && second != null
                && first.getType() == second.getType()
                && isEligible(first)
                && isEligible(second);
    }

    public ItemStack withLogicalAmount(ItemStack source, int logicalAmount) {
        if (source == null || logicalAmount <= 0) {
            return null;
        }

        ItemStack result = source.clone();
        int vanillaMax = result.getType().getMaxStackSize();
        if (logicalAmount <= vanillaMax) {
            result.setAmount(logicalAmount);
            clearVirtualMetadata(result);
            return result;
        }

        result.setAmount(vanillaMax);
        ItemMeta meta = result.getItemMeta();
        meta.getPersistentDataContainer().set(virtualAmountKey, PersistentDataType.INTEGER, logicalAmount);
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);

        if (plugin.getConfig().getBoolean("ui.lore", true)) {
            meta.lore(List.of(
                    Component.text("Virtual Stack: ", NamedTextColor.DARK_GRAY)
                            .append(Component.text(logicalAmount, NamedTextColor.AQUA))
            ));
        }

        result.setItemMeta(meta);
        return result;
    }

    public ItemStack toVanillaTemplate(ItemStack source) {
        if (source == null) {
            return null;
        }
        ItemStack result = source.clone();
        result.setAmount(1);
        clearVirtualMetadata(result);
        return result;
    }

    public void clearVirtualMetadata(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        boolean ours = pdc.has(markerKey, PersistentDataType.BYTE)
                || pdc.has(virtualAmountKey, PersistentDataType.INTEGER);
        pdc.remove(virtualAmountKey);
        pdc.remove(markerKey);
        if (ours && plugin.getConfig().getBoolean("ui.lore", true)) {
            meta.lore(null);
        }
        item.setItemMeta(meta);
    }

    public int addToPlayer(Player player, ItemStack source, int logicalAmount, int limit) {
        if (logicalAmount <= 0 || source == null || !isEligible(source)) {
            return logicalAmount;
        }

        PlayerInventory inventory = player.getInventory();
        int remaining = logicalAmount;

        for (int slot = 0; slot < inventory.getStorageContents().length && remaining > 0; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing == null || !canMerge(existing, source)) {
                continue;
            }

            int existingLogical = getLogicalAmount(existing);
            if (existingLogical >= limit) {
                continue;
            }

            int move = Math.min(limit - existingLogical, remaining);
            inventory.setItem(slot, withLogicalAmount(existing, existingLogical + move));
            remaining -= move;
        }

        while (remaining > 0) {
            int emptySlot = inventory.firstEmpty();
            if (emptySlot < 0 || emptySlot >= inventory.getStorageContents().length) {
                break;
            }
            int move = Math.min(limit, remaining);
            inventory.setItem(emptySlot, withLogicalAmount(source, move));
            remaining -= move;
        }

        return remaining;
    }

    public void normalizeInventory(Player player, int limit) {
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> overflow = new ArrayList<>();

        for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !isVirtual(item)) {
                continue;
            }

            int logicalAmount = getLogicalAmount(item);
            if (logicalAmount <= limit) {
                inventory.setItem(slot, withLogicalAmount(item, logicalAmount));
                continue;
            }

            inventory.setItem(slot, withLogicalAmount(item, limit));
            int remaining = logicalAmount - limit;
            ItemStack template = toVanillaTemplate(item);

            while (remaining > 0) {
                int emptySlot = inventory.firstEmpty();
                if (emptySlot < 0 || emptySlot >= inventory.getStorageContents().length) {
                    break;
                }
                int move = Math.min(limit, remaining);
                inventory.setItem(emptySlot, withLogicalAmount(template, move));
                remaining -= move;
            }

            while (remaining > 0) {
                int move = Math.min(template.getType().getMaxStackSize(), remaining);
                ItemStack drop = template.clone();
                drop.setAmount(move);
                overflow.add(drop);
                remaining -= move;
            }
        }

        for (ItemStack drop : overflow) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    public void showAmount(Player player, ItemStack item, int limit) {
        if (!plugin.getConfig().getBoolean("ui.actionbar", true) || item == null || !isVirtual(item)) {
            return;
        }
        int logical = getLogicalAmount(item);
        String materialName = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        player.sendActionBar(
                Component.text(materialName, NamedTextColor.GRAY)
                        .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(logical, NamedTextColor.AQUA))
                        .append(Component.text(" / " + limit, NamedTextColor.DARK_GRAY))
        );
    }
}
