package cn.blaze.hwidban.hwid;

/** 一条机器码封禁记录 (由 Gson 序列化到 bans.json)。 */
public class BanEntry {

    /** 机器码 (64 位十六进制 SHA-256, 统一小写)。 */
    public String hwid;
    /** FINGERPRINT = 客户端指纹 / REPORTED = 客户端上报 / MANUAL = 手动封禁。 */
    public String type;
    public String reason;
    public String by;
    public long at;
    public String playerName;
    public String uuid;
    /** 过期时间戳 (毫秒), 0 = 永久。 */
    public long expires;
}
