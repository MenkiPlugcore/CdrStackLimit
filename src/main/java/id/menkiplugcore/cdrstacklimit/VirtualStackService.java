package id.menkiplugcore.cdrstacklimit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
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
    private static final List<String> DEFAULT_SUPPORTED_CONTAINERS = List.of(
            "CHEST", "BARREL", "SHULKER_BOX", "ENDER_CHEST", "HOPPER", "DISPENSER", "DROPPER"
    );

    private final CdrStackLimitPlugin plugin;
    private final NamespacedKey virtualAmountKey;
    private final NamespacedKey markerKey;
    private final Set<Material> blockedMaterials = new HashSet<>();
    private final Set<String> supportedContainerTypes = new HashSet<>();

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

        supportedContainerTypes.clear();
        List<String> configured = plugin.getConfig().getStringList("behavior.supported-containers");
        List<String> source = configured.isEmpty() ? DEFAULT_SUPPORTED_CONTAINERS : configured;
        for (String raw : source) {
            if (raw != null && !raw.isBlank()) {
                supportedContainerTypes.add(raw.trim().toUpperCase(Locale.ROOT));
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

    public boolean isSupportedContainer(Inventory inventory) {
        return inventory != null && supportedContainerTypes.contains(inventory.getType().name());
    }

    public int addToPlayer(Player player, ItemStack source, int logicalAmount, int limit) {
        int storageSize = player.getInventory().getStorageContents().length;
        int[] slots = new int[storageSize];
        for (int i = 0; i < storageSize; i++) {
            slots[i] = i;
        }
        return addToPlayerSlots(player, source, logicalAmount, limit, slots);
    }

    public int addToPlayerSlots(Player player, ItemStack source, int logicalAmount, int limit, int[] slots) {
        if (logicalAmount <= 0 || source == null || !isEligible(source)) {
            return logicalAmount;
        }

        PlayerInventory inventory = player.getInventory();
        int storageSize = inventory.getStorageContents().length;
        int remaining = logicalAmount;

        for (int slot : slots) {
            if (remaining <= 0) {
                break;
            }
            if (slot < 0 || slot >= storageSize) {
                continue;
            }
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

        for (int slot : slots) {
            if (remaining <= 0) {
                break;
            }
            if (slot < 0 || slot >= storageSize || inventory.getItem(slot) != null) {
                continue;
            }

            int move = Math.min(limit, remaining);
            inventory.setItem(slot, withLogicalAmount(source, move));
            remaining -= move;
        }

        return remaining;
    }

    public int addToContainer(Inventory inventory, ItemStack source, int logicalAmount) {
        if (inventory == null || source == null || logicalAmount <= 0 || !isEligible(source)) {
            return logicalAmount;
        }

        ItemStack template = toVanillaTemplate(source);
        int slotMax = Math.max(1, Math.min(template.getType().getMaxStackSize(), inventory.getMaxStackSize()));
        int remaining = logicalAmount;

        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing == null || existing.getType().isAir() || isVirtual(existing)) {
                continue;
            }
            if (!isSameVanillaItem(existing, template) || existing.getAmount() >= slotMax) {
                continue;
            }

            int move = Math.min(slotMax - existing.getAmount(), remaining);
            existing.setAmount(existing.getAmount() + move);
            inventory.setItem(slot, existing);
            remaining -= move;
        }

        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing != null && !existing.getType().isAir()) {
                continue;
            }

            int move = Math.min(slotMax, remaining);
            ItemStack placed = template.clone();
            placed.setAmount(move);
            inventory.setItem(slot, placed);
            remaining -= move;
        }

        return remaining;
    }

    public int depositIntoContainerSlot(Inventory inventory, int slot, ItemStack source, int requestedAmount) {
        if (inventory == null || source == null || requestedAmount <= 0 || slot < 0 || slot >= inventory.getSize()) {
            return 0;
        }

        ItemStack template = toVanillaTemplate(source);
        int slotMax = Math.max(1, Math.min(template.getType().getMaxStackSize(), inventory.getMaxStackSize()));
        ItemStack existing = inventory.getItem(slot);

        if (existing == null || existing.getType().isAir()) {
            int move = Math.min(slotMax, requestedAmount);
            ItemStack placed = template.clone();
            placed.setAmount(move);
            inventory.setItem(slot, placed);
            return move;
        }

        if (isVirtual(existing) || !isSameVanillaItem(existing, template) || existing.getAmount() >= slotMax) {
            return 0;
        }

        int move = Math.min(slotMax - existing.getAmount(), requestedAmount);
        existing.setAmount(existing.getAmount() + move);
        inventory.setItem(slot, existing);
        return move;
    }

    public int mergeIntoPlayerSlot(Player player, int slot, ItemStack source, int requestedAmount, int limit) {
        PlayerInventory inventory = player.getInventory();
        int storageSize = inventory.getStorageContents().length;
        if (source == null || requestedAmount <= 0 || slot < 0 || slot >= storageSize) {
            return 0;
        }

        ItemStack existing = inventory.getItem(slot);
        if (existing == null || existing.getType().isAir()) {
            int move = Math.min(limit, requestedAmount);
            inventory.setItem(slot, withLogicalAmount(source, move));
            return move;
        }

        if (!canMerge(existing, source)) {
            return 0;
        }

        int existingLogical = getLogicalAmount(existing);
        if (existingLogical >= limit) {
            return 0;
        }

        int move = Math.min(limit - existingLogical, requestedAmount);
        inventory.setItem(slot, withLogicalAmount(existing, existingLogical + move));
        return move;
    }

    public boolean isSameVanillaItem(ItemStack first, ItemStack second) {
        if (first == null || second == null) {
            return false;
        }
        ItemStack a = first.clone();
        ItemStack b = second.clone();
        clearVirtualMetadata(a);
        clearVirtualMetadata(b);
        a.setAmount(1);
        b.setAmount(1);
        return a.isSimilar(b);
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

    public void dropVanilla(Player player, ItemStack source, int logicalAmount) {
        if (source == null || logicalAmount <= 0) {
            return;
        }
        ItemStack template = toVanillaTemplate(source);
        int max = template.getType().getMaxStackSize();
        int remaining = logicalAmount;
        while (remaining > 0) {
            int move = Math.min(max, remaining);
            ItemStack drop = template.clone();
            drop.setAmount(move);
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
            remaining -= move;
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
