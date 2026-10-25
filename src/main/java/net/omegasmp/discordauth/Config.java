package net.omegasmp.discordauth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;

/**
 * Plain JSON configuration, generated on first start as plugins/discord-auth/config.json
 */
public class Config {

  public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  /** Logs extra details about every login/server switch (useful while debugging). */
  public boolean debug = false;

  public Discord discord = new Discord();
  public Auth auth = new Auth();
  public LimboSettings limbo = new LimboSettings();
  public Tab tab = new Tab();
  public Password password = new Password();
  public Commands commands = new Commands();
  public Bridge bridge = new Bridge();
  public Messages messages = new Messages();

  public static class Discord {

    /** Bot token from https://discord.com/developers/applications */
    public String botToken = "";
    /** Guild the bot must operate in (leave empty to allow any guild). */
    public String guildId = "";
    /** Role granted after a successful verification (leave empty to disable). */
    public String verifiedRoleId = "";
    /**
     * Role whose members may use the admin bot command {@code /password-reset}. Leave empty to allow
     * only the Discord server owner.
     */
    public String adminRoleId = "";
    /**
     * Discord user ids that may always use the admin bot command {@code /password-reset} — handy to
     * give one person access without handing out a role. Combined with {@link #adminRoleId} and the
     * server owner (any of the three is enough).
     */
    public List<String> adminUserIds = new ArrayList<>();
    /** Invite link to your Discord server, shown in Minecraft as a clickable button. */
    public String inviteUrl = "";
    /** Reject /verify outside of the configured guild. */
    public boolean requireGuildMember = true;
    /** Overall safety cap; the Java/Bedrock limits below are the real policy. */
    public int maxAccountsPerDiscord = 2;
    /** How many Java accounts one Discord account may link. */
    public int maxJavaAccountsPerDiscord = 1;
    /** How many Bedrock accounts one Discord account may link. */
    public int maxBedrockAccountsPerDiscord = 1;
  }

  public static class Auth {

    /** Server in velocity.toml that players are sent to after verification. */
    public String mainServer = "main";
    /** How long the code is valid. */
    public int codeTtlSeconds = 300;
    /** Digits in the generated code. */
    public int codeLength = 6;
    /** Wrong codes before a temporary block. */
    public int maxAttemptsPerUser = 5;
    /** Block duration in seconds after too many wrong codes. */
    public int attemptCooldownSeconds = 60;
    /**
     * Release verified players by asking their client to reconnect (Minecraft Transfer packet,
     * 1.20.5+). This bypasses the login-limbo {@code passLoginLimbo} state switch, which never
     * completes on modern clients and leaves the player stuck on the "Reconfiguring" screen.
     * The player reconnects, is already linked, and lands on {@code mainServer} in one step.
     * Bedrock/Geyser needs the Geyser TransferTool extension to map the Java transfer target to
     * the Bedrock listener; otherwise the phone just shows "Transferred to another server".
     */
    public boolean transferOnVerify = true;
    /**
     * Optional {@code host[:port]} used for the transfer above. Leave empty to reuse the exact
     * address the player connected with (e.g. {@code 192.168.0.10:25565}).
     */
    public String transferAddress = "";
    /** How long to wait before transferring, so the player can read the "verified" message. */
    public int transferDelayMillis = 1200;
    /**
     * If the client has not managed to reconnect this long after the transfer was sent (for example
     * because the router does not loop the connection back), the player is disconnected with
     * {@link Messages#transferKick} so they can simply rejoin and land on the main server.
     */
    public int transferReconnectTimeoutMillis = 15000;
    /**
     * Last-resort {@code host[:port]} when the client did not send a host at all (rare). Adjust it if
     * you copy this plugin to a proxy that listens on a different port.
     */
    public String transferFallbackAddress = "127.0.0.1:25565";

