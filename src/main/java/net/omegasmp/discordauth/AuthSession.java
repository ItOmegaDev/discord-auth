package net.omegasmp.discordauth;

import com.velocitypowered.api.proxy.Player;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.bossbar.BossBar;

/**
 * One pending authorization for a player that is currently held in the limbo.
 * <p>
 * A session can require two independent steps, in any order:
 * </p>
 * <ul>
 *   <li><b>Discord link</b> — the player confirms the code shown in the limbo with
 *       <code>/verify &lt;code&gt;</code> on the Discord server;</li>
 *   <li><b>Password</b> — the player runs <code>/register</code> (first time) or <code>/login</code>
 *       in the limbo chat.</li>
 * </ul>
 * The player is released to the main server only when every required step is done.
 * <p>
 * While the player is inside the login-limbo they are <b>not</b> registered in
 * {@code ProxyServer#getPlayer(UUID)} yet, so the {@link Player} reference captured from the
 * login event must be kept here and used to release the player later.
 * </p>
 */
public class AuthSession {

  private final UUID uuid;
  private final String username;
  private final String code;
  private volatile long expiresAt;
  private final boolean discordRequired;
  private final boolean passwordRequired;
  private final AtomicBoolean discordDone;
  private final AtomicBoolean passwordDone;
  private final AtomicBoolean released = new AtomicBoolean(false);
  private volatile Player player;
  private volatile BossBar bossBar;
  private int failedPasswordAttempts;
  private long passwordBlockedUntil;

  public AuthSession(UUID uuid, String username, String code, long expiresAt,
      boolean needsDiscord, boolean needsPassword) {
    this.uuid = uuid;
    this.username = username;
    this.code = code;
    this.expiresAt = expiresAt;
    this.discordRequired = needsDiscord;
    this.passwordRequired = needsPassword;
    this.discordDone = new AtomicBoolean(!needsDiscord);
    this.passwordDone = new AtomicBoolean(!needsPassword);
  }

  public UUID getUuid() {
    return this.uuid;
  }

  public String getUsername() {
    return this.username;
  }

  public String getCode() {
    return this.code;
  }

  public long getExpiresAt() {
    return this.expiresAt;
  }

  public boolean isExpired() {
    return System.currentTimeMillis() > this.expiresAt;
  }

  /** Extends the deadline (used when one step is done and the other one is still pending). */
  public void extendTo(long newExpiresAt) {
    if (newExpiresAt > this.expiresAt) {
      this.expiresAt = newExpiresAt;
    }
  }

  public boolean needsDiscord() {
    return !this.discordDone.get();
  }

  public boolean needsPassword() {
    return !this.passwordDone.get();
  }

  /** Whether a Discord link was required for this session (regardless of whether it is done). */
  public boolean isDiscordRequired() {
    return this.discordRequired;
  }

  /** Whether a password was required for this session (regardless of whether it is done). */
  public boolean isPasswordRequired() {
    return this.passwordRequired;
  }

  /** @return true if this call actually completed the Discord step. */
  public boolean markDiscordDone() {
    return this.discordDone.compareAndSet(false, true);
  }

  /** @return true if this call actually completed the password step. */
  public boolean markPasswordDone() {
    return this.passwordDone.compareAndSet(false, true);
  }

  public boolean isComplete() {
    return this.discordDone.get() && this.passwordDone.get();
  }

  /** @return true if this call actually released the session (guards double releases). */
  public boolean markReleased() {
    return this.released.compareAndSet(false, true);
  }

  public int getFailedPasswordAttempts() {
    return this.failedPasswordAttempts;
  }

  public void registerPasswordFailure(int maxAttempts, long cooldownMillis) {
    this.failedPasswordAttempts++;
    if (this.failedPasswordAttempts >= maxAttempts) {
      this.failedPasswordAttempts = 0;
      this.passwordBlockedUntil = System.currentTimeMillis() + cooldownMillis;
    }
  }

  /** @return remaining block time in seconds, or 0 when the player may try again. */
  public long blockedSeconds() {
    long remaining = this.passwordBlockedUntil - System.currentTimeMillis();
    return remaining > 0 ? remaining / 1000L + 1L : 0L;
  }

  /** The connection of the player while they are waiting in the limbo (may be null). */
  public Player getPlayer() {
    return this.player;
  }

  public void setPlayer(Player player) {
    this.player = player;
  }

  /** The boss bar currently shown for this authorization, or {@code null}. */
  public BossBar getBossBar() {
    return this.bossBar;
  }

  public void setBossBar(BossBar bossBar) {
    this.bossBar = bossBar;
  }
}
