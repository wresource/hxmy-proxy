package com.mzstd.hxmyproxy.core.model

import java.net.InetAddress

/**
 * V1 最小客户端会话视图（按来源 IP 聚合）。
 * 富统计（实时速率、历史、每客户端限速）延后到 V1.2。
 */
data class ClientSession(
    val clientIp: InetAddress,
    val interfaceId: String,
    val activeConnections: Int = 0,
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val lastSeenAtEpochMs: Long = 0,
    val blocked: Boolean = false,
)
