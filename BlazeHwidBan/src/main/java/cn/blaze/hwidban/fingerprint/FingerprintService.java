package cn.blaze.hwidban.fingerprint;

import cn.blaze.hwidban.util.Hashing;
import com.destroystokyo.paper.ClientOption;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 客户端指纹服务: 将服务器可见的客户端特征组合后计算 SHA-256, 作为原版客户端的"机器码"。
 *
 * 特征信号全部来自 Paper 公开 API (无 NMS), 因此 1.21.x 到最新版本通用:
 * - 客户端品牌 (vanilla / fabric / forge 等)
 * - 游戏语言
 * - 客户端设置: 主手、聊天可见性、聊天颜色、文本过滤、允许服务器列表、渲染距离、粒子状态、皮肤部件
 * - 客户端注册的插件频道列表 (模组安装情况)
 *
 * 说明: 对原版客户端而言这是统计意义上的设备指纹, 不是真实硬件 ID;
 * 真实 HWID 需配套客户端模组通过 blaze:hwid 频道上报, 由 HwidMessageListener 处理。
 */
public class FingerprintService {

    private final String salt;
    private final boolean includeBrand;
    private final boolean includeLocale;
    private final boolean includeSettings;
    private final boolean includeChannels;

    public FingerprintService(String salt, boolean includeBrand, boolean includeLocale,
                              boolean includeSettings, boolean includeChannels) {
        this.salt = salt == null ? "blaze-hwidban" : salt;
        this.includeBrand = includeBrand;
        this.includeLocale = includeLocale;
        this.includeSettings = includeSettings;
        this.includeChannels = includeChannels;
    }

    /** 计算玩家当前的客户端指纹 (64 位十六进制)。 */
    public String compute(Player player) {
        List<String> parts = new ArrayList<>();
        if (includeBrand) {
            parts.add("brand=" + safe(player.getClientBrandName()));
        }
        if (includeLocale) {
            parts.add("locale=" + player.locale());
        }
        if (includeSettings) {
            parts.add("hand=" + player.getClientOption(ClientOption.MAIN_HAND));
            parts.add("chatvis=" + player.getClientOption(ClientOption.CHAT_VISIBILITY));
            parts.add("chatcol=" + player.getClientOption(ClientOption.CHAT_COLORS_ENABLED));
            parts.add("filter=" + player.getClientOption(ClientOption.TEXT_FILTERING_ENABLED));
            parts.add("serverlist=" + player.getClientOption(ClientOption.ALLOW_SERVER_LISTINGS));
            parts.add("viewdist=" + player.getClientOption(ClientOption.VIEW_DISTANCE));
            parts.add("particles=" + player.getClientOption(ClientOption.PARTICLE_VISIBILITY));
            parts.add("skin=" + player.getClientOption(ClientOption.SKIN_PARTS).getRaw());
        }
        if (includeChannels) {
            List<String> channels = new ArrayList<>(player.getListeningPluginChannels());
            Collections.sort(channels);
            parts.add("channels=" + String.join(",", channels));
        }
        return Hashing.sha256(salt + "|" + String.join(";", parts));
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s;
    }
}
