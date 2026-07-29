package com.mzstd.hxmyproxy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mzstd.hxmyproxy.core.stats.EgressKind

/**
 * 出口分类色（历史流量统计的堆叠条与图例）。四档必须**互相可辨**且不抢主色——堆叠条上它们是
 * 紧挨着的相邻段，色相太近就等于没分类。
 *
 * 取色理由见 [Color.kt] 的出口色段：VPN 用 primary（它是主路径，占比通常 80%+，理应是主色）、
 * 蜂窝用 tertiary 粉（占比小、要显眼，符合「粉只做点睛 ≤10%」的纪律）、以太网用 secondary 蓝灰、
 * 其他用 outline；只有 Wi-Fi 在既有色板里没有能与蓝拉开距离的位置，单独取青绿。
 */
@Composable
fun egressColor(kind: EgressKind): Color = when (kind) {
    EgressKind.VPN -> MaterialTheme.colorScheme.primary
    EgressKind.WIFI -> if (LocalDarkTheme.current) EgressWifiDark else EgressWifiLight
    EgressKind.CELLULAR -> MaterialTheme.colorScheme.tertiary
    EgressKind.ETHERNET -> MaterialTheme.colorScheme.secondary
    EgressKind.OTHER -> MaterialTheme.colorScheme.outline
}