    /**
     * Let Bedrock players (the ones Floodgate prefixes with {@link #bedrockUsernamePrefixes}) skip
     * the Discord link step. They still create/login with a password unless
     * {@link #bedrockBypassPassword} is enabled. Useful for phones/consoles where opening Discord
     * and copying a code is awkward.
     */
    public boolean bedrockBypassVerification = false;
    /**
     * Username prefixes Geyser/Floodgate uses for Bedrock players. The default Floodgate prefix is
     * {@code "."}; add more prefixes if you changed Floodgate's {@code username-prefix}.
     */
    public List<String> bedrockUsernamePrefixes = new ArrayList<>(List.of("."));
    /**
     * Also skip the password for Bedrock players. This makes joining instant, but on an offline-mode
     * proxy a Java client could imitate a Bedrock nickname; leave it off unless you accept that.
     */
    public boolean bedrockBypassPassword = false;
    /**
     * Exact nicknames that skip the Discord step (for a phone Java client that cannot be detected as
     * Bedrock). Matching is case-insensitive. They still need a password.
     */
    public List<String> verificationBypassNames = new ArrayList<>();
  }

  public static class LimboSettings {

    public String dimension = "THE_END";
    public double x = 0.5;
    public double y = 64.0;
    public double z = 0.5;
    public float yaw = 0.0f;
    public float pitch = 0.0f;
  }

  /**
   * Tab-list branding: replaces the player's name in the TAB list with the linked Discord tag.
   * Uses legacy {@code &}-codes; {@code \n} starts a second line. Tokens: {@code %player%},
   * {@code %discord%}, {@code %discord_id%}.
   */
  /**
   * Bridge to the backend server(s): the linked Discord identity is pushed there over plugin
   * messaging, so a small companion plugin can expose it as PlaceholderAPI placeholders
   * (for TAB, chat plugins, scoreboards, …).
   */
  public static class Bridge {

    /** Push the identity to the backend. Turn off if you don't use the companion plugin. */
    public boolean enabled = true;
    /** Plugin-messaging channel, must match the one in the companion plugin's config. */
    public String channel = "discordauth:identity";
  }

  public static class Tab {

    /** Turn the built-in tab-list rename on/off. */
    public boolean enabled = true;
    /**
     * Which Discord name to show: {@code nickname} (nickname on your Discord server), {@code global}
     * (Discord's global display name) or {@code username} (the plain @username). Missing values fall
     * back to the next one in that order.
     */
    public String nameSource = "nickname";
    /**
     * Display name in the TAB list. Tokens: {@code %player%}, {@code %discord%} (per nameSource),
     * {@code %discord_nick%}, {@code %discord_username%}, {@code %discord_global%}, {@code %discord_id%}.
     * Use {@code \n} for a second line.
     */
    public String format = "&#39D353%player%\n&8&o%discord%";
    /** Hover text over the TAB entry (empty string disables the hover). */
    public String hover = "&8Minecraft&7: &f%player%\n&#39D353Discord&7: &f%discord%\n&8Discord ID&7: &f%discord_id%";
    /** Shown instead of the Discord name when it is not known. */
    public String unknownDiscord = "unknown";
    /**
     * Remove characters Minecraft's default font cannot render (anything outside the Unicode BMP,
     * e.g. the Egyptian hieroglyphs some fancy nicknames use). With {@code false} the nickname is
     * shown exactly as it is on Discord — such characters appear as empty boxes in game.
     */
    public boolean stripUnsupported = true;
    /**
     * Leave the tab list alone when the TAB plugin (NEZNAMY) is installed — it manages the tab list
     * itself and would override our entry anyway. Use its placeholders instead:
     * {@code %discordauth_nick%}, {@code %discordauth_username%}, {@code %discordauth_id%}.
     */
    public boolean skipWhenTabPluginInstalled = true;
    /** Register {@code %discordauth_*%} placeholders in the TAB plugin when it is installed. */
    public boolean registerTabPluginPlaceholders = true;
    /** Refresh interval for those placeholders in ms (must be a multiple of 50). */
    public int tabPluginPlaceholderRefreshMillis = 1000;
  }

  /**
   * Command names and aliases. The first entry of each list is the "primary" name used in the
   * messages/tips; all of them work in the limbo chat (and appear in the client's command list).
   */
  public static class Commands {

