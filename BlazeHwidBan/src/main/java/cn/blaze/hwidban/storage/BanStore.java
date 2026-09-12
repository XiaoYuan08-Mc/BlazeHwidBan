package cn.blaze.hwidban.storage;

import cn.blaze.hwidban.hwid.BanEntry;

import java.util.Map;

/**
 * 封禁数据存储抽象: json = 单机文件 (默认, 不同步), sqlite/mysql = 共享数据库 (多服同步)。
 * 所有实现必须线程安全 (会被异步线程与同步轮询并发调用)。
 */
public interface BanStore {

    /** 读取全部封禁, 键为小写 hwid。 */
    Map<String, BanEntry> loadAll() throws Exception;

    /** 写入/更新一条封禁。 */
    void upsert(BanEntry entry) throws Exception;

    /** 删除一条封禁。 */
    void delete(String hwid) throws Exception;

    /** 停服前落盘 (sql 实现为空操作, 写入即落库)。 */
    void flush();

    /** 释放资源。 */
    void close();
}
