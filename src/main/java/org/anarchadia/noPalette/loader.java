package org.anarchadia.noPalette;

import org.bukkit.plugin.java.JavaPlugin;
import org.anarchadia.noPalette.PaletteExploitPatcher;

public final class loader extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        new PaletteExploitPatcher(this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
