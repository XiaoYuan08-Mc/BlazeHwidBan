package cn.blaze.hwidban.hwid;

import cn.blaze.hwidban.HwidBanPlugin;
import cn.blaze.hwidban.util.Hashing;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 封禁数据管理: 内存缓存 + JSON 异步落盘 (bans.json / profiles.json)。 */
public class HwidManager {

    public static final String FINGERPRINT = "FINGERPRINT";
    public static final String REPORTED = "REPORTED";
    public static final String MANUAL = "MANUAL";

    private static final int MAX_FINGERPRINTS = 8;

    private final HwidBanPlugin plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String, BanEntry> bans = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
    private final Object ioLock = new Object();
    private final AtomicBoolean pending = new AtomicBoolean(false);
    private final Path bansFile;
    private final Path profilesFile;

    public HwidManager(HwidBanPlugin plugin) {
        this.plugin = plugin;
        File dir = plugin.getDataFolder();
        this.bansFile = new File(dir, "bans.json").toPath();
        this.profilesFile = new File(dir, "profiles.json").toPath();
    }

    public synchronized void load() {
        try {
            if (Files.exists(bansFile)) {
                List<BanEntry> list = gson.fromJson(Files.readString(bansFile, StandardCharsets.UTF_8),
                        new TypeToken<List<BanEntry>>() { }.getType());
                bans.clear();
                if (list != null) {
                    for (BanEntry e : list) {
                        if (e != null && e.hwid != null) {
                            bans.put(e.hwid.toLowerCase(Locale.ROOT), e);
                        }
                    }
                }
            }
            if (Files.exists(profilesFile)) {
                Map<String, PlayerProfile> map = gson.fromJson(Files.readString(profilesFile, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, PlayerProfile>>() { }.getType());
                profiles.clear();
                if (map != null) {
                    for (Map.Entry<String, PlayerProfile> en : map.entrySet()) {
                        try {
                            profiles.put(UUID.fromString(en.getKey()), en.getValue());
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().severe("加载数据失败: " + ex.getMessage());
        }
        int swept = sweepExpired();
        if (swept > 0) {
            plugin.getLogger().info("启动清理: 已移除 " + swept + " 条过期临时封禁。");
        }
    }

    /** 停服前同步落盘。 */
    public void flush() {
        synchronized (ioLock) {
            writeAll();
        }
    }

    private void saveAsync() {
        if (!pending.compareAndSet(false, true)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            pending.set(false);
            synchronized (ioLock) {
                writeAll();
            }
        });
    }

    private void writeAll() {
        try {
            Files.createDirectories(bansFile.getParent());
            Files.writeString(bansFile, gson.toJson(new ArrayList<>(bans.values())), StandardCharsets.UTF_8);
            Map<String, PlayerProfile> out = new LinkedHashMap<>();
            profiles.forEach((u, p) -> out.put(u.toString(), p));
            Files.writeString(profilesFile, gson.toJson(out), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().severe("保存数据失败: " + ex.getMessage());
        }
    }

    public BanEntry add(String hwid, String type, String reason, CommandSender by, OfflinePlayer target) {
        BanEntry e = new BanEntry();
        e.hwid = hwid.toLowerCase(Locale.ROOT);
        e.type = type;
        e.reason = reason;
        e.by = by.getName();
        e.at = System.currentTimeMillis();
        if (target != null && target.getName() != null) {
            e.playerName = target.getName();
            e.uuid = target.getUniqueId().toString();
        }
        bans.put(e.hwid, e);
        saveAsync();
        audit("ADD", e);
        return e;
    }

    /** 添加封禁记录 (直接指定执行者与归属玩家), 供原生封禁联动使用。 */
    public BanEntry addFor(String hwid, String type, String reason, String by, UUID target, String targetName) {
        return addFor(hwid, type, reason, by, target, targetName, 0L);
    }

    /** 添加封禁记录, expires > 0 表示临时封禁 (毫秒时间戳)。 */
    public BanEntry addFor(String hwid, String type, String reason, String by, UUID target, String targetName, long expires) {
        BanEntry e = new BanEntry();
        e.hwid = hwid.toLowerCase(Locale.ROOT);
        e.type = type;
        e.reason = reason;
        e.by = by;
        e.at = System.currentTimeMillis();
        e.expires = expires;
        if (target != null) {
            e.uuid = target.toString();
        }
        if (targetName != null) {
            e.playerName = targetName;
        }
        bans.put(e.hwid, e);
        saveAsync();
        audit("ADD", e);
        return e;
    }

    public BanEntry isBanned(String hwid) {
        if (hwid == null) {
            return null;
        }
        BanEntry e = bans.get(hwid.toLowerCase(Locale.ROOT));
        if (e != null && e.expires > 0 && e.expires < System.currentTimeMillis()) {
            // 临时封禁已过期: 惰性清除
            bans.remove(e.hwid);
            saveAsync();
            audit("EXPIRE", e);
            return null;
        }
        return e;
    }

    /** 清理全部过期临时封禁, 返回清理数量。 */
    public int sweepExpired() {
        long now = System.currentTimeMillis();
        List<BanEntry> expired = new ArrayList<>();
        for (BanEntry e : bans.values()) {
            if (e.expires > 0 && e.expires < now) {
                expired.add(e);
            }
        }
        for (BanEntry e : expired) {
            bans.remove(e.hwid);
            audit("EXPIRE", e);
        }
        if (!expired.isEmpty()) {
            saveAsync();
        }
        return expired.size();
    }

    /** 获取已存在的档案, 不创建新档案。 */
    public PlayerProfile peekProfile(UUID uuid) {
        return profiles.get(uuid);
    }

    /** 按机器码前缀或玩家名解封, 返回解封数量。优先匹配玩家名, 否则按哈希 (前缀) 匹配。 */
    public int unban(String query) {
        return unbanEntries(query).size();
    }

    /** 解除匹配的封禁并返回被移除的条目 (供命令层联动解除原版封禁)。 */
    public java.util.List<BanEntry> unbanEntries(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        List<BanEntry> removed = new ArrayList<>();
        boolean nameHit = false;
        for (BanEntry e : List.copyOf(bans.values())) {
            if (e.playerName != null && e.playerName.equalsIgnoreCase(query)) {
                removed.add(e);
                nameHit = true;
            }
        }
        if (!nameHit && Hashing.isHashOrPrefix(q)) {
            for (BanEntry e : List.copyOf(bans.values())) {
                if (e.hwid.startsWith(q)) {
                    removed.add(e);
                }
            }
        }
        removed.forEach(e -> bans.remove(e.hwid));
        if (!removed.isEmpty()) {
            saveAsync();
            audit("REMOVE x" + removed.size() + " (query=" + query + ")", null);
        }
        return removed;
    }

    public java.util.List<BanEntry> listBans() {
        java.util.List<BanEntry> list = new ArrayList<>(bans.values());
        list.sort(Comparator.comparingLong((BanEntry e) -> e.at).reversed());
        return list;
    }

    public PlayerProfile profile(UUID uuid) {
        return profiles.computeIfAbsent(uuid, k -> new PlayerProfile());
    }

    public UUID uuidByName(String name) {
        for (Map.Entry<UUID, PlayerProfile> e : profiles.entrySet()) {
            if (name.equalsIgnoreCase(e.getValue().name)) {
                return e.getKey();
            }
        }
        return null;
    }

    public Collection<PlayerProfile> allProfiles() {
        return profiles.values();
    }

    /** 遍历档案 (含 UUID), 供同机关联查询使用。 */
    public Set<Map.Entry<UUID, PlayerProfile>> allProfileEntries() {
        return profiles.entrySet();
    }

    public void recordFingerprint(UUID uuid, String name, String fp) {
        PlayerProfile p = profile(uuid);
        boolean changed = false;
        if (!p.fingerprints.contains(fp)) {
            p.fingerprints.add(fp);
            while (p.fingerprints.size() > MAX_FINGERPRINTS) {
                p.fingerprints.remove(0);
            }
            changed = true;
        }
        if (!Objects.equals(p.name, name)) {
            p.name = name;
            changed = true;
        }
        p.lastSeen = System.currentTimeMillis();
        if (changed) {
            saveAsync();
        }
    }

    public void recordReported(UUID uuid, String name, String hwid) {
        PlayerProfile p = profile(uuid);
        String normalized = hwid.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        boolean changed = false;
        if (!normalized.equals(p.reportedHwid)) {
            p.reportedHwid = normalized;
            p.hwidSince = now;
            changed = true;
        }
        p.lastSeen = now;
        p.lastReportAt = now;
        if (!Objects.equals(p.name, name)) {
            p.name = name;
            changed = true;
        }
        if (changed) {
            saveAsync();
        }
    }

    /* ---------- 审计日志 ---------- */

    private final Object auditLock = new Object();

    /** 追加一条操作记录到 bans.log (audit-log 开启时)。 */
    public void audit(String action, BanEntry e) {
        if (!plugin.getConfig().getBoolean("audit-log", true)) {
            return;
        }
        try {
            String line = new java.sql.Timestamp(System.currentTimeMillis())
                    + " " + action
                    + (e != null ? " hwid=" + e.hwid + " type=" + e.type
                            + " by=" + e.by + " player=" + (e.playerName == null ? "-" : e.playerName)
                            + " reason=" + (e.reason == null ? "-" : e.reason)
                            + (e.expires > 0 ? " expires=" + new java.sql.Timestamp(e.expires) : "") : "")
                    + System.lineSeparator();
            synchronized (auditLock) {
                Files.writeString(new File(plugin.getDataFolder(), "bans.log").toPath(),
                        line, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (IOException ignored) {
            // 审计日志失败不影响主流程
        }
    }
}
