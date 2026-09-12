package cn.blaze.hwidban.storage;

import cn.blaze.hwidban.hwid.BanEntry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * SQL 存储 (sqlite / mysql): 多台服务器共用同一个库, 封禁全服同步。
 * 连接复用 + 同步访问 (JDBC Connection 非线程安全, 统一加锁), 断线自动重连一次。
 */
public class SqlBanStore implements BanStore {

    private static final String TABLE = "hwidban_bans";

    private final String driverClass;
    private final String url;
    private final String user;
    private final String password;
    private final boolean mysql;
    private Connection conn;

    public SqlBanStore(String driverClass, String url, String user, String password, boolean mysql) throws Exception {
        this.driverClass = driverClass;
        this.url = url;
        this.user = user;
        this.password = password;
        this.mysql = mysql;
        Class.forName(driverClass);
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "hwid VARCHAR(64) PRIMARY KEY,"
                    + "type VARCHAR(16) NOT NULL,"
                    + "reason VARCHAR(255),"
                    + "by_name VARCHAR(64),"
                    + "at BIGINT NOT NULL,"
                    + "player_name VARCHAR(64),"
                    + "uuid VARCHAR(36),"
                    + "expires BIGINT NOT NULL DEFAULT 0)");
        }
    }

    private synchronized Connection conn() throws SQLException {
        if (conn == null) {
            conn = user == null || user.isEmpty()
                    ? DriverManager.getConnection(url)
                    : DriverManager.getConnection(url, user, password);
        }
        return conn;
    }

    /** 执行一次数据库操作, 失败时重连再试一次。 */
    private synchronized <T> T exec(SqlCall<T> call) throws SQLException {
        try {
            return call.run(conn());
        } catch (SQLException first) {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException ignored) {
            }
            conn = null;
            return call.run(conn());
        }
    }

    @FunctionalInterface
    private interface SqlCall<T> {
        T run(Connection c) throws SQLException;
    }

    @Override
    public Map<String, BanEntry> loadAll() throws SQLException {
        return exec(c -> {
            Map<String, BanEntry> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT hwid,type,reason,by_name,at,player_name,uuid,expires FROM " + TABLE);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BanEntry e = new BanEntry();
                    e.hwid = rs.getString(1);
                    e.type = rs.getString(2);
                    e.reason = rs.getString(3);
                    e.by = rs.getString(4);
                    e.at = rs.getLong(5);
                    e.playerName = rs.getString(6);
                    e.uuid = rs.getString(7);
                    e.expires = rs.getLong(8);
                    if (e.hwid != null) {
                        out.put(e.hwid.toLowerCase(Locale.ROOT), e);
                    }
                }
            }
            return out;
        });
    }

    @Override
    public void upsert(BanEntry e) throws SQLException {
        String sql = mysql
                ? "INSERT INTO " + TABLE + " (hwid,type,reason,by_name,at,player_name,uuid,expires)"
                + " VALUES (?,?,?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE type=VALUES(type),reason=VALUES(reason),by_name=VALUES(by_name),"
                + "at=VALUES(at),player_name=VALUES(player_name),uuid=VALUES(uuid),expires=VALUES(expires)"
                : "INSERT INTO " + TABLE + " (hwid,type,reason,by_name,at,player_name,uuid,expires)"
                + " VALUES (?,?,?,?,?,?,?,?)"
                + " ON CONFLICT(hwid) DO UPDATE SET type=excluded.type,reason=excluded.reason,"
                + "by_name=excluded.by_name,at=excluded.at,player_name=excluded.player_name,"
                + "uuid=excluded.uuid,expires=excluded.expires";
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, e.hwid.toLowerCase(Locale.ROOT));
                ps.setString(2, e.type);
                ps.setString(3, e.reason);
                ps.setString(4, e.by);
                ps.setLong(5, e.at);
                ps.setString(6, e.playerName);
                ps.setString(7, e.uuid);
                ps.setLong(8, e.expires);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void delete(String hwid) throws SQLException {
        exec(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + TABLE + " WHERE hwid=?")) {
                ps.setString(1, hwid.toLowerCase(Locale.ROOT));
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void flush() {
        // 写入即落库
    }

    @Override
    public synchronized void close() {
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (SQLException ignored) {
        }
        conn = null;
    }
}
