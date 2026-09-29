package id.menkiplugcore.cdrstacklimit;

import id.menkiplugcore.cdrstacklimit.command.CdrStackLimitCommand;
import id.menkiplugcore.cdrstacklimit.listener.StackListener;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class CdrStackLimitPlugin extends JavaPlugin {
    private StackLimitService stackLimitService;
    private VirtualStackService virtualStackService;
    private int reconcileTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        stackLimitService = new StackLimitService(this);
        virtualStackService = new VirtualStackService(this);

        Bukkit.getPluginManager().registerEvents(
                new StackListener(this, stackLimitService, virtualStackService),
                this
        );

        PluginCommand command = getCommand("cdrstacklimit");
        if (command != null) {
            CdrStackLimitCommand executor = new CdrStackLimitCommand(this, stackLimitService, virtualStackService);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        startReconcileTask();
        getLogger().info("CdrStackLimit v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (reconcileTaskId != -1) {
            Bukkit.getScheduler().cancelTask(reconcileTaskId);
        }
    }

    public void reloadPlugin() {
        reloadConfig();
        stackLimitService.reload();
        virtualStackService.reload();
        startReconcileTask();
    }

    private void startReconcileTask() {
        if (reconcileTaskId != -1) {
            Bukkit.getScheduler().cancelTask(reconcileTaskId);
        }

        long interval = Math.max(20L, getConfig().getLong("behavior.reconcile-interval-ticks", 100L));
        reconcileTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (var player : Bukkit.getOnlinePlayers()) {
                virtualStackService.normalizeInventory(player, stackLimitService.getLimit(player));
            }
        }, interval, interval);
    }

    public String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public String message(String key) {
        String prefix = getConfig().getString("messages.prefix", "&8[&bCdrStackLimit&8] &r");
        String body = getConfig().getString("messages." + key, "");
        return color(prefix + body);
    }
}
