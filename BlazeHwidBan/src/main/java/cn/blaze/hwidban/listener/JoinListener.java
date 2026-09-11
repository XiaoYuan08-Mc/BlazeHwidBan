package cn.blaze.hwidban.listener;

import cn.blaze.hwidban.HwidBanPlugin;
import cn.blaze.hwidban.hwid.BanEntry;
import cn.blaze.hwidban.hwid.HwidManager;
import cn.blaze.hwidban.hwid.PlayerProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** 进服时异步采集客户端指纹并校验封禁, 附带同机关联提醒与配套客户端覆盖率提醒。 */
public class JoinListener implements Listener {

    private final HwidBanPlugin plugin;
    /** 本次运行中已做过同机提醒的玩家, 防止重复进服刷屏。 */
    private final Set<UUID> altNotified = ConcurrentHashMap.newKeySet();

    public JoinListener(HwidBanPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> check(player));
    }

    private void check(Player player) {
        if (player.hasPermission("hwidban.exempt")) {
            return; // 豁免权限: 不采集、不校验
        }
        HwidManager mgr = plugin.hwidManager();
        try {
            String fp = plugin.fingerprints().compute(player);
            mgr.recordFingerprint(player.getUniqueId(), player.getName(), fp);

            BanEntry hit = mgr.isBanned(fp);
            PlayerProfile prof = mgr.profile(player.getUniqueId());
            if (prof.reportedHwid != null) {
                BanEntry reportedHit = mgr.isBanned(prof.reportedHwid);
                if (reportedHit != null) {
                    hit = reportedHit; // 上报机器码命中时优先展示, 踢出画面信息更完整
                }
            }
            if (hit != null) {
                Component kickMsg = plugin.msg().kick(player.getName(), hit);
                // Paper 的 kick 可在异步线程安全调用
                player.kick(kickMsg);
                plugin.getLogger().warning("已踢出被机器码封禁的玩家 " + player.getName()
                        + " (标识: " + hit.hwid + ", 类型: " + hit.type + ")");
                return;
            }
            notifyAlts(player);
            scheduleNoModCheck(player);
        } catch (Throwable t) {
            plugin.getLogger().warning("指纹校验出错: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** 同机关联提醒: 进服玩家的上报机器码与其他档案一致时, 提醒在线管理员。 */
    private void notifyAlts(Player player) {
        if (!plugin.getConfig().getBoolean("alt-notify-admins", true)) {
            return;
        }
        PlayerProfile prof = plugin.hwidManager().peekProfile(player.getUniqueId());
        if (prof == null || prof.reportedHwid == null || !altNotified.add(player.getUniqueId())) {
            return;
        }
        String others = plugin.hwidManager().allProfileEntries().stream()
                .filter(e -> !e.getKey().equals(player.getUniqueId())
                        && prof.reportedHwid.equalsIgnoreCase(e.getValue().reportedHwid))
                .map(e -> e.getValue().name == null ? "?" : e.getValue().name)
                .collect(Collectors.joining(", "));
        if (others.isEmpty()) {
            return;
        }
        plugin.getLogger().info("[同机提醒] " + player.getName() + " 与 " + others + " 同机 (机器码 "
                + prof.reportedHwid.substring(0, 8) + "…)");
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("hwidban.admin"))
                .forEach(p -> plugin.msg().send(p, "alt-notify",
                        "player", player.getName(), "others", others,
                        "hwid", prof.reportedHwid.substring(0, 8) + "…"));
    }

    /** 配套客户端覆盖率提醒: 进服数秒后仍未收到机器码上报则提醒管理员。 */
    private void scheduleNoModCheck(Player player) {
        if (!plugin.getConfig().getBoolean("no-mod-notify", true)) {
            return;
        }
        long joinedAt = System.currentTimeMillis();
        int delayTicks = Math.max(1, plugin.getConfig().getInt("no-mod-delay", 5)) * 20;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            PlayerProfile prof = plugin.hwidManager().peekProfile(player.getUniqueId());
            if (prof != null && prof.lastReportAt >= joinedAt) {
                return; // 已收到本会话上报
            }
            plugin.getLogger().info("[覆盖率] " + player.getName() + " 未安装配套客户端, 仅指纹兜底。");
            Bukkit.getOnlinePlayers().stream()
                    .filter(p -> p.hasPermission("hwidban.admin"))
                    .forEach(p -> plugin.msg().send(p, "no-mod-notify", "player", player.getName()));
        }, delayTicks);
    }
}
