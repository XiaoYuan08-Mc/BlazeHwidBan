package cn.blaze.hwidmod;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 机器码采集: 组合操作系统级稳定标识后计算 SHA-256。
 * 只输出哈希, 原始硬件信息不出本机。
 *
 * 标识来源:
 * - Windows: 注册表 MachineGuid (主), 失败回退 csproduct UUID
 * - Linux:   /etc/machine-id 或 /var/lib/dbus/machine-id
 * - macOS:   IOPlatformUUID
 * - 所有平台: 非回环网卡的 MAC 地址 (排序后合并)
 */
public final class HwidCollector {

    private static CompletableFuture<String> future;

    private HwidCollector() {
    }

    /**
     * 异步获取机器码。首次调用启动采集线程, 之后复用同一结果。
     * 采集失败 (结果为 null) 时会在下次进服自动重试。
     * 回调可能在不同线程触发, 调用方需自行切回主线程。
     */
    public static synchronized CompletableFuture<String> request() {
        if (future != null && future.isDone() && future.join() == null) {
            future = null; // 上次采集失败, 允许重试
        }
        if (future == null) {
            future = CompletableFuture.supplyAsync(HwidCollector::compute);
        }
        return future;
    }

    private static String compute() {
        try {
            List<String> parts = new ArrayList<>();
            String uuid = machineUuid();
            if (uuid != null && !uuid.isBlank()) {
                parts.add("uuid=" + uuid.trim().toLowerCase());
            }
            String macs = macAddresses();
            if (!macs.isEmpty()) {
                parts.add("macs=" + macs);
            }
            parts.add("os=" + System.getProperty("os.name", "unknown"));
            if (parts.size() <= 1) {
                return null; // 拿不到任何稳定标识, 宁可不报
            }
            return sha256(String.join("|", parts));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String machineUuid() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                String out = exec("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid");
                if (out != null) {
                    for (String line : out.split("\\R")) {
                        if (line.contains("MachineGuid")) {
                            String[] t = line.trim().split("\\s+");
                            return t[t.length - 1];
                        }
                    }
                }
                out = exec("powershell", "-NoProfile", "-Command",
                        "(Get-CimInstance Win32_ComputerSystemProduct).UUID");
                if (out != null) {
                    String v = out.trim();
                    if (!v.isEmpty() && !"03000200-0400-0500-0006-000700080009".equals(v)) {
                        return v;
                    }
                }
                return null;
            }
            if (os.contains("linux")) {
                for (String p : List.of("/etc/machine-id", "/var/lib/dbus/machine-id")) {
                    File f = new File(p);
                    if (f.canRead()) {
                        return Files.readString(f.toPath()).trim();
                    }
                }
                return null;
            }
            if (os.contains("mac") || os.contains("darwin")) {
                String out = exec("ioreg", "-rd1", "-c", "IOPlatformExpertDevice");
                if (out != null) {
                    for (String line : out.split("\\R")) {
                        if (line.contains("IOPlatformUUID")) {
                            String[] t = line.split("\"");
                            return t.length >= 4 ? t[3] : null;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String macAddresses() {
        List<String> macs = new ArrayList<>();
        try {
            for (java.net.NetworkInterface nif : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (nif.isLoopback() || nif.isVirtual()) {
                    continue;
                }
                byte[] ha = nif.getHardwareAddress();
                if (ha == null || ha.length != 6) {
                    continue;
                }
                StringBuilder sb = new StringBuilder(17);
                for (byte b : ha) {
                    sb.append(String.format("%02x", b));
                }
                macs.add(sb.toString());
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(macs);
        return String.join(",", macs);
    }

    private static String exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor(5, TimeUnit.SECONDS);
            p.destroyForcibly();
            return new String(out, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
