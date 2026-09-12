package cn.blaze.hwidban.sync;

import cn.blaze.hwidban.HwidBanPlugin;
import cn.blaze.hwidban.hwid.BanEntry;
import cn.blaze.hwidban.hwid.HwidManager;
import cn.blaze.hwidban.hwid.PlayerProfile;
import cn.blaze.hwidban.storage.BanStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 多服务器封禁同步 (sqlite/mysql 后端时启用):
 * - 本服封禁/解封 → 立即异步写库 (由 HwidManager 持久化钩子触发)
 * - 每隔 interval 秒异步拉取全表 → 与内存缓存对账 → 新增封禁即刻踢出在线命中者
 * - 首次启用时若库为空而本地 bans.json 有数据, 自动迁移入库
 */
public class SyncService {

    private final HwidBanPlugin plugin;
    private final HwidManager mgr;
    private final BanStore store;
    private final int intervalSeconds;

    public SyncService(HwidBanPlugin plugin, HwidManager mgr, BanStore store, int intervalSeconds) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.store = store;
        this.intervalSeconds = Math.max(1, intervalSeconds);
    }

    /** 启动迁移与轮询任务。 */
    public void start() {
        migrateIfNeeded();
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::pull,
                20L * intervalSeconds, 20L * intervalSeconds);
        plugin.getLogger().info("多服同步已启用: backend 每 " + intervalSeconds + " 秒与共享封禁库对账一次。");
    }

    /** 旧数据迁移: 库为空且本地 bans.json 有历史封禁时, 整体推入数据库。 */
    private void migrateIfNeeded() {
        try {
            if (!store.loadAll().isEmpty()) {
                return;
            }
            List<BanEntry> old = readLocalJson(new File(plugin.getDataFolder(), "bans.json"));
            if (old.isEmpty()) {
                return;
            }
            int n = 0;
            for (BanEntry e : old) {
                if (e != null && e.hwid != null) {
                    store.upsert(e);
                    n++;
                }
            }
            plugin.getLogger().info("已把本地 bans.json 的 " + n + " 条封禁迁移到共享封禁库。");
        } catch (Exception ex) {
            plugin.getLogger().warning("封禁数据迁移失败: " + ex.getMessage());
        }
    }

    /** 拉取共享库并对账; 新出现的封禁立即处理在线命中者。 */
    private void pull() {
        try {
            Map<String, BanEntry> fresh = store.loadAll();
            Map<String, BanEntry> added = mgr.replaceAllBans(fresh);
            if (added.isEmpty()) {
                return;
            }
            plugin.getLogger().info("[同步] 从共享封禁库收到 " + added.size() + " 条新增封禁。");
            Set<String> hwids = added.keySet();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("hwidban.exempt")) {
                    continue;
                }
                String hit = null;
                String fp = plugin.fingerprints().compute(p);
                if (fp != null && hwids.contains(fp.toLowerCase(Locale.ROOT))) {
                    hit = fp.toLowerCase(Locale.ROOT);
                }
                if (hit == null) {
                    PlayerProfile prof = mgr.peekProfile(p.getUniqueId());
                    if (prof != null && prof.reportedHwid != null
                            && hwids.contains(prof.reportedHwid.toLowerCase(Locale.ROOT))) {
                        hit = prof.reportedHwid.toLowerCase(Locale.ROOT);
                    }
                }
                if (hit != null) {
                    BanEntry e = added.get(hit);
                    p.kick(plugin.msg().kick(p.getName(), e));
                    plugin.getLogger().warning("[同步] 已踢出被其他服务器封禁的玩家 " + p.getName()
                            + " (标识: " + hit.substring(0, 8) + "…)");
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[同步] 拉取共享封禁库失败: " + t.getMessage());
        }
    }

    public void close() {
        store.close();
    }

    /** 读取本地 bans.json (首次迁移用)。 */
    public static List<BanEntry> readLocalJson(File f) throws Exception {
        if (!f.exists()) {
            return new ArrayList<>();
        }
        Gson gson = new GsonBuilder().create();
        List<BanEntry> list = gson.fromJson(Files.readString(f.toPath(), StandardCharsets.UTF_8),
                new TypeToken<List<BanEntry>>() { }.getType());
        return list == null ? new ArrayList<>() : list;
    }
}
