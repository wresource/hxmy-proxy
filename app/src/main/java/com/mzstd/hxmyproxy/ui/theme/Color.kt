package com.mzstd.hxmyproxy.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * hxmy proxy 品牌色板「Candy Azure × 糖果粉」：蓝 #3A7DFF 为 primary（保真色板，
 * 不经 TonalSpot 压灰）、糖果粉 #FF4D8D 为 tertiary 点睛、secondary 为同色相蓝灰。
 *
 * 全部色值由 Google 官方算法（material-color-utilities，2021 spec）生成，
 * on- 配对对比度已逐对验证（均 ≥6.4:1，超 WCAG AA）。使用纪律：
 * - 粉（tertiary）只做点睛（徽标/高亮/强调），占比 ≤10%，大面积留给蓝与中性色；
 * - 危险/停止操作一律 error 红，不用粉（糖果粉与红同为暖色，避免语义混淆）；
 * - 层级用 surfaceContainer 色阶 + 留白表达，少用阴影。
 */

// ---- Primary（蓝，保真 palette：hue/chroma 取自 seed #3A7DFF）----
val BluePrimaryLight = Color(0xFF0057CD)          // P40
val OnBluePrimaryLight = Color(0xFFFFFFFF)
val BlueContainerLight = Color(0xFFDAE2FF)        // P90
val OnBlueContainerLight = Color(0xFF00419D)      // P30
val BluePrimaryDark = Color(0xFFB1C5FF)           // P80
val OnBluePrimaryDark = Color(0xFF002C70)         // P20
val BlueContainerDark = Color(0xFF00419D)         // P30
val OnBlueContainerDark = Color(0xFFDAE2FF)       // P90

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
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF93000A)
val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)

// ---- Neutral surface 系（蓝调中性，chroma6；替换旧薄荷底，避免与粉打架）----
val SurfaceLight = Color(0xFFFAF8FF)              // N98
val OnSurfaceLight = Color(0xFF1A1B21)            // N10
val SurfaceDimLight = Color(0xFFDAD9E0)           // N87
val SurfaceBrightLight = Color(0xFFFAF8FF)        // N98
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)  // N100
val SurfaceContainerLowLight = Color(0xFFF4F3FA)     // N96
val SurfaceContainerLight = Color(0xFFEEEDF4)        // N94
val SurfaceContainerHighLight = Color(0xFFE8E7EF)    // N92
val SurfaceContainerHighestLight = Color(0xFFE2E2E9) // N90
val InverseSurfaceLight = Color(0xFF2F3036)       // N20
val InverseOnSurfaceLight = Color(0xFFF1F0F7)     // N95

val SurfaceDark = Color(0xFF121318)               // N6
val OnSurfaceDark = Color(0xFFE2E2E9)             // N90
val SurfaceDimDark = Color(0xFF121318)            // N6
val SurfaceBrightDark = Color(0xFF38393F)         // N24
val SurfaceContainerLowestDark = Color(0xFF0C0E13)   // N4
val SurfaceContainerLowDark = Color(0xFF1A1B21)      // N10
val SurfaceContainerDark = Color(0xFF1E1F25)         // N12
val SurfaceContainerHighDark = Color(0xFF282A2F)     // N17
val SurfaceContainerHighestDark = Color(0xFF33343A)  // N22
val InverseSurfaceDark = Color(0xFFE2E2E9)        // N90
val InverseOnSurfaceDark = Color(0xFF2F3036)      // N20

// ---- NeutralVariant（outline / surfaceVariant，chroma8）----
val SurfaceVariantLight = Color(0xFFE1E2EC)       // NV90
val OnSurfaceVariantLight = Color(0xFF44464F)     // NV30
val OutlineLight = Color(0xFF757780)              // NV50
val OutlineVariantLight = Color(0xFFC5C6D0)       // NV80
val SurfaceVariantDark = Color(0xFF44464F)        // NV30
val OnSurfaceVariantDark = Color(0xFFC5C6D0)      // NV80
val OutlineDark = Color(0xFF8F9099)               // NV60
val OutlineVariantDark = Color(0xFF44464F)        // NV30

// ---- 语义状态色（成功/警告；与主题同 tone 规则生成，视觉重量一致。差=error）----
val SuccessLight = Color(0xFF1B6D24)
val SuccessContainerLight = Color(0xFFA3F69C)
val SuccessDark = Color(0xFF88D982)
val SuccessContainerDark = Color(0xFF005312)
val WarningLight = Color(0xFF964900)
val WarningContainerLight = Color(0xFFFFDCC7)
val WarningDark = Color(0xFFFFB787)
val WarningContainerDark = Color(0xFF723600)

/** 通知（Notification.setColor）等非 Compose 场景用的品牌蓝 seed。 */
const val BRAND_BLUE_ARGB = 0xFF3A7DFF.toInt()
