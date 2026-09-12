package cn.blaze.hwidban.listener;

import cn.blaze.hwidban.HwidBanPlugin;
import cn.blaze.hwidban.command.HwidBanCommand;
import cn.blaze.hwidban.hwid.BanEntry;
import cn.blaze.hwidban.hwid.HwidManager;
import cn.blaze.hwidban.hwid.PlayerProfile;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接收配套客户端模组上报的真实机器码。
 *
 * 协议约定: 客户端向 "blaze:hwid" 频道发送一条文本, 内容包含 64 位十六进制 SHA-256。
 * 兼容两种编码: Fabric StandardStringCodec (带 VarInt 长度前缀) 与裸 "HWID:..." 文本。
 * 服务端据此记录档案并校验封禁, 换账号登录同一台机器同样会被拦截。
 * strict 模式下, 同一机器码的非首个账号会被直接拒绝 (一台机器一号)。
 */
public class HwidMessageListener implements PluginMessageListener {

    private static final Pattern HASH_PATTERN = Pattern.compile("\\b[0-9a-fA-F]{64}\\b");
    /** 黑客端识别: MODS: 段携带 Fabric 模组 id 列表 (配套 mod 上报, 可选, 老版本 mod 无此段)。 */
    private static final Pattern MODS_PATTERN = Pattern.compile("MODS:([A-Za-z0-9_,\\-. ]+)");

    /** 本次运行中已做过同机多账号提醒的机器码, 防止刷屏。 */
    private final java.util.Set<String> altAlerted = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final HwidBanPlugin plugin;

    public HwidMessageListener(HwidBanPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!plugin.channel().equals(channel) || player == null) {
            return;
        }
        try {
            String data = new String(message, StandardCharsets.UTF_8);
            Matcher m = HASH_PATTERN.matcher(data);
            if (!m.find()) {
                return; // 非法载荷直接忽略
            }
            String hwid = m.group().toLowerCase(Locale.ROOT);
            HwidManager mgr = plugin.hwidManager();
            mgr.recordReported(player.getUniqueId(), player.getName(), hwid);

            BanEntry hit = mgr.isBanned(hwid);
            if (hit != null) {
                player.kick(plugin.msg().kick(player.getName(), hit));
                plugin.getLogger().warning("已踢出被机器码封禁的玩家 " + player.getName()
                        + " (标识: " + hit.hwid + ", 来源: 客户端上报)");
                return;
            }

            if (player.hasPermission("hwidban.exempt")) {
                return; // 豁免权限: 不参与 strict 判定
            }
            // 黑客端识别: 模组黑名单由配套 mod 上报 (未上报/无 MODS: 段则跳过)
            Matcher mods = MODS_PATTERN.matcher(data);
            if (mods.find()) {
                List<String> ids = new ArrayList<>();
                for (String id : mods.group(1).split(",")) {
                    id = id.trim().toLowerCase(Locale.ROOT);
                    if (!id.isEmpty()) {
                        ids.add(id);
                    }
                }
                plugin.clientGuard().checkMods(player, ids);
            }
            if (checkAltAuto(player, mgr, hwid)) {
                return; // 已被同机多账号自动处罚踢出
            }
            if (plugin.getConfig().getBoolean("strict-mode", false)) {
                UUID owner = findOwner(mgr, hwid, player.getUniqueId());
                if (owner != null) {
                    if (plugin.getConfig().getBoolean("strict-auto-ban", false)) {
                        mgr.addFor(hwid, HwidManager.REPORTED, "strict: 同机新账号",
                                "STRICT", player.getUniqueId(), player.getName());
                    }
                    player.kick(plugin.msg().component("strict-kick"));
                    plugin.getLogger().warning("[strict] 已拒绝同机新账号 " + player.getName()
                            + " (机器码已被 " + owner + " 绑定)");
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("处理客户端上报机器码出错: " + t.getMessage());
        }
    }

    /**
     * 同机多账号自动处罚: 同一机器码下的不同账号数达到阈值时触发。
     * action=alert 仅提醒管理员; action=tempban 临时封禁该机器码 (含主号, 整机生效)。
     * 返回 true 表示已执行处罚并踢出。
     */
    private boolean checkAltAuto(Player player, HwidManager mgr, String hwid) {
        org.bukkit.configuration.file.FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("alt-auto-action.enabled", false)) {
            return false;
        }
        int max = Math.max(2, c.getInt("alt-auto-action.max-accounts", 5));
        int count = 0;
        for (PlayerProfile p : mgr.allProfiles()) {
            if (hwid.equalsIgnoreCase(p.reportedHwid)) {
                count++; // 含本次上报的玩家自己 (recordReported 已写入档案)
            }
        }
        if (count < max) {
            return false;
        }
        String action = c.getString("alt-auto-action.action", "alert");
        String reason = c.getString("alt-auto-action.reason", "同机账号数量异常");
        String shortHwid = hwid.substring(0, 8) + "…";
        if ("tempban".equalsIgnoreCase(action)) {
            long ms = HwidBanCommand.parseDuration(c.getString("alt-auto-action.tempban-duration", "7d"));
            if (ms <= 0) {
                ms = 7 * 86400000L;
            }
            BanEntry e = mgr.addFor(hwid, HwidManager.REPORTED, reason, "ALT-AUTO",
                    player.getUniqueId(), player.getName(), System.currentTimeMillis() + ms);
            player.kick(plugin.msg().kick(player.getName(), e));
            plugin.getLogger().warning("[同机多账号] " + player.getName() + " 所在机器已有 " + count
                    + " 个账号, 已自动临时封禁机器码 " + shortHwid);
            alertAdmins("alt-auto-alert", player.getName(), String.valueOf(count), shortHwid);
            return true;
        }
        // alert 模式: 每个机器码每次运行只提醒一次, 避免刷屏
        if (altAlerted.add(hwid)) {
            plugin.getLogger().info("[同机多账号] " + player.getName() + " 所在机器已有 " + count + " 个账号 (" + shortHwid + ")");
            alertAdmins("alt-auto-alert", player.getName(), String.valueOf(count), shortHwid);
        }
        return false;
    }

    private void alertAdmins(String key, String player, String count, String hwid) {
        org.bukkit.Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("hwidban.admin"))
                .forEach(p -> plugin.msg().send(p, key, "player", player, "count", count, "hwid", hwid));
    }

    /**
     * 返回该机器码的"主号" (最早上报者); 若发起上报的玩家自己就是主号或机器码尚无档案, 返回 null。
     * hwidSince=0 的旧数据视为最早 (保留老玩家为主号), 时间相同按 UUID 稳定排序。
     */
    private UUID findOwner(HwidManager mgr, String hwid, UUID self) {
        UUID owner = null;
        long best = Long.MAX_VALUE;
        String bestId = null;
        for (Map.Entry<UUID, PlayerProfile> e : mgr.allProfileEntries()) {
            if (hwid.equalsIgnoreCase(e.getValue().reportedHwid)) {
                long since = e.getValue().hwidSince;
                String id = e.getKey().toString();
                if (since < best || (since == best && id.compareTo(bestId) < 0)) {
                    best = since;
                    bestId = id;
                    owner = e.getKey();
                }
            }
        }
        return owner != null && !owner.equals(self) ? owner : null;
    }
}
