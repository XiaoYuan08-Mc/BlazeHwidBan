package cn.blaze.hwidban;

import cn.blaze.hwidban.command.HwidBanCommand;
import cn.blaze.hwidban.fingerprint.FingerprintService;
import cn.blaze.hwidban.hwid.HwidManager;
import cn.blaze.hwidban.listener.ClientGuardListener;
import cn.blaze.hwidban.listener.HwidMessageListener;
import cn.blaze.hwidban.listener.JoinListener;
import cn.blaze.hwidban.listener.VanillaBanSync;
import cn.blaze.hwidban.util.Msg;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** BlazeHwidBan 主类。 */
public final class HwidBanPlugin extends JavaPlugin {

    private HwidManager hwidManager;
    private FingerprintService fingerprintService;
    private Msg msg;
    private VanillaBanSync vanillaSync;
    private ClientGuardListener clientGuard;
    private String channel = "blaze:hwid";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mergeConfigDefaults();
        FileConfiguration c = getConfig();
        if ("auto".equalsIgnoreCase(c.getString("salt", "auto"))) {
            String salt = randomHex(32);
            c.set("salt", salt);
            saveConfig();
            getLogger().info("已自动生成指纹盐值并写入 config.yml");
        }
        reloadServices();
        hwidManager = new HwidManager(this);
        hwidManager.load();
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);
        clientGuard = new ClientGuardListener(this);
        getServer().getPluginManager().registerEvents(clientGuard, this);
        VanillaBanSync vanillaSync = new VanillaBanSync(this);
        this.vanillaSync = vanillaSync;
        getServer().getPluginManager().registerEvents(vanillaSync, this);
        vanillaSync.syncStartupBans();
        getServer().getMessenger().registerIncomingPluginChannel(this, channel, new HwidMessageListener(this));
        PluginCommand cmd = getCommand("hwidban");
        if (cmd != null) {
            HwidBanCommand exec = new HwidBanCommand(this);
            cmd.setExecutor(exec);
            cmd.setTabCompleter(exec);
        }
        // 定时清理过期临时封禁: 首次 1 分钟后, 此后每 10 分钟
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            int n = hwidManager.sweepExpired();
            if (n > 0) {
                getLogger().info("定时清理: 已移除 " + n + " 条过期临时封禁。");
            }
        }, 20L * 60, 20L * 600);
        getLogger().info("BlazeHwidBan 已启用 (兼容 Paper 1.21.x+)");
    }

    @Override
    public void onDisable() {
        if (hwidManager != null) {
            hwidManager.flush();
        }
    }

    /**
     * 老版本升级兼容: saveDefaultConfig 不会覆盖已存在的 config.yml, 新版本新增的
     * 配置项与消息键会缺失。此处把内置默认配置合并进现有文件 (只补缺失键, 不动
     * 已有值与盐值), 使老安装无需手动迁移。
     */
    private void mergeConfigDefaults() {
        try (InputStream in = getResource("config.yml")) {
            if (in == null) {
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            FileConfiguration cfg = getConfig();
            // 注意: 必须在 setDefaults 之前检测 (isSet 会把默认值也当作已设置)
            boolean missing = false;
            for (String key : defaults.getKeys(true)) {
                if (!cfg.isSet(key)) {
                    missing = true;
                    break;
                }
            }
            cfg.setDefaults(defaults);
            cfg.options().copyDefaults(true);
            if (missing) {
                saveConfig();
                getLogger().info("检测到 config.yml 缺少新版本配置项, 已自动补全 (盐值与已有数据不变)。");
            }
        } catch (Exception e) {
            getLogger().warning("补全 config.yml 失败: " + e.getMessage());
        }
    }

    /** 读取配置并重建服务, 供启动与 /hwidban reload 使用。 */
    public void reloadServices() {
        reloadConfig();
        FileConfiguration c = getConfig();
        channel = c.getString("channel", "blaze:hwid");
        fingerprintService = new FingerprintService(
                c.getString("salt", "auto"),
                c.getBoolean("fingerprint.include-brand", true),
                c.getBoolean("fingerprint.include-locale", true),
                c.getBoolean("fingerprint.include-settings", true),
                c.getBoolean("fingerprint.include-channels", true));
        if (msg == null) {
            msg = new Msg(this);
        } else {
            msg.reload();
        }
    }

    private static String randomHex(int len) {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append("0123456789abcdef".charAt(r.nextInt(16)));
        }
        return sb.toString();
    }

    public HwidManager hwidManager() {
        return hwidManager;
    }

    /** 原版封禁联动服务 (供命令层调用 pardonVanilla 等)。 */
    public VanillaBanSync vanillaSync() {
        return vanillaSync;
    }

    /** 黑客端识别 (client-guard) 监听器, 供 mod 上报模组列表时调用。 */
    public ClientGuardListener clientGuard() {
        return clientGuard;
    }

    public FingerprintService fingerprints() {
        return fingerprintService;
    }

    public Msg msg() {
        return msg;
    }

    public String channel() {
        return channel;
    }
}
