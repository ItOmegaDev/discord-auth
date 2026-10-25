package net.omegasmp.discordauth;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;

/**
 * Optional hook into the TAB plugin (NEZNAMY), which takes over the tab list on the proxy.
 *
 * <p>Instead of fighting over the same player-info entry, we register player placeholders that TAB
 * can use anywhere in its own configuration (tab list, header/footer, scoreboard, …):
 * {@code %discordauth_nick%}, {@code %discordauth_username%}, {@code %discordauth_global%} and
 * {@code %discordauth_id%}.
 *
 * <p>Everything here is reflective on purpose: TAB stays a completely optional plugin, so this
 * plugin keeps building and running without TAB's API jar.
 */
public class TabIntegration {

  private final DiscordAuthPlugin plugin;
  private final Logger logger;

  private Object placeholderManager;
  private Method registerPlayerPlaceholder;
  private Method tabPlayerGetUniqueId;

  public TabIntegration(DiscordAuthPlugin plugin) {
    this.plugin = plugin;
    this.logger = plugin.getLogger();
  }

  /** @return {@code true} when TAB (NEZNAMY) is installed and its API could be hooked. */
  public boolean hook() {
    try {
      Object tabInstance = this.plugin.getServer().getPluginManager()
          .getPlugin("TAB")
          .flatMap(container -> container.getInstance().map(loaded -> (Object) loaded))
          .orElse(null);
      if (tabInstance == null) {
        return false;
      }
      // TAB's classes live in TAB's own plugin classloader, so load them through its instance.
      ClassLoader loader = tabInstance.getClass().getClassLoader();
      Class<?> api = Class.forName("me.neznamy.tab.api.TabAPI", true, loader);
      Object tabApi = api.getMethod("getInstance").invoke(null);
      if (tabApi == null) {
        return false;
      }
      Object manager = api.getMethod("getPlaceholderManager").invoke(tabApi);
      Class<?> managerClass = Class.forName("me.neznamy.tab.api.placeholder.PlaceholderManager", true, loader);
      Class<?> tabPlayerClass = Class.forName("me.neznamy.tab.api.TabPlayer", true, loader);

      this.registerPlayerPlaceholder =
          managerClass.getMethod("registerPlayerPlaceholder", String.class, int.class, Function.class);
      this.tabPlayerGetUniqueId = tabPlayerClass.getMethod("getUniqueId");
      this.placeholderManager = manager;
      return true;
    } catch (Throwable throwable) {
      this.logger.warn("TAB plugin was found but its API could not be hooked: {}", throwable.toString());
      return false;
    }
  }

  /**
   * Registers a player placeholder. The supplier receives the player's UUID and returns the text to
   * show; it is called on TAB's refresh threads, so it must be fast (ours only reads a map).
   */
  public void registerPlaceholder(String identifier, Function<UUID, String> value) {
    if (this.registerPlayerPlaceholder == null || this.placeholderManager == null) {
      return;
    }
    int refresh = Math.max(50, this.plugin.getConfig().tab.tabPluginPlaceholderRefreshMillis / 50 * 50);
    Function<Object, String> function = tabPlayer -> {
      try {
        Object uuid = this.tabPlayerGetUniqueId.invoke(tabPlayer);
        return uuid instanceof UUID ? value.apply((UUID) uuid) : "";
      } catch (Throwable throwable) {
        return "";
      }
    };
    try {
      this.registerPlayerPlaceholder.invoke(this.placeholderManager, identifier, refresh, function);
    } catch (Throwable throwable) {
      this.logger.warn("Unable to register the TAB placeholder {}: {}", identifier, throwable.toString());
    }
  }
}
