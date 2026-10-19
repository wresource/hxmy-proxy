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
 * - **双语**：文案按扫码设备浏览器的 Accept-Language 走（[isZh]）——扫码方可能是英文电脑，
 *   与手机 app 的语言无关（双语检查原则）。默认中文保持既有行为。
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

    /** 请求方是否偏好中文（Accept-Language 含 zh，如 `zh-CN,zh;q=0.9`）。 */
    fun isZh(acceptLanguage: String): Boolean = "zh" in acceptLanguage.lowercase()

    /**
     * 落地页 HTML。
     * @param base 形如 `http://192.168.1.34:8899`（无尾斜杠）——扫码设备正是连到此地址。
     * @param zh 按扫码设备的 Accept-Language 选语言（true=中文）。
     */
    fun html(base: String, userAgent: String, manualProxy: Boolean = true, zh: Boolean = true): String {
        val pac = "$base/proxy.pac"
        val pacEsc = pac.htmlEscape()
        val platform = detectPlatform(userAgent)
        val primary = when (platform) {
            Platform.APPLE -> appleSection(pacEsc, manualProxy, zh,
                isMac = "mac" in userAgent.lowercase() && "mobile" !in userAgent.lowercase())
            Platform.WINDOWS -> windowsSection(pacEsc, zh)
            Platform.OTHER -> androidSection(pacEsc, zh)
        }
        // 始终附「其他系统」可展开，避免误判 UA 时无路可走。
        val others = buildString {
            append("<details><summary>")
            append(if (zh) "其他系统的设置方法" else "Setup for other systems")
            append("</summary>")
            if (platform != Platform.APPLE) append(appleSection(pacEsc, manualProxy, zh))
            if (platform != Platform.WINDOWS) append(windowsSection(pacEsc, zh))
            if (platform != Platform.OTHER) append(androidSection(pacEsc, zh))
            append("</details>")
        }
        val title = if (zh) "hxmy proxy · 配置" else "hxmy proxy · Setup"
        val h1 = if (zh) "把这台设备接入 hxmy proxy" else "Connect this device through hxmy proxy"
        val sub = if (zh) "请在这台客户端上完成以下步骤。扫码打开此页不会自动修改代理设置。"
            else "Complete these steps on this client. Opening this page does not automatically change its proxy settings."
        val pacTag = if (zh) "通用：自动配置（PAC）地址，复制到系统代理「自动/脚本」一栏"
            else "Universal: auto-config (PAC) URL — paste it into your system proxy's \"Automatic / Script\" field"
        val copy = if (zh) "复制地址" else "Copy URL"
        val copied = if (zh) "已复制" else "Copied"
        val select = if (zh) "地址已选中，请使用系统复制菜单。" else "URL selected. Use the system Copy command."
        val verify = if (zh) """
<section class="card"><h2>验证与排障</h2><ol>
<li>保存客户端代理设置后，在支持代理的应用中打开网页。</li>
<li>回到手机的“访问”，检查设备、域名和新流量。仅打开此配置页不代表已使用代理。</li>
<li>没有新流量时，核对接入网络、协议端口、客户端代理设置及认证信息；有流量但访问失败时，点域名查看最近结果、命中规则和出口。</li>
</ol><p class="tag">仅经过 HTTP、SOCKS5 或 PAC 的流量受代理规则控制；不遵循代理设置的应用不会被自动接管。</p></section>
<section class="card"><h2>停止使用时</h2><p>先关闭客户端的代理设置，或在设备管理中移除 hxmy proxy 配置文件，再停止手机共享或离开此网络。固定代理地址在手机不可达时会影响客户端联网。</p></section>
""" else """
<section class="card"><h2>Check the connection</h2><ol>
<li>Save the client proxy settings, then open a web page in an app that supports the proxy.</li>
<li>On the phone, open Activity and look for the device, domain and new traffic. This setup page alone does not confirm proxy use.</li>
<li>If no traffic appears, check Inbound, protocol ports, client settings and authentication. If traffic appears but the page fails, open the domain to review the latest result, rule and outbound.</li>
</ol><p class="tag">Rules apply to traffic sent through HTTP, SOCKS5 or PAC. Apps that ignore proxy settings are not automatically routed through this gateway.</p></section>
<section class="card"><h2>When you stop using it</h2><p>Turn off the client proxy or remove the hxmy proxy profile in device management before stopping sharing or leaving this network. A fixed proxy address can prevent client access when the phone is unavailable.</p></section>
"""
        return """<!DOCTYPE html>
<html lang="${if (zh) "zh" else "en"}"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<meta name="referrer" content="no-referrer">
<title>$title</title>
<style>
:root{color-scheme:light dark;--bg:#f6f8ff;--card:#fff;--ink:#172033;--muted:#526075;--line:#b9c2d0;--accent:#245bb7;--on-accent:#fff;--field:#edf2fb}
@media(prefers-color-scheme:dark){:root{--bg:#101318;--card:#1b202b;--ink:#e3e7f0;--muted:#b8c2d4;--line:#647086;--accent:#adc7ff;--on-accent:#102e61;--field:#252d3b}}
*{box-sizing:border-box}
body{margin:0;padding:20px;font:16px/1.6 -apple-system,system-ui,"PingFang SC","Microsoft YaHei",sans-serif;color:var(--ink);background:var(--bg)}
.wrap{max-width:640px;margin:0 auto}
h1{font-size:1.5rem;margin:0 0 8px}h2{font-size:1.125rem;margin:0 0 12px}
.sub{color:var(--muted);margin:0 0 24px}
.card{background:var(--card);border-radius:24px;padding:20px;margin:0 0 16px;overflow-wrap:anywhere}
.pac{display:flex;flex-wrap:wrap;gap:12px;align-items:center}
.pac input{font-family:ui-monospace,Menlo,Consolas,monospace;min-width:0}
.btn{display:inline-flex;align-items:center;justify-content:center;min-height:48px;background:var(--accent);color:var(--on-accent);text-decoration:none;border:0;border-radius:24px;padding:12px 20px;font:inherit;cursor:pointer}
.btn.sec{background:var(--field);color:var(--accent)}
button:focus-visible,a:focus-visible,input:focus-visible,summary:focus-visible{outline:3px solid var(--accent);outline-offset:3px}
ol{margin:8px 0 0;padding-left:22px}li{margin:4px 0}
input{width:100%;min-height:48px;padding:12px;border:1px solid var(--line);border-radius:12px;font:inherit;color:var(--ink);background:var(--field);margin:8px 0}
details{margin-top:12px}summary{cursor:pointer;color:var(--accent);min-height:48px;padding:12px 0}
.tag{font-size:.875rem;color:var(--muted)}code{overflow-wrap:anywhere}
</style></head>
<body><div class="wrap">
<h1>$h1</h1>
<p class="sub">$sub</p>
$primary
<div class="card">
<label for="pac" class="tag">$pacTag</label>
<div class="pac"><input id="pac" readonly value="$pacEsc"><button class="btn sec" type="button" onclick="copyPac()">$copy</button></div>
<p id="copy-status" class="tag" role="status" aria-live="polite"></p>
</div>
$verify
$others
<script>
async function copyPac(){var field=document.getElementById('pac');field.focus();field.select();field.setSelectionRange(0,field.value.length);var ok=false;
try{if(navigator.clipboard&&window.isSecureContext){await navigator.clipboard.writeText(field.value);ok=true;}else{ok=document.execCommand('copy');}}catch(e){}
document.getElementById('copy-status').textContent=ok?'$copied':'$select';}
</script>
</div></body></html>"""
    }

    private fun appleSection(pacEsc: String, manualProxy: Boolean, zh: Boolean, isMac: Boolean = false): String {
        // manualProxy=true(已开 HTTP 代理)→ 描述文件用 Manual HTTP，装上即生效、不 fetch PAC，iPhone 最稳。
        // manualProxy=false(仅 SOCKS)→ 描述文件回退 Auto/PAC，提示用户开 HTTP 代理可获最稳体验。
        val effect = when {
            manualProxy && zh -> "<li>该文件为指定 Wi-Fi 设置 HTTP 代理。安装后，请按下方步骤检查客户端访问。</li>"
            manualProxy -> "<li>The profile sets an HTTP proxy for the specified Wi-Fi. After installing, check client access using the steps below.</li>"
            zh -> "<li>该文件为指定 Wi-Fi 设置 PAC 自动代理。安装后，请按下方步骤检查客户端访问。</li>"
            else -> "<li>The profile sets automatic PAC proxy configuration for the specified Wi-Fi. After installing, check client access below.</li>"
        }
        val hint = when {
            manualProxy -> ""
            zh -> "<p class=\"tag\">提示：在 hxmy 开启「HTTP 代理」后再扫码，iPhone 体验最稳（免 PAC 拉取）。</p>"
            else -> "<p class=\"tag\">Tip: enable \"HTTP proxy\" in hxmy before scanning — most reliable on iPhone (no PAC fetch).</p>"
        }
        val head = if (zh) "<p>① 填入你当前连接的 Wi-Fi 名称，② 下载并安装配置文件：</p>"
            else "<p>1. Enter the Wi-Fi name you're currently on, 2. download &amp; install the profile:</p>"
        val placeholder = if (zh) "你的 Wi-Fi 名称（区分大小写）" else "Your Wi-Fi name (case-sensitive)"
        val dl = if (zh) "下载配置文件" else "Download profile"
        val iosStep = if (zh) "<li>iPhone / iPad：打开设置中的“已下载描述文件”，或通用 → <b>VPN 与设备管理</b>，检查内容后安装并确认。</li>"
            else "<li>iPhone / iPad: open Profile Downloaded in Settings, or General → <b>VPN &amp; Device Management</b>. Review and install the profile.</li>"
        val macStep = if (zh) "<li>Mac：打开下载的文件，再到系统设置 → 通用 → <b>设备管理</b>，检查并安装该描述文件。</li>"
            else "<li>Mac: open the downloaded file, then System Settings → General → <b>Device Management</b>. Review and install the profile.</li>"
        val step1 = if (isMac) macStep else iosStep
        val otherApple = if (isMac) iosStep else macStep
        val unsigned = if (zh) "未签名文件会显示“未验证”。请确认它来自你自己的 hxmy 手机，且 Wi-Fi 名称及代理地址正确，再决定安装。它只包含此 Wi-Fi 的代理配置，不包含证书或账号；可在设备管理中移除。也可使用下方 PAC 地址手动设置。"
            else "An unsigned profile is shown as Unverified. Install only after checking that it came from your hxmy phone and that the Wi-Fi name and proxy address are correct. It contains Wi-Fi proxy settings, not certificates or accounts, and can be removed in device management. Manual PAC setup is also available below."
        val alertMsg = if (zh) "请先填写 Wi-Fi 名称" else "Please enter your Wi-Fi name first"
        return """
<div class="card">
<b>iPhone / iPad / Mac</b>
$head
<label for="ssid">$placeholder</label>
<input id="ssid" placeholder="$placeholder" autocapitalize="off" autocorrect="off">
<a id="dl" class="btn" href="#" onclick="return go()">$dl</a>
<ol>
$step1
$effect
</ol>
<details><summary>${if (zh) "另一种 Apple 设备" else "Another Apple device"}</summary><ol>$otherApple</ol></details>
$hint
<p class="tag">$unsigned</p>
<script>
function go(){var s=document.getElementById('ssid').value.trim();
if(!s){alert('$alertMsg');return false;}
document.getElementById('dl').setAttribute('href','/hxmy.mobileconfig?ssid='+encodeURIComponent(s));
return true;}
</script>
</div>"""
    }

    private fun windowsSection(pacEsc: String, zh: Boolean): String = if (zh) """
<div class="card">
<b>Windows 电脑</b>
<ol>
<li>设置 → 网络和 Internet → <b>代理</b>。</li>
<li>「使用安装脚本」打开，地址填：<code>$pacEsc</code>，保存。</li>
<li>打开网页验证即可。</li>
</ol>
</div>""" else """
<div class="card">
<b>Windows PC</b>
<ol>
<li>Settings → Network &amp; Internet → <b>Proxy</b>.</li>
<li>Turn on "Use setup script", set the address to <code>$pacEsc</code>, save.</li>
<li>Open any webpage to verify.</li>
</ol>
</div>"""

    private fun androidSection(pacEsc: String, zh: Boolean): String = if (zh) """
<div class="card">
<b>安卓 / 其他</b>
<ol>
<li>设置 → WiFi → 长按当前网络 → 修改 → 高级 → 代理：<b>自动配置（PAC）</b>。</li>
<li>PAC 地址填：<code>$pacEsc</code>，保存。</li>
<li>打开网页验证即可。</li>
</ol>
</div>""" else """
<div class="card">
<b>Android / Others</b>
<ol>
<li>Settings → Wi-Fi → long-press the current network → Modify → Advanced → Proxy: <b>Auto-config (PAC)</b>.</li>
<li>Set the PAC URL to <code>$pacEsc</code>, save.</li>
<li>Open any webpage to verify.</li>
</ol>
</div>"""

    /**
     * Apple 配置描述文件（`com.apple.wifi.managed` Wi-Fi 载荷，绑定 [ssid]）。
     * - [manualHttp] 非空（host, httpPort）→ **Manual HTTP 代理**：iOS 不 fetch PAC，最稳
     *   （grounded：iOS Wi-Fi Manual 只支持 HTTP/HTTPS、且免去 http 弃用/可达性/弱网放大全部坑）。
     * - [manualHttp] 为空 → 回退 **Auto + PAC URL**（HTTP 代理没开时，SOCKS-only 仍可用 PAC）。
     * @param base 形如 `http://192.168.1.34:8899`（PAC URL 基址 + UUID 派生种子）。
     * @param ssid 目标 Wi-Fi 名称（用户在落地页填写）。
     * @param zh 与落地页同语言（描述文件的名称/说明会显示在 iOS 安装界面）。
     */
    fun mobileconfig(base: String, ssid: String, manualHttp: Pair<String, Int>? = null, zh: Boolean = true): String {
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
            desc = if (zh) "为当前 Wi-Fi 设置固定 HTTP 代理（无需 PAC），把流量经 hxmy proxy 中转。"
            else "Sets a fixed HTTP proxy for this Wi-Fi (no PAC), routing traffic through hxmy proxy."
        } else {
            val pac = "$base/proxy.pac".xmlEscape()
            proxyKeys = """
      <key>ProxyType</key><string>Auto</string>
      <key>ProxyPACURL</key><string>$pac</string>
      <key>ProxyPACFallbackAllowed</key><true/>"""
            desc = if (zh) "为当前 Wi-Fi 设置自动代理（PAC），把流量经 hxmy proxy 中转。"
            else "Sets an automatic proxy (PAC) for this Wi-Fi, routing traffic through hxmy proxy."
        }
        val wifiName = if (zh) "hxmy proxy Wi-Fi 代理" else "hxmy proxy Wi-Fi proxy"
        val cfgName = if (zh) "hxmy proxy 配置" else "hxmy proxy configuration"
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
      <key>PayloadDisplayName</key><string>$wifiName</string>
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
  <key>PayloadDisplayName</key><string>$cfgName</string>
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
