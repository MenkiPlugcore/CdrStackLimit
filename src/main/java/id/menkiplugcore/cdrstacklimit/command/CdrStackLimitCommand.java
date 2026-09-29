package id.menkiplugcore.cdrstacklimit.command;

import id.menkiplugcore.cdrstacklimit.CdrStackLimitPlugin;
import id.menkiplugcore.cdrstacklimit.StackLimitService;
import id.menkiplugcore.cdrstacklimit.VirtualStackService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CdrStackLimitCommand implements CommandExecutor, TabCompleter {
    private final CdrStackLimitPlugin plugin;
    private final StackLimitService limits;
    private final VirtualStackService stacks;

    public CdrStackLimitCommand(CdrStackLimitPlugin plugin, StackLimitService limits, VirtualStackService stacks) {
        this.plugin = plugin;
        this.limits = limits;
        this.stacks = stacks;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.color("&bCdrStackLimit &7v" + plugin.getDescription().getVersion()));
                return true;
            }

            int limit = limits.getLimit(player);
            ItemStack hand = player.getInventory().getItemInMainHand();
            sender.sendMessage(plugin.color("&8&m--------------------------------"));
            sender.sendMessage(plugin.color("&b&lCdrStackLimit &7v" + plugin.getDescription().getVersion()));
            sender.sendMessage(plugin.color("&7Stack limit kamu: &f" + limit));
            if (stacks.isVirtual(hand)) {
                sender.sendMessage(plugin.color("&7Item di tangan: &f" + hand.getType().name()));
                sender.sendMessage(plugin.color("&7Jumlah virtual: &b" + stacks.getLogicalAmount(hand)));
            }
            sender.sendMessage(plugin.color("&8&m--------------------------------"));
            return true;
        }

        if (!sender.hasPermission("cdrstacklimit.admin")) {
            sender.sendMessage(plugin.message("no-permission"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadPlugin();
            sender.sendMessage(plugin.message("reloaded"));
            return true;
        }

        if (args[0].equalsIgnoreCase("normalize")) {
            Player target;
            if (args.length >= 2) {
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(plugin.color("&cPlayer tidak ditemukan."));
                    return true;
                }
            } else if (sender instanceof Player player) {
                target = player;
            } else {
                sender.sendMessage(plugin.color("&cGunakan: /cdrstacklimit normalize <player>"));
                return true;
            }

            stacks.normalizeInventory(target, limits.getLimit(target));
            sender.sendMessage(plugin.message("normalized"));
            return true;
        }

        sender.sendMessage(plugin.color("&7/cdrstacklimit info"));
        sender.sendMessage(plugin.color("&7/cdrstacklimit reload"));
        sender.sendMessage(plugin.color("&7/cdrstacklimit normalize [player]"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>();
            values.add("info");
            if (sender.hasPermission("cdrstacklimit.admin")) {
                values.add("reload");
                values.add("normalize");
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return values.stream().filter(value -> value.startsWith(prefix)).toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("normalize") && sender.hasPermission("cdrstacklimit.admin")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }

        return List.of();
    }
}