    /** Creating a password, e.g. /register, /reg, /r. */
    public List<String> register = new ArrayList<>(List.of("register", "reg", "r"));
    /** Logging in, e.g. /login, /l. */
    public List<String> login = new ArrayList<>(List.of("login", "l"));
    /** The admin command (proxy console + in-game), e.g. /discordauth, /dauth. */
    public List<String> admin = new ArrayList<>(List.of("discordauth", "dauth"));
    /** Lets an already logged-in player generate a Discord link code, e.g. /discordlink. */
    public List<String> link = new ArrayList<>(List.of("discordlink", "linkdiscord"));
  }

  /**
   * Password step in the limbo ({@code /register}, {@code /login}). It protects accounts whose
   * nickname is <b>not</b> verified by Mojang — i.e. everything when the proxy runs in
   * {@code online-mode = false}, or non-premium players in mixed setups.
   */
  public static class Password {

    /** Master switch for the limbo /register + /login step. */
    public boolean enabled = true;
    /**
     * Also ask Mojang-verified players for a password (proxy {@code online-mode = true}).
     * Off by default: Mojang already proved who they are.
     */
    public boolean requireWhenOnlineMode = false;
    /**
     * Remember a successful password login from the same IP address for this many minutes
     * (0 = ask on every join — the safe default for cracked servers).
     */
    public int sessionMinutes = 0;
    /**
     * How long (seconds) the one-shot ticket stays valid that lets the reconnect performed by our
     * own Transfer packet skip the password. It is bound to the address that got the transfer and is
     * consumed by the first login, so a regular "leave and join again" always asks for the password
     * again. Keep it as short as your players' connections allow (the client usually reconnects
     * within 1-2 seconds); this is the only window in which the password can be skipped at all.
     */
    public int transferTicketSeconds = 30;
    public int minLength = 8;
    public int maxLength = 64;
    /** Wrong passwords before a temporary block for this session. */
    public int maxAttempts = 5;
    /** Block duration in seconds after too many wrong passwords for this session. */
    public int cooldownSeconds = 60;
    /**
     * Wrong passwords from one IP address before that address is blocked. This survives a
     * reconnect, so closing the client cannot reset the counter.
     */
    public int ipMaxAttempts = 10;
    /** Block duration in seconds for an IP address that exceeded {@link #ipMaxAttempts}. */
    public int ipCooldownSeconds = 300;
    /** How long the player has to finish the password step, in seconds. */
    public int timeoutSeconds = 60;
  }

  /**
   * Messages use legacy {@code &}-codes. Special tokens:
   * <ul>
   *   <li>{@code %discord_link%} - clickable Discord invite button</li>
   *   <li>{@code %copy_code%} - the code as a clickable "copy to clipboard" button</li>
   *   <li>{@code %code%}, {@code %player%}, {@code %minutes%}, {@code %seconds%}, {@code %guild%} - plain text</li>
   *   <li>{@code %register_command%}, {@code %login_command%}, {@code %admin_command%} - the primary
   *       command names from the {@code commands} section, so you can rename commands and keep the
   *       texts in sync automatically</li>
   * </ul>
   */
  public static class Messages {

    public String title = "&#39D353&lACCOUNT VERIFICATION";
    public String subtitle = "&8Code: &#39D353&l%code%";
    public String chat = "&8&m                                                  \n"
        + "&#39D353&lVerify your account\n"
        + "&8Step 1: &7Join our Discord — %discord_link%\n"
        + "&8Step 2: &7Type &#39D353/verify %copy_code%\n"
        + "&8The code expires in &#39D353%minutes% &8min.\n"
        + "&8&m                                                  ";
    public String actionBar = "&8Code: &#39D353&l%code% &8— &7/verify %code% &8in Discord";
    /** Boss bar shown while the Discord step is still pending. {@code %time%} is m:ss. */
    public String bossBarDiscord = "&#39D353&lDISCORD &8— &f%time% &7left";
    /** Boss bar shown while the password step is still pending. {@code %time%} is m:ss. */
    public String bossBarPassword = "&#39D353&lPASSWORD &8— &f%time% &7left";

