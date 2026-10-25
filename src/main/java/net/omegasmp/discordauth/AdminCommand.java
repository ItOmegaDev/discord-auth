package net.omegasmp.discordauth;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * /discordauth status &lt;player&gt; | /discordauth unlink &lt;player&gt;
 */
public class AdminCommand implements SimpleCommand {

  private final DiscordAuthPlugin plugin;

  public AdminCommand(DiscordAuthPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public void execute(Invocation invocation) {
    CommandSource source = invocation.source();
    String[] args = invocation.arguments();

    if (args.length < 2) {
      source.sendMessage(Component.text(
          "Usage: " + this.prefix() + " <status|unlink|password|unregister|list> <player>",
          NamedTextColor.YELLOW));
      return;
    }

    String sub = args[0].toLowerCase(Locale.ROOT);
    String target = args[1];
    LinkStore store = this.plugin.getLinkStore();
    AccountStore accounts = this.plugin.getAccountStore();

    switch (sub) {
      case "status" -> {
        LinkStore.Link link = store.getLinkByName(target);
        if (link == null) {
          source.sendMessage(Component.text(target + " is not linked to Discord.", NamedTextColor.RED));
          return;
        }
        source.sendMessage(Component.text(target + " -> Discord ID " + link.discordId
            + (link.discordName == null || link.discordName.isBlank() ? "" : " (@" + link.discordName + ")")
            + " (linked " + link.linkedAt + ")", NamedTextColor.GREEN));
      }
      case "unlink" -> {
        String uuid = store.getUuidByName(target);
        if (uuid == null) {
          source.sendMessage(Component.text("No link found for " + target, NamedTextColor.RED));
          return;
        }
        store.unlink(UUID.fromString(uuid));
        source.sendMessage(Component.text("Link for " + target + " removed.", NamedTextColor.GREEN));
      }
      case "password" -> {
        String uuid = store.getUuidByName(target);
        if (uuid == null) {
          source.sendMessage(Component.text("No account for " + target + " (not linked).", NamedTextColor.RED));
          return;
        }
        boolean registered = accounts.isRegistered(UUID.fromString(uuid));
        source.sendMessage(Component.text(target + (registered ? " has a password."
            : " has no password yet."), registered ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
      }
      case "unregister" -> {
        String uuid = store.getUuidByName(target);
        UUID id = uuid == null ? null : UUID.fromString(uuid);
        if (id == null && target.length() == 36) {
          try {
            id = UUID.fromString(target);
          } catch (IllegalArgumentException ignored) {
            // not a UUID after all
          }
        }
        if (id == null || !accounts.unregister(id)) {
          source.sendMessage(Component.text("No password for " + target, NamedTextColor.RED));
          return;
        }
        source.sendMessage(Component.text("Password for " + target + " removed - the player will create a new one with /register.",
            NamedTextColor.GREEN));
      }
      case "list" -> {
        source.sendMessage(Component.text("Linked accounts: " + store.size()
            + ", with a password: " + accounts.size(), NamedTextColor.AQUA));
        this.plugin.pendingSessions().forEach(session ->
            source.sendMessage(Component.text("  " + session.getUsername() + " -> code " + session.getCode()
                + (session.needsPassword() ? " + password" : ""), NamedTextColor.GRAY)));
      }
      default -> source.sendMessage(Component.text(
          "Unknown action: " + sub + " (status|unlink|password|unregister|list)", NamedTextColor.RED));
    }
  }

  @Override
  public boolean hasPermission(Invocation invocation) {
    return invocation.source().hasPermission("discordauth.admin");
  }

  /** The primary admin command name from the config (aliases are registered next to it). */
  private String prefix() {
    java.util.List<String> names = this.plugin.getConfig().commands.admin;
    return "/" + (names == null || names.isEmpty() ? "discordauth" : names.get(0));
  }
}
