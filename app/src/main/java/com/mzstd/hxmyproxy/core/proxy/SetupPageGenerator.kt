package com.mzstd.hxmyproxy.core.proxy

import java.util.UUID

/**
 * 扫码配置的落地页（HTML）与 Apple 描述文件（.mobileconfig）纯生成器——无副作用、可单测。
 *
 * 设计要点：
 * - 所有产物**自包含、不引任何外部资源**（内联 CSS/JS），符合「纯本地、不联网」。
 * - 系统层面**没有「扫码即自动设代理」的通道**，故落地页给的是「最省事的半自动路径」：
 *   Apple → 描述文件（需输入 Wi-Fi 名以绑定网络）；Windows/Android → PAC 地址 + 图文。
 * - 标识（UUID）用 base 确定性派生（[stableUuid]），不依赖随机数/时间，保证可复现。
 */
object SetupPageGenerator {

    enum class Platform { APPLE, WINDOWS, OTHER }

    fun detectPlatform(userAgent: String): Platform {
        val ua = userAgent.lowercase()
        return when {
            "iphone" in ua || "ipad" in ua || "macintosh" in ua || "mac os" in ua -> Platform.APPLE
            "windows" in ua -> Platform.WINDOWS
            else -> Platform.OTHER
        }
    }

    /**
     * 落地页 HTML。
     * @param base 形如 `http://192.168.1.34:8899`（无尾斜杠）——扫码设备正是连到此地址。
     */
    fun html(base: String, userAgent: String, manualProxy: Boolean = true): String {
        val pac = "$base/proxy.pac"
        val pacEsc = pac.htmlEscape()
        val platform = detectPlatform(userAgent)
        val primary = when (platform) {
            Platform.APPLE -> appleSection(pacEsc, manualProxy)
            Platform.WINDOWS -> windowsSection(pacEsc)
            Platform.OTHER -> androidSection(pacEsc)
        }
        // 始终附「其他系统」可展开，避免误判 UA 时无路可走。
        val others = buildString {
            append("<details><summary>其他系统的设置方法</summary>")
            if (platform != Platform.APPLE) append(appleSection(pacEsc, manualProxy))
            if (platform != Platform.WINDOWS) append(windowsSection(pacEsc))
            if (platform != Platform.OTHER) append(androidSection(pacEsc))
            append("</details>")
        }
        return """<!DOCTYPE html>
<html lang="zh"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>hxmy proxy · 配置</title>
<style>
*{box-sizing:border-box}
body{margin:0;padding:20px;font:16px/1.6 -apple-system,system-ui,"PingFang SC","Microsoft YaHei",sans-serif;color:#1c1b1f;background:#fafafa}
.wrap{max-width:560px;margin:0 auto}
h1{font-size:22px;margin:0 0 4px}
.sub{color:#666;margin:0 0 20px}
.card{background:#fff;border:1px solid #e5e5e5;border-radius:14px;padding:18px;margin:0 0 14px}
.pac{display:flex;gap:8px;align-items:center;background:#f1f3f5;border-radius:10px;padding:10px 12px;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:14px;word-break:break-all}
.btn{display:inline-block;background:#006a64;color:#fff;text-decoration:none;border:0;border-radius:10px;padding:11px 16px;font-size:15px;cursor:pointer}
.btn.sec{background:#e7f1f0;color:#006a64}
ol{margin:8px 0 0;padding-left:22px}li{margin:4px 0}
input{width:100%;padding:11px 12px;border:1px solid #ccc;border-radius:10px;font-size:15px;margin:8px 0}
details{margin-top:8px}summary{cursor:pointer;color:#006a64}
.tag{font-size:13px;color:#888}
</style></head>
<body><div class="wrap">
<h1>把这台设备接入 hxmy proxy</h1>
<p class="sub">借旁边那台手机的网络上网。按下面的步骤设置一次即可。</p>
$primary
<div class="card">
<p class="tag">通用：自动配置（PAC）地址，复制到系统代理「自动/脚本」一栏</p>
<div class="pac"><span id="pac">$pacEsc</span></div>
</div>
$others
</div></body></html>"""
    }

    private fun appleSection(pacEsc: String, manualProxy: Boolean): String {
        // manualProxy=true(已开 HTTP 代理)→ 描述文件用 Manual HTTP，装上即生效、不 fetch PAC，iPhone 最稳。
        // manualProxy=false(仅 SOCKS)→ 描述文件回退 Auto/PAC，提示用户开 HTTP 代理可获最稳体验。
        val effect = if (manualProxy)
            "<li>装好后该 Wi-Fi 立即经 hxmy proxy 上网，无需额外步骤（仅对这个 Wi-Fi 生效）。</li>"
        else
            "<li>装好后该 Wi-Fi 会自动经 hxmy proxy 上网（仅对这个 Wi-Fi 生效）。</li>"
        val hint = if (manualProxy) ""
            else "<p class=\"tag\">提示：在 hxmy 开启「HTTP 代理」后再扫码，iPhone 体验最稳（免 PAC 拉取）。</p>"
        return """
<div class="card">
<b>iPhone / iPad / Mac</b>
<p>① 填入你当前连接的 Wi-Fi 名称，② 下载并安装配置文件：</p>
<input id="ssid" placeholder="你的 Wi-Fi 名称（区分大小写）" autocapitalize="off" autocorrect="off">
<a id="dl" class="btn" href="#" onclick="return go()">下载配置文件</a>
<ol>
<li>下载后：设置 → 通用 → <b>VPN 与设备管理</b> → 安装该描述文件 → 输入锁屏密码确认。</li>
$effect
</ol>
$hint
<p class="tag">未签名会显示「未验证」，可放心安装；也可改用下方 PAC 地址手动设置。</p>
<script>
function go(){var s=document.getElementById('ssid').value.trim();
if(!s){alert('请先填写 Wi-Fi 名称');return false;}
document.getElementById('dl').setAttribute('href','/hxmy.mobileconfig?ssid='+encodeURIComponent(s));
return true;}
</script>
</div>"""
    }

