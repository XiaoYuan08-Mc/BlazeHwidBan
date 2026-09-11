package cn.blaze.hwidban.listener;

import cn.blaze.hwidban.HwidBanPlugin;
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
