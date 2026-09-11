package cn.blaze.hwidban.listener;

import cn.blaze.hwidban.HwidBanPlugin;
import cn.blaze.hwidban.hwid.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 黑客端识别 (client-guard), 三道防线:
 * 1. 客户端品牌检查: getClientBrandName() 匹配黑名单正则 (或白名单模式);
 * 2. 违规模组检查: 配套客户端 mod 上报的 Fabric 模组列表命中黑名单;
 * 3. require-mod 强制安装: 未装配套 mod 的玩家拒绝进入 (最强防护, 默认关闭)。
 * 处理动作 client-guard.action: kick=踢出, alert=仅提醒管理员, none=不处理。
 * 说明: Paper API 两版均无品牌事件, 品牌在进服后延迟读取 (登录配置阶段已上报)。
 */
public final class ClientGuardListener implements Listener {

    private final HwidBanPlugin plugin;
    /** 本会话收到的 Fabric 模组 id 列表 (配套 mod 上报的 MODS: 段)。 */
    private final Map<UUID, List<String>> reportedMods = new ConcurrentHashMap<>();

    public ClientGuardListener(HwidBanPlugin plugin) {
        this.plugin = plugin;
    }

    /* ---------- 事件 ---------- */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long joinedAt = System.currentTimeMillis();
        long delay = Math.max(1, plugin.getConfig().getInt("client-guard.check-delay", 2)) * 20L;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            evaluate(player, joinedAt);
        }, delay);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        reportedMods.remove(event.getPlayer().getUniqueId());
    }

    /* ---------- 评估 ---------- */

    private void evaluate(Player player, long joinedAt) {
        if (!enabled()) {
            return;
        }
        // 1. 客户端品牌
        String brand = player.getClientBrandName();
        if (brand != null && !brand.isBlank()) {
            boolean whitelist = plugin.getConfig().getBoolean("client-guard.whitelist-mode", false);
            if (whitelist) {
                if (!matchAny(plugin.getConfig().getStringList("client-guard.allowed-brands"), brand)) {
                    hit(player, "客户端品牌: " + brand + " (不在允许列表)");
                    return;
                }
            } else if (matchAny(plugin.getConfig().getStringList("client-guard.blocked-brands"), brand)) {
                hit(player, "客户端品牌: " + brand);
                return;
            }
        }
        // 2. 模组黑名单 (若配套 mod 已上报; 上报较晚时由 checkMods 兜底)
        List<String> mods = reportedMods.get(player.getUniqueId());
        if (mods != null && checkMods(player, mods)) {
            return;
        }
        // 3. require-mod 由独立延迟任务处理 (给 mod 上报留足时间)
        if (plugin.getConfig().getBoolean("client-guard.require-mod", false)) {
            long requireDelay = Math.max(1, plugin.getConfig().getInt("client-guard.require-mod-delay", 8)) * 20L;
            long checkDelay = Math.max(1, plugin.getConfig().getInt("client-guard.check-delay", 2)) * 20L;
            long extra = requireDelay - checkDelay;
            if (extra <= 0) {
                checkRequireMod(player, joinedAt);
            } else {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        checkRequireMod(player, joinedAt);
                    }
                }, extra);
            }
        }
    }

    private void checkRequireMod(Player player, long joinedAt) {
        if (!enabled() || !plugin.getConfig().getBoolean("client-guard.require-mod", false)) {
            return;
        }
        PlayerProfile prof = plugin.hwidManager().peekProfile(player.getUniqueId());
        if (prof != null && prof.lastReportAt >= joinedAt) {
            return; // 本会话已收到配套 mod 上报
        }
        hit(player, "未安装配套客户端 mod");
    }

    /** 模组黑名单评估 (HwidMessageListener 收到 MODS: 段时调用)。返回是否命中。 */
    public boolean checkMods(Player player, List<String> mods) {
        if (!enabled() || mods == null || mods.isEmpty()) {
            return false;
        }
        reportedMods.put(player.getUniqueId(), mods);
        List<String> hits = new ArrayList<>();
        for (String banned : plugin.getConfig().getStringList("client-guard.blocked-mods")) {
            for (String m : mods) {
                if (m.equalsIgnoreCase(banned)) {
                    hits.add(m);
                }
            }
        }
        if (hits.isEmpty()) {
            return false;
        }
        hit(player, "违规模组: " + String.join(", ", hits));
        return true;
    }

    /* ---------- 处置 ---------- */

    private void hit(Player player, String detail) {
        String action = plugin.getConfig().getString("client-guard.action", "kick");
        boolean kick = "kick".equalsIgnoreCase(action);
        plugin.getLogger().warning("[客户端拦截] " + player.getName() + " (" + detail + ") → "
                + (kick ? "已踢出" : "仅提醒管理员"));
        if (kick) {
            player.kick(plugin.msg().component("guard-kick",
                    "player", player.getName(), "detail", detail));
        }
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("hwidban.admin"))
                .forEach(p -> plugin.msg().send(p, "guard-alert",
                        "player", player.getName(), "detail", detail,
                        "act", kick ? "已踢出" : "仅提醒"));
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("client-guard.enabled", true);
    }

    private static boolean matchAny(List<String> regexes, String value) {
        for (String regex : regexes) {
            try {
                if (Pattern.compile(regex).matcher(value).matches()) {
                    return true;
                }
            } catch (Exception ignored) {
                // 无效正则跳过, 不影响其他规则
            }
        }
        return false;
    }
}
