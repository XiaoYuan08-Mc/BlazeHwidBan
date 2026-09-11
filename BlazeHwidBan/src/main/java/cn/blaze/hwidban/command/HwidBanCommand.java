package cn.blaze.hwidban.command;

import cn.blaze.hwidban.HwidBanPlugin;
import cn.blaze.hwidban.hwid.BanEntry;
import cn.blaze.hwidban.hwid.HwidManager;
import cn.blaze.hwidban.hwid.PlayerProfile;
import cn.blaze.hwidban.util.Hashing;
import cn.blaze.hwidban.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** /hwidban 命令: ban / banhwid / unban / check / list / reload / info。 */
public class HwidBanCommand implements TabExecutor {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int PAGE_SIZE = 8;

    private final HwidBanPlugin plugin;

    public HwidBanCommand(HwidBanPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hwidban.admin")) {
            plugin.msg().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "ban" -> ban(sender, args);
            case "tempban" -> tempban(sender, args);
            case "banhwid" -> banhwid(sender, args);
            case "unban" -> unban(sender, args);
            case "check" -> check(sender, args);
            case "alt" -> alt(sender, args);
            case "list" -> list(sender, args);
            case "reload" -> {
                plugin.reloadServices();
                plugin.msg().send(sender, "reloaded");
            }
            case "info" -> plugin.msg().send(sender, "info",
                    "version", plugin.getPluginMeta().getVersion());
            default -> help(sender);
        }
        return true;
    }

    private void ban(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.msg().send(sender, "usage-ban");
            return;
        }
        String reason = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "未指定";
        doBan(sender, args[1], reason, 0L);
    }

    private void tempban(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.msg().send(sender, "usage-tempban");
            return;
        }
        long ms = parseDuration(args[2]);
        if (ms <= 0) {
            plugin.msg().send(sender, "invalid-duration");
            return;
        }
        String reason = args.length >= 4 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "未指定";
        doBan(sender, args[1], reason, System.currentTimeMillis() + ms);
    }

    /** 时长解析: 7d / 12h / 30m, 可组合 (1d12h), 纯数字按天。 */
    static long parseDuration(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)([dhm])").matcher(s.toLowerCase(Locale.ROOT));
        long ms = 0;
        int used = 0;
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            ms += switch (m.group(2)) {
                case "d" -> n * 86400000L;
                case "h" -> n * 3600000L;
                default -> n * 60000L;
            };
            used += m.group(1).length() + 1;
        }
        if (used == 0) {
            try {
                return Long.parseLong(s) * 86400000L;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return used == s.length() && ms > 0 ? ms : -1;
    }

    private void doBan(CommandSender sender, String name, String reason, long expires) {
        HwidManager mgr = plugin.hwidManager();
        List<BanEntry> added = new ArrayList<>();
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            String fp = plugin.fingerprints().compute(online);
            mgr.recordFingerprint(online.getUniqueId(), online.getName(), fp);
            added.add(mgr.addFor(fp, HwidManager.FINGERPRINT, reason, sender.getName(),
                    online.getUniqueId(), online.getName(), expires));
            String reported = mgr.profile(online.getUniqueId()).reportedHwid;
            if (reported != null) {
                added.add(mgr.addFor(reported, HwidManager.REPORTED, reason, sender.getName(),
                        online.getUniqueId(), online.getName(), expires));
            }
        } else {
            UUID uuid = mgr.uuidByName(name);
            if (uuid == null) {
                plugin.msg().send(sender, "no-record", "player", name);
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(uuid);
            PlayerProfile prof = mgr.profile(uuid);
            for (String fp : prof.fingerprints) {
                added.add(mgr.addFor(fp, HwidManager.FINGERPRINT, reason, sender.getName(),
                        uuid, target.getName(), expires));
            }
            if (prof.reportedHwid != null) {
                added.add(mgr.addFor(prof.reportedHwid, HwidManager.REPORTED, reason, sender.getName(),
                        uuid, target.getName(), expires));
            }
            if (added.isEmpty()) {
                plugin.msg().send(sender, "no-record", "player", name);
                return;
            }
        }
        if (expires > 0) {
            plugin.msg().send(sender, "tempban-ok", "player", name,
                    "count", String.valueOf(added.size()),
                    "until", DATE.format(java.time.Instant.ofEpochMilli(expires)), "reason", reason);
        } else {
            plugin.msg().send(sender, "banned", "player", name,
                    "count", String.valueOf(added.size()), "reason", reason);
        }
    }

    private void banhwid(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.msg().send(sender, "usage-banhwid");
            return;
        }
        String hwid = args[1].toLowerCase(Locale.ROOT);
        if (!Hashing.isHash(hwid)) {
            plugin.msg().send(sender, "invalid-hwid");
            return;
        }
        String reason = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "未指定";
        plugin.hwidManager().add(hwid, HwidManager.MANUAL, reason, sender, null);
        plugin.msg().send(sender, "banhwid-ok", "hwid", hwid);
    }

    private void unban(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.msg().send(sender, "usage-unban");
            return;
        }
        List<BanEntry> removed = plugin.hwidManager().unbanEntries(args[1]);
        if (removed.isEmpty()) {
            plugin.msg().send(sender, "unban-none");
        } else {
            plugin.msg().send(sender, "unban-ok", "count", String.valueOf(removed.size()));
        }
        // 原版联动: 插件解封后若 banned-players.json 仍有记录, 玩家依然进不来, 一并解除
        Set<String> names = new LinkedHashSet<>();
        if (removed.isEmpty()) {
            if (args[1].matches("[A-Za-z0-9_.\\-]+")) {
                names.add(args[1]); // 无机器码记录时按玩家名尝试解原版封禁
            }
        } else {
            for (BanEntry e : removed) {
                if (e.playerName != null && !e.playerName.isBlank()) {
                    names.add(e.playerName);
                }
            }
        }
        for (String name : names) {
            if (plugin.vanillaSync().pardonVanilla(name)) {
                plugin.msg().send(sender, "unban-vanilla", "player", name);
            }
        }
    }

    private void check(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.msg().send(sender, "usage-check");
            return;
        }
        Msg m = plugin.msg();
        HwidManager mgr = plugin.hwidManager();
        PlayerProfile prof;
        Player online = Bukkit.getPlayerExact(args[1]);
        if (online != null) {
            String fp = plugin.fingerprints().compute(online);
            mgr.recordFingerprint(online.getUniqueId(), online.getName(), fp);
            prof = mgr.profile(online.getUniqueId());
        } else {
            UUID uuid = mgr.uuidByName(args[1]);
            if (uuid == null) {
                m.send(sender, "no-record", "player", args[1]);
                return;
            }
            prof = mgr.profile(uuid);
        }
        m.raw(sender, m.format(m.get("check-header"), "player", args[1]));
        if (prof.fingerprints.isEmpty() && prof.reportedHwid == null) {
            m.send(sender, "check-none");
            return;
        }
        for (String fp : prof.fingerprints) {
            boolean banned = mgr.isBanned(fp) != null;
            m.raw(sender, m.format(m.get("check-line"), "hwid", fp,
                    "status", m.get(banned ? "status-banned" : "status-clean")));
        }
        if (prof.reportedHwid != null) {
            boolean banned = mgr.isBanned(prof.reportedHwid) != null;
            m.raw(sender, m.format(m.get("check-reported"), "hwid", prof.reportedHwid,
                    "status", m.get(banned ? "status-banned" : "status-clean")));
        }
    }

    /** /hwidban alt: 列出与目标玩家同机 (相同上报机器码) 或同指纹的其他账号。 */
    private void alt(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.msg().send(sender, "usage-alt");
            return;
        }
        Msg m = plugin.msg();
        HwidManager mgr = plugin.hwidManager();
        UUID self;
        PlayerProfile prof;
        Player online = Bukkit.getPlayerExact(args[1]);
        if (online != null) {
            String fp = plugin.fingerprints().compute(online);
            mgr.recordFingerprint(online.getUniqueId(), online.getName(), fp);
            self = online.getUniqueId();
            prof = mgr.profile(self);
        } else {
            self = mgr.uuidByName(args[1]);
            if (self == null) {
                m.send(sender, "no-record", "player", args[1]);
                return;
            }
            prof = mgr.profile(self);
        }
        m.raw(sender, m.format(m.get("alt-header"), "player", args[1]));

        List<Map.Entry<UUID, PlayerProfile>> machine = new ArrayList<>();
        for (Map.Entry<UUID, PlayerProfile> e : mgr.allProfileEntries()) {
            if (prof.reportedHwid != null && prof.reportedHwid.equalsIgnoreCase(e.getValue().reportedHwid)
                    && !e.getKey().equals(self)) {
                machine.add(e);
            }
        }
        if (prof.reportedHwid != null) {
            m.raw(sender, m.format(m.get("alt-machine"),
                    "hwid", prof.reportedHwid.substring(0, 8) + "…",
                    "count", String.valueOf(machine.size())));
            for (Map.Entry<UUID, PlayerProfile> e : machine) {
                m.raw(sender, m.format(m.get("alt-line"),
                        "player", e.getValue().name == null ? "?" : e.getValue().name,
                        "seen", e.getValue().lastSeen > 0 ? DATE.format(Instant.ofEpochMilli(e.getValue().lastSeen)) : "-"));
            }
        }

        Set<String> fps = new HashSet<>(prof.fingerprints);
        java.util.Set<UUID> machineIds = new java.util.HashSet<>();
        machine.forEach(e -> machineIds.add(e.getKey()));
        List<Map.Entry<UUID, PlayerProfile>> fpHit = new ArrayList<>();
        for (Map.Entry<UUID, PlayerProfile> e : mgr.allProfileEntries()) {
            if (e.getKey().equals(self) || machineIds.contains(e.getKey())) {
                continue;
            }
            long shared = e.getValue().fingerprints.stream().filter(fps::contains).count();
            if (shared > 0) {
                fpHit.add(e);
            }
        }
        if (!fpHit.isEmpty()) {
            m.raw(sender, m.get("alt-fp-header"));
            for (Map.Entry<UUID, PlayerProfile> e : fpHit) {
                m.raw(sender, m.format(m.get("alt-fp-line"),
                        "player", e.getValue().name == null ? "?" : e.getValue().name,
                        "seen", e.getValue().lastSeen > 0 ? DATE.format(Instant.ofEpochMilli(e.getValue().lastSeen)) : "-"));
            }
        }
        if (machine.isEmpty() && fpHit.isEmpty()) {
            m.send(sender, "alt-none");
        }
    }

    private void list(CommandSender sender, String[] args) {
        List<BanEntry> all = plugin.hwidManager().listBans();
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        page = Math.min(Math.max(page, 1), pages);
        Msg m = plugin.msg();
        m.raw(sender, m.format(m.get("list-header"), "page", String.valueOf(page), "pages", String.valueOf(pages)));
        all.subList((page - 1) * PAGE_SIZE, Math.min(page * PAGE_SIZE, all.size()))
                .forEach(e -> m.raw(sender, m.format(m.get("ban-line"),
                        "hwid", e.hwid, "type", Msg.typeCn(e.type),
                        "player", e.playerName == null ? "-" : e.playerName,
                        "reason", e.reason == null ? "-" : e.reason, "by", e.by,
                        "expires", e.expires > 0 ? "至 " + DATE.format(Instant.ofEpochMilli(e.expires)) : "永久",
                        "date", DATE.format(Instant.ofEpochMilli(e.at)))));
    }

    private void help(CommandSender sender) {
        Msg m = plugin.msg();
        m.raw(sender, "<aqua>BlazeHwidBan</aqua> <gray>- 机器码(客户端指纹)封禁</gray>");
        m.raw(sender, "<gray>/hwidban ban 玩家名 [理由] <white>-</white> 封禁该玩家当前与历史机器码</gray>");
        m.raw(sender, "<gray>/hwidban tempban 玩家名 时长 [理由] <white>-</white> 临时封禁 (7d/12h/30m, 可组合)</gray>");
        m.raw(sender, "<gray>/hwidban banhwid 机器码 [理由] <white>-</white> 封禁指定机器码</gray>");
        m.raw(sender, "<gray>/hwidban unban 机器码前缀或玩家名 <white>-</white> 解封</gray>");
        m.raw(sender, "<gray>/hwidban check 玩家名 <white>-</white> 查看机器码记录</gray>");
        m.raw(sender, "<gray>/hwidban alt 玩家名 <white>-</white> 查询同机/同指纹关联账号</gray>");
        m.raw(sender, "<gray>/hwidban list [页码] | reload | info</gray>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("hwidban.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return Stream.of("ban", "tempban", "banhwid", "unban", "check", "alt", "list", "reload", "info")
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("list")) {
                return List.of("1");
            }
            if (sub.equals("tempban")) {
                return List.of("7d", "12h", "30m", "1d12h");
            }
            if (sub.equals("ban") || sub.equals("check") || sub.equals("unban") || sub.equals("alt")) {
                Set<String> names = Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName).collect(Collectors.toCollection(LinkedHashSet::new));
                if (!sub.equals("ban")) {
                    plugin.hwidManager().allProfiles().forEach(p -> {
                        if (p.name != null) {
                            names.add(p.name);
                        }
                    });
                }
                return names.stream()
                        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .sorted().collect(Collectors.toList());
            }
        }
        return List.of();
    }
}