    /** Text of the clickable Discord button. */
    public String linkText = "&#39D353&l[ OPEN DISCORD ]";
    /** Tooltip of the Discord button. */
    public String linkHover = "&#39D353Click to open the Discord server\n&7Then type &f/verify <code>";
    /** Shown instead of the button when discord.invite-url is empty. */
    public String linkTextFallback = "&#39D353Discord server &fProject OmegaSMP";
    /** How the code itself is rendered (clickable, copies to clipboard). */
    public String codeText = "&#39D353&l%code%";
    /** Tooltip of the code. */
    public String codeHover = "&#39D353Click to copy the code";

    public String verified = "&#39D353&l✔ &7Account linked! Sending you to the server…";
    public String transferKick = "&#39D353&l✔ &7Account linked!\n&8The transfer failed — reconnect to the server and you will join the game directly.";
    public String timeoutKick = "&4&lVerification timed out\n&7Join again to get a new code.\n&8Discord: &#39D353%discord_link%";
    public String passwordTimeoutKick = "&4&lTimed out\n&7Join again and enter your password: &f/%login_command% <password>";
    public String chatIgnored = "&cOnly &f/%login_command% <password>&c and &f/%register_command% <password> <password>&c work in chat. Verify the code in Discord: &f/verify <code>&c.";

    public String discordSuccess = "✅ Account **%player%** has been linked! Sending you to the server…";
    public String discordSuccessNeedsPassword = "✅ Discord account linked to **%player%**! Return to the game and finish signing in with your password: `/%login_command% <password>` or `/%register_command% <password> <password>`.";
    public String discordAlreadyLinked = "ℹ️ Your Discord is already linked to the account **%player%**.";

    // ---- password step (limbo /register, /login) ----
    public String passwordRegister = "&8&m                                                  \n"
        + "&#39D353&lCREATE A PASSWORD\n"
        + "&8Type in chat: &f/%register_command% <password> <password>\n"
        + "&8Password length: &#39D353%min% &8to &#39D353%max% &8characters\n"
        + "&8&m                                                  ";
    public String passwordLogin = "&8&m                                                  \n"
        + "&#39D353&lENTER YOUR PASSWORD\n"
        + "&8Type in chat: &f/%login_command% <password>\n"
        + "&8&m                                                  ";
    public String passwordHint = "&8Only &f/%login_command% <password> &8and &f/%register_command% <password> <password> &8are available in the limbo.";
    public String discordLinkedInGame = "&#39D353&l✔ &7Discord linked!";
    public String passwordSuccess = "&#39D353&l✔ &7Signed in! Sending you to the server…";
    public String passwordAccepted = "&#39D353&l✔ &7Password correct!";
    public String passwordCreated = "&#39D353&l✔ &7Password created!";
    public String passwordChanged = "&#39D353&l✔ &7Password changed.";
    public String passwordWrong = "&c✘ Wrong password&8. Try again: &f/%login_command% <password>&8.";
    public String passwordBlocked = "&c⏳ Too many failed attempts&8. Try again in &f%seconds% s&8.";
    public String passwordUsage = "&cUsage: &f/%register_command% <password> <password> &cor &f/%login_command% <password>&8.";
    public String passwordTitle = "&#39D353&lAUTHORIZATION";
    public String passwordSubtitle = "&7Enter your password in chat";
    public String passwordActionBar = "&8/%login_command% <password> &7or &8/%register_command% <password> <password>";
    public String passwordMismatch = "&cPasswords do not match&8. Usage: &f/%register_command% <password> <password>&8.";
    public String passwordTooShort = "&cPassword too short&8 (minimum &f%min%&8 characters).";
    public String passwordTooLong = "&cPassword too long&8 (maximum &f%max%&8 characters).";
    public String passwordAlreadyRegistered = "&cA password already exists&8. Use &f/%login_command% <password>&8.";
    public String passwordNotRegistered = "&cCreate a password first&8: &f/%register_command% <password> <password>&8.";
    public String discordWrongCode = "❌ Invalid or expired code. A new code appears in Minecraft when you join the server.";
    public String discordUserLinkedToOther = "❌ Your Discord is already linked to the account **%player%**. Unlink it first: `/%admin_command% unlink %player%`.";
    /** Shown when this Discord already reached discord.max-accounts-per-discord. */
    public String discordAccountLimit = "❌ This Discord already has the maximum of **%max%** Minecraft accounts: **%player%**. Unlink one first with `/unlink`.";
    /** Shown when the Discord already linked its one Java account. */
    public String discordJavaAccountLimit = "❌ This Discord already has its Java account: **%player%**. Unlink it first with `/unlink`.";
    /** Shown when the Discord already linked its one Bedrock account. */
    public String discordBedrockAccountLimit = "❌ This Discord already has its Bedrock account: **%player%**. Unlink it first with `/unlink`.";
    public String discordGuildOnly = "❌ This command only works on the project's Discord server.";
    public String discordRateLimited = "⏳ Too many failed attempts. Try again in **%seconds% s**.";

