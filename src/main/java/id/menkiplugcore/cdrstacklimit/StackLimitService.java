package id.menkiplugcore.cdrstacklimit;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class StackLimitService {
    private final CdrStackLimitPlugin plugin;
    private int defaultLimit;
    private List<Tier> tiers = List.of();

    public StackLimitService(CdrStackLimitPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        defaultLimit = Math.max(1, plugin.getConfig().getInt("stack-limits.default", 64));

        List<Tier> loaded = new ArrayList<>();
        List<?> rawTiers = plugin.getConfig().getList("stack-limits.tiers", List.of());
        for (Object raw : rawTiers) {
            if (!(raw instanceof java.util.Map<?, ?> map)) {
                continue;
            }
            Object permissionRaw = map.get("permission");
            Object limitRaw = map.get("limit");
            if (!(permissionRaw instanceof String permission) || permission.isBlank()) {
                continue;
            }
            int limit;
            if (limitRaw instanceof Number number) {
                limit = number.intValue();
            } else {
                try {
                    limit = Integer.parseInt(String.valueOf(limitRaw));
                } catch (NumberFormatException ignored) {
                    continue;
                }
            }
            loaded.add(new Tier(permission, Math.max(1, limit)));
        }

        loaded.sort(Comparator.comparingInt(Tier::limit).reversed());
        tiers = List.copyOf(loaded);
    }

    public int getLimit(Player player) {
        for (Tier tier : tiers) {
            if (player.hasPermission(tier.permission())) {
                return Math.max(defaultLimit, tier.limit());
            }
        }
        return defaultLimit;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public List<Tier> getTiers() {
        return tiers;
    }

    public record Tier(String permission, int limit) {}
}
