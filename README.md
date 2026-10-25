# DiscordAuth

A Velocity proxy plugin that requires players to link a Discord account and set a password before they reach the Minecraft server.

This repository is a **sanitized source copy**. It contains no bot tokens, Discord IDs, invite links, server addresses, player records, password hashes, or runtime configuration.

## Features

- Discord verification through a short code shown in a Velocity limbo.
- Password registration/login in the limbo (`/register`, `/login`) with BCrypt hashes.
- Mandatory Discord by default, for both Java and Bedrock players.
- Per-Discord limits: **1 Java + 1 Bedrock account** by default.
- Optional Transfer-packet reconnect after verification (Minecraft 1.20.5+).
- Geyser/Floodgate support; Bedrock players use the same Discord flow by default.
- Boss bar with the remaining Discord/password time.
- Built-in TAB-list integration and `%discordauth_*%` placeholders.
- Optional bridge that pushes the linked Discord identity to the backend over plugin messaging.
- Discord moderation commands: ban, tempban, unban, kick, ban list.
- In-game admin command for status/unlink/password removal/list.

## Requirements

| Component | Version / notes |
|---|---|
| Java | 25 for building (the POM targets Java 25) |
| Velocity | 4.x |
| LimboAPI | Required at runtime (`limboapi`) |
| Maven | 3.9+ |
| Geyser + Floodgate | Optional, for Bedrock |
| Geyser TransferTool | Needed if `auth.transferOnVerify=true` and Bedrock players should transfer automatically |

The plugin is a proxy plugin. Do **not** install it on the Paper/Purpur backend.

## Build

```bash
mvn -B package
```

The output is `target/discord-auth-<version>.jar`. Copy it into the proxy's `plugins/` directory and restart Velocity.

This snapshot has no automated test suite; `mvn -B package` compiles and packages the plugin.

The source has no bundled `config.json`. On first start the plugin creates one in `plugins/discord-auth/config.json` with mode `600` on POSIX systems.

## Install

1. Put `discord-auth-<version>.jar` and `limboapi-<version>.jar` in `plugins/`.
2. Start the proxy once. The plugin creates `plugins/discord-auth/config.json`.
3. Create a Discord application and bot at <https://discord.com/developers/applications>.
4. Invite the bot with the `bot` and `applications.commands` scopes.
5. Fill in `discord.botToken`, `discord.guildId`, `discord.inviteUrl`, and optionally `discord.verifiedRoleId`.
6. Restart the proxy.

A starter file is available as [`config.example.json`](config.example.json). It contains empty placeholders only. You can copy it to `plugins/discord-auth/config.json` and fill it in, or let the plugin generate the complete file and edit that.

## Discord setup

Minimum bot permissions:

- View Channels
- Send Messages
- Use Slash Commands
- Embed Links
- Manage Roles (only if `discord.verifiedRoleId` is set; the bot role must be above the verified role)

No privileged gateway intents are required by this plugin.

Recommended settings:

- Set `discord.guildId` to your server ID so slash commands register instantly in that guild. If it is empty, commands are registered globally, which can take time to propagate.
- Keep `discord.requireGuildMember=true` in production.
- Set `discord.inviteUrl` to your own Discord invite. It is rendered as a clickable button in Minecraft.
- Set `discord.adminRoleId` and/or `discord.adminUserIds` for Discord-side administrator commands. The Discord guild owner is always allowed.

Never commit the real `config.json`: it contains the bot token.

## Configuration

The plugin writes a complete `config.json` on first start. The most important sections are below.

### `discord`

| Key | Default | Purpose |
|---|---|---|
| `botToken` | empty | Discord bot token. **Secret.** |
| `guildId` | empty | Guild where slash commands are registered and where `/verify` is accepted. |
| `verifiedRoleId` | empty | Role granted after a successful link. Empty disables role assignment. |
| `adminRoleId` | empty | Role allowed to use Discord admin commands. |
| `adminUserIds` | `[]` | Discord user IDs allowed to use admin commands. |
| `inviteUrl` | empty | Clickable Discord invite shown in the limbo. |
| `requireGuildMember` | `true` | Reject `/verify` outside the configured guild. |
| `maxAccountsPerDiscord` | `2` | Overall safety cap. |
| `maxJavaAccountsPerDiscord` | `1` | Java accounts per Discord. |
| `maxBedrockAccountsPerDiscord` | `1` | Bedrock accounts per Discord. |

