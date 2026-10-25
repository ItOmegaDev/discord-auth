package net.omegasmp.discordauth;

import com.google.inject.Inject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.command.LimboCommandMeta;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import net.elytrium.limboapi.api.player.GameMode;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

/**
 * Holds unverified players in a virtual limbo (no world, no extra server) and releases them
 * to the main backend server once they confirm the shown code with the Discord bot.
 */
@Plugin(
    id = "discord-auth",
    name = "DiscordAuth",
    version = "1.7.6",
    description = "Discord-based account verification with a Velocity limbo",
    authors = {"OmegaSMP"},
    dependencies = {@Dependency(id = "limboapi")}
)
public class DiscordAuthPlugin {

  private static final SecureRandom RANDOM = new SecureRandom();
  /** Timestamps shown by the Discord bot ({@code /account}). */
  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
  private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
      .character('&')
      // &#RRGGBB is supported too, so any palette (e.g. a website's exact colours) can be used.
      .hexColors()
      .build();
  /**
   * The limbo commands only exist so the client sees them (highlighting + TAB completion); every
   * command is handled in {@link LimboHandler#onChat(String)}, so this one never runs.
   */
  private static final SimpleCommand LIMBO_NOOP = invocation -> {
  };

  private final ProxyServer server;
  private final Logger logger;
  private final Path dataDirectory;

  private final Map<String, AuthSession> sessionsByCode = new ConcurrentHashMap<>();
  private final Map<String, AuthSession> linkSessionsByCode = new ConcurrentHashMap<>();
  private final Map<UUID, AuthSession> sessionsByPlayer = new ConcurrentHashMap<>();
  /** Remembered password logins: uuid -> "ip|expiresAt" (only used when password.sessionMinutes > 0). */
  private final Map<UUID, String> passwordSessions = new ConcurrentHashMap<>();
  /**
   * One-shot tickets for the reconnect our own Transfer packet causes: uuid -> ticket. They are
   * consumed by the very next login of that player and are bound to the address that got the
   * transfer, so a plain "leave and rejoin" always asks for the password again.
   */
  private final Map<UUID, TransferTicket> transferTickets = new ConcurrentHashMap<>();
  /** Wrong-password counters per source IP; they survive a reconnect, unlike the per-session counter. */
  private final Map<String, IpAttempts> passwordAttemptsByIp = new ConcurrentHashMap<>();

  private Config config;
  private LinkStore linkStore;
  private AccountStore accountStore;
  private BanStore banStore;
  private LimboFactory limboFactory;
  private Limbo limbo;
  private DiscordBot bot;
  private ScheduledTask expireTask;
  private ScheduledTask bossBarTask;
  /** Optional TAB-plugin (NEZNAMY) hook — null/not hooked when TAB is not installed. */
  private TabIntegration tabIntegration;
  /** Players already warned about unrenderable Discord characters (once per join). */
  private final java.util.Set<UUID> tabWarned = ConcurrentHashMap.newKeySet();
  /** Plugin-messaging channel used to hand the Discord identity to the backend (null when disabled). */
  private MinecraftChannelIdentifier identityChannel;

  @Inject
  public DiscordAuthPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
    this.server = server;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    this.config = Config.load(this.dataDirectory, this.logger);
    this.linkStore = new LinkStore(this.dataDirectory.resolve("links.json"), this.logger);
    this.accountStore = new AccountStore(this.dataDirectory.resolve("accounts.json"), this.logger);
    this.banStore = new BanStore(this.dataDirectory.resolve("bans.json"), this.logger);

    Optional<PluginContainer> container = this.server.getPluginManager().getPlugin("limboapi");
    if (container.isEmpty()) {
      this.logger.error("LimboAPI was not found. DiscordAuth cannot create a limbo — verification is disabled.");
      return;
    }
    try {
      this.limboFactory = (LimboFactory) container.get().getInstance().orElseThrow();
    } catch (Throwable throwable) {
      this.logger.error("Unable to hook into LimboAPI", throwable);
      return;
    }

