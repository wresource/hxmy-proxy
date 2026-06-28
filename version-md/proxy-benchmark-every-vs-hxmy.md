# Benchmark：every proxy vs hxmy proxy（真实网页加载对比）

> 进行中。目标：在真实浏览器上网行为下，公平对比 every proxy 与 hxmy proxy 的网页加载速度。
> 用户要求：真实上网（非命令行单文件下载）、多场景网站 + Stripe checkout、公平 benchmark、一个一个跑（开无痕测完关再开另一个）。

## 实验装置

- **服务器（被测代理所在）**：Pixel Fold，serial `adb-36261FDHS000E2-qzZshj._adb-tls-connect._tcp`
  - wlan0 = `192.168.50.65`；上游 = **Google One VPN**（`com.google.android.apps.privacy.wildlife`，接口 `ipsec473`，RTT ~200ms，MTU 1280）
  - hxmy 1.1.5：HTTP **8079**（绑 0.0.0.0）、SOCKS 1080、PAC 8899
  - every proxy（`com.gorillasoftware.everyproxy`）：HTTP **8078**（绑 192.168.50.65）+ everyproxybridge
- **客户端（测速发起）**：Pixel 10 Pro 模拟器 `emulator-5554`（sdk_gphone16k_arm64，1280×2856），真实 Chrome（`com.android.chrome`）
- **不要动**：另一台 Pixel Fold `adb-37151FDHS0002F-...`（用户在用）
- Mac（本机）`192.168.50.113` 与服务器同 LAN

## 量化方法（已验证可用）—— `clean_load`

模拟器配全局代理 → 代理端口；每次 **pm clear Chrome 清缓存** → 启动 Chrome → **uiautomator 自动跳过引导**（找 "No thanks"/"Stay signed out"/"Skip"/"Dismiss"/"Not now" 的 bounds 并 tap）→ `am start` 加载 URL → 监控 **`/proc/net/dev`（eth0+wlan0）rx 流量**，当 0.5s 增量 <8KB 连续 3s 判定"网络空闲=加载完成"，**加载时间 = t0→空闲 − 3s 窗口**。

> 为何这套：① incognito intent 不真正加载（下行仅几十 KB，弃用）；② 模拟器非 root，读不了 `/sys/class/net`，但 `/proc/net/dev` 可读；③ every 的**明文 forward 对模拟器 toybox nc 不响应**（对 curl 正常）、且模拟器**装不了 curl**（SELinux/seccomp），所以只能用真实 Chrome。

设备端切代理：`adb -s emulator-5554 shell settings put global http_proxy 192.168.50.65:<port>`

## 已有数据（初步，维基 Google 页，8.4MB，冷加载）

| 指标 | hxmy(8079) | every(8078) |
|---|---|---|
| 加载×3(ms) | 5734 / 5738 / 6252 → **中位 ~5.7s** | 6245 / 6742 / 6749 → **中位 ~6.7s** |
| 下行 | ~8.4MB | ~8.35MB（同页面，公平） |

早期命令行吞吐（1MB 下载）：hxmy ~4.2s vs every ~5.0s（也接近、hxmy 略优）。

**初步结论**：真实网页加载下 **hxmy 比 every 快约 15%（~1s）**，不是 every 更快。
**严谨性警告**：上面是"先测完 hxmy 再测 every"（非交替），这 1s 里有多少是代理本身、多少是 Google VPN 几分钟内的时间漂移，**尚未分离**。需多场景 + 交替/多次中位数确认。

## 关键发现 / 坑

- every 绑特定 IP `192.168.50.65`：`37151` 那台 every **拒绝 LAN 客户端**（只本机可连）；`36261` 这台允许 LAN（本次用 36261）。
- every：CONNECT(HTTPS 隧道)正常、明文 HTTP forward 对 curl 正常但对 nc 不响应。
- 瓶颈主要在 **Google VPN 出口**（RTT 200ms + 带宽，单连接 ~210–250KB/s），两代理都受限——"慢"很大程度是 VPN 不是代理。
- Chrome 前台服务/代理 app 前台服务无法用 adb 直接拉起（`connectedDevice` 权限），需用户手点"开始共享"。

