package net.omegasmp.discordauth;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import org.slf4j.Logger;

/**
 * Discord bot (JDA) that runs inside the Velocity plugin.
 * <p>Commands: {@code /verify <code>} (link a Minecraft account), {@code /account} (what is linked),
 * {@code /password} (change the password), {@code /unlink} (remove the link) and
 * {@code /password-reset <player>} (administrators only).
 * <p>Passwords are never typed into the chat: everything sensitive goes through a Discord modal, and
 * all replies are ephemeral (only the caller sees them).
 */
public class DiscordBot extends ListenerAdapter {

  private static final String OPTION_CODE = "code";
  private static final String OPTION_PLAYER = "player";
  private static final String OPTION_REASON = "reason";
  private static final String OPTION_DURATION = "duration";

  private static final String MODAL_PASSWORD = "da_password";
  private static final String MODAL_UNLINK = "da_unlink";
  private static final String MODAL_RESET_PREFIX = "da_reset:";
  private static final String INPUT_CURRENT = "current";
  private static final String INPUT_NEW = "new";
  private static final String INPUT_CONFIRM = "confirm";
  private static final String INPUT_NICK = "nick";

  /** Discord's limit for a modal id — {@code da_reset:} + a 16 character nick still fits. */
  private static final int MAX_MODAL_ID = 32;

  private static class Attempts {

    private int count;
    private long blockedUntil;
  }

  private final DiscordAuthPlugin plugin;
  private final Logger logger;
  private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
  private JDA jda;

  public DiscordBot(DiscordAuthPlugin plugin) {
    this.plugin = plugin;
    this.logger = plugin.getLogger();
  }

  public void start() {
    String token = this.plugin.getConfig().discord.botToken;
    if (token == null || token.isBlank()) {
      this.logger.warn("discord.bot-token is empty — Discord verification is disabled until a token is set.");
      return;
    }
    try {
      this.jda = JDABuilder.createLight(token)
          .setActivity(Activity.playing("Minecraft | /verify"))
          .addEventListeners(this)
          .build();
      this.logger.info("Connecting to Discord…");
    } catch (Throwable throwable) {
      this.logger.error("Unable to start the Discord bot", throwable);
    }
  }

  public void stop() {
    if (this.jda != null) {
      this.jda.shutdownNow();
      this.jda = null;
    }
  }

  private static List<CommandData> commands() {
    return List.of(
        Commands.slash("verify", "Link your Minecraft account")
            .addOption(OptionType.STRING, OPTION_CODE, "The code shown in Minecraft", true),
        Commands.slash("account", "Show your linked Minecraft accounts"),
        Commands.slash("password", "Change a linked account's Minecraft password")
            .addOption(OptionType.STRING, OPTION_PLAYER, "Which linked account (needed if you have two)", false),
        Commands.slash("unlink", "Unlink your Minecraft account from Discord"),
        Commands.slash("password-reset", "Set a new password for a player (administrators)")
            .addOption(OptionType.STRING, OPTION_PLAYER, "Minecraft nickname", true),
        Commands.slash("ban", "Ban a player permanently (administrators)")
            .addOption(OptionType.STRING, OPTION_PLAYER, "Minecraft nickname", true)
            .addOption(OptionType.STRING, OPTION_REASON, "Reason", false),
        Commands.slash("tempban", "Ban a player for a period of time (administrators)")
            .addOption(OptionType.STRING, OPTION_PLAYER, "Minecraft nickname", true)
            .addOption(OptionType.STRING, OPTION_DURATION, "Duration, e.g. 30m, 12h, 7d, 2w", true)
            .addOption(OptionType.STRING, OPTION_REASON, "Reason", false),
        Commands.slash("unban", "Lift a ban (administrators)")
            .addOption(OptionType.STRING, OPTION_PLAYER, "Minecraft nickname", true),
        Commands.slash("kick", "Kick a player (administrators)")
            .addOption(OptionType.STRING, OPTION_PLAYER, "Minecraft nickname", true)
            .addOption(OptionType.STRING, OPTION_REASON, "Reason", false),
        Commands.slash("banlist", "List active bans (administrators)"));
  }