    // ---- Discord bot: /account, /password, /unlink, /password-reset ----
    public String discordNoAccount = "❌ Your Discord is not linked to a Minecraft account.\nJoin the server and confirm the code: `/verify <code>`.";
    /** Header above the list returned by /account. */
    public String discordAccountListHeader = "🔗 **Linked accounts — Java %java%/%java_max%, Bedrock %bedrock%/%bedrock_max%:**";
    /** Shown when the requested account is not linked to the caller's Discord. */
    public String discordTargetNotYours = "❌ **%player%** is not linked to your Discord. Use `/account` to see your accounts.";
    /** Shown by /password when the caller has several accounts and did not choose one. */
    public String discordChooseAccount = "ℹ️ You have several linked accounts. Use `/password player:<nickname>` to choose one: **%player%**.";
    public String discordNoPassword = "❌ The account **%player%** has no password yet.\nCreate one in game: `/%register_command% <password> <password>`.";
    public String discordPasswordChanged = "✅ Password for **%player%** has been changed. The next login uses the new password.";
    public String discordPasswordWrongOld = "❌ The current password is incorrect.";
    public String discordPasswordMismatch = "❌ The new passwords do not match.";
    public String discordPasswordRule = "❌ The password must be **%min%**–**%max%** characters long.";
    public String discordAccountInfo = "👤 **Minecraft:** `%player%` (%edition%)\n"
        + "🆔 **UUID:** `%uuid%`\n"
        + "💬 **Discord:** %discord%\n"
        + "🔗 **Discord ID:** `%discord_id%`\n"
        + "👤 **Username:** %discord_username%\n"
        + "🏷️ **Server nickname:** %discord_nick%\n"
        + "🌐 **Global name:** %discord_global%\n"
        + "🔑 **Password:** %password%\n"
        + "📅 **Registered:** %registered%\n"
        + "🔗 **Linked:** %linked%\n"
        + "🕒 **Last login:** %last_login%\n"
        + "🚫 **Ban:** %ban%";
    /** Value of {@code %password%} in {@link #discordAccountInfo} when a password exists. */
    public String discordAccountPasswordYes = "✔ set";
    /** Value of {@code %password%} in {@link #discordAccountInfo} when there is no password yet. */
    public String discordAccountPasswordNo = "— not set";
    public String discordUnlinkMismatch = "❌ Nickname does not match. Type **%player%** to confirm unlinking.";
    public String discordUnlinked = "✅ Account **%player%** has been unlinked from Discord. The password remains — link Discord again to play: `/verify <code>`.";
    // ---- optional in-game linking (especially for Bedrock players who skipped /verify) ----
    public String discordLinkPlayerOnly = "❌ Only an in-game player can request a Discord link code.";
    public String discordLinkAlready = "✅ **%player%** is already linked to Discord. Use `/account` in Discord to see it.";
    public String discordLinkPrompt = "🔗 **Discord link code:** `%code%`\n"
        + "Run `/verify %code%` in Discord within %minutes% minutes.\n"
        + "This links **%player%**. One Java and one Bedrock account are allowed per Discord.";
    public String discordLinkSuccess = "✅ Linked **%player%** to your Discord.";
    public String discordLinkSuccessPlayer = "✅ **%player%** is now linked to Discord.";
    public String discordAdminOnly = "❌ This command is available to administrators only.";
    public String discordTargetNotLinked = "❌ The player **%player%** is not linked to any Discord account.";
    public String discordResetDone = "✅ Password for **%player%** has been changed by an administrator.";
    /** Shown in game to a player whose password was changed from Discord. */
    public String passwordChangedDiscord = "&#39D353&l✔ &7Password changed via Discord&8. The next login uses the new password.";

