package net.omegasmp.discordauth;

import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;

/**
 * Persistent Minecraft &lt;-&gt; Discord link storage backed by links.json.
 */
public class LinkStore {

  public static class Link {

    public String name;
    public String discordId;
    /** Discord username (without the discriminator), stored so the tab list needs no API call. */
    public String discordName;
    /** Nickname on the Discord server (per-guild "nick"), the nicest thing to display. */
    public String discordNick;
    /** Discord global display name (the "display name" of the account). */
    public String discordGlobal;
    public long linkedAt;

    public Link() {
    }

    public Link(String name, String discordId, String discordName) {
      this.name = name;
      this.discordId = discordId;
      this.discordName = discordName;
      this.linkedAt = System.currentTimeMillis();
    }

    /** The Discord name for the configured {@code tab.nameSource}. */
    public String displayName(String source) {
      for (String candidate : displayNameCandidates(source)) {
        if (candidate != null && !candidate.isBlank()) {
          return candidate;
        }
      }
      return null;
    }

    /**
     * The names to try for a source, best first (it always ends with the @username, which is plain
     * ASCII). Other sources are used as fallbacks when the preferred one is unreadable — see
     * {@code tab.stripUnsupported}.
     */
    public List<String> displayNameCandidates(String source) {
      if ("username".equalsIgnoreCase(source)) {
        return Arrays.asList(this.discordName, this.discordGlobal, this.discordNick);
      }
      if ("global".equalsIgnoreCase(source)) {
        return Arrays.asList(this.discordGlobal, this.discordNick, this.discordName);
      }
      return Arrays.asList(this.discordNick, this.discordGlobal, this.discordName);
    }
  }

  public static class Data {

    public int version = 1;
    public Map<String, Link> links = new LinkedHashMap<>();
  }

  private static final Type DATA_TYPE = new TypeToken<Data>() {
  }.getType();

  private final Path file;
  private final Logger logger;
  private final Data data;
  private final Object lock = new Object();

  public LinkStore(Path file, Logger logger) {
    this.file = file;
    this.logger = logger;
    this.data = this.load();
  }

  private Data load() {
    try {
      if (Files.exists(this.file)) {
        try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
          Data loaded = Config.GSON.fromJson(reader, DATA_TYPE);
          if (loaded != null && loaded.links != null) {
            return loaded;
          }
        }
      }
    } catch (IOException exception) {
      this.logger.error("Unable to read links.json", exception);
    }
    return new Data();
  }

  private void save() {
    try {
      Files.createDirectories(this.file.getParent());
      try (Writer writer = Files.newBufferedWriter(this.file, StandardCharsets.UTF_8)) {
        Config.GSON.toJson(this.data, writer);
      }
    } catch (IOException exception) {
      this.logger.error("Unable to write links.json", exception);
    }
  }

  public boolean isLinked(UUID uuid) {
    synchronized (this.lock) {
      return this.data.links.containsKey(uuid.toString());
    }
  }

  public Link getLink(UUID uuid) {
    synchronized (this.lock) {
      return this.data.links.get(uuid.toString());
    }
  }

  public Link getLinkByName(String name) {
    synchronized (this.lock) {
      for (Link link : this.data.links.values()) {
        if (link.name != null && link.name.equalsIgnoreCase(name)) {
          return link;
        }
      }
      return null;
    }
  }

  public String getUuidByName(String name) {
    synchronized (this.lock) {
      for (Map.Entry<String, Link> entry : this.data.links.entrySet()) {
        if (entry.getValue().name != null && entry.getValue().name.equalsIgnoreCase(name)) {
          return entry.getKey();
        }
      }
      return null;
    }
  }

  public String getUuidByDiscordId(String discordId) {
    synchronized (this.lock) {
      for (Map.Entry<String, Link> entry : this.data.links.entrySet()) {
        if (entry.getValue().discordId != null && entry.getValue().discordId.equals(discordId)) {
          return entry.getKey();
        }
      }
      return null;
    }
  }

  /** Every Minecraft account linked to one Discord account, oldest link first. */
  public List<Link> getLinksByDiscordId(String discordId) {
    List<Link> result = new java.util.ArrayList<>();
    synchronized (this.lock) {
      for (Link link : this.data.links.values()) {
        if (link.discordId != null && link.discordId.equals(discordId)) {
          result.add(link);
        }
      }
    }
    result.sort(java.util.Comparator.comparingLong(link -> link.linkedAt));
    return result;
  }

  /** How many Minecraft accounts this Discord account has linked. */
  public int countByDiscordId(String discordId) {
    synchronized (this.lock) {
      int result = 0;
      for (Link link : this.data.links.values()) {
        if (link.discordId != null && link.discordId.equals(discordId)) {
          result++;
        }
      }
      return result;
    }
  }

  public String getNameByDiscordId(String discordId) {
    synchronized (this.lock) {
      for (Link link : this.data.links.values()) {
        if (link.discordId != null && link.discordId.equals(discordId)) {
          return link.name;
        }
      }
      return null;
    }
  }

  public void link(UUID uuid, String name, String discordId, String discordName) {
    synchronized (this.lock) {
      this.data.links.put(uuid.toString(), new Link(name, discordId, discordName));
      this.save();
    }
    this.logger.info("Linked Minecraft {} ({}) to Discord {}{}",
        name, uuid, discordId,
        discordName == null || discordName.isBlank() ? "" : " (@" + discordName + ")");
  }

  /** Stores the Discord username for an existing link (e.g. resolved lazily for old records). */
  public void setDiscordName(UUID uuid, String discordName) {
    synchronized (this.lock) {
      Link link = this.data.links.get(uuid.toString());
      if (link == null || discordName == null || discordName.isBlank() || discordName.equals(link.discordName)) {
        return;
      }
      link.discordName = discordName;
      this.save();
    }
  }

  /**
   * Stores everything we know about the Discord account (username, server nickname, global display
   * name) and returns {@code true} when something actually changed.
   */
  public boolean setDiscordIdentity(UUID uuid, String discordName, String discordNick, String discordGlobal) {
    synchronized (this.lock) {
      Link link = this.data.links.get(uuid.toString());
      if (link == null) {
        return false;
      }
      boolean changed = false;
      if (discordName != null && !discordName.isBlank() && !discordName.equals(link.discordName)) {
        link.discordName = discordName;
        changed = true;
      }
      if (discordNick != null && !discordNick.isBlank() && !discordNick.equals(link.discordNick)) {
        link.discordNick = discordNick;
        changed = true;
      }
      if (discordGlobal != null && !discordGlobal.isBlank() && !discordGlobal.equals(link.discordGlobal)) {
        link.discordGlobal = discordGlobal;
        changed = true;
      }
      if (changed) {
        this.save();
      }
      return changed;
    }
  }

  public boolean unlink(UUID uuid) {
    boolean removed;
    synchronized (this.lock) {
      removed = this.data.links.remove(uuid.toString()) != null;
      if (removed) {
        this.save();
      }
    }
    if (removed) {
      this.logger.info("Unlinked Minecraft account {}", uuid);
    }
    return removed;
  }

  public int size() {
    synchronized (this.lock) {
      return this.data.links.size();
    }
  }

  public String normalize(String discordId) {
    return discordId == null ? null : discordId.toLowerCase(Locale.ROOT);
  }
}
