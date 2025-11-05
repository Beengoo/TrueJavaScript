package ua.beengoo.trueJavaScript;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class TrueJavaScript extends JavaPlugin {

    private ScriptManager scriptManager;
    private static TrueJavaScript instance;

    @Override
    public void onEnable() {
        getLogger().info("Plugin starting...");
        instance = this;
        scriptManager = new ScriptManager(this);
        boolean ok = scriptManager.loadAll();
        if (!ok) {
            getLogger().warning("Some scripts failed to load!");
        }

        Objects.requireNonNull(getCommand("scripts")).setExecutor((sender, cmd, label, args) -> {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                sender.sendMessage("§eReloading scripts...");
                if (scriptManager.reloadAllAtomic()) {
                    sender.sendMessage("§aScripts reloaded successfully!");
                } else {
                    sender.sendMessage("§cReload failed. Check console for details.");
                }
                return true;
            }
            sender.sendMessage("§7Usage: /scripts reload");
            return true;
        });
    }

    @Override
    public void onDisable() {
        if (scriptManager != null) {
            scriptManager.unloadAll();
        }
    }

    public static TrueJavaScript getInstance() {
        return instance;
    }
}
