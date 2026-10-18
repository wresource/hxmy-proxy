package com.mzstd.hxmyproxy.ui.theme

import androidx.compose.ui.graphics.Color

/** Approved October prototype palette. Body text and controls use paired M3 colors;
 * semantic red is reserved for errors, and runtime traffic uses restrained blue.
 * The former bright-blue T53 choice had insufficient contrast for normal white text.
 */

val BluePrimaryLight = Color(0xFF2159B8)
val OnBluePrimaryLight = Color(0xFFFFFFFF)
val BlueContainerLight = Color(0xFFDDE8FF)
val OnBlueContainerLight = Color(0xFF193E74)
val BluePrimaryDark = Color(0xFFAFCAFF)
val OnBluePrimaryDark = Color(0xFF102C5E)
val BlueContainerDark = Color(0xFF233D65)
val OnBlueContainerDark = Color(0xFFD8E6FF)

// ---- Secondary（蓝灰，chroma16 低饱和辅助）----
val SecondaryLight = Color(0xFF585E71)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFDCE2F9)
val OnSecondaryContainerLight = Color(0xFF404659)
val SecondaryDark = Color(0xFFC0C6DC)
val OnSecondaryDark = Color(0xFF2A3042)
val SecondaryContainerDark = Color(0xFF404659)
val OnSecondaryContainerDark = Color(0xFFDCE2F9)

// ---- Tertiary（糖果粉 #FF4D8D，保真 palette。亮粉配白字仅 2.6:1，必须用 tone40/80）----
val PinkTertiaryLight = Color(0xFFB90A5A)         // T40
val OnPinkTertiaryLight = Color(0xFFFFFFFF)
val PinkContainerLight = Color(0xFFFFD9E0)        // T90
val OnPinkContainerLight = Color(0xFF8F0043)      // T30
val PinkTertiaryDark = Color(0xFFFFB1C4)          // T80
val OnPinkTertiaryDark = Color(0xFF65002E)        // T20
val PinkContainerDark = Color(0xFF8F0043)         // T30
val OnPinkContainerDark = Color(0xFFFFD9E0)       // T90

// ---- Error（M3 baseline 红）----
val ErrorLight = Color(0xFFA62C32)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDADC)
val OnErrorContainerLight = Color(0xFF8C2028)
val ErrorDark = Color(0xFFFFB4B6)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF522428)
val OnErrorContainerDark = Color(0xFFFFDADC)

// ---- Neutral surface 系（蓝调中性，chroma6；替换旧薄荷底，避免与粉打架）----
val SurfaceLight = Color(0xFFF8F9FF)
val OnSurfaceLight = Color(0xFF18202F)
val SurfaceDimLight = Color(0xFFDAD9E0)           // N87
val SurfaceBrightLight = Color(0xFFF8F9FF)
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)  // N100
val SurfaceContainerLowLight = Color(0xFFFFFFFF)
val SurfaceContainerLight = Color(0xFFEDF1F9)
val SurfaceContainerHighLight = Color(0xFFE7EDF7)
val SurfaceContainerHighestLight = Color(0xFFDDE5F2)
val InverseSurfaceLight = Color(0xFF2F3036)       // N20
val InverseOnSurfaceLight = Color(0xFFF1F0F7)     // N95

val SurfaceDark = Color(0xFF101318)
val OnSurfaceDark = Color(0xFFE3E7F0)
val SurfaceDimDark = Color(0xFF101318)
val SurfaceBrightDark = Color(0xFF38393F)         // N24
val SurfaceContainerLowestDark = Color(0xFF101318)
val SurfaceContainerLowDark = Color(0xFF1B202A)
val SurfaceContainerDark = Color(0xFF1B202A)
val SurfaceContainerHighDark = Color(0xFF242C38)
val SurfaceContainerHighestDark = Color(0xFF2D3747)
val InverseSurfaceDark = Color(0xFFE2E2E9)        // N90
val InverseOnSurfaceDark = Color(0xFF2F3036)      // N20

