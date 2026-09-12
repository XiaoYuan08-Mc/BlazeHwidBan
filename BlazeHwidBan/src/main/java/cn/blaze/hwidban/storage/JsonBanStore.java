package cn.blaze.hwidban.storage;

import cn.blaze.hwidban.hwid.BanEntry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** JSON 文件存储 (bans.json, 单机模式): 整文件异步落盘, 带合并写入去重。 */
public class JsonBanStore implements BanStore {

    private final JavaPlugin plugin;
    private final Path file;
    /** 提供内存中最新封禁集合, 落盘时整体序列化。 */
    private final Supplier<Collection<BanEntry>> source;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Object ioLock = new Object();
    private final AtomicBoolean pending = new AtomicBoolean(false);

    public JsonBanStore(JavaPlugin plugin, File file, Supplier<Collection<BanEntry>> source) {
        this.plugin = plugin;
        this.file = file.toPath();
        this.source = source;
    }

    @Override
    public Map<String, BanEntry> loadAll() throws IOException {
        synchronized (ioLock) {
            Map<String, BanEntry> out = new LinkedHashMap<>();
            if (Files.exists(file)) {
                List<BanEntry> list = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                        new TypeToken<List<BanEntry>>() { }.getType());
                if (list != null) {
                    for (BanEntry e : list) {
                        if (e != null && e.hwid != null) {
                            out.put(e.hwid.toLowerCase(Locale.ROOT), e);
                        }
                    }
                }
            }
            return out;
        }
    }

    @Override
    public void upsert(BanEntry entry) {
        scheduleWrite();
    }

    @Override
    public void delete(String hwid) {
        scheduleWrite();
    }

    @Override
    public void flush() {
        synchronized (ioLock) {
            writeAll();
        }
    }

    @Override
    public void close() {
        // 无需释放资源
    }

    private void scheduleWrite() {
        if (!pending.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            pending.set(false);
            synchronized (ioLock) {
                writeAll();
            }
        });
    }

    private void writeAll() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(new ArrayList<>(source.get())), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().severe("保存 bans.json 失败: " + ex.getMessage());
        }
    }
}