    // ---- moderation (administrators) ----
    /** Disconnect screen of a banned player. Tokens: %reason%, %expires%, %by%. */
    public String banKickMessage = "&c&lYOU ARE BANNED\n&7Reason: &f%reason%\n&7Expires: &f%expires%\n&8Banned by: &f%by%";
    /** Disconnect screen of a kicked player. Tokens: %reason%, %by%. */
    public String kickMessage = "&c&lKICKED\n&7Reason: &f%reason%\n&8By: &f%by%";
    public String discordBanDone = "✅ **%player%** has been banned permanently.\n**Reason:** %reason%";
    public String discordTempBanDone = "✅ **%player%** has been banned for **%duration%**.\n**Reason:** %reason%";
    public String discordUnbanDone = "✅ **%player%** has been unbanned.";
    public String discordKicked = "✅ **%player%** has been kicked.\n**Reason:** %reason%";
    public String discordKickOffline = "❌ **%player%** is not online.";
    public String discordBanNotFound = "❌ **%player%** is not banned.";
    public String discordBanAlready = "❌ **%player%** is already banned.";
    public String discordBanSelf = "❌ You cannot ban yourself.";
    public String discordBanListEmpty = "✅ There are no active bans.";
    public String discordBanListHeader = "🚫 **Active bans (%count%):**";
    /** One line of the ban list. Tokens: %player%, %reason%, %expires%. */
    public String discordBanListEntry = "• **%player%** — %reason% (%expires%)";
    public String discordDurationInvalid = "❌ Invalid duration. Use e.g. `30m`, `12h`, `7d`, `2w` or `1d12h`.";
    public String discordReasonNone = "No reason given";
    /** Value of %expires% for a permanent ban. */
    public String banExpiresNever = "never";
  }

