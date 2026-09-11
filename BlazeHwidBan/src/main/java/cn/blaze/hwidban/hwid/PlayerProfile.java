package cn.blaze.hwidban.hwid;

import java.util.ArrayList;
import java.util.List;

/** 每个玩家的机器码档案: 名字、历史客户端指纹、客户端上报的真实 HWID。 */
public class PlayerProfile {

    public String name;
    /** 配套客户端模组上报的机器码, 原版客户端为 null。 */
    public String reportedHwid;
    /** 当前机器码首次上报时间 (毫秒), strict 模式用它判定同机主号; 0 = 旧数据/未上报。 */
    public long hwidSince;
    /** 最近一次进服采集时间 (毫秒)。 */
    public long lastSeen;
    /** 最近一次收到机器码上报的时间 (毫秒), 用于判断是否安装配套客户端。 */
    public long lastReportAt;
    /** 历次进服采集到的客户端指纹 (最多保留 8 条)。 */
    public List<String> fingerprints = new ArrayList<>();
}
