package com.mzstd.hxmyproxy.core.model

/** 被监控的常用服务（测 TCP 连接延迟）。 */
data class MonitoredService(val name: String, val host: String, val port: Int = 443)

/** 一次延迟测量结果；[millis] 为 null 表示超时/不可达。 */
data class LatencyResult(val service: MonitoredService, val millis: Long?)

/** 默认监控的常用服务清单（可后续做成可编辑）。 */
val DEFAULT_MONITORED_SERVICES: List<MonitoredService> = listOf(
    MonitoredService("GitHub", "github.com"),
    MonitoredService("Google", "www.google.com"),
    MonitoredService("OpenAI", "api.openai.com"),
    MonitoredService("Claude", "api.anthropic.com"),
    MonitoredService("Cloudflare", "cloudflare.com"),
    MonitoredService("YouTube", "www.youtube.com"),
    MonitoredService("Bing", "www.bing.com"),
    MonitoredService("Wikipedia", "www.wikipedia.org"),
    MonitoredService("X", "x.com"),
    MonitoredService("npm", "registry.npmjs.org"),
    MonitoredService("Hugging Face", "huggingface.co"),
    MonitoredService("Docker Hub", "registry-1.docker.io"),
)
