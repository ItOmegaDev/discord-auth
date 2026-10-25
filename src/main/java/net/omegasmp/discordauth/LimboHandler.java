package net.omegasmp.discordauth;

import java.time.Duration;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;

/**
 * Handles one player while they are held in the virtual limbo.
 * <p>
 * Two steps can be required, in any order: the Discord link (code shown here, confirmed with
 * <code>/verify</code> on Discord) and the password (<code>/register</code> or
 * <code>/login</code> typed in this limbo chat).
 * </p>
 */
public class LimboHandler implements LimboSessionHandler {

  private final DiscordAuthPlugin plugin;
  private final AuthSession session;
  private volatile LimboPlayer limboPlayer;

  public LimboHandler(DiscordAuthPlugin plugin, AuthSession session) {
    this.plugin = plugin;
    this.session = session;
  }

  @Override
  public void onSpawn(Limbo server, LimboPlayer player) {
    this.limboPlayer = player;
    player.disableFalling();

    Config config = this.plugin.getConfig();
    String code = this.session.getCode();
    String playerName = this.session.getUsername();

    if (this.session.needsDiscord()) {
      this.send(this.plugin.buildMessage(config.messages.chat, this.session));
      this.showTitle(player, config.messages.title, config.messages.subtitle, playerName, code);
      this.showActionBar(player, config.messages.actionBar, playerName, code);
    }

    if (this.session.needsPassword()) {
      if (!this.session.needsDiscord()) {
        // Password only: the code is irrelevant, so show a neutral title instead.
        this.showTitle(player, config.messages.passwordTitle, config.messages.passwordSubtitle, playerName, code);
      }
      this.send(this.plugin.passwordPrompt(this.session.getUuid()));
      this.showActionBar(player, config.messages.passwordActionBar, playerName, code);
    }
  }


  /**
   * Shows the big title/subtitle. Both can be blanked in the config to keep only the chat prompt
   * (useful when players find the pop-ups intrusive).
   */
  private void showTitle(LimboPlayer player, String titleTemplate, String subtitleTemplate, String playerName, String code) {
    if ((titleTemplate == null || titleTemplate.isBlank())
        && (subtitleTemplate == null || subtitleTemplate.isBlank())) {
      return;
    }
    Component title = this.plugin.component(this.plugin.render(titleTemplate, playerName, code, 0));
    Component subtitle = this.plugin.component(this.plugin.render(subtitleTemplate, playerName, code, 0));
    player.getProxyPlayer().showTitle(Title.title(title, subtitle,
        Times.times(Duration.ofMillis(400), Duration.ofSeconds(12), Duration.ofMillis(800))));
  }

  /** Action bar is optional too — a blank template disables it. */
  private void showActionBar(LimboPlayer player, String template, String playerName, String code) {
    if (template == null || template.isBlank()) {
      return;
    }
    player.getProxyPlayer().sendActionBar(
        this.plugin.component(this.plugin.render(template, playerName, code, 0)));
  }

  @Override
  public void onChat(String message) {
    if (message == null || this.session.isComplete()) {
      return;
    }
    String trimmed = message.trim();
    if (!trimmed.startsWith("/")) {
      this.send(this.plugin.limboHint(this.session));
      return;
    }

    String commandLine = trimmed.substring(1).trim();
    String[] args = commandLine.isEmpty() ? new String[0] : commandLine.split("\\s+");
    this.send(this.plugin.limboCommand(this.session, args));
  }

  @Override
  public void onDisconnect() {
    this.plugin.onLimboDisconnect(this.session);
  }

  private void send(Component component) {
    LimboPlayer player = this.limboPlayer;
    if (component == null || player == null) {
      return;
    }
    try {
      player.getProxyPlayer().sendMessage(component);
    } catch (Throwable throwable) {
      // The connection may already be gone; nothing useful to do here.
    }
  }
}