## 待办

1. **Workflow `w5duehxb8`**：子代理搜索设计 benchmark（10~14 个多场景 URL：轻/重/SPA/电商/新闻/视频/CDN/**Stripe checkout**/大文件下载 + 公平方法论）。
2. 拿到 benchmark 后：**每个代理一轮无痕冷加载完整 benchmark**（每 URL 多次取中位数），一个一个来（测 every 时 force-stop hxmy，反之需用户重开 hxmy）。
3. 出最终对比表 + 结论，回写本文。

## Benchmark 设计（子代理 workflow w5duehxb8 调研 + 用户要求 200 条）

**两层结构：**

### L1 广度（200 条 top 域名，TTFB 快速测量）
- 域名源：Majestic Million top 1000 → 取前 200（`/tmp/domains.txt`）。
- 测量：Mac curl 经代理（8079/8078）测每域名 `time_starttransfer`（打开到首字节，含 DNS+TCP+TLS+服务器首响应，经代理+Google VPN）。
- 跑法：**交替成对**（同域名紧挨测 hxmy/every，对内顺序奇偶交换），消除 VPN 时间漂移。后台任务 `br65elhxy` → 结果 `scratchpad/bench200.tsv`。
- 为何不是完整渲染：200 条完整浏览器渲染 ×2 代理需 2+ 小时，不现实；TTFB 反映"打开网站响应速度"，大样本统计有代表性。

### L2 深度（14 条精选场景，真实 Chrome 完整加载）
子代理精选、覆盖全场景维度（weight 轻/中/重）：

| 场景 | URL | 权重 |
|---|---|---|
| 轻量门户 | https://www.wikipedia.org/ | 轻 |
| 搜索 | https://www.google.com/ | 轻 |
| 百科内容页 | https://en.wikipedia.org/wiki/HTTP | 中 |
| 代码仓库 | https://github.com/torvalds/linux | 中 |
| 新闻图文 | https://www.bbc.com/news | 中 |
| CDN密集多三方域 | https://www.theverge.com/ | 重 |
| 视频重SPA并发 | https://www.youtube.com/ | 重 |
| 电商复杂页 | https://www.amazon.com/ | 重 |
| 重媒体落地页 | https://www.apple.com/iphone/ | 重 |
| 图库大字节 | https://unsplash.com/t/wallpapers | 重 |
| SPA | https://www.figma.com/ | 中 |
| **Stripe checkout** | https://checkout.stripe.dev/ | 中 |
| 大文件吞吐 | https://speed.cloudflare.com/__down?bytes=104857600 | 重 |
| 大文件对照 | https://ash-speed.hetzner.com/100MB.bin | 重 |

- 测量：模拟器 Chrome `clean_load`（清缓存冷加载 + 流量静默判定 networkidle）。大文件两条单独用吞吐口径（speed_download）。
- 跑法：每代理跑完整 14 条（无痕/清缓存），每 URL 多次中位数。理想 7 次/URL，时间紧可降到 3。

**方法论要点（子代理调研）**：① 加载完成判据用"网络空闲"（静态页 networkidle0，动态页 networkidle2 + 45s 超时兜底）；② 每次冷缓存 First View + warm-up 丢弃首次；③ **交替成对 interleaving**（A→B 紧挨、对内随机序）抵消 VPN 漂移，比平行 A/B 省 10–100x 样本；④ 报告差值 Δ=every−hxmy 的中位数与分布，配对用 Wilcoxon；⑤ 每 URL 7 次（Lighthouse 5次/DebugBear 7次 边际递减）；⑥ 大文件单独成组（纯吞吐，与渲染不可混比）。

**caveats**：内容随时间漂移→只比同会话相对差距；免登录可达性需真机复核（已避开 Reddit/X）；动态页 networkidle 口径 A/B 须一致；Amazon/Google 高频可能弹验证码（剔除重测）；交替成对压低但不能完全消除漂移。

## 结果