### `auth`

| Key | Default | Purpose |
|---|---|---|
| `mainServer` | `main` | Velocity server name to send players to after verification. |
| `codeTtlSeconds` | `300` | Discord code lifetime. |
| `codeLength` | `6` | Number of digits in the code. |
| `maxAttemptsPerUser` | `5` | Wrong `/verify` attempts before a cooldown. |
| `attemptCooldownSeconds` | `60` | Cooldown for wrong `/verify` attempts. |
| `transferOnVerify` | `true` | Use the Minecraft Transfer packet after verification. |
| `transferAddress` | empty | Public `host[:port]` used for Transfer. Empty reuses the address the player connected with. |
| `transferDelayMillis` | `1200` | Delay before sending Transfer, so the player can read the message. |
| `transferReconnectTimeoutMillis` | `15000` | Kick the player if the Transfer reconnect never arrives. |
| `transferFallbackAddress` | `127.0.0.1:25565` | Fallback address when the client sent no host. Set this if your proxy listens elsewhere. |
| `bedrockBypassVerification` | `false` | Skip Discord for Bedrock players. Keep `false` for mandatory Discord. |
| `bedrockUsernamePrefixes` | `["."]` | Floodgate username prefixes used to detect Bedrock players. |
| `bedrockBypassPassword` | `false` | Skip the password for Bedrock. Do **not** enable on an offline-mode proxy: a Java client can imitate the prefix. |
| `verificationBypassNames` | `[]` | Exact nicknames that skip Discord. They still need a password. |

For Transfer, `velocity.toml` must contain:

```toml
accepts-transfers = true
kick-existing-players = true
```

For Bedrock, install **Geyser TransferTool** and map the Java transfer target to the Bedrock listener. Without it, phones show “Transferred to another server”.

### `password`

| Key | Default | Purpose |
|---|---|---|
| `enabled` | `true` | Master switch for `/register` and `/login`. |
| `requireWhenOnlineMode` | `false` | Also ask Mojang-verified players for a password. |
| `sessionMinutes` | `0` | `0` asks for the password on **every** join. |
| `transferTicketSeconds` | `30` | One-shot ticket for the reconnect caused by the plugin's own Transfer. |
| `minLength` | `8` | Minimum new-password length. |
| `maxLength` | `64` | Maximum password length. |
| `maxAttempts` | `5` | Wrong passwords before a per-session cooldown. |
| `cooldownSeconds` | `60` | Per-session cooldown. |
| `ipMaxAttempts` | `10` | Wrong passwords from one IP before an IP block. |
| `ipCooldownSeconds` | `300` | IP block duration. This survives a reconnect. |
| `timeoutSeconds` | `60` | Time allowed to complete the password step. |

The IP throttle is intentionally separate from the per-session counter. A player cannot reset it by reconnecting. If many players share one NAT address, raise `ipMaxAttempts` or lower the impact by keeping passwords long.

### `limbo`

Spawn position and dimension for the limbo. The defaults are:

```json
{
  "dimension": "THE_END",
  "x": 0.5,
  "y": 64.0,
  "z": 0.5,
  "yaw": 0.0,
  "pitch": 0.0
}
```

### `messages`

All messages use legacy `&` color codes and MiniMessage-style hex colors such as `&#39D353`.

Useful tokens:

- `%code%` — the verification code
- `%copy_code%` — clickable “copy code” button
- `%discord_link%` — clickable Discord invite button
- `%player%`, `%minutes%`, `%seconds%`, `%guild%`
- `%register_command%`, `%login_command%`, `%admin_command%`

Boss bars:

- `messages.bossBarDiscord` — shown during the Discord step; `%time%` is `m:ss`
- `messages.bossBarPassword` — shown during the password step
- an empty template hides that boss bar

### `commands`

The first name in each list is the primary name used in messages; all aliases work in the limbo.

```json
{
  "register": ["register", "reg", "r"],
  "login": ["login", "l"],
  "admin": ["discordauth", "dauth"],
  "link": ["discordlink", "linkdiscord"]
}
```

### `tab` and `bridge`

`tab.enabled` replaces the TAB-list name with the linked Discord name. If the TAB plugin is installed, the built-in rename is skipped and `%discordauth_nick%`, `%discordauth_username%`, and `%discordauth_id%` are registered instead.

