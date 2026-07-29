package com.mzstd.hxmyproxy.core.stats

/**
 * 流量**离开本机时走的那张网**。历史流量统计的分类维度。
 *
 * 判定在 [com.mzstd.hxmyproxy.core.network.EgressClassifier]：**VPN 优先**——系统 VPN 的
 * `NetworkCapabilities` 同时带 `TRANSPORT_VPN` 与底层物理 transport（如 WIFI），先判物理会把
 * 隧道流量误记成 Wi-Fi 直连，那正是这张表最要区分的两件事。
 *
 * 顺序即落盘槽位（`ordinal * 2 + 0/1` = 上行/下行），**只能追加、不能重排**——重排会让旧文件里的
 * 历史数据串类。
 */
enum class EgressKind {
    /** 经手机上的 VPN 隧道出去（共享出口模式的主路径）。 */
    VPN,

    /** Wi-Fi 物理网卡：规则判 DIRECT 绕过 VPN，或用户手动指定 Wi-Fi 出口。 */
    WIFI,

    /** 蜂窝数据——唯一要花钱的一档，UI 单列一格。 */
    CELLULAR,

    /** 以太网 / USB 网卡。 */
    ETHERNET,

    /** 分类不出来（拿不到 capabilities、或蓝牙等冷门 transport）。 */
    OTHER,
}