    Dimension dimension;
    try {
      dimension = Dimension.valueOf(this.config.limbo.dimension.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      this.logger.warn("Unknown limbo dimension '{}', falling back to THE_END", this.config.limbo.dimension);
      dimension = Dimension.THE_END;
    }

    VirtualWorld world = this.limboFactory.createVirtualWorld(
        dimension,
        this.config.limbo.x, this.config.limbo.y, this.config.limbo.z,
        this.config.limbo.yaw, this.config.limbo.pitch
    );
    this.limbo = this.limboFactory.createLimbo(world)
        .setName("DiscordAuth")
        .setGameMode(GameMode.ADVENTURE)
        .setReducedDebugInfo(true)
        .setViewDistance(4)
        .setSimulationDistance(4);

    this.registerLimboCommands();
    this.registerLinkCommand();

    List<String> adminNames = this.config.commands.admin;
    CommandMeta meta = this.server.getCommandManager().metaBuilder(adminNames.get(0))
        .aliases(adminNames.subList(1, adminNames.size()).toArray(new String[0]))
        .plugin(this)
        .build();
    this.server.getCommandManager().register(meta, new AdminCommand(this));

    this.bot = new DiscordBot(this);
    this.bot.start();

    this.expireTask = this.server.getScheduler()
        .buildTask(this, this::expireSessions)
        .repeat(5L, TimeUnit.SECONDS)
        .schedule();
    this.bossBarTask = this.server.getScheduler()
        .buildTask(this, this::updateBossBars)
        .repeat(1L, TimeUnit.SECONDS)
        .schedule();

    this.checkProxyConfiguration();
    this.logCommands();
    this.hookTabPlugin();
    this.registerBridgeChannel();

    this.logger.info(
        "DiscordAuth enabled ({} linked account(s), {} password(s)). Verification via Discord '/verify <code>'{}.",
        this.linkStore.size(),
        this.accountStore.size(),
        this.config.password.enabled
            ? ", passwords "
                + (this.config.password.requireWhenOnlineMode ? "for everyone" : "for non-premium players")
                + " via '/register' + '/login'"
            : ", passwords disabled");
  }

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event) {
    if (this.expireTask != null) {
      this.expireTask.cancel();
    }
    if (this.bossBarTask != null) {
      this.bossBarTask.cancel();
    }
    if (this.identityChannel != null) {
      this.server.getChannelRegistrar().unregister(this.identityChannel);
      this.identityChannel = null;
    }
    if (this.bot != null) {
      this.bot.stop();
    }
  }

  /**
   * Puts the limbo commands into the client's command list (LimboAPI sends them inside the
   * {@code AvailableCommands} packet), so they are highlighted as known commands and can be
   * tab-completed while the player waits in the limbo. Handling itself happens in
   * {@link LimboHandler#onChat(String)}: inside the limbo no command is dispatched anywhere.
   */
  private void registerLimboCommands() {
    Object plugin = this;
    PluginContainer container = this.server.getPluginManager().getPlugin("discord-auth").orElse(null);
    if (container != null) {
      Object instance = container.getInstance().orElse(null);
      if (instance != null) {
        plugin = instance;
      }
    }

    CommandNode<CommandSource> password = RequiredArgumentBuilder
        .<CommandSource, String>argument("password", StringArgumentType.word())
        .build();
    CommandNode<CommandSource> passwordTwice = RequiredArgumentBuilder
        .<CommandSource, String>argument("password", StringArgumentType.word())
        .then(RequiredArgumentBuilder.<CommandSource, String>argument("confirm", StringArgumentType.word()))
        .build();

    this.limbo.registerCommand(
        new LimboCommandMeta(this.config.commands.login, List.of(password), plugin), LIMBO_NOOP);
    this.limbo.registerCommand(
        new LimboCommandMeta(this.config.commands.register, List.of(passwordTwice), plugin), LIMBO_NOOP);
  }

  /**
   * Registers {@code /discordlink}: an already logged-in player can generate a code and link the
   * current account from Discord. This is the optional path for Bedrock players who skipped the
   * normal limbo verification.
   */
  private void registerLinkCommand() {
    List<String> names = this.config.commands.link;
    if (names == null || names.isEmpty()) {
      names = List.of("discordlink");
    }
    String primary = names.get(0);
    CommandMeta meta = this.server.getCommandManager().metaBuilder(primary)
        .aliases(names.subList(1, names.size()).toArray(new String[0]))
        .plugin(this)
        .build();
    this.server.getCommandManager().register(meta, (SimpleCommand) invocation -> this.startLink(invocation.source()));
  }

  private void startLink(CommandSource source) {
    if (!(source instanceof Player player)) {
      source.sendMessage(this.component(this.config.messages.discordLinkPlayerOnly));
      return;
    }
    UUID uuid = player.getUniqueId();
    String name = player.getUsername();
    if (this.linkStore.isLinked(uuid)) {
      player.sendMessage(this.component(this.render(this.config.messages.discordLinkAlready, name, null, 0)));
      return;
    }

    // A new code replaces an earlier one for the same player.
    this.linkSessionsByCode.entrySet().removeIf(entry -> entry.getValue().getUuid().equals(uuid));
    String code = this.generateCode();
    AuthSession session = new AuthSession(
        uuid,
        name,
        code,
        System.currentTimeMillis() + Math.max(30, this.config.auth.codeTtlSeconds) * 1000L,
        true,
        false
    );
    session.setPlayer(player);
    this.linkSessionsByCode.put(code, session);
    player.sendMessage(this.buildMessage(this.config.messages.discordLinkPrompt, session));
    this.logger.info("{} requested a Discord link code from the game.", name);
  }

  /** Makes sure the proxy has the two flags the transfer release depends on. */
  private void checkProxyConfiguration() {
    if (this.server.getConfiguration().isOnlineMode()) {
      this.logger.info("Proxy: online-mode=true — Mojang checks every nick{}.",
          this.config.password.enabled && this.config.password.requireWhenOnlineMode
              ? ", plus the password step" : "");
    } else if (!this.config.password.enabled) {
      this.logger.warn("Proxy: online-mode=false and password.enabled=false — ANY player can take ANY nick! "
          + "Turn passwords on in config.json.");
    } else {
      this.logger.info("Proxy: online-mode=false — nicks are not verified by Mojang, so the password is the only "
          + "gate (asked on every join, nothing is remembered by IP).");
    }
    if (!this.config.auth.transferOnVerify) {
      return;
    }
    Path toml = Paths.get("velocity.toml");
    if (!Files.isRegularFile(toml)) {
      return;
    }
    try {
      String content = Files.readString(toml, StandardCharsets.UTF_8);
      List<String> missing = new ArrayList<>();
      if (!content.matches("(?s).*accepts-transfers\\s*=\\s*true.*")) {
        missing.add("accepts-transfers = true");
      }
      if (!content.matches("(?s).*kick-existing-players\\s*=\\s*true.*")) {
        missing.add("kick-existing-players = true");
      }
      if (!missing.isEmpty()) {
        this.logger.warn("velocity.toml is missing {}. Without it the player would stay in the limbo "
            + "after authorization — add it and restart the proxy.", String.join(" and ", missing));
      }
    } catch (Exception exception) {
      this.logger.debug("Unable to check velocity.toml", exception);
    }
  }

  private void logCommands() {
    this.logger.info("Limbo commands: /{} (aliases: {}), /{} (aliases: {})",
        this.primaryCommand(this.config.commands.register, "register"),
        this.aliases(this.config.commands.register),
        this.primaryCommand(this.config.commands.login, "login"),
        this.aliases(this.config.commands.login));
  }

  /**
   * Optional integration with the TAB plugin (NEZNAMY). TAB owns the tab list, so instead of fighting
   * over the display name we register {@code %discordauth_*%} placeholders it can use in its config
   * and — by default — leave the tab list to it.
   */
  private void hookTabPlugin() {
    TabIntegration integration = new TabIntegration(this);
    if (!integration.hook()) {
      return;
    }
    this.tabIntegration = integration;
    if (this.config.tab.registerTabPluginPlaceholders) {
      integration.registerPlaceholder("%discordauth_nick%", uuid -> this.discordValue(uuid, "nickname"));
      integration.registerPlaceholder("%discordauth_username%", uuid -> this.discordValue(uuid, "username"));
      integration.registerPlaceholder("%discordauth_global%", uuid -> this.discordValue(uuid, "global"));
      integration.registerPlaceholder("%discordauth_id%", uuid -> {
        LinkStore.Link link = this.linkStore.getLink(uuid);
        return this.orEmpty(link == null ? null : link.discordId);
      });
    }
    this.logger.info("TAB plugin detected: placeholders %discordauth_nick%, %discordauth_username%, "
            + "%discordauth_global%, %discordauth_id% are available{}. Use them in TAB's config.",
        this.tabPluginOwnsTabList() ? " (built-in TAB rename skipped)" : "");
  }

  private String discordValue(UUID uuid, String source) {
    LinkStore.Link link = this.linkStore.getLink(uuid);
    if (link == null) {
      return "";
    }
    String value = this.discordDisplay(link, source);
    return value == null || value.isBlank() ? this.config.tab.unknownDiscord : value;
  }

  /** True when the TAB plugin is installed and the config says it should own the tab list. */
  private boolean tabPluginOwnsTabList() {
    return this.tabIntegration != null && this.config.tab.skipWhenTabPluginInstalled;
  }

  // ------------------------------------------------------------- backend bridge (plugin messaging)

  /**
   * Registers the plugin-messaging channel. Velocity only fires {@link PluginMessageEvent} for
   * registered channels, so without this the backend's requests would never reach us.
   */
  private void registerBridgeChannel() {
    if (!this.config.bridge.enabled) {
      return;
    }
    try {
      this.identityChannel = MinecraftChannelIdentifier.from(this.config.bridge.channel);
      this.server.getChannelRegistrar().register(this.identityChannel);
      this.logger.info("Backend bridge ready on channel '{}' (placeholders %{}_* on the backend).",
          this.config.bridge.channel, this.config.bridge.channel.split(":")[0]);
    } catch (Throwable throwable) {
      this.identityChannel = null;
      this.logger.warn("Unable to register the bridge channel '{}': {}",
          this.config.bridge.channel, throwable.toString());
    }
  }

  /**
   * Answers the companion plugin on the backend: it asks for the identity of the player who just
   * joined, we reply with the linked Discord data.
   */
  @Subscribe
  public void onPluginMessage(PluginMessageEvent event) {
    if (this.identityChannel == null || !this.identityChannel.equals(event.getIdentifier())) {
      return;
    }
    Player player = messagePlayer(event);
    if (player == null) {
      return;
    }
    event.setResult(PluginMessageEvent.ForwardResult.handled());
    String message = new String(event.getData(), StandardCharsets.UTF_8);
    if (message.startsWith("REQ")) {
      if (this.config.debug) {
        this.logger.info("[trace] {} requested the Discord identity from the backend", player.getUsername());
      }
      this.sendIdentity(player);
    }
  }

  /**
   * The player a plugin message belongs to. Velocity uses the player as the source for messages
   * coming from the client and the server connection as the source for messages coming from a
   * backend server, so both sides have to be checked.
   */
  private static Player messagePlayer(PluginMessageEvent event) {
    if (event.getSource() instanceof Player source) {
      return source;
    }
    if (event.getTarget() instanceof Player target) {
      return target;
    }
    return null;
  }

  /** Sends the (cached) linked Discord identity of a player to the server they are on. */
  public void sendIdentity(Player player) {
    if (this.identityChannel == null || player == null || !player.isActive()) {
      return;
    }
    LinkStore.Link link = this.linkStore.getLink(player.getUniqueId());
    String nick = link == null ? null : this.discordDisplay(link, "nickname");
    String username = link == null ? null : link.discordName;
    String global = link == null ? null : link.discordGlobal;
    String discordId = link == null ? null : link.discordId;
    boolean linked = discordId != null && !discordId.isBlank();
    String payload = String.join("\u0000",
        "ID",
        player.getUniqueId().toString(),
        linked ? "1" : "0",
        this.clean(nick),
        this.clean(username),
        this.clean(global),
        this.clean(discordId));
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    player.getCurrentServer().ifPresent(connection -> {
      boolean sent = connection.sendPluginMessage(this.identityChannel, bytes);
      if (this.config.debug) {
        this.logger.info("[trace] identity of {} -> '{}' (sent={}, linked={})",
            player.getUsername(), connection.getServerInfo().getName(), sent, linked);
      }
    });
  }

  /** Sends the identity right away and a few more times (the Discord lookup may still be running). */
  private void scheduleIdentityPush(Player player) {
    if (this.identityChannel == null) {
      return;
    }
    this.sendIdentity(player);
    for (long delay : new long[] {1000L, 3000L, 7000L}) {
      this.server.getScheduler()
          .buildTask(this, () -> this.sendIdentity(player))
          .delay(delay, TimeUnit.MILLISECONDS)
          .schedule();
    }
  }

  /** NUL is the field separator of the bridge protocol, so it must never appear inside a value. */
  private String clean(String value) {
    return value == null ? "" : value.replace("\u0000", "");
  }

  private String primaryCommand(List<String> names, String fallback) {
    return names == null || names.isEmpty() ? fallback : names.get(0);
  }

  private String aliases(List<String> names) {
    if (names == null || names.size() < 2) {
      return "none";
    }
    return String.join(", ", names.subList(1, names.size()));
  }

  /**
   * Routes a command typed in the limbo. The names/aliases come from {@code commands} in the config,
   * so a renamed command keeps working and every name's alias is accepted.
   */
  public Component limboCommand(AuthSession session, String[] args) {
    if (args.length == 0) {
      return this.limboHint(session);
    }
    String name = args[0].toLowerCase(Locale.ROOT);
    if (this.config.commands.login.contains(name)) {
      return this.limboLogin(session, args);
    }
    if (this.config.commands.register.contains(name)) {
      return this.limboRegister(session, args);
    }
    return this.limboHint(session);
  }

  /**
   * Reminder of everything that is still pending — shown for plain chat and for unknown commands
   * (inside the limbo nothing else is available).
   */
  public Component limboHint(AuthSession session) {
    Component result = null;
    if (session.needsDiscord()) {
      result = this.buildMessage(this.config.messages.chat, session);
    }
    if (session.needsPassword()) {
      Component prompt = this.passwordPrompt(session.getUuid());
      result = result == null ? prompt : result.append(Component.newline()).append(prompt);
    }
    return result == null ? this.buildMessage(this.config.messages.passwordHint, session) : result;
  }

  @Subscribe
  public void onLoginLimboRegister(LoginLimboRegisterEvent event) {
    Player player = event.getPlayer();
    if (this.limbo == null) {
      return;
    }
    // Bans live on the proxy, so they also stop players before the limbo / backend is reached.
    BanStore.Ban ban = this.banStore.find(player.getUniqueId(), player.getUsername());
    if (ban != null) {
      player.disconnect(this.banComponent(ban));
      this.logger.info("{} tried to join but is banned ({}).", player.getUsername(), ban.reason);
      return;
    }
    boolean bedrock = this.isBedrock(player);
    boolean bypassDiscord = this.bypassesDiscord(player);
    boolean needsDiscord = !this.linkStore.isLinked(player.getUniqueId()) && !bypassDiscord;
    boolean passwordRequired = this.needsPassword(player);
    if (bedrock && this.config.auth.bedrockBypassPassword) {
      passwordRequired = false;
    }
    final boolean needsPassword = passwordRequired;
    if (bypassDiscord && !this.linkStore.isLinked(player.getUniqueId())) {
      this.logger.info("{} skips the Discord step ({}).", player.getUsername(),
          bedrock ? "Bedrock/Floodgate player" : "verification-bypass nickname");
    }
    if (!needsDiscord && !needsPassword) {
      // Fully authorized: let the login finish so Velocity sends the player to the main server.
      return;
    }
    event.addOnJoinCallback(() -> this.holdInLimbo(player, needsDiscord, needsPassword));
  }

  /** True when the player connected through Geyser/Floodgate (detected by its username prefix). */
  private boolean isBedrock(Player player) {
    return player != null && this.isBedrockName(player.getUsername());
  }

  private boolean isBedrockName(String name) {
    if (name == null || name.isEmpty()) {
      return false;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    for (String prefix : this.config.auth.bedrockUsernamePrefixes) {
      if (prefix != null && !prefix.isEmpty() && lower.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private String editionName(String name) {
    return this.isBedrockName(name) ? "Bedrock" : "Java";
  }

  /**
   * Whether this connection may skip the Discord link. Bedrock phones/consoles are the main use
   * case; exact nicknames can also be listed for phone Java clients that cannot be detected.
   */
  private boolean bypassesDiscord(Player player) {
    if (this.isBedrock(player) && this.config.auth.bedrockBypassVerification) {
      return true;
    }
    String name = player.getUsername();
    if (name == null) {
      return false;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    for (String bypass : this.config.auth.verificationBypassNames) {
      if (bypass != null && bypass.equals(lower)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether this connection has to go through the password step. A password only protects
   * accounts whose nickname Mojang did not verify, so premium players on an online-mode proxy
   * skip it unless {@code password.requireWhenOnlineMode} is enabled.
   */
  private boolean needsPassword(Player player) {
    if (!this.config.password.enabled) {
      return false;
    }
    if (player.isOnlineMode() && !this.config.password.requireWhenOnlineMode) {
      return false;
    }
    if (this.consumeTransferTicket(player)) {
      // This login is the reconnect our own Transfer packet caused, not a new join.
      return false;
    }
    return !this.hasPasswordSession(player);
  }

  /**
   * Consumes the one-shot ticket created when we asked the client to reconnect. Anything else —
   * leaving and joining again, a second client, a reconnect minutes later — has no ticket and
   * therefore has to enter the password.
   */
  private boolean consumeTransferTicket(Player player) {
    TransferTicket ticket = this.transferTickets.remove(player.getUniqueId());
    if (ticket == null) {
      return false;
    }
    boolean accepted = ticket.matches(player, this.addressOf(player));
    if (this.config.debug) {
      this.logger.info("[trace] transfer ticket of {} {} ({} s left)",
          player.getUsername(), accepted ? "accepted — password step skipped for this reconnect" : "rejected",
          Math.max(0, (ticket.expiresAt - System.currentTimeMillis()) / 1000));
    }
    return accepted;
  }

  private boolean hasPasswordSession(Player player) {
    String entry = this.passwordSessions.get(player.getUniqueId());
    if (entry == null) {
      return false;
    }
    int split = entry.lastIndexOf('|');
    if (split <= 0) {
      this.passwordSessions.remove(player.getUniqueId());
      return false;
    }
    long expiresAt;
    try {
      expiresAt = Long.parseLong(entry.substring(split + 1));
    } catch (NumberFormatException exception) {
      this.passwordSessions.remove(player.getUniqueId());
      return false;
    }
    if (System.currentTimeMillis() > expiresAt) {
      this.passwordSessions.remove(player.getUniqueId());
      return false;
    }
    return entry.substring(0, split).equals(this.addressOf(player));
  }

  private String addressOf(Player player) {
    if (player.getRemoteAddress() == null || player.getRemoteAddress().getAddress() == null) {
      return "";
    }
    return player.getRemoteAddress().getAddress().getHostAddress();
  }

  private void holdInLimbo(Player player, boolean needsDiscord, boolean needsPassword) {
    if (player == null) {
      return;
    }
    long now = System.currentTimeMillis();
    long ttlSeconds = needsDiscord ? this.config.auth.codeTtlSeconds : this.config.password.timeoutSeconds;
    AuthSession session = new AuthSession(
        player.getUniqueId(),
        player.getUsername(),
        this.generateCode(),
        now + ttlSeconds * 1000L,
        needsDiscord,
        needsPassword
    );
    session.setPlayer(player);

    AuthSession previous = this.sessionsByPlayer.put(session.getUuid(), session);
    if (previous != null) {
      this.sessionsByCode.remove(previous.getCode(), previous);
      this.hideBossBar(previous);
    }
    if (needsDiscord) {
      this.sessionsByCode.put(session.getCode(), session);
    }

    this.limbo.spawnPlayer(player, new LimboHandler(this, session));
    this.logger.info("{} is not fully authorized — holding in the limbo (discord={}, password={}{}).",
        player.getUsername(), needsDiscord, needsPassword,
        needsDiscord ? ", code " + session.getCode() : "");
  }

  /**
   * Builds the prompt for the password step: {@code /register} for a fresh account,
   * {@code /login} for one that already has a password.
   */
  public Component passwordPrompt(UUID uuid) {
    String template = this.accountStore.isRegistered(uuid)
        ? this.config.messages.passwordLogin
        : this.config.messages.passwordRegister;
    return this.buildMessage(template, null);
  }

  private String generateCode() {
    int digits = this.config.auth.codeLength;
    String code;
    do {
      StringBuilder builder = new StringBuilder(digits);
      for (int i = 0; i < digits; i++) {
        builder.append(RANDOM.nextInt(10));
      }
      code = builder.toString();
    } while (this.sessionsByCode.containsKey(code) || this.linkSessionsByCode.containsKey(code));
    return code;
  }

  private void expireSessions() {
    for (AuthSession session : List.copyOf(this.sessionsByPlayer.values())) {
      if (!session.isExpired()) {
        continue;
      }
      this.dropSession(session);
      Player player = this.resolvePlayer(session);
      if (player != null) {
        String template = session.needsDiscord()
            ? this.config.messages.timeoutKick
            : this.config.messages.passwordTimeoutKick;
        try {
          player.disconnect(this.buildMessage(template, session));
        } catch (Throwable throwable) {
          this.logger.warn("Unable to kick {} after the authorization expired", session.getUsername(), throwable);
        }
      }
      this.logger.info("Authorization for {} expired.", session.getUsername());
    }
    // Link codes are intentionally not tied to a player session (Bedrock can link after joining).
    this.linkSessionsByCode.entrySet().removeIf(entry -> entry.getValue().isExpired());
    // Prune the remembered password logins so the map does not grow forever.
    this.passwordSessions.entrySet().removeIf(entry -> {
      int split = entry.getValue().lastIndexOf('|');
      if (split <= 0) {
        return true;
      }
      try {
        return System.currentTimeMillis() > Long.parseLong(entry.getValue().substring(split + 1));
      } catch (NumberFormatException exception) {
        return true;
      }
    });
    // Drop transfer tickets whose client never came back.
    this.transferTickets.entrySet().removeIf(entry -> System.currentTimeMillis() > entry.getValue().expiresAt);
    // Forget IP throttling state that has been idle for an hour.
    long now = System.currentTimeMillis();
    this.passwordAttemptsByIp.entrySet().removeIf(entry ->
        now - entry.getValue().lastAttemptAt > 3_600_000L);
  }

  /**
   * The player may not be registered in the proxy yet (login-limbo), so the reference captured
   * on login is preferred over the lookup by UUID.
   */
  private Player resolvePlayer(AuthSession session) {
    Player player = session.getPlayer();
    if (player == null) {
      player = this.server.getPlayer(session.getUuid()).orElse(null);
    }
    return player;
  }

  private void dropSession(AuthSession session) {
    this.hideBossBar(session);
    this.sessionsByPlayer.remove(session.getUuid(), session);
    this.sessionsByCode.remove(session.getCode(), session);
  }

  /** Removes the authorization boss bar, if one was shown for this session. */
  private void hideBossBar(AuthSession session) {
    BossBar bossBar = session.getBossBar();
    if (bossBar == null) {
      return;
    }
    session.setBossBar(null);
    Player player = this.resolvePlayer(session);
    if (player == null) {
      return;
    }
    try {
      player.hideBossBar(bossBar);
    } catch (Throwable throwable) {
      this.logger.debug("Unable to hide the authorization boss bar for {}", session.getUsername(), throwable);
    }
  }

  /**
   * Refreshes the boss bar once per second. The same bar switches from the Discord countdown to
   * the password countdown as soon as the Discord step is completed.
   */
  private void updateBossBars() {
    for (AuthSession session : List.copyOf(this.sessionsByPlayer.values())) {
      if (session.isComplete()) {
        this.hideBossBar(session);
        continue;
      }
      Player player = this.resolvePlayer(session);
      if (player == null || !player.isActive()) {
        this.hideBossBar(session);
        continue;
      }

      boolean discordStep = session.needsDiscord();
      long totalSeconds = Math.max(1L, discordStep
          ? this.config.auth.codeTtlSeconds
          : this.config.password.timeoutSeconds);
      long remaining = Math.max(0L,
          (session.getExpiresAt() - System.currentTimeMillis() + 999L) / 1000L);
      float progress = Math.max(0.0F, Math.min(1.0F, remaining / (float) totalSeconds));
      String template = discordStep
          ? this.config.messages.bossBarDiscord
          : this.config.messages.bossBarPassword;
      if (template == null || template.isBlank()) {
        this.hideBossBar(session);
        continue;
      }

      BossBar bossBar = session.getBossBar();
      if (bossBar == null) {
        bossBar = BossBar.bossBar(
            Component.empty(), progress, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        session.setBossBar(bossBar);
        if (this.config.debug) {
          this.logger.info("[trace] showing {} boss bar to {} ({}s left).",
              discordStep ? "Discord" : "password", session.getUsername(), remaining);
        }
      }
      bossBar.name(this.component(this.render(
          template, session.getUsername(), session.getCode(), remaining)));
      bossBar.progress(progress);
      try {
        // Calling this every tick also covers a re-created player connection.
        player.showBossBar(bossBar);
      } catch (Throwable throwable) {
        this.logger.debug("Unable to show the authorization boss bar to {}", session.getUsername(), throwable);
      }
    }
  }

  /** Called by the limbo session handler when the player leaves the limbo (disconnect). */
  public void onLimboDisconnect(AuthSession session) {
    this.dropSession(session);
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    AuthSession session = this.sessionsByPlayer.get(event.getPlayer().getUniqueId());
    if (session != null) {
      this.dropSession(session);
    }
  }

  // ------------------------------------------------------- login/server switch tracing

  /**
   * Makes sure every player always has a server to land on, even when the (LimboAPI) login path
   * does not select one from the {@code try} list of velocity.toml.
   */
  @Subscribe
  public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
    if (event.getInitialServer().isEmpty()) {
      Optional<RegisteredServer> main = this.server.getServer(this.config.auth.mainServer);
      if (main.isPresent()) {
        event.setInitialServer(main.get());
        this.logger.info("[trace] {} had no initial server — forcing '{}'.", event.getPlayer().getUsername(), this.config.auth.mainServer);
        return;
      }
      this.logger.warn("[trace] {} has no initial server and '{}' is not registered in velocity.toml.",
          event.getPlayer().getUsername(), this.config.auth.mainServer);
      return;
    }
    if (this.config.debug) {
      this.logger.info("[trace] {} initial server: '{}'.",
          event.getPlayer().getUsername(), event.getInitialServer().get().getServerInfo().getName());
    }
  }

  @Subscribe
  public void onPostLogin(PostLoginEvent event) {
    // Defensive second check: even if LimboAPI skipped the limbo for this player, a ban must win.
    BanStore.Ban ban = this.banStore.find(event.getPlayer().getUniqueId(), event.getPlayer().getUsername());
    if (ban != null) {
      event.getPlayer().disconnect(this.banComponent(ban));
      this.logger.info("{} tried to join but is banned ({}).", event.getPlayer().getUsername(), ban.reason);
      return;
    }
    if (this.config.debug) {
      this.logger.info("[trace] {} finished the login phase.", event.getPlayer().getUsername());
    }
  }

  @Subscribe
  public void onServerConnected(ServerConnectedEvent event) {
    if (this.config.debug) {
      this.logger.info("[trace] {} connected to '{}' (previous: '{}').",
          event.getPlayer().getUsername(),
          event.getServer().getServerInfo().getName(),
          event.getPreviousServer().map(server -> server.getServerInfo().getName()).orElse("none"));
    }
    this.scheduleTabUpdate(event.getPlayer());
    this.scheduleIdentityPush(event.getPlayer());
    // Refresh the Discord nickname in the background so it never gets stale.
    this.refreshDiscordIdentity(event.getPlayer().getUniqueId());
  }

  // ------------------------------------------------------------- tab list branding

  /**
   * Replaces the player's TAB entry with the linked Discord name. The backend sends its own
   * player-info entries right after the connection is established (and again after a server
   * switch), so the display name is applied immediately and then a few more times.
   *
   * <p>When the TAB plugin (NEZNAMY) is installed it owns the tab list, so by default we don't touch
   * it and only expose {@code %discordauth_*%} placeholders for it (see {@link TabIntegration}).
   */
  private void scheduleTabUpdate(Player player) {
    if (!this.config.tab.enabled || this.tabPluginOwnsTabList()) {
      return;
    }
    this.applyTabName(player);
    for (long delay : new long[] {1000L, 3000L, 7000L, 15000L}) {
      this.server.getScheduler()
          .buildTask(this, () -> this.applyTabName(player))
          .delay(delay, TimeUnit.MILLISECONDS)
          .schedule();
    }
  }

  private void applyTabName(Player player) {
    if (!player.isActive()) {
      return;
    }
    LinkStore.Link link = this.linkStore.getLink(player.getUniqueId());
    if (link == null) {
      return;
    }

    String resolved = this.discordDisplay(link, this.config.tab.nameSource);
    final String shown = resolved == null || resolved.isBlank()
        ? this.config.tab.unknownDiscord : resolved;
    this.warnAboutUnsupportedName(player, resolved);

    Component base = this.tabComponent(this.config.tab.format, player.getUsername(), link);
    String hover = this.config.tab.hover;
    Component display = hover == null || hover.isBlank()
        ? base
        : base.hoverEvent(HoverEvent.showText(this.tabComponent(hover, player.getUsername(), link)));
    player.getTabList().getEntry(player.getUniqueId()).ifPresentOrElse(
        entry -> {
          entry.setDisplayName(display);
          if (this.config.debug) {
            this.logger.info("[trace] TAB name for {} set to '{}' ({})",
                player.getUsername(), shown, this.config.tab.nameSource);
          }
        },
        () -> {
          if (this.config.debug) {
            this.logger.info("[trace] TAB entry for {} is not available yet", player.getUsername());
          }
        });
  }

  /**
   * Picks up the current Discord username / server nickname / global display name for a linked
   * account (one REST call) and stores it, so the tab list, hover and placeholders are up to date.
   */
  public void refreshDiscordIdentity(UUID uuid) {
    LinkStore.Link link = this.linkStore.getLink(uuid);
    if (link == null || this.bot == null || link.discordId == null || link.discordId.isBlank()) {
      return;
    }
    String discordId = link.discordId;
    this.bot.resolveMember(discordId, member -> {
      boolean changed = this.linkStore.setDiscordIdentity(uuid, member.username, member.nick, member.global);
      if (this.config.debug) {
        this.logger.info("[trace] Discord identity of {}: username='{}', nick='{}', global='{}' (changed={})",
            uuid, member.username, member.nick, member.global, changed);
      }
      Player player = this.server.getPlayer(uuid).orElse(null);
      if (player != null && player.isActive()) {
        this.applyTabName(player);
        this.sendIdentity(player);
      }
    });
  }

  private Component tabComponent(String template, String player, LinkStore.Link link) {
    String text = template == null ? "" : template
        .replace("%player%", player == null ? "" : player)
        .replace("%discord%", this.orEmpty(link == null ? null : this.discordDisplay(link, this.config.tab.nameSource)))
        .replace("%discord_nick%", this.orEmpty(link == null ? null : this.discordDisplay(link, "nickname")))
        .replace("%discord_username%", this.orEmpty(link == null ? null : link.discordName))
        .replace("%discord_global%", this.orEmpty(link == null ? null : link.discordGlobal))
        .replace("%discord_id%", this.orEmpty(link == null ? null : link.discordId));
    return SERIALIZER.deserialize(text);
  }

  /**
   * The Discord name for a source, cleaned of characters the client cannot display. Nicknames
   * written in fancy Unicode fonts (mathematical script, hieroglyphs, …) lose everything when that
   * happens, so the next source is tried — the @username is plain ASCII and always works.
   */
  private String discordDisplay(LinkStore.Link link, String source) {
    if (link == null) {
      return null;
    }
    String first = null;
    for (String candidate : link.displayNameCandidates(source)) {
      if (candidate == null || candidate.isBlank()) {
        continue;
      }
      String value = this.config.tab.stripUnsupported ? stripUnsupported(candidate) : candidate;
      if (first == null) {
        first = value;
      }
      if (!this.config.tab.stripUnsupported || isReadable(value)) {
        return value;
      }
    }
    return first;
  }

  /** True when a name still contains letters or digits, i.e. is not pure decoration. */
  private static boolean isReadable(String text) {
    return text != null && text.codePoints().anyMatch(Character::isLetterOrDigit);
  }

  /**
   * Minecraft's default font has no glyphs outside the Unicode BMP, so nicknames built from exotic
   * scripts (hieroglyphs, cuneiform, …) show up as empty boxes. Warn once per player.
   */
  private void warnAboutUnsupportedName(Player player, String name) {
    if (name == null || !hasUnsupportedChars(name) || !this.tabWarned.add(player.getUniqueId())) {
      return;
    }
    this.logger.warn("The Discord name of {} contains characters Minecraft cannot render ({}). "
            + "Set tab.nameSource to \"global\" or tab.stripUnsupported to true to avoid empty boxes.",
        player.getUsername(), unsupportedCodePoints(name));
  }

  private static boolean hasUnsupportedChars(String text) {
    return text.codePoints().anyMatch(codePoint -> codePoint > 0xFFFF);
  }

  private static String stripUnsupported(String text) {
    StringBuilder builder = new StringBuilder(text.length());
    text.codePoints().filter(codePoint -> codePoint <= 0xFFFF).forEach(builder::appendCodePoint);
    return builder.toString();
  }

  private static String unsupportedCodePoints(String text) {
    StringBuilder builder = new StringBuilder();
    text.codePoints().filter(codePoint -> codePoint > 0xFFFF)
        .forEach(codePoint -> builder.append(String.format("U+%X ", codePoint)));
    return builder.toString().trim();
  }

  private String orEmpty(String value) {
    return value == null ? "" : value;
  }

  @Subscribe
  public void onKickedFromServer(KickedFromServerEvent event) {
    if (this.config.debug) {
      this.logger.info("[trace] {} was kicked from '{}' (duringConnect={}): {}",
          event.getPlayer().getUsername(),
          event.getServer().getServerInfo().getName(),
          event.kickedDuringServerConnect(),
          event.getServerKickReason().map(SERIALIZER::serialize).orElse(""));
    }
  }

  /**
   * Called by the Discord bot. Links the Discord user to the Minecraft account that is
   * currently waiting in the limbo with the given code, then releases them to the main server.
   */
  public VerifyResult verify(String rawCode, String discordId, String discordName) {
    String wrongCode = this.render(this.config.messages.discordWrongCode, null, null, 0);
    if (rawCode == null || rawCode.isBlank()) {
      return VerifyResult.fail(wrongCode);
    }

    AuthSession session = this.sessionsByCode.get(rawCode.trim());
    boolean linkOnly = false;
    if (session == null) {
      session = this.linkSessionsByCode.get(rawCode.trim());
      linkOnly = true;
    }
    if (session == null) {
      return VerifyResult.fail(wrongCode);
    }
    if (session.isExpired()) {
      if (linkOnly) {
        this.linkSessionsByCode.remove(session.getCode(), session);
      } else {
        this.dropSession(session);
      }
      return VerifyResult.fail(wrongCode);
    }

    boolean bedrock = this.isBedrockName(session.getUsername());
    LinkStore.Link existing = this.linkStore.getLink(session.getUuid());
    if (existing == null) {
      List<LinkStore.Link> links = this.linkStore.getLinksByDiscordId(discordId);
      int max = Math.max(1, this.config.discord.maxAccountsPerDiscord);
      if (links.size() >= max) {
        String names = links.stream()
            .map(link -> link.name)
            .filter(name -> name != null && !name.isBlank())
            .collect(java.util.stream.Collectors.joining(", "));
        return VerifyResult.fail(this.render(this.config.messages.discordAccountLimit, names, session.getCode(), 0)
            .replace("%max%", String.valueOf(max)));
      }

      int typeMax = bedrock
          ? Math.max(0, this.config.discord.maxBedrockAccountsPerDiscord)
          : Math.max(0, this.config.discord.maxJavaAccountsPerDiscord);
      String sameTypeNames = links.stream()
          .filter(link -> this.isBedrockName(link.name) == bedrock)
          .map(link -> link.name)
          .filter(name -> name != null && !name.isBlank())
          .collect(java.util.stream.Collectors.joining(", "));
      long sameTypeCount = links.stream()
          .filter(link -> this.isBedrockName(link.name) == bedrock)
          .count();
      if (sameTypeCount >= typeMax) {
        String template = bedrock
            ? this.config.messages.discordBedrockAccountLimit
            : this.config.messages.discordJavaAccountLimit;
        return VerifyResult.fail(this.render(template, sameTypeNames, session.getCode(), 0)
            .replace("%max%", String.valueOf(typeMax)));
      }
    }

    boolean firstLink = existing == null;
    if (firstLink) {
      this.linkStore.link(session.getUuid(), session.getUsername(), discordId, discordName);
    } else {
      this.linkStore.setDiscordName(session.getUuid(), discordName);
    }
    // Fill in the Discord server nickname / global display name (and refresh them on every join).
    this.refreshDiscordIdentity(session.getUuid());

    this.completeDiscordStep(session);

    if (linkOnly) {
      this.linkSessionsByCode.remove(session.getCode(), session);
      Player player = this.resolvePlayer(session);
      if (player != null) {
        player.sendMessage(this.component(this.render(
            this.config.messages.discordLinkSuccessPlayer, session.getUsername(), session.getCode(), 0)));
      }
      return VerifyResult.ok(this.render(
          this.config.messages.discordLinkSuccess, session.getUsername(), session.getCode(), 0));
    }

    boolean completed = this.releaseIfComplete(session);
    String template;
    if (!completed && session.needsPassword()) {
      template = this.config.messages.discordSuccessNeedsPassword;
    } else {
      template = firstLink ? this.config.messages.discordSuccess : this.config.messages.discordAlreadyLinked;
    }
    String message = this.render(template, session.getUsername(), session.getCode(), 0);
    if (!completed && !session.needsPassword()) {
      message += "\nℹ️ Link saved. If the player has already left, the next login goes straight to the server.";
    }
    return VerifyResult.ok(message);
  }

  // --------------------------------------------------- Discord bot: account commands

  /** Minecraft UUID linked to a Discord account, or {@code null}. */
  public UUID uuidByDiscordId(String discordId) {
    String raw = discordId == null ? null : this.linkStore.getUuidByDiscordId(discordId);
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  /** Minecraft name linked to a Discord account, or {@code null}. */
  public String nameByDiscordId(String discordId) {
    return discordId == null ? null : this.linkStore.getNameByDiscordId(discordId);
  }

  /** Minecraft UUID of a linked name, or {@code null}. */
  public UUID uuidByName(String name) {
    String raw = name == null ? null : this.linkStore.getUuidByName(name.trim());
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  /**
   * Changes the password of the account linked to a Discord user. The current password is required,
   * so knowing the Discord account alone is not enough.
   */
  public VerifyResult changePasswordFromDiscord(String discordId, String playerName, String current,
      String newPassword, String confirm) {
    LinkStore.Link link = this.linkForDiscord(discordId, playerName);
    if (link == null) {
      return VerifyResult.fail(this.render(
          playerName == null || playerName.isBlank()
              ? this.config.messages.discordNoAccount
              : this.config.messages.discordTargetNotYours,
          playerName, null, 0));
    }
    UUID uuid = this.uuidByName(link.name);
    String name = link.name;
    if (uuid == null || name == null) {
      return VerifyResult.fail(this.render(this.config.messages.discordNoAccount, null, null, 0));
    }
    VerifyResult invalid = this.validateNewPassword(name, newPassword, confirm);
    if (invalid != null) {
      return invalid;
    }
    if (!this.accountStore.isRegistered(uuid)) {
      return VerifyResult.fail(this.render(this.config.messages.discordNoPassword, name, null, 0));
    }
    if (current == null || current.isEmpty() || !this.accountStore.verify(uuid, current)) {
      this.logger.info("Rejected a password change from Discord for Minecraft {} ({}) — wrong current password", name, uuid);
      return VerifyResult.fail(this.render(this.config.messages.discordPasswordWrongOld, name, null, 0));
    }
    if (!this.accountStore.changePassword(uuid, newPassword)) {
      return VerifyResult.fail(this.render(this.config.messages.discordNoPassword, name, null, 0));
    }
    this.logger.info("Discord user {} changed the password of Minecraft {} ({})", discordId, name, uuid);
    this.notifyPasswordChanged(uuid, name);
    return VerifyResult.ok(this.render(this.config.messages.discordPasswordChanged, name, null, 0));
  }

  /** Sets a new password for a linked player (admin command, current password not required). */
  public VerifyResult resetPasswordFromDiscord(String playerName, String newPassword, String confirm) {
    UUID uuid = this.uuidByName(playerName);
    if (uuid == null) {
      return VerifyResult.fail(this.render(this.config.messages.discordTargetNotLinked, playerName, null, 0));
    }
    LinkStore.Link link = this.linkStore.getLink(uuid);
    String name = link == null || link.name == null ? playerName : link.name;
    VerifyResult invalid = this.validateNewPassword(name, newPassword, confirm);
    if (invalid != null) {
      return invalid;
    }
    if (!this.accountStore.isRegistered(uuid)) {
      return VerifyResult.fail(this.render(this.config.messages.discordNoPassword, name, null, 0));
    }
    if (!this.accountStore.changePassword(uuid, newPassword)) {
      return VerifyResult.fail(this.render(this.config.messages.discordNoPassword, name, null, 0));
    }
    this.logger.info("An administrator set a new password for Minecraft {} ({})", name, uuid);
    this.notifyPasswordChanged(uuid, name);
    return VerifyResult.ok(this.render(this.config.messages.discordResetDone, name, null, 0));
  }

  /** Removes one Discord link of the caller; the Minecraft nick has to be typed to confirm. */
  public VerifyResult unlinkFromDiscord(String discordId, String typedName) {
    if (this.linkStore.getLinksByDiscordId(discordId).isEmpty()) {
      return VerifyResult.fail(this.render(this.config.messages.discordNoAccount, null, null, 0));
    }
    LinkStore.Link link = this.linkForDiscord(discordId, typedName == null ? null : typedName.trim());
    if (link == null) {
      return VerifyResult.fail(this.render(this.config.messages.discordUnlinkMismatch,
          this.nameByDiscordId(discordId), null, 0));
    }
    UUID uuid = this.uuidByName(link.name);
    String name = link.name;
    if (uuid == null) {
      return VerifyResult.fail(this.render(this.config.messages.discordNoAccount, null, null, 0));
    }
    this.linkStore.unlink(uuid);
    this.logger.info("Discord user {} unlinked Minecraft {} ({})", discordId, name, uuid);
    this.server.getPlayer(uuid).ifPresent(this::sendIdentity);
    return VerifyResult.ok(this.render(this.config.messages.discordUnlinked, name, null, 0));
  }

  /** One linked account of a Discord user, selected by nickname (first account when name is empty). */
  private LinkStore.Link linkForDiscord(String discordId, String playerName) {
    List<LinkStore.Link> links = this.linkStore.getLinksByDiscordId(discordId);
    if (links.isEmpty()) {
      return null;
    }
    if (playerName == null || playerName.isBlank()) {
      return links.size() == 1 ? links.get(0) : null;
    }
    for (LinkStore.Link link : links) {
      if (link.name != null && link.name.equalsIgnoreCase(playerName.trim())) {
        return link;
      }
    }
    return null;
  }

  /** All accounts linked to a Discord user (used by the bot's account picker). */
  public List<LinkStore.Link> linksByDiscordId(String discordId) {
    return this.linkStore.getLinksByDiscordId(discordId);
  }

  /** Text shown by {@code /account} in Discord — one section per linked Minecraft account. */
  public String accountInfo(String discordId, String discordTag) {
    List<LinkStore.Link> links = this.linkStore.getLinksByDiscordId(discordId);
    if (links.isEmpty()) {
      return this.render(this.config.messages.discordNoAccount, null, null, 0);
    }
    long javaCount = links.stream().filter(link -> !this.isBedrockName(link.name)).count();
    long bedrockCount = links.stream().filter(link -> this.isBedrockName(link.name)).count();
    String header = this.render(this.config.messages.discordAccountListHeader, null, null, 0)
        .replace("%count%", String.valueOf(links.size()))
        .replace("%max%", String.valueOf(this.config.discord.maxAccountsPerDiscord))
        .replace("%java%", String.valueOf(javaCount))
        .replace("%bedrock%", String.valueOf(bedrockCount))
        .replace("%java_max%", String.valueOf(this.config.discord.maxJavaAccountsPerDiscord))
        .replace("%bedrock_max%", String.valueOf(this.config.discord.maxBedrockAccountsPerDiscord));
    StringBuilder result = new StringBuilder(header);
    for (LinkStore.Link link : links) {
      UUID uuid = this.uuidByName(link.name);
      if (uuid == null) {
        continue;
      }
      result.append('\n').append(this.accountInfoForLink(uuid, link, discordId, discordTag));
    }
    return result.toString();
  }

  private String accountInfoForLink(UUID uuid, LinkStore.Link link, String discordId, String discordTag) {
    String name = link.name;
    boolean hasPassword = this.accountStore.isRegistered(uuid);
    long lastLogin = this.accountStore.lastLogin(uuid);
    long registeredAt = this.accountStore.registeredAt(uuid);
    BanStore.Ban ban = this.banStore.find(uuid, name);
    return this.render(this.config.messages.discordAccountInfo, name, null, 0)
        .replace("%edition%", this.editionName(name))
        .replace("%uuid%", uuid.toString())
        .replace("%discord%", discordTag == null || discordTag.isBlank() ? "—" : discordTag)
        .replace("%discord_id%", discordId == null || discordId.isBlank() ? "—" : discordId)
        .replace("%discord_username%", valueOrDash(link.discordName))
        .replace("%discord_nick%", valueOrDash(link.discordNick))
        .replace("%discord_global%", valueOrDash(link.discordGlobal))
        .replace("%password%", hasPassword
            ? this.config.messages.discordAccountPasswordYes
            : this.config.messages.discordAccountPasswordNo)
        .replace("%registered%", registeredAt <= 0 ? "—" : DATE_FORMAT.format(Instant.ofEpochMilli(registeredAt)))
        .replace("%linked%", link.linkedAt <= 0 ? "—" : DATE_FORMAT.format(Instant.ofEpochMilli(link.linkedAt)))
        .replace("%last_login%", lastLogin <= 0 ? "—" : DATE_FORMAT.format(Instant.ofEpochMilli(lastLogin)))
        .replace("%ban%", this.banSummary(ban));
  }

  private static String valueOrDash(String value) {
    return value == null || value.isBlank() ? "—" : value;
  }

  /** Plain-text summary of a ban for `/account` and admin replies. */
  public String banSummary(BanStore.Ban ban) {
    if (ban == null) {
      return "—";
    }
    String reason = ban.reason == null || ban.reason.isBlank() ? this.config.messages.discordReasonNone : ban.reason;
    return reason + " (" + this.banExpiresText(ban) + ")";
  }

  /** Human-readable expiry ("never" for a permanent ban). */
  public String banExpiresText(BanStore.Ban ban) {
    if (ban == null || ban.permanent()) {
      return this.config.messages.banExpiresNever;
    }
    return DATE_FORMAT.format(Instant.ofEpochMilli(ban.expiresAt));
  }

  /** Disconnect screen shown to a banned player. */
  public Component banComponent(BanStore.Ban ban) {
    String reason = ban.reason == null || ban.reason.isBlank()
        ? this.config.messages.discordReasonNone
        : ban.reason;
    String by = ban.bannedBy == null || ban.bannedBy.isBlank() ? "console" : ban.bannedBy;
    String text = this.render(this.config.messages.banKickMessage, ban.name, null, 0)
        .replace("%reason%", reason)
        .replace("%expires%", this.banExpiresText(ban))
        .replace("%by%", by);
    return this.component(text);
  }

  /** Disconnect screen shown to a kicked player. */
  public Component kickComponent(String reason, String by) {
    String text = this.render(this.config.messages.kickMessage, null, null, 0)
        .replace("%reason%", reason == null || reason.isBlank() ? this.config.messages.discordReasonNone : reason)
        .replace("%by%", by == null || by.isBlank() ? "console" : by);
    return this.component(text);
  }

  /**
   * Bans a player by name (permanent when {@code durationMillis <= 0}). The ban is stored on the
   * proxy, so it also applies before the limbo and cannot be bypassed by a direct backend connect.
   */
  public BanStore.Ban ban(String rawName, long durationMillis, String reason, String bannedBy) {
    String name = rawName == null ? "" : rawName.trim();
    Player online = this.server.getPlayer(name).orElse(null);
    String cleanReason = reason == null || reason.isBlank()
        ? this.config.messages.discordReasonNone
        : reason.trim();
    BanStore.Ban ban = this.banStore.add(name, online == null ? null : online.getUniqueId(),
        cleanReason, bannedBy, durationMillis);
    if (online != null) {
      online.disconnect(this.banComponent(ban));
    }
    this.logger.info("{} banned {} ({}): {}", bannedBy, name,
        ban.permanent() ? "permanent" : "until " + this.banExpiresText(ban), cleanReason);
    return ban;
  }

  public boolean unban(String name) {
    boolean removed = this.banStore.remove(name == null ? null : name.trim());
    if (removed) {
      this.logger.info("Ban for {} was lifted.", name);
    }
    return removed;
  }

  public boolean kick(String name, String reason, String kickedBy) {
    Optional<Player> target = this.server.getPlayer(name == null ? "" : name.trim());
    if (target.isEmpty()) {
      return false;
    }
    Player player = target.get();
    String cleanReason = reason == null || reason.isBlank()
        ? this.config.messages.discordReasonNone
        : reason.trim();
    player.disconnect(this.kickComponent(cleanReason, kickedBy));
    this.logger.info("{} kicked {}: {}", kickedBy, player.getUsername(), cleanReason);
    return true;
  }

  public List<BanStore.Ban> activeBans() {
    return this.banStore.active();
  }


  /** Shared checks for a new password coming from Discord. Returns {@code null} when it is fine. */
  private VerifyResult validateNewPassword(String name, String newPassword, String confirm) {
    if (newPassword == null || newPassword.isEmpty() || !newPassword.equals(confirm)) {
      return VerifyResult.fail(this.render(this.config.messages.discordPasswordMismatch, name, null, 0));
    }
    if (newPassword.length() < this.config.password.minLength || newPassword.length() > this.config.password.maxLength) {
      return VerifyResult.fail(this.render(this.config.messages.discordPasswordRule, name, null, 0));
    }
    return null;
  }

  /** Tells a player who is online right now that their password was changed from Discord. */
  private void notifyPasswordChanged(UUID uuid, String name) {
    try {
      this.server.getPlayer(uuid).ifPresent(player ->
          player.sendMessage(this.component(this.render(this.config.messages.passwordChangedDiscord, name, null, 0))));
    } catch (Throwable throwable) {
      this.logger.warn("Unable to notify {} about the password change", name, throwable);
    }
  }

  /**
   * Marks the Discord step done: the code stops working, the player gets a short extra window for
   * the remaining steps and is told what to do next while still inside the limbo.
   */
  private void completeDiscordStep(AuthSession session) {
    if (!session.markDiscordDone()) {
      return;
    }
    this.sessionsByCode.remove(session.getCode(), session);
    session.extendTo(System.currentTimeMillis() + Math.max(30, this.config.password.timeoutSeconds) * 1000L);

    Player player = this.resolvePlayer(session);
    if (player == null || !session.needsPassword()) {
      return;
    }
    try {
      player.sendMessage(this.buildMessage(this.config.messages.discordLinkedInGame, session));
      player.sendMessage(this.passwordPrompt(session.getUuid()));
    } catch (Throwable throwable) {
      this.logger.warn("Unable to send the password prompt to {}", session.getUsername(), throwable);
    }
  }

  // ------------------------------------------------------------- limbo commands

  /** Handles {@code /login <password>} typed in the limbo chat. Returns a message for the player. */
  public Component limboLogin(AuthSession session, String[] args) {
    if (session.isComplete()) {
      // Already authorized — the client is being transferred, no point in answering.
      return null;
    }
    if (!session.needsPassword()) {
      // The password step is already done; the Discord one may still be pending.
      return session.needsDiscord() ? this.discordPrompt(session) : null;
    }
    if (args.length != 2 || args[1].isEmpty()) {
      return this.buildMessage(this.config.messages.passwordUsage, session);
    }
    long blocked = session.blockedSeconds();
    if (blocked > 0) {
      return this.blockedMessage(session, blocked);
    }

    String ip = this.addressOf(session);
    long ipBlocked = this.ipBlockedSeconds(ip);
    if (ipBlocked > 0) {
      return this.blockedMessage(session, ipBlocked);
    }

    String password = args[1];
    // Hashing is deliberately slow (~300 ms with cost 12): keep it off the connection thread.
    this.server.getScheduler().buildTask(this, () -> {
      if (!this.accountStore.isRegistered(session.getUuid())) {
        this.sendTo(session, this.buildMessage(this.config.messages.passwordNotRegistered, session));
        return;
      }
      if (!this.accountStore.verify(session.getUuid(), password)) {
        session.registerPasswordFailure(
            this.config.password.maxAttempts, this.config.password.cooldownSeconds * 1000L);
        this.registerIpFailure(ip);
        this.logger.info("Wrong password for {} ({}).", session.getUsername(), session.getUuid());
        long wait = Math.max(session.blockedSeconds(), this.ipBlockedSeconds(ip));
        this.sendTo(session, wait > 0
            ? this.blockedMessage(session, wait)
            : this.buildMessage(this.config.messages.passwordWrong, session));
        return;
      }
      this.clearIpFailures(ip);
      this.finishPasswordStep(session, this.config.messages.passwordAccepted);
    }).schedule();
    return null;
  }

  /** Handles {@code /register <password> <password>} typed in the limbo chat. */
  public Component limboRegister(AuthSession session, String[] args) {
    if (session.isComplete()) {
      return null;
    }
    if (!session.needsPassword()) {
      // The password step is already done; the Discord one may still be pending.
      return session.needsDiscord() ? this.discordPrompt(session) : null;
    }
    if (args.length != 3 || args[1].isEmpty()) {
      return this.buildMessage(this.config.messages.passwordUsage, session);
    }
    long blocked = session.blockedSeconds();
    if (blocked > 0) {
      return this.blockedMessage(session, blocked);
    }
    String password = args[1];
    if (!password.equals(args[2])) {
      return this.buildMessage(this.config.messages.passwordMismatch, session);
    }
    if (password.length() < this.config.password.minLength) {
      return this.buildMessage(this.config.messages.passwordTooShort, session);
    }
    if (password.length() > this.config.password.maxLength) {
      return this.buildMessage(this.config.messages.passwordTooLong, session);
    }

    this.server.getScheduler().buildTask(this, () -> {
      if (!this.accountStore.register(session.getUuid(), session.getUsername(), password)) {
        this.sendTo(session, this.buildMessage(this.config.messages.passwordAlreadyRegistered, session));
        return;
      }
      this.finishPasswordStep(session, this.config.messages.passwordCreated);
    }).schedule();
    return null;
  }

  /**
   * Closes the password step, notifies the player and releases them when nothing else is pending.
   *
   * @param confirmationTemplate message sent right away ("password correct" / "password created").
   */
  private void finishPasswordStep(AuthSession session, String confirmationTemplate) {
    session.markPasswordDone();
    this.rememberPasswordSession(session);
    this.sendTo(session, this.buildMessage(confirmationTemplate, session));

    if (this.releaseIfComplete(session)) {
      return;
    }
    // The Discord step is still pending: remind the player what is left.
    if (session.needsDiscord()) {
      this.sendTo(session, this.discordPrompt(session));
    }
  }

  /** Sends a limbo message if the connection is still there and the session still needs it. */
  private void sendTo(AuthSession session, Component message) {
    if (message == null) {
      return;
    }
    Player player = this.resolvePlayer(session);
    if (player == null) {
      return;
    }
    try {
      player.sendMessage(message);
    } catch (Throwable throwable) {
      this.logger.debug("Unable to send a limbo message to {}", session.getUsername(), throwable);
    }
  }

  private Component blockedMessage(AuthSession session, long seconds) {
    String template = this.render(this.config.messages.passwordBlocked, session.getUsername(), session.getCode(), seconds);
    return this.buildMessage(template, session);
  }

  /** Source address of the session, or an empty string when it is already gone. */
  private String addressOf(AuthSession session) {
    Player player = this.resolvePlayer(session);
    return player == null ? "" : this.addressOf(player);
  }

  /**
   * IP-wide wrong-password throttle. The per-session counter in {@link AuthSession} is reset by a
   * reconnect; this one is not, so an attacker cannot brute-force a password by reconnecting after
   * every five attempts.
   */
  private void registerIpFailure(String ip) {
    if (ip == null || ip.isBlank() || this.config.password.ipMaxAttempts <= 0) {
      return;
    }
    long now = System.currentTimeMillis();
    this.passwordAttemptsByIp.compute(ip, (key, state) -> {
      if (state == null) {
        state = new IpAttempts();
      }
      state.failures++;
      state.lastAttemptAt = now;
      if (state.failures >= this.config.password.ipMaxAttempts) {
        state.failures = 0;
        state.blockedUntil = now + Math.max(1, this.config.password.ipCooldownSeconds) * 1000L;
        this.logger.warn("Too many wrong passwords from {} — blocking the address for {}s.",
            ip, Math.max(1, this.config.password.ipCooldownSeconds));
      }
      return state;
    });
  }

  /** @return remaining IP block time in seconds, or 0 when the address may try again. */
  private long ipBlockedSeconds(String ip) {
    if (ip == null || ip.isBlank()) {
      return 0L;
    }
    IpAttempts state = this.passwordAttemptsByIp.get(ip);
    if (state == null) {
      return 0L;
    }
    long remaining = state.blockedUntil - System.currentTimeMillis();
    return remaining > 0 ? remaining / 1000L + 1L : 0L;
  }

  private void clearIpFailures(String ip) {
    if (ip != null && !ip.isBlank()) {
      this.passwordAttemptsByIp.remove(ip);
    }
  }

  private static final class IpAttempts {

    private int failures;
    private long blockedUntil;
    private long lastAttemptAt;
  }

  /**
   * The in-chat Discord instructions (code + clickable invite) for a player whose link is still
   * missing — used on limbo spawn and whenever the password step is finished first.
   */
  public Component discordPrompt(AuthSession session) {
    return this.buildMessage(this.config.messages.chat, session);
  }

  /**
   * Remembers a successful password login by IP address — but only when
   * {@code password.sessionMinutes} is greater than 0. With the default {@code 0} nothing is
   * remembered, so the password is asked on every single join; the reconnect caused by our own
   * Transfer packet is handled by a one-shot ticket instead (see {@link #transfer(Player, InetSocketAddress)}).
   */
  private void rememberPasswordSession(AuthSession session) {
    long seconds = Math.max(0, this.config.password.sessionMinutes) * 60L;
    if (seconds <= 0) {
      this.passwordSessions.remove(session.getUuid());
      return;
    }
    Player player = session.getPlayer();
    long expiresAt = System.currentTimeMillis() + seconds * 1000L;
    this.passwordSessions.put(session.getUuid(), (player == null ? "" : this.addressOf(player)) + "|" + expiresAt);
  }

  /** A one-shot permission to skip the password for the reconnect our Transfer packet causes. */
  private static final class TransferTicket {

    private final long expiresAt;
    private final String address;

    private TransferTicket(long expiresAt, String address) {
      this.expiresAt = expiresAt;
      this.address = address == null ? "" : address;
    }

    private boolean matches(Player player, String playerAddress) {
      if (System.currentTimeMillis() > this.expiresAt) {
        return false;
      }
      // The client reconnects from the same address; only check it when we know both.
      if (this.address.isEmpty() || playerAddress == null || playerAddress.isEmpty()) {
        return true;
      }
      return this.address.equals(playerAddress);
    }
  }

  /**
   * Releases the player to the main server as soon as every required step is done. Safe to call
   * multiple times: the first successful call wins.
   *
   * @return true when this call released the player.
   */
  private boolean releaseIfComplete(AuthSession session) {
    if (!session.isComplete() || !session.markReleased()) {
      return false;
    }
    this.dropSession(session);

    Player player = this.resolvePlayer(session);
    if (player == null) {
      this.logger.warn("{} is authorized, but there is no connection to release — the state is saved.",
          session.getUsername());
      return false;
    }

    String template = session.isPasswordRequired()
        ? this.config.messages.passwordSuccess
        : this.config.messages.verified;
    try {
      player.sendMessage(this.buildMessage(template, session));
    } catch (Throwable throwable) {
      this.logger.warn("Unable to send the authorization message to {}", session.getUsername(), throwable);
    }

    boolean transfer = this.config.auth.transferOnVerify
        && player.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_20_5) >= 0;
    if (!transfer) {
      // Older clients cannot be transferred: let LimboAPI finish the login it paused.
      try {
        this.limboFactory.passLoginLimbo(player);
      } catch (Throwable throwable) {
        this.logger.error("Unable to release {} to the main server", session.getUsername(), throwable);
        return false;
      }
      this.logger.info("{} authorized — releasing to the main server through LimboAPI.", session.getUsername());
      return true;
    }

    InetSocketAddress target = this.transferTarget(player);
    long delay = Math.max(0, this.config.auth.transferDelayMillis);
    this.logger.info("{} authorized — transferring the client to {} in {} ms.", session.getUsername(), target, delay);
    if (delay == 0) {
      this.transfer(player, target);
    } else {
      this.server.getScheduler()
          .buildTask(this, () -> this.transfer(player, target))
          .delay(delay, TimeUnit.MILLISECONDS)
          .schedule();
    }
    return true;
  }

  /**
   * Asks the client to reconnect to the proxy (Minecraft Transfer packet). The player comes back
   * already linked, so the standard login path sends them straight to the main server. This avoids
   * LimboAPI's {@code passLoginLimbo} state switch, which silently never completes on 1.20.2+.
   */
  private void transfer(Player player, InetSocketAddress target) {
    if (!player.isActive()) {
      return;
    }
    // One-shot ticket for the reconnect this Transfer is about to cause: only that login may skip
    // the password. It is consumed by the first login and dropped if the client never comes back.
    long ticketMillis = Math.max(5, this.config.password.transferTicketSeconds) * 1000L;
    TransferTicket ticket =
        new TransferTicket(System.currentTimeMillis() + ticketMillis, this.addressOf(player));
    this.transferTickets.put(player.getUniqueId(), ticket);
    if (this.config.debug) {
      this.logger.info("[trace] transfer ticket issued for {} (valid {} s) — only this reconnect skips the password",
          player.getUsername(), ticketMillis / 1000);
    }
    try {
      player.transferToHost(target);
    } catch (Throwable throwable) {
      this.transferTickets.remove(player.getUniqueId(), ticket);
      this.logger.error("Unable to transfer {} to {} — falling back to LimboAPI", player.getUsername(), target, throwable);
      try {
        this.limboFactory.passLoginLimbo(player);
      } catch (Throwable inner) {
        this.logger.error("Unable to release {} either", player.getUsername(), inner);
      }
      return;
    }
    // If the client never reaches the proxy again (e.g. the router cannot loop the address back),
    // release it with a hint instead of leaving it stuck in the limbo forever.
    long timeout = Math.max(1000, this.config.auth.transferReconnectTimeoutMillis);
    this.server.getScheduler()
        .buildTask(this, () -> {
          if (player.isActive()) {
            this.logger.warn("[trace] {} did not reconnect within {} ms after the transfer — kicking so they can join again.",
                player.getUsername(), timeout);
            // No ticket may survive the kick: the next join is a normal one and must ask for the password.
            this.transferTickets.remove(player.getUniqueId());
            player.disconnect(this.buildMessage(this.config.messages.transferKick, null));
          }
        })
        .delay(timeout, TimeUnit.MILLISECONDS)
        .schedule();
  }

  /**
   * Where the client should reconnect to: an explicit override from the config, otherwise the exact
   * address the player used to reach the proxy.
   */
  private InetSocketAddress transferTarget(Player player) {
    String configured = this.config.auth.transferAddress;
    if (configured != null && !configured.isBlank()) {
      InetSocketAddress parsed = parseAddress(configured.trim());
      if (parsed != null) {
        return parsed;
      }
      this.logger.warn("auth.transferAddress '{}' is not a valid host[:port] — using the player's own address", configured);
    }
    // Prefer the address Velocity recorded for this connection: getRawVirtualHost() is only the
    // hostname (no port), so parsing it would silently fall back to 25565 and the client would be
    // sent to the wrong port after the transfer.
    Optional<InetSocketAddress> virtualHost = player.getVirtualHost();
    if (virtualHost.isPresent()) {
      return virtualHost.get();
    }
    Optional<String> raw = player.getRawVirtualHost();
    if (raw.isPresent()) {
      InetSocketAddress parsed = parseAddress(raw.get().trim());
      if (parsed != null) {
        return parsed;
      }
    }
    InetSocketAddress fallback = parseAddress(
        this.config.auth.transferFallbackAddress == null ? "" : this.config.auth.transferFallbackAddress.trim());
    return fallback == null ? new InetSocketAddress("127.0.0.1", 25565) : fallback;
  }

  private static InetSocketAddress parseAddress(String value) {
    if (value.isEmpty()) {
      return null;
    }
    String host = value;
    int port = 25565;
    int colon = value.lastIndexOf(':');
    if (colon > 0 && value.indexOf(':') == colon) {
      try {
        port = Integer.parseInt(value.substring(colon + 1).trim());
      } catch (NumberFormatException exception) {
        return null;
      }
      host = value.substring(0, colon).trim();
    }
    if (host.isEmpty() || port <= 0 || port > 65535) {
      return null;
    }
    return InetSocketAddress.createUnresolved(host, port);
  }

  // ------------------------------------------------------------------ messages

  public Component component(String message) {
    return SERIALIZER.deserialize(message == null ? "" : message);
  }

  public String render(String template, String player, String code, long seconds) {
    if (template == null) {
      return "";
    }
    return template
        .replace("%player%", player == null ? "" : player)
        .replace("%code%", code == null ? "" : code)
        .replace("%guild%", this.config.discord.guildId == null ? "" : this.config.discord.guildId)
        .replace("%seconds%", String.valueOf(seconds))
        .replace("%time%", formatTime(seconds))
        .replace("%min%", String.valueOf(this.config.password.minLength))
        .replace("%max%", String.valueOf(this.config.password.maxLength))
        .replace("%register_command%", this.primaryCommand(this.config.commands.register, "register"))
        .replace("%login_command%", this.primaryCommand(this.config.commands.login, "login"))
        .replace("%admin_command%", this.primaryCommand(this.config.commands.admin, "discordauth"))
        .replace("%minutes%", String.valueOf(Math.max(1, this.config.auth.codeTtlSeconds / 60)));
  }

  private static String formatTime(long seconds) {
    long safe = Math.max(0L, seconds);
    return String.format("%d:%02d", safe / 60L, safe % 60L);
  }

  /**
   * Builds a component from a message template, turning {@code %discord_link%} into a clickable
   * Discord invite button and {@code %copy_code%} into a clickable "copy code" button.
   */
  public Component buildMessage(String template, AuthSession session) {
    if (template == null) {
      return Component.empty();
    }
    String[] linkParts = template.split("%discord_link%", -1);
    Component result = Component.empty();
    for (int i = 0; i < linkParts.length; i++) {
      if (i > 0) {
        result = result.append(this.discordLinkComponent());
      }
      result = result.append(this.injectCodeComponent(linkParts[i], session));
    }
    return result;
  }

  private Component injectCodeComponent(String text, AuthSession session) {
    String code = session == null ? "" : session.getCode();
    String name = session == null ? null : session.getUsername();
    String[] parts = text.split("%copy_code%", -1);
    Component result = Component.empty();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        result = result.append(this.codeComponent(code));
      }
      if (!parts[i].isEmpty()) {
        result = result.append(this.component(this.render(parts[i], name, code, 0)));
      }
    }
    return result;
  }

  private Component discordLinkComponent() {
    String url = this.config.discord.inviteUrl == null ? "" : this.config.discord.inviteUrl.trim();
    if (url.isEmpty()) {
      return this.component(this.config.messages.linkTextFallback);
    }
    return this.component(this.config.messages.linkText)
        .clickEvent(ClickEvent.openUrl(url))
        .hoverEvent(HoverEvent.showText(this.component(this.config.messages.linkHover)));
  }

  private Component codeComponent(String code) {
    String template = this.config.messages.codeText == null ? "%code%" : this.config.messages.codeText;
    Component text = this.component(template.replace("%code%", code));
    if (code.isEmpty()) {
      return text;
    }
    return text
        .clickEvent(ClickEvent.copyToClipboard(code))
        .hoverEvent(HoverEvent.showText(this.component(this.config.messages.codeHover)));
  }

  // ------------------------------------------------------------------ accessors

  public Config getConfig() {
    return this.config;
  }

  public LinkStore getLinkStore() {
    return this.linkStore;
  }

  public BanStore getBanStore() {
    return this.banStore;
  }

  public AccountStore getAccountStore() {
    return this.accountStore;
  }

  public Logger getLogger() {
    return this.logger;
  }

  public ProxyServer getServer() {
    return this.server;
  }

  public List<AuthSession> pendingSessions() {
    return new ArrayList<>(this.sessionsByPlayer.values());
  }

  /** Result of a Discord verification attempt. */
  public static class VerifyResult {

    private final boolean success;
    private final String message;

    private VerifyResult(boolean success, String message) {
      this.success = success;
      this.message = message;
    }

    public static VerifyResult ok(String message) {
      return new VerifyResult(true, message);
    }

    public static VerifyResult fail(String message) {
      return new VerifyResult(false, message);
    }

    public boolean isSuccess() {
      return this.success;
    }

    public String getMessage() {
      return this.message;
    }
  }
}
