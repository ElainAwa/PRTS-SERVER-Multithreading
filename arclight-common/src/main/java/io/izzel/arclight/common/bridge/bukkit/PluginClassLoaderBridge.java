package io.izzel.arclight.common.bridge.bukkit;

import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.SimplePluginManager;

import java.util.logging.Logger;

public interface PluginClassLoaderBridge {
    PluginDescriptionFile arclight$desc();
    Class<?> arclight$loadFromExternal(String name, boolean initialize, boolean checkLibraries) throws ClassNotFoundException;
    SimplePluginManager arclight$getPluginManager();
    Logger arclight$systemLogger();
}