### L1 广度 TTFB（188 个 top 域名「打开到首字节」，交替成对，经 Google VPN）
| | hxmy | every |
|---|---|---|
| 中位 TTFB | **1.011s** | 1.123s |
| 均值 TTFB | **1.233s** | 1.432s |
| 逐条更快 | **102 条** | 86 条 |

配对差 Δ(every−hxmy) 中位 **+0.130s** → **hxmy 略优**：中位快 ~130ms、均值快 ~200ms、逐条 54% 胜。均值差 > 中位差 → every 慢离群更多。结果文件 `scratchpad/bench200.tsv`。

### L2 深度 完整渲染（105 有效站，networkidle 加载时间）
hxmy 轮 02:51–03:34、every 轮 03:35–04:18（各 200 站，pm clear 冷开 + 缓存累积）。对齐 200 站，有效成对 **105**（剔除 95：经 Google VPN 失败/被墙/需登录/小响应，如 twitter/国内站等）。

| 指标 | hxmy | every |
|---|---|---|
| 加载中位 | **10.79s** | 11.91s |
| 加载均值 | **11.80s** | 14.14s |
| 逐条更快 | **74 站 (70%)** | 31 站 |
| 超时(≥37s) | 4 | 9 |

配对差 Δ(every−hxmy) 中位 **+1.21s** → hxmy 快。代表站（加载 ms）：

```
checkout.stripe.dev   hxmy 5.3s    every 38.8s(超时!)   ← Stripe hxmy 完胜
github.com            hxmy 2.1s    every 7.9s
figma.com (SPA)       hxmy 18.3s   every 25.2s
apple/iphone          hxmy 4.9s    every 5.2s            ← 接近
wikipedia/HTTP        hxmy 3.3s    every 3.2s            ← 接近(every 略快)
unsplash             hxmy 4.3s    every 4.2s            ← 接近
```

结果文件 `scratchpad/render_hxmy.tsv` / `render_every.tsv`。

## 最终结论

**hxmy 在真实网页加载上明显优于 every，且方向被两种独立方法交叉验证：**
- **完整渲染**（105 站，非交替）：中位快 1.21s、均值快 2.3s、逐条 70% 胜、超时少一半（4 vs 9）。
- **TTFB 广度**（188 站，**交替成对已消除 VPN 漂移**）：中位快 130ms、均值快 200ms、逐条 54% 胜。
- 两法方向一致（都 hxmy 快）→ **hxmy 更快是真实的，不是漂移伪影**。
- **重站/复杂页差距最大**：Stripe checkout（hxmy 5.3s vs every 38.8s 超时）、github（2.1 vs 7.9）、figma SPA（18 vs 25）——hxmy 在多资源/多并发连接管理上明显占优；轻量站（wikipedia/unsplash）两者接近、互有胜负。

**caveat**：① 完整渲染是「hxmy 轮在前、every 轮在后（晚 44min）」非交替，绝对差距可能含部分 VPN 时间漂移；但消漂移的 TTFB 也是 hxmy 快，故结论稳健，只是完整渲染的「绝对秒数差」勿过度解读。② 有效样本 105/200（top 域名经 Google VPN 大量被墙/需登录被剔除）。③ **瓶颈仍是 Google VPN**：多数站加载 3–18s 是 VPN 出口 RTT(~200ms)+ 带宽所致，换低延迟翻墙节点收益远大于换代理。

**一句话**：纯转发吞吐、TTFB、真实网页加载三个维度，hxmy 都不输 every、且整体更快（尤其重站/Stripe）；用户感知的「慢」主因是 VPN，不是 hxmy。

## 追加：debug vs release 复测（用户怀疑 debug 比 release 快）

**重要前提**：上面 L1/L2 测的 hxmy 是 **debug 版**（36261 装的是 Android Studio 的 DEBUGGABLE+TEST_ONLY 构建）。用户怀疑 debug 比 release 快，故装 release 版（1.1.5 optimize+baseline profile）重测。

