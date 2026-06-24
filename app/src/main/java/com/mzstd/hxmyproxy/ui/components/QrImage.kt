package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder

/**
 * 把文本编码为二维码并用 Compose Canvas 绘制（白底黑点，含 quiet zone 边距）。
 *
 * 纯本地、不联网。编码失败（空串 / 内容超出 QR 容量）时只画白底、不崩溃。
 * 用 zxing 的模块级 [ByteMatrix]（如 25×25）而非像素位图，绘制开销小。
 */
@Composable
fun QrImage(
    content: String,
    modifier: Modifier = Modifier,
    sizeDp: Int = 220,
) {
    // 仅当内容变化才重新编码；matrix 为 null 表示编码失败（画纯白兜底）。
    val matrix = remember(content) { encodeModules(content) }
    Canvas(modifier.size(sizeDp.dp)) {
        drawRect(Color.White, size = size)
        val m = matrix ?: return@Canvas
        val n = m.width
        if (n <= 0) return@Canvas
        val margin = 4                       // 标准 quiet zone（4 模块）
        val cell = size.width / (n + margin * 2)
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (m.get(x, y).toInt() == 1) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset((x + margin) * cell, (y + margin) * cell),
                        // +0.6 轻微外扩，消除相邻模块间的反锯齿白缝。
                        size = Size(cell + 0.6f, cell + 0.6f),
                    )
                }
            }
        }
    }
}

private fun encodeModules(content: String): ByteMatrix? {
    if (content.isBlank()) return null
    return try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        Encoder.encode(content, ErrorCorrectionLevel.M, hints).matrix
    } catch (e: Exception) {
        null
    }
}