// ---- NeutralVariant（outline / surfaceVariant，chroma8）----
val SurfaceVariantLight = Color(0xFFE1E2EC)       // NV90
val OnSurfaceVariantLight = Color(0xFF535E70)
val OutlineLight = Color(0xFF757780)              // NV50
val OutlineVariantLight = Color(0xFFC3CAD7)
val SurfaceVariantDark = Color(0xFF44464F)        // NV30
val OnSurfaceVariantDark = Color(0xFFB6BFCE)
val OutlineDark = Color(0xFF8F9099)               // NV60
val OutlineVariantDark = Color(0xFF455064)

// ---- 语义状态色（成功/警告；与主题同 tone 规则生成，视觉重量一致。差=error）----
val SuccessLight = Color(0xFF25623F)
val SuccessContainerLight = Color(0xFFD6EFDD)
val SuccessDark = Color(0xFFA0D7B1)
val SuccessContainerDark = Color(0xFF173C2B)
val WarningLight = Color(0xFF78510B)
val WarningContainerLight = Color(0xFFFFF0D1)
val WarningDark = Color(0xFFF2D08C)
val WarningContainerDark = Color(0xFF423318)

// ---- 状态点专用色（首页运行/停止圆点）：实心圆无需文字对比,用鲜活青春色（Tailwind 系）,
// 比语义 good()/bad()（要兼顾文字可读故偏深）更浅更年轻。运行=鲜绿、停止=珊瑚红。----
val RunningDotLight = Color(0xFF22C55E)           // green-500
val RunningDotDark = Color(0xFF4ADE80)            // green-400
val StoppedDotLight = Color(0xFFF0635A)           // coral
val StoppedDotDark = Color(0xFFFF9B90)            // coral-light

// ---- 头像柔和粉彩色板（服务延迟等字母头像）：6 色低饱和(chroma30)，深浅各一套。
// 替代原先「复用主题 6 对高饱和实色」——那在深色下会出现大面积艳粉/玫红(违背粉≤10%纪律、扎眼花哨)。
// 天蓝/青/薄荷/琥珀/薰衣草/青绿,字母 fg on bg 对比 ~6:1。----
val AvatarBgLight = listOf(0xFFCFE5FF, 0xFFADEEF0, 0xFFC1EEC0, 0xFFFFDBCB, 0xFFE6DEFF, 0xFFB0EFDD).map { Color(it) }
val AvatarFgLight = listOf(0xFF315577, 0xFF125B5E, 0xFF345B38, 0xFF79452A, 0xFF554C79, 0xFF1C5C4E).map { Color(it) }
val AvatarBgDark = listOf(0xFF2A4E6F, 0xFF035457, 0xFF2D5432, 0xFF703E24, 0xFF4E4572, 0xFF125547).map { Color(it) }
val AvatarFgDark = listOf(0xFFCFE5FF, 0xFFADEEF0, 0xFFC1EEC0, 0xFFFFDBCB, 0xFFE6DEFF, 0xFFB0EFDD).map { Color(it) }

// ---- 出口分类色（历史流量统计的堆叠条/图例）：VPN=primary 蓝、蜂窝=tertiary 粉（占比通常极小，
// 正好符合「粉只做点睛」的纪律）、以太网=secondary 蓝灰、其他=outline。只有 Wi-Fi 在既有色板里
// 没有能与蓝区分开的位置，故单独取一支青绿（与蓝差 ~60° 色相，堆叠条相邻两段一眼可辨）。----
val EgressWifiLight = Color(0xFF1F9A8A)
val EgressWifiDark = Color(0xFF5FD3C0)

/** 通知（Notification.setColor）等非 Compose 场景用的品牌蓝 seed（青春天蓝，与 primary 同色相）。 */
const val BRAND_BLUE_ARGB = 0xFF2E90FA.toInt()
