package cn.blaze.hwidban.listener;

import cn.blaze.hwidban.HwidBanPlugin;
import cn.blaze.hwidban.hwid.BanEntry;
import cn.blaze.hwidban.hwid.HwidManager;
import cn.blaze.hwidban.hwid.PlayerProfile;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 原版与第三方封禁联动:
 * 1. 启动时把 banned-players.json 里的既有原生封禁转换为机器码封禁;
 * 2. 拦截 /ban (玩家或控制台), 自动补封目标玩家的指纹与上报机器码, 并踢出在线同机账号;
 * 3. 拦截 /pardon, 同步解除该玩家名下的机器码封禁;
 * 4. 反作弊联动 (anticheat-sync): 识别 Grim/Vulcan/LiteBans/AdvancedBan 等处罚插件
 *    执行的封禁/解封命令 (反作弊自动处罚本质是配置的命令执行, 无事件 API 可用),
 *    同样补封/解除机器码。
 * 兼容任何最终写入原生封禁列表的 /ban 实现 (含 Essentials 等);
 * 自建数据库不落盘的插件 (LiteBans 等) 通过 ban-commands 正则声明。
 */
public class VanillaBanSync implements Listener {

    private static final Pattern BAN = Pattern.compile(
            "^/?(?:minecraft:)?ban\\s+(\\S+)(?:\\s+(\\S.*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARDON = Pattern.compile(
            "^/?(?:minecraft:)?pardon\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final String DEFAULT_REASON = "Banned by an operator.";

    private final HwidBanPlugin plugin;
    private final Gson gson = new Gson();

    public VanillaBanSync(HwidBanPlugin plugin) {
        this.plugin = plugin;
    }

    /* ---------- 命令拦截 ---------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        handle(event.getMessage(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        handle(event.getCommand(), event.getSender());
    }

    private void handle(String raw, CommandSender by) {
        String line = raw == null ? "" : raw.trim();
        // 反作弊/处罚插件联动优先: 命中的命令不走 banned-players.json 校验
        // (LiteBans/AdvancedBan 等自建数据库, 不落盘)
        if (acHandle(line, by)) {
            return;
        }
        Matcher m = BAN.matcher(line);
        if (m.matches()) {
            String target = m.group(1);
            if (!validName(target)) {
                return;
            }
            String reason = m.group(2) != null ? m.group(2).trim() : DEFAULT_REASON;
            // 延迟 1 tick: 等原生封禁先落盘 (banned-players.json), 再做联动校验
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> syncBan(target, by, reason, true), 1L);
            return;
        }
        Matcher p = PARDON.matcher(line);
        if (p.matches() && validName(p.group(1))) {
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> syncPardon(p.group(1)), 1L);
        }
    }

    /* ---------- 反作弊处罚联动 (Grim / Vulcan / LiteBans / AdvancedBan ...) ----------
     * 这些插件的自动处罚本质是"执行配置的命令" (如 punishments.yml 里的 ban %player%),
     * 没有封禁事件 API; 因此通过识别它们执行的封禁命令来联动机器码封禁。
     * 只要反作弊处罚命令写成 ban 类, 本联动即自动生效, 无需其他桥接插件。 */

    private volatile List<Pattern> acBanPatterns = List.of();
    private volatile List<Pattern> acUnbanPatterns = List.of();
    private volatile int acPatternHash = -1;

    private void refreshAcPatterns() {
        List<String> banCfg = plugin.getConfig().getStringList("anticheat-sync.ban-commands");
        List<String> unbanCfg = plugin.getConfig().getStringList("anticheat-sync.unban-commands");
        int hash = Objects.hash(banCfg, unbanCfg);
        if (hash != acPatternHash) {
            acPatternHash = hash;
            acBanPatterns = compileAcPatterns(banCfg);
            acUnbanPatterns = compileAcPatterns(unbanCfg);
        }
    }

    private List<Pattern> compileAcPatterns(List<String> regexes) {
        List<Pattern> out = new ArrayList<>();
        for (String r : regexes) {
            try {
                out.add(Pattern.compile(r));
            } catch (Exception ex) {
                plugin.getLogger().warning("[反作弊联动] 无效正则已跳过: " + r + " (" + ex.getMessage() + ")");
            }
        }
        return out;
    }

    /**
     * 尝试按反作弊命令模式处理。返回是否已处理 (处理后不再走原版联动路径, 避免重复)。
     */
    private boolean acHandle(String line, CommandSender by) {
        if (!plugin.getConfig().getBoolean("anticheat-sync.enabled", true)) {
            return false;
        }
        refreshAcPatterns();
        if (acBanPatterns.isEmpty() && acUnbanPatterns.isEmpty()) {
            return false;
        }
        // only-console=true 时仅控制台与 hwidban.admin 管理员触发:
        // 反作弊自动处罚一律走控制台; 普通玩家乱敲 ban 命令不会真正生效, 联动会造成误封
        boolean console = !(by instanceof Player);
        boolean admin = by instanceof Player && ((Player) by).hasPermission("hwidban.admin");
        if (!console && !admin && plugin.getConfig().getBoolean("anticheat-sync.only-console", true)) {
            return false;
        }
        for (Pattern p : acBanPatterns) {
            String[] parsed = parseBanCommand(line, p);
            if (parsed != null) {
                plugin.getServer().getScheduler().runTaskLater(plugin,
                        () -> syncBan(parsed[0], by, parsed[1], false), 1L);
                return true;
            }
        }
        for (Pattern p : acUnbanPatterns) {
            String name = parseTarget(line, p);
            if (name != null) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> acPardon(name), 1L);
                return true;
            }
        }
        return false;
    }

    /** 命令头正则命中后提取目标: 参数里取第一个非 -flag token 作玩家名, 其余作理由。 */
    private String[] parseBanCommand(String line, Pattern head) {
        Matcher m = head.matcher(line);
        if (!m.find()) {
            return null;
        }
        String rest = line.substring(m.end()).trim();
        if (rest.isEmpty()) {
            return null;
        }
        String target = null;
        StringBuilder reason = new StringBuilder();
        for (String t : rest.split("\\s+")) {
            if (target == null) {
                if (t.startsWith("-")) {
                    continue; // LiteBans -s/-p 等开关参数
                }
                target = t;
            } else {
                if (reason.length() > 0) {
                    reason.append(' ');
                }
                reason.append(t);
            }
        }
        if (target == null || !validName(target)) {
            return null;
        }
        return new String[]{target, reason.length() > 0 ? reason.toString() : "反作弊处罚"};
    }

    /** 仅提取目标玩家名 (解封命令用)。 */
    private String parseTarget(String line, Pattern head) {
        Matcher m = head.matcher(line);
        if (!m.find()) {
            return null;
        }
        for (String t : line.substring(m.end()).trim().split("\\s+")) {
            if (t.isEmpty() || t.startsWith("-")) {
                continue;
            }
            return validName(t) ? t : null;
        }
        return null;
    }

    /** 第三方解封命令联动: 同步解除机器码封禁 + 原版封禁。 */
    private void acPardon(String name) {
        if (!plugin.getConfig().getBoolean("sync-vanilla-unban", true)) {
            return;
        }
        int n = plugin.hwidManager().unban(name);
        boolean vanilla = pardonVanilla(name);
        plugin.getLogger().info("[反作弊联动] " + name + " 第三方解封, 已同步解除 "
                + n + " 条机器码封禁" + (vanilla ? " 及原版封禁" : "") + "。");
    }

    private boolean validName(String s) {
        return s != null && !s.isEmpty() && !s.startsWith("@") && s.matches("[A-Za-z0-9_.\\-]+");
    }

    /* ---------- /ban 联动 ---------- */

    private void syncBan(String name, CommandSender executor, String reason, boolean verifyVanilla) {
        // 两条路径各自独立开关: 原版联动看 sync-vanilla-ban, 反作弊联动看 anticheat-sync.enabled
        if (verifyVanilla && !plugin.getConfig().getBoolean("sync-vanilla-ban", true)) {
            return;
        }
        if (!verifyVanilla && !plugin.getConfig().getBoolean("anticheat-sync.enabled", true)) {
            return;
        }
        String by = executor != null ? executor.getName() : "CONSOLE";
        String tag = verifyVanilla ? "[原生联动]" : "[反作弊联动]";
        // 原生路径校验封禁确实落盘 (权限不足的 /ban 不会落盘, 也就不联动);
        // 反作弊路径免校验 (LiteBans/AdvancedBan 等自建数据库, 不写 banned-players.json)
        VanillaBan vb = findVanillaBan(name);
        if (verifyVanilla && vb == null) {
            return;
        }
        HwidManager mgr = plugin.hwidManager();
        Player online = Bukkit.getPlayerExact(name);
        UUID uuid = uuidOf(vb != null ? vb.uuid : null);
        if (uuid == null && online != null) {
            uuid = online.getUniqueId();
        }
        if (uuid == null) {
            uuid = mgr.uuidByName(name);
        }
        if (uuid == null) {
            plugin.getLogger().info(tag + " " + name + " 已被封禁, 但没有机器码档案, 无法联动。");
            if (executor != null) {
                plugin.msg().send(executor, verifyVanilla ? "sync-ban-norecord" : "sync-ac-norecord", "player", name);
            }
            return;
        }
        PlayerProfile prof = mgr.profile(uuid);
        String targetName = online != null ? online.getName()
                : (vb != null && vb.name != null ? vb.name : name);
        List<BanEntry> added = new ArrayList<>();
        if (online != null) {
            String fp = plugin.fingerprints().compute(online);
            mgr.recordFingerprint(uuid, online.getName(), fp);
            added.add(banIfNew(mgr, fp, HwidManager.FINGERPRINT, reason, by, uuid, targetName));
        } else {
            for (String fp : prof.fingerprints) {
                added.add(banIfNew(mgr, fp, HwidManager.FINGERPRINT, reason, by, uuid, targetName));
            }
        }
        if (prof.reportedHwid != null) {
            added.add(banIfNew(mgr, prof.reportedHwid, HwidManager.REPORTED, reason, by, uuid, targetName));
        }
        added.removeIf(e -> e == null);
        if (added.isEmpty()) {
            plugin.getLogger().info(tag + " " + targetName + " 的机器码已在封禁列表, 无需重复添加。");
            if (executor != null) {
                plugin.msg().send(executor, verifyVanilla ? "sync-ban-none" : "sync-ac-none", "player", targetName);
            }
            return;
        }
        int kicked = kickSameMachine(added);
        plugin.getLogger().warning(tag + " " + by + (verifyVanilla ? " 原生封禁 " : " 反作弊封禁 ") + targetName
                + ", 联动封禁机器码 " + added.size() + " 条, 同机踢出 " + kicked + " 人。");
        if (executor != null) {
            plugin.msg().send(executor, verifyVanilla ? "sync-ban-done" : "sync-ac-done", "player", targetName,
                    "count", String.valueOf(added.size()), "kicked", String.valueOf(kicked));
        }
    }

    private BanEntry banIfNew(HwidManager mgr, String hwid, String type, String reason,
                              String by, UUID uuid, String name) {
        if (hwid == null || mgr.isBanned(hwid) != null) {
            return null;
        }
        return mgr.addFor(hwid, type, reason, by, uuid, name);
    }

    /** 封禁落盘后立即踢出在线的同机账号 (机器码或当前指纹命中), 返回踢出人数。 */
    private int kickSameMachine(List<BanEntry> entries) {
        HwidManager mgr = plugin.hwidManager();
        int kicked = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("hwidban.exempt")) {
                continue;
            }
            PlayerProfile prof = mgr.peekProfile(p.getUniqueId());
            BanEntry hit = null;
            if (prof != null && prof.reportedHwid != null) {
                for (BanEntry e : entries) {
                    if (HwidManager.REPORTED.equals(e.type) && e.hwid.equalsIgnoreCase(prof.reportedHwid)) {
                        hit = e;
                        break;
                    }
                }
            }
            if (hit == null) {
                String fp = plugin.fingerprints().compute(p);
                for (BanEntry e : entries) {
                    if (HwidManager.FINGERPRINT.equals(e.type) && e.hwid.equalsIgnoreCase(fp)) {
                        hit = e;
                        break;
                    }
                }
            }
            if (hit != null) {
                p.kick(plugin.msg().kick(p.getName(), hit));
                kicked++;
                plugin.getLogger().warning("[原生联动] 同机账号 " + p.getName() + " 已被一并踢出。");
            }
        }
        return kicked;
    }

    /* ---------- /pardon 联动 ---------- */

    private void syncPardon(String name) {
        if (!plugin.getConfig().getBoolean("sync-vanilla-unban", true)) {
            return;
        }
        int n = plugin.hwidManager().unban(name);
        if (n > 0) {
            plugin.getLogger().info("[原生联动] " + name + " 原生解封, 已同步解除 " + n + " 条机器码封禁。");
        }
    }

    /* ---------- 原版封禁解除 (供 /hwidban unban 联动) ---------- */

    /**
     * 解除玩家名下的原版 (banned-players.json) 封禁。
     * 优先用档案 UUID 构造 PlayerProfile (原版封禁按 UUID 匹配, 最可靠), 拿不到时退回按名匹配。
     *
     * @return 是否确实存在并已解除原版封禁
     */
    public boolean pardonVanilla(String name) {
        if (!plugin.getConfig().getBoolean("sync-vanilla-unban", true)) {
            return false;
        }
        try {
            org.bukkit.BanList<org.bukkit.profile.PlayerProfile> list =
                    Bukkit.getBanList(org.bukkit.BanList.Type.NAME);
            UUID uuid = plugin.hwidManager().uuidByName(name);
            org.bukkit.profile.PlayerProfile prof = uuid != null
                    ? Bukkit.createPlayerProfile(uuid, name)
                    : Bukkit.createPlayerProfile(name);
            if (!list.isBanned(prof)) {
                return false;
            }
            list.pardon(prof);
            plugin.getLogger().info("[原生联动] 已解除 " + name + " 的原版封禁。");
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("[原生联动] 解除原版封禁失败 (" + name + "): " + ex.getMessage());
            return false;
        }
    }

    /* ---------- 启动同步: 已有的原生封禁 → 机器码封禁 ---------- */

    public void syncStartupBans() {
        if (!plugin.getConfig().getBoolean("sync-vanilla-ban", true)) {
            return;
        }
        List<VanillaBan> list = readVanillaBans();
        if (list.isEmpty()) {
            return;
        }
        HwidManager mgr = plugin.hwidManager();
        int converted = 0;
        for (VanillaBan vb : list) {
            UUID uuid = uuidOf(vb.uuid);
            PlayerProfile prof = uuid != null ? mgr.peekProfile(uuid) : null;
            if (prof == null && vb.name != null) {
                UUID byName = mgr.uuidByName(vb.name);
                if (byName != null) {
                    uuid = byName;
                    prof = mgr.peekProfile(byName);
                }
            }
            if (prof == null) {
                continue;
            }
            String reason = vb.reason != null ? vb.reason : DEFAULT_REASON;
            String by = vb.source != null ? vb.source : "CONSOLE";
            String targetName = vb.name != null ? vb.name : "?";
            List<BanEntry> added = new ArrayList<>();
            for (String fp : prof.fingerprints) {
                added.add(banIfNew(mgr, fp, HwidManager.FINGERPRINT, reason, by, uuid, targetName));
            }
            if (prof.reportedHwid != null) {
                added.add(banIfNew(mgr, prof.reportedHwid, HwidManager.REPORTED, reason, by, uuid, targetName));
            }
            added.removeIf(e -> e == null);
            if (!added.isEmpty()) {
                converted += added.size();
                plugin.getLogger().warning("[原生联动] 原生封禁 " + targetName + " 转换为 "
                        + added.size() + " 条机器码封禁。");
            }
        }
        if (converted > 0) {
            plugin.getLogger().warning("[原生联动] 启动同步: 共从原生封禁转换 " + converted + " 条机器码封禁。");
        }
    }

    /* ---------- banned-players.json 读取 ---------- */

    private static final class VanillaBan {
        String uuid;
        String name;
        String source;
        String reason;
    }

    private File vanillaBanFile() {
        File pluginsDir = plugin.getDataFolder().getParentFile();
        return new File(pluginsDir.getParentFile(), "banned-players.json");
    }

    private List<VanillaBan> readVanillaBans() {
        File f = vanillaBanFile();
        if (!f.isFile()) {
            return List.of();
        }
        try {
            List<VanillaBan> list = gson.fromJson(Files.readString(f.toPath(), StandardCharsets.UTF_8),
                    new TypeToken<List<VanillaBan>>() { }.getType());
            return list != null ? list : List.of();
        } catch (Exception ex) {
            plugin.getLogger().warning("[原生联动] 读取 banned-players.json 失败: " + ex.getMessage());
            return List.of();
        }
    }

    private VanillaBan findVanillaBan(String name) {
        for (VanillaBan vb : readVanillaBans()) {
            if (name.equalsIgnoreCase(vb.name)) {
                return vb;
            }
        }
        return null;
    }

    private static UUID uuidOf(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