  public static Config load(Path dataDirectory, Logger logger) {
    Path file = dataDirectory.resolve("config.json");
    Config config = new Config();
    try {
      Files.createDirectories(dataDirectory);
      if (Files.exists(file)) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
          Config loaded = GSON.fromJson(reader, Config.class);
          if (loaded != null) {
            config = loaded;
          }
        }
      }
      config.applyDefaults();
      try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
        GSON.toJson(config, writer);
      }
      try {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      } catch (Throwable ignored) {
        // non-posix filesystem
      }
    } catch (IOException exception) {
      logger.error("Unable to read/write config.json, using defaults", exception);
    }
    return config;
  }

  private void applyDefaults() {
    if (this.discord == null) {
      this.discord = new Discord();
    }
    if (this.auth == null) {
      this.auth = new Auth();
    }
    if (this.limbo == null) {
      this.limbo = new LimboSettings();
    }
    if (this.messages == null) {
      this.messages = new Messages();
    } else {
      // Fill any message field that is missing from an older config.json with its default.
      Messages defaults = new Messages();
      for (java.lang.reflect.Field field : Messages.class.getFields()) {
        if (field.getType() != String.class) {
          continue;
        }
        try {
          if (field.get(this.messages) == null) {
            field.set(this.messages, field.get(defaults));
          }
        } catch (ReflectiveOperationException ignored) {
          // nothing to do
        }
      }
    }
    if (this.discord.inviteUrl == null) {
      this.discord.inviteUrl = "";
    }
    if (this.discord.adminRoleId == null) {
      this.discord.adminRoleId = "";
    }
    if (this.discord.adminUserIds == null) {
      this.discord.adminUserIds = new ArrayList<>();
    }
    if (this.discord.maxAccountsPerDiscord < 1) {
      this.discord.maxAccountsPerDiscord = 2;
    }
    this.discord.maxAccountsPerDiscord = Math.min(10, this.discord.maxAccountsPerDiscord);
    if (this.discord.maxJavaAccountsPerDiscord < 0) {
      this.discord.maxJavaAccountsPerDiscord = 1;
    }
    if (this.discord.maxBedrockAccountsPerDiscord < 0) {
      this.discord.maxBedrockAccountsPerDiscord = 1;
    }
    this.discord.maxJavaAccountsPerDiscord = Math.min(10, this.discord.maxJavaAccountsPerDiscord);
    this.discord.maxBedrockAccountsPerDiscord = Math.min(10, this.discord.maxBedrockAccountsPerDiscord);
    this.auth.bedrockUsernamePrefixes = normalizeList(this.auth.bedrockUsernamePrefixes, ".");
    this.auth.verificationBypassNames = normalizeList(this.auth.verificationBypassNames, null);
    this.auth.codeTtlSeconds = Math.max(30, this.auth.codeTtlSeconds);
    this.auth.codeLength = Math.max(4, Math.min(10, this.auth.codeLength));
    if (this.auth.mainServer == null || this.auth.mainServer.isBlank()) {
      this.auth.mainServer = "main";
    }
    if (this.auth.transferFallbackAddress == null || this.auth.transferFallbackAddress.isBlank()) {
      this.auth.transferFallbackAddress = "127.0.0.1:25565";
    }
    if (this.commands == null) {
      this.commands = new Commands();
    }
    this.commands.register = normalizeNames(this.commands.register, "register");
    this.commands.login = normalizeNames(this.commands.login, "login");
    this.commands.admin = normalizeNames(this.commands.admin, "discordauth");
    if (this.tab == null) {
      this.tab = new Tab();
    }
    String source = this.tab.nameSource == null ? "" : this.tab.nameSource.trim().toLowerCase(Locale.ROOT);
    if (!source.equals("nickname") && !source.equals("global") && !source.equals("username")) {
      source = "nickname";
    }
    this.tab.nameSource = source;
    this.tab.tabPluginPlaceholderRefreshMillis = Math.max(50, this.tab.tabPluginPlaceholderRefreshMillis);
    if (this.tab.format == null || this.tab.format.isBlank()) {
      this.tab.format = "&#39D353%player%\n&8&o%discord%";
    }
    if (this.bridge == null) {
      this.bridge = new Bridge();
    }
    if (this.bridge.channel == null || this.bridge.channel.isBlank()) {
      this.bridge.channel = "discordauth:identity";
    }
  }

  /**
   * Cleans a list of command names: trims, lower-cases, drops blanks/duplicates and falls back to
   * the default name when the list ends up empty (so a broken config never locks everyone out).
   */
  /** Trims, de-duplicates and lower-cases entries; optionally falls back to one default value. */
  private static List<String> normalizeList(List<String> values, String fallback) {
    List<String> result = new ArrayList<>();
    if (values != null) {
      for (String value : values) {
        if (value == null || value.isBlank()) {
          continue;
        }
        String cleaned = value.trim().toLowerCase(Locale.ROOT);
        if (!result.contains(cleaned)) {
          result.add(cleaned);
        }
      }
    }
    if (result.isEmpty() && fallback != null) {
      result.add(fallback);
    }
    return result;
  }

  private static List<String> normalizeNames(List<String> names, String fallback) {
    List<String> result = new ArrayList<>();
    if (names != null) {
      for (String name : names) {
        if (name == null || name.isBlank()) {
          continue;
        }
        String cleaned = name.trim().toLowerCase(Locale.ROOT);
        if (!result.contains(cleaned)) {
          result.add(cleaned);
        }
      }
    }
    if (result.isEmpty()) {
      result.add(fallback);
    }
    return result;
  }
}
