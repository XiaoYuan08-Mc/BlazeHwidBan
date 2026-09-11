package cn.blaze.hwidban.util;

import cn.blaze.hwidban.HwidBanPlugin;
import cn.blaze.hwidban.hwid.BanEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;

/** 基于 MiniMessage 的配置化消息工具。 */
public class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final HwidBanPlugin plugin;
    private final Map<String, String> messages = new HashMap<>();
    private String prefix = "";

    public Msg(HwidBanPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        messages.clear();
        // 内置默认消息兜底: 老配置文件缺新版本消息键时, 从 jar 内 config.yml 取默认值
        try (java.io.InputStream in = plugin.getResource("config.yml")) {
            if (in != null) {
                org.bukkit.configuration.file.YamlConfiguration def =
                        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                ConfigurationSection ds = def.getConfigurationSection("messages");
                if (ds != null) {
                    for (String key : ds.getKeys(false)) {
                        messages.put(key.toLowerCase(java.util.Locale.ROOT), ds.getString(key, ""));
                    }
                }
            }
        } catch (Exception ignored) {
            // 兜底失败不影响正常加载
        }
        // 文件中的用户自定义消息覆盖默认值
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("messages");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                messages.put(key.toLowerCase(java.util.Locale.ROOT), sec.getString(key, ""));
            }
        }
        prefix = messages.getOrDefault("prefix", "");
    }

    public String get(String key) {
        String v = messages.get(key.toLowerCase(java.util.Locale.ROOT));
        return v != null ? v : "<yellow>[HwidBan] 缺少消息键: " + key;
    }

    /** 依次替换 text 中的 {key} 占位符。kv 形如 "hwid", "abc...", "reason", "xxx"。 */
    public static String format(String text, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            text = text.replace("{" + kv[i] + "}", kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return text;
    }

    public void send(CommandSender to, String key, String... kv) {
        to.sendMessage(MM.deserialize(prefix + format(get(key), kv)));
    }

    public void raw(CommandSender to, String miniMessageText) {
        to.sendMessage(MM.deserialize(miniMessageText));
    }

    private static final java.time.format.DateTimeFormatter DATE =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(java.time.ZoneId.systemDefault());

    /** AdvancedBan 风格踢出画面: 原因/操作者/封禁时间/到期/解封标识。 */
    public Component kick(String playerName, BanEntry entry) {
        String hwidShort = entry.hwid != null && entry.hwid.length() > 8
                ? entry.hwid.substring(0, 8) + "…" : entry.hwid;
        return MM.deserialize(format(get("kick"),
                "player", playerName != null ? playerName
                        : (entry.playerName == null ? "-" : entry.playerName),
                "reason", entry.reason == null || entry.reason.isBlank() ? "未指定" : entry.reason,
                "by", entry.by == null ? "-" : entry.by,
                "date", DATE.format(java.time.Instant.ofEpochMilli(entry.at)),
                "expires", entry.expires > 0 ? DATE.format(java.time.Instant.ofEpochMilli(entry.expires)) : "永久",
                "hwid", hwidShort == null ? "-" : hwidShort,
                "type", typeCn(entry.type)));
    }

    /** 按消息键生成组件 (用于踢出等非 CommandSender 场景)。 */
    public Component component(String key, String... kv) {
        return MM.deserialize(format(get(key), kv));
    }

    public static String typeCn(String type) {
        return switch (type == null ? "" : type) {
            case "FINGERPRINT" -> "客户端指纹";
            case "REPORTED" -> "客户端上报";
            default -> "手动";
        };
    }
}