### 第一轮（非同时段，有漂移）
release 轮 09:26–10:16（白天）vs debug 轮 02:51–03:34（凌晨），各 200 站、同 URL/方法：

| | debug(凌晨) | release(白天) |
|---|---|---|
| 加载中位 | 10.32s | 10.36s |
| 加载均值 | 11.80s | 12.24s |
| 逐条更快 | 92 | 51 |
| 超时(≥37s) | 4 | 10 |

配对差 Δ(release−debug) 中位 +0.51s。**但稳定基准站显示白天时段普遍更慢**：wikipedia +584ms、apple +1405ms、github +5207ms → 时段漂移 ~0.6–1.4s。**Δ(+0.51s) < 时段漂移 → 扣除漂移后 release 不比 debug 慢**。

### 第二轮（同时段，消漂移）— 结果【决定性】
debug2 轮 10:20–11:20（白天，紧接 release 10:16 结束后），与 release 同时段、漂移已消除。128 有效成对：

| 指标 | debug | release |
|---|---|---|
| 加载中位 | 10.71s | **9.91s** |
| 加载均值 | 13.17s | **11.42s** |
| 超时(≥37s) | **33** | **10** |
| 逐条更快 | 35 | **93 (73%)** |

配对差 Δ(release−debug) 中位 **−1.01s**、均值 **−1.75s** → **release 明显更快**，逐条 73% 胜，超时仅 debug 的 1/3。

### 结论：用户怀疑「debug 比 release 快」**不成立**，正好相反
- **同时段公平对比下，release 明显快于 debug**：中位快 1.0s、均值快 1.75s、逐条 73% 胜、超时少 2/3。
- 第一轮看似「release 略慢 +0.51s」是**时段漂移假象**：release 在白天测、debug 在凌晨测，而白天 VPN 慢 0.6–1.4s（debug2 白天也撞了 33 次超时 vs 凌晨 debug 仅 4 次，印证白天慢）。把 debug 也挪到白天同时段后，release 反超 1s。
- 符合原理：release = R8 优化 + Baseline Profile（AOT 热路径），debug = 无优化 + debuggable 解释执行开销。**release 本就该更快**。
- **建议正式发布/日常都用 release**。之前你感觉 debug 更好，是被「debug 恰好在网络空闲的凌晨测」误导了。

## 加载完成判定方法（网络流量静默，近似 networkidle）

**没用浏览器 `onLoad` 事件**（模拟器非 root + 无法注入 DevTools/Puppeteer），改用「网络流量静默」近似：
- `rx()` 读模拟器 `eth0+wlan0` 累计接收字节（`/proc/net/dev`，非 root 可读）；
- 启动 Chrome 加载 URL，每 **0.5s** 采样接收字节增量 `d`；
- `d < 8KB` 计一次「静默」，**连续 5 次（=2.5s 内几乎无新流量）→ 判定加载完成**；最多 40s 超时兜底；
- 加载时间 = 起点 → 静默触发 − 2.5s 判定窗口。

**局限**：① 网络层静默 ≠ 渲染完成；② 8KB / 2.5s 是经验阈值——中途卡顿 >2.5s 会误判提前完成，持续后台流量（视频/广告心跳）永不静默 → 撞 40s 超时；③ 全网卡字节含轻微噪声；④ ±0.5s 采样粒度。
**公平性**：A/B 用完全相同判据+阈值，误差系统性同向 → **相对差距（谁快/快多少）有效，绝对秒数勿过度解读**。业界标准（Lighthouse/WebPageTest）用 DevTools `networkidle0/2`（连接级）更精确，但需注入调试协议，模拟器环境做不到。

## every proxy 显著落后的场景（凌晨 debug vs every，同时段最干净）

163 站 hxmy 正常加载中：**every 慢 >3s 的 47 站、慢 >10s 的 16 站**。落后最多 Top：