`bridge.enabled` pushes the linked identity to the backend over the `discordauth:identity` plugin-messaging channel. A companion backend plugin is required to expose those values through PlaceholderAPI.

## Player flow

1. The player joins Velocity and is held in the limbo.
2. The limbo shows a code and a clickable Discord invite.
3. The player runs `/verify <code>` in Discord.
4. The player creates or enters a password in Minecraft chat (`/register` or `/login`).
5. When both steps are complete, the player is transferred to `auth.mainServer`.

The two steps can be completed in either order. The Discord code and the password step have separate timeouts.

## Commands

### Discord

| Command | Who | Purpose |
|---|---|---|
| `/verify <code>` | everyone | Link a Minecraft account. |
| `/account` | linked users | Show linked accounts and their details. |
| `/password [player]` | linked users | Change a password through a Discord modal. |
| `/unlink` | linked users | Unlink an account after typing its nickname. |
| `/password-reset <player>` | admins | Set a new password for another player. |
| `/ban <player> [reason]` | admins | Permanent ban. |
| `/tempban <player> <duration> [reason]` | admins | Temporary ban (for example `30m`, `12h`, `7d`). |
| `/unban <player>` | admins | Lift a ban. |
| `/kick <player> [reason]` | admins | Kick an online player. |
| `/banlist` | admins | List active bans. |

### In-game / proxy

| Command | Who | Purpose |
|---|---|---|
| `/register <password> <password>` | new players | Create a password. |
| `/login <password>` | returning players | Sign in. |
| `/discordlink` | linked-in-game players | Generate a code to link the current account from Discord. |
| `/discordauth status <player>` | `discordauth.admin` | Show the link status. |
| `/discordauth unlink <player>` | `discordauth.admin` | Remove the Discord link. |
| `/discordauth password <player>` | `discordauth.admin` | Show whether a password exists. |
| `/discordauth unregister <player>` | `discordauth.admin` | Remove the stored password. |
| `/discordauth list` | `discordauth.admin` | List linked accounts and pending sessions. |

## Security notes

- **Never commit runtime files.** `config.json` contains the bot token; `accounts.json` contains BCrypt hashes; `links.json` and `bans.json` contain player data. `.gitignore` excludes them.
- Keep runtime files at mode `600` and the plugin directory readable only by the proxy user.
- Rotate the bot token immediately if it is ever exposed.
- On an offline-mode proxy (`online-mode=false`), the nickname is not verified by Mojang. The password is the only account barrier, so keep `password.enabled=true` and `sessionMinutes=0`.
- Do not enable `bedrockBypassPassword` on an offline-mode proxy. A Java client can imitate a Bedrock nickname prefix.
- Passwords are stored as BCrypt hashes (cost 12) and are never logged by the plugin.
- The limbo intercepts `/register` and `/login`; do not add chat plugins that log raw chat in the limbo.
- The IP throttle is per source address. Shared NATs can hit it; tune it for your player base.
- Set `debug=false` in production. Debug mode can include extra login details in logs.
- Use a dedicated Discord guild and a bot role that is only as powerful as necessary. The bot needs Manage Roles only if `verifiedRoleId` is used.
- If the proxy is public, put the backend behind Velocity forwarding and do not expose the backend port directly.

## Runtime files

Created in `plugins/discord-auth/`:

| File | Contents | Recommended mode |
|---|---|---|
| `config.json` | Bot token and settings | `600` |
| `accounts.json` | BCrypt password hashes | `600` |
| `links.json` | Minecraft ↔ Discord links | `600` |
| `bans.json` | Active bans | `600` |

The plugin writes `config.json` with mode `600`. On non-POSIX filesystems it cannot enforce permissions; secure the directory yourself.

## Publishing checklist

Before pushing this repository:

```bash
gitleaks dir . --redact --no-banner
```

Then confirm:

- [ ] `config.json`, `accounts.json`, `links.json`, and `bans.json` are absent.
- [ ] No real bot token, webhook URL, Discord ID, server address, or player data is present.
- [ ] `config.example.json` contains placeholders only.
- [ ] `target/` is not committed.
- [ ] A license file is added if you want to grant reuse rights.

## License

No license is included in this snapshot. Add a `LICENSE` file of your choice before distributing the project publicly.