    private fun windowsSection(pacEsc: String): String = """
<div class="card">
<b>Windows 电脑</b>
<ol>
<li>设置 → 网络和 Internet → <b>代理</b>。</li>
<li>「使用安装脚本」打开，地址填：<code>$pacEsc</code>，保存。</li>
<li>打开网页验证即可。</li>
</ol>
</div>"""

    private fun androidSection(pacEsc: String): String = """
<div class="card">
<b>安卓 / 其他</b>
<ol>
<li>设置 → WiFi → 长按当前网络 → 修改 → 高级 → 代理：<b>自动配置（PAC）</b>。</li>
<li>PAC 地址填：<code>$pacEsc</code>，保存。</li>
<li>打开网页验证即可。</li>
</ol>
</div>"""

    /**
     * Apple 配置描述文件（`com.apple.wifi.managed` Wi-Fi 载荷，绑定 [ssid]）。
     * - [manualHttp] 非空（host, httpPort）→ **Manual HTTP 代理**：iOS 不 fetch PAC，最稳
     *   （grounded：iOS Wi-Fi Manual 只支持 HTTP/HTTPS、且免去 http 弃用/可达性/弱网放大全部坑）。
     * - [manualHttp] 为空 → 回退 **Auto + PAC URL**（HTTP 代理没开时，SOCKS-only 仍可用 PAC）。
     * @param base 形如 `http://192.168.1.34:8899`（PAC URL 基址 + UUID 派生种子）。
     * @param ssid 目标 Wi-Fi 名称（用户在落地页填写）。
     */
    fun mobileconfig(base: String, ssid: String, manualHttp: Pair<String, Int>? = null): String {
        val ssidEsc = ssid.xmlEscape()
        val topUuid = stableUuid(base)
        val wifiUuid = stableUuid("$base#wifi#$ssid")
        val proxyKeys: String
        val desc: String
        if (manualHttp != null) {
            val (host, port) = manualHttp
            proxyKeys = """
      <key>ProxyType</key><string>Manual</string>
      <key>ProxyServer</key><string>${host.xmlEscape()}</string>
      <key>ProxyServerPort</key><integer>$port</integer>"""
            desc = "为当前 Wi-Fi 设置固定 HTTP 代理（无需 PAC），把流量经 hxmy proxy 中转。"
        } else {
            val pac = "$base/proxy.pac".xmlEscape()
            proxyKeys = """
      <key>ProxyType</key><string>Auto</string>
      <key>ProxyPACURL</key><string>$pac</string>
      <key>ProxyPACFallbackAllowed</key><true/>"""
            desc = "为当前 Wi-Fi 设置自动代理（PAC），把流量经 hxmy proxy 中转。"
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>PayloadContent</key>
  <array>
    <dict>
      <key>PayloadType</key><string>com.apple.wifi.managed</string>
      <key>PayloadVersion</key><integer>1</integer>
      <key>PayloadIdentifier</key><string>com.mzstd.hxmyproxy.wifi.$wifiUuid</string>
      <key>PayloadUUID</key><string>$wifiUuid</string>
      <key>PayloadDisplayName</key><string>hxmy proxy Wi-Fi 代理</string>
      <key>SSID_STR</key><string>$ssidEsc</string>
      <key>HIDDEN_NETWORK</key><false/>
      <key>AutoJoin</key><true/>
      <key>EncryptionType</key><string>Any</string>$proxyKeys
    </dict>
  </array>
  <key>PayloadType</key><string>Configuration</string>
  <key>PayloadVersion</key><integer>1</integer>
  <key>PayloadIdentifier</key><string>com.mzstd.hxmyproxy.$topUuid</string>
  <key>PayloadUUID</key><string>$topUuid</string>
  <key>PayloadDisplayName</key><string>hxmy proxy 配置</string>
  <key>PayloadDescription</key><string>$desc</string>
  <key>PayloadRemovalDisallowed</key><false/>
</dict>
</plist>
"""
    }

    /** 由字符串确定性派生 UUID（MD5 命名 UUID，无随机/时间依赖）。 */
    internal fun stableUuid(seed: String): String =
        UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString().uppercase()

    private fun String.htmlEscape(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    private fun String.xmlEscape(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
}