  @Override
  public void onReady(ReadyEvent event) {
    this.logger.info("Discord bot logged in as {} ({} guilds).", event.getJDA().getSelfUser().getName(), event.getJDA().getGuilds().size());
    String guildId = this.plugin.getConfig().discord.guildId;
    if (guildId == null || guildId.isBlank()) {
      this.jda.updateCommands()
          .addCommands(commands())
          .queue(commands -> this.logger.info("Registered {} global slash command(s).", commands.size()),
              error -> this.logger.error("Unable to register global slash commands", error));
      return;
    }
    Guild guild = this.jda.getGuildById(guildId);
    if (guild == null) {
      this.logger.error("Bot is not a member of guild {} — invite it and restart.", guildId);
      return;
    }
    guild.updateCommands()
        .addCommands(commands())
        .queue(commands -> this.logger.info("Registered {} slash command(s) in guild {}.", commands.size(), guild.getName()),
            error -> this.logger.error("Unable to register slash commands in guild {}", guildId, error));
  }

  // --------------------------------------------------------------- slash commands

  @Override
  public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    switch (event.getName()) {
      case "verify" -> this.verify(event);
      case "account" -> this.account(event);
      case "password" -> this.passwordModal(event);
      case "unlink" -> this.unlinkModal(event);
      case "password-reset" -> this.resetModal(event);
      case "ban" -> this.ban(event, false);
      case "tempban" -> this.ban(event, true);
      case "unban" -> this.unban(event);
      case "kick" -> this.kick(event);
      case "banlist" -> this.banList(event);
      default -> {
      }
    }
  }

  private void verify(SlashCommandInteractionEvent event) {
    if (!this.guildAllowed(event)) {
      return;
    }
    Config config = this.plugin.getConfig();
    Guild guild = event.getGuild();
    String userId = event.getUser().getId();
    long blockedSeconds = this.blockedSeconds(userId);
    if (blockedSeconds > 0) {
      event.reply(this.plugin.render(config.messages.discordRateLimited, null, null, blockedSeconds)).setEphemeral(true).queue();
      return;
    }

    OptionMapping option = event.getOption(OPTION_CODE);
    if (option == null) {
      event.reply("Usage: `/verify <code>`").setEphemeral(true).queue();
      return;
    }

    String code = option.getAsString().trim();
    Member member = event.getMember();
    event.deferReply(true).queue();

    this.plugin.getServer().getScheduler().buildTask(this.plugin, () -> {
      DiscordAuthPlugin.VerifyResult result = this.plugin.verify(code, userId, event.getUser().getName());
      if (!result.isSuccess()) {
        this.registerFailure(userId);
        event.getHook().sendMessage(result.getMessage()).queue(null, error -> { });
        return;
      }

      this.clearAttempts(userId);
      this.grantRole(guild, member);
      event.getHook().sendMessage(result.getMessage()).queue(null, error -> { });
    }).schedule();
  }

  /** {@code /account} — everything we know about the linked Minecraft account. */
  private void account(SlashCommandInteractionEvent event) {
    if (!this.guildAllowed(event)) {
      return;
    }
    String userId = event.getUser().getId();
    String tag = event.getUser().getAsMention();
    event.deferReply(true).queue();
    this.plugin.getServer().getScheduler().buildTask(this.plugin, () ->
        event.getHook().sendMessage(this.plugin.accountInfo(userId, tag)).queue(null, error -> { })).schedule();
  }

  /** {@code /password} — opens the modal that changes the caller's own password. */
  private void passwordModal(SlashCommandInteractionEvent event) {
    if (!this.guildAllowed(event)) {
      return;
    }
    Config config = this.plugin.getConfig();
    List<LinkStore.Link> links = this.plugin.linksByDiscordId(event.getUser().getId());
    if (links.isEmpty()) {
      event.reply(this.plugin.render(config.messages.discordNoAccount, null, null, 0)).setEphemeral(true).queue();
      return;
    }
    String requested = optionText(event, OPTION_PLAYER, "");
    LinkStore.Link chosen = null;
    if (requested.isEmpty()) {
      if (links.size() > 1) {
        String names = links.stream().map(link -> link.name)
            .collect(java.util.stream.Collectors.joining(", "));
        event.reply(this.plugin.render(config.messages.discordChooseAccount, names, null, 0))
            .setEphemeral(true).queue();
        return;
      }
      chosen = links.get(0);
    } else {
      for (LinkStore.Link link : links) {
        if (link.name != null && link.name.equalsIgnoreCase(requested)) {
          chosen = link;
          break;
        }
      }
    }
    if (chosen == null) {
      event.reply(this.plugin.render(config.messages.discordTargetNotYours, requested, null, 0))
          .setEphemeral(true).queue();
      return;
    }
    int min = config.password.minLength;
    int max = config.password.maxLength;
    String modalId = MODAL_PASSWORD + ":" + chosen.name;
    if (modalId.length() > MAX_MODAL_ID) {
      modalId = modalId.substring(0, MAX_MODAL_ID);
    }
    Modal modal = Modal.create(modalId, "Change password: " + chosen.name)
        .addComponents(
            ActionRow.of(TextInput.create(INPUT_CURRENT, "Current password", TextInputStyle.SHORT)
                .setPlaceholder("the password you use in game")
                .setRequired(true).setMinLength(1).setMaxLength(max).build()),
            ActionRow.of(TextInput.create(INPUT_NEW, "New password", TextInputStyle.SHORT)
                .setPlaceholder("from " + min + " to " + max + " characters")
                .setRequired(true).setMinLength(min).setMaxLength(max).build()),
            ActionRow.of(TextInput.create(INPUT_CONFIRM, "Confirm new password", TextInputStyle.SHORT)
                .setRequired(true).setMinLength(min).setMaxLength(max).build()))
        .build();
    event.replyModal(modal).queue();
  }

  /** {@code /unlink} — asks the caller to type their nick, then removes the link. */
  private void unlinkModal(SlashCommandInteractionEvent event) {
    if (!this.guildAllowed(event)) {
      return;
    }
    Config config = this.plugin.getConfig();
    String name = this.plugin.nameByDiscordId(event.getUser().getId());
    if (name == null) {
      event.reply(this.plugin.render(config.messages.discordNoAccount, null, null, 0)).setEphemeral(true).queue();
      return;
    }
    Modal modal = Modal.create(MODAL_UNLINK, "Unlink account")
        .addComponents(ActionRow.of(TextInput.create(INPUT_NICK, "Enter your Minecraft nickname", TextInputStyle.SHORT)
            .setPlaceholder(name)
            .setRequired(true).setMinLength(1).setMaxLength(16).build()))
        .build();
    event.replyModal(modal).queue();
  }

  /** {@code /password-reset <player>} — administrators set a new password for somebody else. */
  private void resetModal(SlashCommandInteractionEvent event) {
    if (!this.guildAllowed(event)) {
      return;
    }
    Config config = this.plugin.getConfig();
    if (!this.isAdmin(event.getMember())) {
      event.reply(this.plugin.render(config.messages.discordAdminOnly, null, null, 0)).setEphemeral(true).queue();
      return;
    }
    OptionMapping option = event.getOption(OPTION_PLAYER);
    if (option == null) {
      return;
    }
    String name = option.getAsString().trim();
    if (name.isEmpty() || this.plugin.uuidByName(name) == null) {
      event.reply(this.plugin.render(config.messages.discordTargetNotLinked, name, null, 0)).setEphemeral(true).queue();
      return;
    }
    int min = config.password.minLength;
    int max = config.password.maxLength;
    String modalId = MODAL_RESET_PREFIX + name;
    if (modalId.length() > MAX_MODAL_ID) {
      modalId = modalId.substring(0, MAX_MODAL_ID);
    }
    Modal modal = Modal.create(modalId, "New password for " + name)
        .addComponents(
            ActionRow.of(TextInput.create(INPUT_NEW, "New password", TextInputStyle.SHORT)
                .setPlaceholder("from " + min + " to " + max + " characters")
                .setRequired(true).setMinLength(min).setMaxLength(max).build()),
            ActionRow.of(TextInput.create(INPUT_CONFIRM, "Confirm password", TextInputStyle.SHORT)
                .setRequired(true).setMinLength(min).setMaxLength(max).build()))
        .build();
    event.replyModal(modal).queue();
  }

  // ---------------------------------------------------------------- moderation

  /** {@code /ban <player> [reason]} and {@code /tempban <player> <duration> [reason]}. */
  private void ban(SlashCommandInteractionEvent event, boolean temporary) {
    if (!this.requireAdmin(event)) {
      return;
    }
    Config config = this.plugin.getConfig();
    String name = optionText(event, OPTION_PLAYER, "");
    if (name.isEmpty()) {
      return;
    }
    if (name.equalsIgnoreCase(this.plugin.nameByDiscordId(event.getUser().getId()))) {
      event.reply(this.plugin.render(config.messages.discordBanSelf, name, null, 0)).setEphemeral(true).queue();
      return;
    }
    String reason = optionText(event, OPTION_REASON, config.messages.discordReasonNone);
    long duration = 0L;
    if (temporary) {
      duration = parseDuration(optionText(event, OPTION_DURATION, ""));
      if (duration <= 0) {
        event.reply(this.plugin.render(config.messages.discordDurationInvalid, name, null, 0)).setEphemeral(true).queue();
        return;
      }
    }

    final long finalDuration = duration;
    final String by = event.getUser().getName();
    event.deferReply(true).queue();
    this.plugin.getServer().getScheduler().buildTask(this.plugin, () -> {
      if (this.plugin.getBanStore().find(null, name) != null) {
        event.getHook().sendMessage(this.plugin.render(config.messages.discordBanAlready, name, null, 0)).queue(null, error -> { });
        return;
      }
      BanStore.Ban ban = this.plugin.ban(name, finalDuration, reason, by);
      String template = temporary ? config.messages.discordTempBanDone : config.messages.discordBanDone;
      String message = this.plugin.render(template, name, null, 0)
          .replace("%reason%", reason)
          .replace("%duration%", humanDuration(finalDuration))
          .replace("%expires%", this.plugin.banExpiresText(ban));
      event.getHook().sendMessage(message).queue(null, error -> { });
    }).schedule();
  }

  /** {@code /unban <player>}. */
  private void unban(SlashCommandInteractionEvent event) {
    if (!this.requireAdmin(event)) {
      return;
    }
    Config config = this.plugin.getConfig();
    String name = optionText(event, OPTION_PLAYER, "");
    if (name.isEmpty()) {
      return;
    }
    event.deferReply(true).queue();
    this.plugin.getServer().getScheduler().buildTask(this.plugin, () -> {
      boolean removed = this.plugin.unban(name);
      String message = removed
          ? this.plugin.render(config.messages.discordUnbanDone, name, null, 0)
          : this.plugin.render(config.messages.discordBanNotFound, name, null, 0);
      event.getHook().sendMessage(message).queue(null, error -> { });
    }).schedule();
  }

  /** {@code /kick <player> [reason]}. */
  private void kick(SlashCommandInteractionEvent event) {
    if (!this.requireAdmin(event)) {
      return;
    }
    Config config = this.plugin.getConfig();
    String name = optionText(event, OPTION_PLAYER, "");
    if (name.isEmpty()) {
      return;
    }
    String reason = optionText(event, OPTION_REASON, config.messages.discordReasonNone);
    String by = event.getUser().getName();
    event.deferReply(true).queue();
    this.plugin.getServer().getScheduler().buildTask(this.plugin, () -> {
      boolean kicked = this.plugin.kick(name, reason, by);
      String message = kicked
          ? this.plugin.render(config.messages.discordKicked, name, null, 0).replace("%reason%", reason)
          : this.plugin.render(config.messages.discordKickOffline, name, null, 0);
      event.getHook().sendMessage(message).queue(null, error -> { });
    }).schedule();
  }

  /** {@code /banlist}. */
  private void banList(SlashCommandInteractionEvent event) {
    if (!this.requireAdmin(event)) {
      return;
    }
    Config config = this.plugin.getConfig();
    event.deferReply(true).queue();
    this.plugin.getServer().getScheduler().buildTask(this.plugin, () -> {
      List<BanStore.Ban> bans = this.plugin.activeBans();
      if (bans.isEmpty()) {
        event.getHook().sendMessage(this.plugin.render(config.messages.discordBanListEmpty, null, null, 0)).queue(null, error -> { });
        return;
      }
      StringBuilder message = new StringBuilder(this.plugin
          .render(config.messages.discordBanListHeader, null, null, 0)
          .replace("%count%", String.valueOf(bans.size())));
      int shown = 0;
      for (BanStore.Ban ban : bans) {
        if (shown++ >= 25) {
          message.append("\n…");
          break;
        }
        message.append('\n').append(this.plugin
            .render(config.messages.discordBanListEntry, ban.name, null, 0)
            .replace("%reason%", ban.reason == null || ban.reason.isBlank()
                ? config.messages.discordReasonNone : ban.reason)
            .replace("%expires%", this.plugin.banExpiresText(ban)));
      }
      event.getHook().sendMessage(message.toString()).queue(null, error -> { });
    }).schedule();
  }

  /** Same rule as {@code /password-reset}: owner, admin role or an id from admin-user-ids. */
  private boolean requireAdmin(SlashCommandInteractionEvent event) {
    if (!this.guildAllowed(event)) {
      return false;
    }
    if (!this.isAdmin(event.getMember())) {
      Config config = this.plugin.getConfig();
      event.reply(this.plugin.render(config.messages.discordAdminOnly, null, null, 0)).setEphemeral(true).queue();
      return false;
    }
    return true;
  }

  private static String optionText(SlashCommandInteractionEvent event, String name, String fallback) {
    OptionMapping mapping = event.getOption(name);
    if (mapping == null) {
      return fallback;
    }
    String value = mapping.getAsString().trim();
    return value.isEmpty() ? fallback : value;
  }

  /** Parses durations like {@code 30m}, {@code 12h}, {@code 7d}, {@code 2w} or {@code 1d12h}. */
  static long parseDuration(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0L;
    }
    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("(?i)(\\d+)\\s*([smhdw])")
        .matcher(raw);
    long total = 0L;
    int parts = 0;
    while (matcher.find()) {
      long value = Long.parseLong(matcher.group(1));
      long unit = switch (matcher.group(2).toLowerCase(java.util.Locale.ROOT)) {
        case "s" -> 1_000L;
        case "m" -> 60_000L;
        case "h" -> 3_600_000L;
        case "d" -> 86_400_000L;
        case "w" -> 604_800_000L;
        default -> 0L;
      };
      total += value * unit;
      parts++;
    }
    return parts == 0 ? 0L : total;
  }

  /** {@code 90m -> "1h 30m"}, {@code 172800000 -> "2d"}. */
  static String humanDuration(long millis) {
    if (millis <= 0) {
      return "0m";
    }
    long seconds = millis / 1000L;
    long days = seconds / 86_400L;
    long hours = (seconds % 86_400L) / 3_600L;
    long minutes = (seconds % 3_600L) / 60L;
    long secs = seconds % 60L;
    StringBuilder out = new StringBuilder();
    if (days > 0) {
      out.append(days).append("d ");
    }
    if (hours > 0) {
      out.append(hours).append("h ");
    }
    if (minutes > 0) {
      out.append(minutes).append("m ");
    }
    if (out.isEmpty() || (secs > 0 && days == 0)) {
      out.append(secs).append("s");
    }
    return out.toString().trim();
  }

  // --------------------------------------------------------------------- modals

  @Override
  public void onModalInteraction(ModalInteractionEvent event) {
    String modalId = event.getModalId();
    if (modalId.equals(MODAL_PASSWORD) || modalId.startsWith(MODAL_PASSWORD + ":")) {
      String player = modalId.length() > MODAL_PASSWORD.length() + 1
          ? modalId.substring(MODAL_PASSWORD.length() + 1) : null;
      this.submitPasswordChange(event, player);
    } else if (MODAL_UNLINK.equals(modalId)) {
      this.submitUnlink(event);
    } else if (modalId.startsWith(MODAL_RESET_PREFIX)) {
      this.submitReset(event, modalId.substring(MODAL_RESET_PREFIX.length()));
    }
  }

  private void submitPasswordChange(ModalInteractionEvent event, String player) {
    Config config = this.plugin.getConfig();
    String userId = event.getUser().getId();
    long blockedSeconds = this.blockedSeconds(userId);
    if (blockedSeconds > 0) {
      event.reply(this.plugin.render(config.messages.discordRateLimited, null, null, blockedSeconds)).setEphemeral(true).queue();
      return;
    }

    String current = value(event, INPUT_CURRENT);
    String next = value(event, INPUT_NEW);
    String confirm = value(event, INPUT_CONFIRM);
    event.deferReply(true).queue();

    // BCrypt is deliberately slow — keep it off the Discord gateway thread.
    this.plugin.getServer().getScheduler().buildTask(this.plugin, () -> {
      DiscordAuthPlugin.VerifyResult result =
          this.plugin.changePasswordFromDiscord(userId, player, current, next, confirm);
      this.finishWithAttempts(userId, event, result);
    }).schedule();
  }

  private void submitUnlink(ModalInteractionEvent event) {
    String userId = event.getUser().getId();
    String typed = value(event, INPUT_NICK);
    event.deferReply(true).queue();
    this.plugin.getServer().getScheduler().buildTask(this.plugin, () -> {
      DiscordAuthPlugin.VerifyResult result = this.plugin.unlinkFromDiscord(userId, typed == null ? null : typed.trim());
      event.getHook().sendMessage(result.getMessage()).queue(null, error -> { });
    }).schedule();
  }

  private void submitReset(ModalInteractionEvent event, String playerName) {
    String next = value(event, INPUT_NEW);
    String confirm = value(event, INPUT_CONFIRM);
    event.deferReply(true).queue();
    this.plugin.getServer().getScheduler().buildTask(this.plugin, () -> {
      DiscordAuthPlugin.VerifyResult result = this.plugin.resetPasswordFromDiscord(playerName, next, confirm);
      event.getHook().sendMessage(result.getMessage()).queue(null, error -> { });
    }).schedule();
  }

  private void finishWithAttempts(String userId, ModalInteractionEvent event, DiscordAuthPlugin.VerifyResult result) {
    if (result.isSuccess()) {
      this.clearAttempts(userId);
    } else {
      this.registerFailure(userId);
    }
    event.getHook().sendMessage(result.getMessage()).queue(null, error -> { });
  }

  // -------------------------------------------------------------------- helpers

  /** Modals and commands only work on the project's Discord server. */
  private boolean guildAllowed(SlashCommandInteractionEvent event) {
    Config config = this.plugin.getConfig();
    Guild guild = event.getGuild();
    String guildId = config.discord.guildId;
    boolean guildRequired = config.discord.requireGuildMember && guildId != null && !guildId.isBlank();
    if (guild == null || (guildRequired && !guild.getId().equals(guildId))) {
      event.reply(this.plugin.render(config.messages.discordGuildOnly, null, null, 0)).setEphemeral(true).queue();
      return false;
    }
    return true;
  }

  /**
   * Who may use the admin bot commands: the server owner, anybody with {@code discord.admin-role-id}
   * and anybody listed in {@code discord.admin-user-ids}.
   */
  private boolean isAdmin(Member member) {
    if (member == null) {
      return false;
    }
    Config.Discord discord = this.plugin.getConfig().discord;
    if (discord.adminUserIds != null && discord.adminUserIds.contains(member.getId())) {
      return true;
    }
    if (member.isOwner()) {
      return true;
    }
    String roleId = discord.adminRoleId;
    if (roleId == null || roleId.isBlank()) {
      return false;
    }
    return member.getRoles().stream().anyMatch(role -> role.getId().equals(roleId));
  }

  private static String value(ModalInteractionEvent event, String id) {
    ModalMapping mapping = event.getValue(id);
    return mapping == null ? null : mapping.getAsString();
  }

  private void grantRole(Guild guild, Member member) {
    String roleId = this.plugin.getConfig().discord.verifiedRoleId;
    if (roleId == null || roleId.isBlank() || member == null) {
      return;
    }
    Role role = guild.getRoleById(roleId);
    if (role == null) {
      this.logger.warn("Verified role {} was not found in guild {}", roleId, guild.getId());
      return;
    }
    if (member.getRoles().contains(role)) {
      return;
    }
    guild.addRoleToMember(member, role).queue(
        success -> this.logger.info("Granted role {} to {}", role.getName(), member.getUser().getName()),
        error -> this.logger.warn("Unable to grant role {} to {}: {}", role.getName(), member.getUser().getName(), error.getMessage()));
  }

  private long blockedSeconds(String userId) {
    Attempts entry = this.attempts.get(userId);
    if (entry == null) {
      return 0L;
    }
    long remaining = entry.blockedUntil - System.currentTimeMillis();
    return remaining > 0 ? remaining / 1000L + 1L : 0L;
  }

  private void registerFailure(String userId) {
    Config config = this.plugin.getConfig();
    Attempts entry = this.attempts.computeIfAbsent(userId, key -> new Attempts());
    entry.count++;
    if (entry.count >= config.auth.maxAttemptsPerUser) {
      entry.count = 0;
      entry.blockedUntil = System.currentTimeMillis() + config.auth.attemptCooldownSeconds * 1000L;
    }
  }

  private void clearAttempts(String userId) {
    this.attempts.remove(userId);
  }

  /**
   * Resolves a Discord username by id. Used for links created before usernames were stored
   * alongside the id, so the tab list can show a human-readable name.
   */
  public void resolveUserName(String discordId, Consumer<String> callback) {
    JDA jda = this.jda;
    if (jda == null || discordId == null || discordId.isBlank()) {
      return;
    }
    jda.retrieveUserById(discordId).queue(
        user -> callback.accept(user.getName()),
        error -> this.logger.warn("Unable to resolve Discord user {}: {}", discordId, error.getMessage()));
  }

  /** Everything we can show about a Discord account. */
  public static class MemberInfo {

    public final String username;
    public final String nick;
    public final String global;

    public MemberInfo(String username, String nick, String global) {
      this.username = username;
      this.nick = nick;
      this.global = global;
    }
  }

  /**
   * Resolves a Discord account: the username, the nickname on the project's Discord server and the
   * global display name. The guild-member lookup is a plain REST call, so it also works with the
   * intent-less ("light") bot we use.
   */
  public void resolveMember(String discordId, Consumer<MemberInfo> callback) {
    JDA jda = this.jda;
    if (jda == null || discordId == null || discordId.isBlank()) {
      return;
    }
    String guildId = this.plugin.getConfig().discord.guildId;
    Guild guild = guildId == null || guildId.isBlank() ? null : jda.getGuildById(guildId);
    if (guild == null) {
      jda.retrieveUserById(discordId).queue(
          user -> callback.accept(new MemberInfo(user.getName(), null, user.getGlobalName())),
          error -> this.logger.warn("Unable to resolve Discord user {}: {}", discordId, error.getMessage()));
      return;
    }
    guild.retrieveMemberById(discordId).queue(
        member -> callback.accept(new MemberInfo(
            member.getUser().getName(), member.getNickname(), member.getUser().getGlobalName())),
        error -> {
          this.logger.warn("Unable to resolve Discord member {} in guild {}: {}",
              discordId, guildId, error.getMessage());
          this.resolveUserName(discordId, name -> callback.accept(new MemberInfo(name, null, null)));
        });
  }
}