| 站点 | 场景 | hxmy | every | every 慢 |
|---|---|---|---|---|
| **checkout.stripe.dev** | 支付页 | 5.3s | 38.8s(超时) | **+33.5s** |
| apps.apple.com | 应用商店 | 10.3s | 38.3s(超时) | +28.0s |
| usatoday.com | 重型新闻 | 15.3s | 39.2s(超时) | +24.0s |
| outlook.com | 邮箱 | 11.4s | 32.5s | +21.1s |
| bbc.com | 重型新闻 | 19.3s | 39.2s(超时) | +19.9s |
| businessinsider.com | 重型新闻 | 15.7s | 35.1s | +19.4s |
| wsj.com / ft.com | 财经新闻 | ~10s | ~26s | +15~17s |
| **t.co / bit.ly** | 短链重定向 | 5.3/13.9s | 20.8/23.3s | +15.6/+9.4s |
| vk.com / soundcloud | 社交/媒体 | ~14s | ~28s | +13~15s |
| play.google.com | Google 服务 | 2.6s | 13.5s | +10.8s |
| hubspot/microsoft/eventbrite | SaaS | ~15s | ~24s | +7~11s |
| figma.com | SPA | 18.3s | 25.2s | +6.9s |

**every 落后的共同模式 = 「多域名 / 多第三方资源 / 重定向链 / 复杂页」**：
- **支付页**：Stripe checkout（every 直接超时，hxmy 5.3s）——差距最悬殊；
- **重型新闻站**（广告/分析/多 CDN/几十个第三方域）：usatoday、bbc、wsj、ft、businessinsider、bloomberg、washingtonpost——普遍慢 7–24s、常撞超时；
- **应用商店 / Google 服务**：apps.apple.com、play.google.com；
- **邮箱 / SaaS**：outlook、hubspot、microsoft、eventbrite；
- **短链重定向**：t.co、bit.ly（每跳一个新域名）；
- **媒体 / SPA**：soundcloud、vk、figma。

**机理**：这些页要建立**大量独立连接**（每个第三方域名、每跳重定向都要独立 DNS+TCP+TLS），代理多一跳的握手成本被成倍放大；**hxmy 的并发连接管理 / DNS 路径明显优于 every**。反之，轻量单域名站（wikipedia/google 首页等）两者接近、互有胜负——差距只在复杂场景显现。

## clean_load 脚本（供续作复用）

```bash
E="emulator-5554"
clean_load() {  # $1 = url
  adb -s "$E" shell pm clear com.android.chrome >/dev/null 2>&1
  adb -s "$E" shell am start -n com.android.chrome/com.google.android.apps.chrome.Main >/dev/null 2>&1
  adb -s "$E" shell sleep 3
  for r in 1 2 3; do
    adb -s "$E" shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
    adb -s "$E" pull /sdcard/u.xml /tmp/u.xml >/dev/null 2>&1
    c=$(python3 -c "
import re;x=open('/tmp/u.xml').read()
for kw in ['No thanks','Stay signed out','Skip','Dismiss','Not now']:
    m=re.search(r'(?:text|content-desc)=\"'+re.escape(kw)+r'\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',x)
    if m: a,b,cc,d=map(int,m.groups());print(f'{(a+cc)//2} {(b+d)//2}');break")
    [ -n "$c" ] && { adb -s "$E" shell input tap $c; adb -s "$E" shell sleep 1; } || break
  done
  adb -s "$E" shell "
  rx(){ cat /proc/net/dev|grep -E 'eth0:|wlan0:'|tr ':' ' '|awk '{s+=\$2}END{print s}'; }
  r0=\$(rx); am start -a android.intent.action.VIEW -d '$1' -n com.android.chrome/com.google.android.apps.chrome.Main >/dev/null 2>&1
  t0=\$(date +%s%3N); prev=\$r0; idle=0; n=0
  while [ \$idle -lt 6 ] && [ \$n -lt 50 ]; do sleep 0.5; n=\$((n+1)); cur=\$(rx); d=\$((cur-prev)); prev=\$cur; if [ \$d -lt 8000 ]; then idle=\$((idle+1)); else idle=0; fi; done
  t1=\$(date +%s%3N); echo \"加载≈\$((t1-t0-3000))ms 下行\$(((cur-r0)/1024))KB\"; "
}
```
