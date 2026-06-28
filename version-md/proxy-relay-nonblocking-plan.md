# Relay 非阻塞化（C / 根治）落地计划

> 来源：用户「A+B 先做、C 先验证」后的 **C 命门验证 + 落地设计**。多 agent 调研（实读 ktorio/ktor 源码 + Android `Network` 文档 + 本仓库调用链）结论。配套止血/加固见 [proxy-perf-optimization.md](proxy-perf-optimization.md)。

## 结论（verdict）

- **不走 ktor-network**。命门坐实：ktor 公开 API 把「channel 创建 + connect」原子锁在 internal `tcpConnect`（`ConnectUtilsJvm.kt`），**无 connect-pre-hook、无 channel 工厂、不暴露底层 `SocketChannel`** → 无法在 connect 前 `bindSocket`（出口分流的命门）。唯一出路是把 `SocketImpl/NIOSocketImpl/CIOReader/CIOWriter` 等 internal 类「影子复制」进同包名，ktor 每次升级都要回归出口分流，脆弱。且本项目**根本没有 ktor 依赖**，引入 = 凭空加一个大依赖。
- **走手写 java.nio Selector**，上游用 `SocketChannel` + **`Network.bindSocket(FileDescriptor)`（反射取 fd）**。与现有架构同源（现状全是 `java.net.Socket` 阻塞），握手 / Happy Eyeballs / EgressGuard / 出口分流可保留，NIO 复杂度严格关进 relay 阶段。
- **Phase 0 是 GATING**：先真机 spike 验证「反射取 SocketChannel 的 fd + `bindSocket(fd)` + connect 前，能在系统 VPN（含 lockdown/always-on）下绕过 VPN 走真实出口」。**通过才继续，不通过则维持现状阻塞 relay**（沉没成本仅 0.5–1 天）。

## 命门坐实（关键技术事实）

1. **出口分流必须 connect 前绑定**：`Network.bindSocket` 三个重载 javadoc 均写 *"The socket must not be connected"*，对已连 socket 抛 `IOException`。绑定改的是 fd 的出口路由标记，必须在三次握手发出前完成。
2. **不能用 `bindSocket(SocketChannel.socket())`**：`SocketChannel.socket()` 返回的 `SocketAdaptor` 以 null `SocketImpl` 构造，`getFileDescriptor$()` 取不到底层 fd → `bindSocket(Socket)` **静默失败**，会造成「以为分流了、其实走了 VPN」的**隐性正确性 bug**。
3. **正解 = `bindSocket(FileDescriptor)`（API 23，minSdk 29 满足）+ 反射取 fd**：`SocketChannel.open()` → 反射 `sun.nio.ch.SocketChannelImpl.fd`（回退 `ch.socket().getFileDescriptor$()`）→ `network.bindSocket(fd)` → `channel.connect()`。参考实战：android-ssl `SocketUtils`。
4. **现状对照**：`OutboundConnector` 用 `network.socketFactory.createSocket()` 产出**已绑定的阻塞 Socket**（工厂内部完成绑定），简单但与非阻塞 selector 不兼容——这正是 connect 至今被迫走阻塞池（96 线程硬顶）的根因。

## 架构（NIO relay）

- `NioRelayReactor` 持 **1~2 个 `SelectorWorker`**（个位数线程撑上千隧道）。每隧道的 client + upstream **两 channel 落同一 worker**（免跨 selector 协调 interestOps）。
- **握手保持阻塞**：accept 出的 `SocketChannel` 先 `configureBlocking(true)`，SOCKS5 协商 / HTTP CONNECT 解析复用现有阻塞代码；**只有进入盲转前一刻才切非阻塞**交给 reactor。利好：`readAsciiLine`/`readFully` 零缓冲（`IoUtils.kt`），切非阻塞**不丢首包**。
- **上游建连保持阻塞 Happy Eyeballs**（connect 池已有界 96，非瓶颈）；第一版**不做** NIO connect（OP_CONNECT）——那要把 RFC 8305 整套状态机重写进 selector，风险高收益低。
- **协程集成**：`handle()` 连好两端后 `suspendCancellableCoroutine` 挂起；隧道关闭时 reactor 在 selector 线程 `resume`（`AtomicBoolean` 保证只一次）；`invokeOnCancellation` → 投递 close 任务 + `wakeup()`，使协程取消 / `stop()` 能即时拆隧道。`finally` 仍走 `registry.release`/`tracker.close`。

### 背压状态机（每方向一个 ByteBuffer，核心）
- 正常：src 注册 `OP_READ`；读就绪 → `read(buf)` → `flip` → `write(dst)`。
- **写不完**（`write < remaining`，对端发送缓冲满）→ `compact` 保留剩余 → **dst 加 `OP_WRITE` + src 去 `OP_READ`**（暂停读源，否则 buffer/内存爆）。
- **写空** → `clear` → **dst 去 `OP_WRITE` + src 重加 `OP_READ`**（解背压）。
- 一个 channel 既是某方向 src（`OP_READ`）又是另一方向 dst（`OP_WRITE`）→ 每个 key 的 interestOps 必须**按位或两边需求统一重算**（`rebuildInterest`）。
- **EOF**（`read==-1`）→ 残留写完后 `dst.shutdownOutput()` 半关、对端收 EOF；**两向都 done 才整关**（视频/SSE/上传等待响应不被截断）。

### hardParts 实施清单（NIO 经典雷区，逐项写测）
- [ ] 跨线程 `register`/`interestOps`/`close` **必须投递任务队列由 selector 线程执行** + `wakeup()`（select 期间外部直接 register 会被 synchronized 卡死——最隐蔽）。
- [ ] `OP_WRITE` 电平触发：写空后**立即去掉 `OP_WRITE`**，否则 100% CPU 自旋。
- [ ] `selectedKeys()` 处理后**必须 `iterator.remove()`**。
- [ ] cancelled key 延迟生效：处理就绪前/readable 后 writable 前**复检 `key.isValid`**。
- [ ] `flip`/`compact`/`clear` 纪律：错一处丢字节或写脏数据。
- [ ] 背压**双向联动**（写满时 src 去 OP_READ；drain 净再恢复）。
- [ ] continuation 多路径（正常/idle/reset/stop/取消）全汇聚 `Tunnel.close()`，`AtomicBoolean` 只 `resume` 一次。

## 范围

**必改**
- `OutboundConnector.kt` — 新增 `connectChannel()` 产出已连接 `SocketChannel`（复用 `connectAny` 的 Happy Eyeballs 编排，仅把 socket 创建参数化为 create+bind(fd)+connect 的 lambda）；反射取 fd 工具；`L228` 注释「无法走非阻塞 NIO」失效需更新。保留 `connect()`（返回 `java.net.Socket`）给 HTTP 明文路径。
- `ProxyServer.kt` — `accept` 改 `ServerSocketChannel`；`handle(client)` 由 `Socket`→`SocketChannel`；`inFlight`/`stop()` 改 `SocketChannel`（关 channel 触发 `AsynchronousCloseException`，语义同现状）。bind 重试 / EMFILE 退避 / 准入 / 计数 / tracker 不变。
- `HttpProxyServer.kt` / `Socks5ProxyServer.kt` — `handle` 签名适配 `SocketChannel`，握手用 `channel.socket()` 阻塞流；CONNECT 进 reactor。
- `NioRelayReactor.kt` — **新建**：Selector reactor + `Tunnel`/`Pipe` 背压状态机。
- `ProxyServerRepository.kt` — 接线 reactor（生命周期随 servers）+ **feature flag**（设置项或 BuildConfig）+ 线程池调整。`trafficSink` 口径不变。

**保留阻塞（第一版不动）**
- HTTP 明文 keep-alive 转发（`HttpForwarding.copyBody`，不经 RelayEngine）；握手阶段；上游 Happy Eyeballs；`RelayEngine.kt`（作 flag 关闭时的回退路径，不删）；`PacServer`。

## 分阶段

| Phase | 内容 | 关键风险 |
|---|---|---|
| **P0（GATING）** | 真机 spike：`SocketChannel.open()` → 反射 fd → `bindSocket(fd)` → 阻塞 connect 到回显出口 IP 的 endpoint，在系统 VPN（含 lockdown）下确认出口 IP 为非 VPN 真实 IP。逐版本验证反射可达性。**androidTest 新增 `BindSocketSpikeTest`** | 反射 fd 跨 ROM 不可达 → 全盘 NO-GO，维持现状 |
| **P1** | `OutboundConnector.connectChannel()`：复用 Happy Eyeballs 编排，socket 创建换 `SocketChannel.open()` + bind(fd) + 阻塞 connect；反射失败回退 `socketFactory` 阻塞路径 | Happy Eyeballs 参数化引入 FD/连接泄漏 |
| **P2** | 接入侧 `accept` 改 `ServerSocketChannel`，`handle(client: SocketChannel)`，握手仍 `blocking(true)` | 签名变更触及全部子类；握手期必须 blocking |
| **P3** | `NioRelayReactor` + `SelectorWorker` + `Tunnel`/`Pipe` 背压状态机（~350–450 LOC，**feature flag 默认关**） | NIO 状态机经典坑（见 hardParts） |
| **P4** | 仅 **CONNECT 隧道**（HTTP CONNECT + SOCKS5）接 reactor，HTTP 明文保持阻塞 | 切非阻塞时序；两路 relay 行为等价 |
| **P5** | Repository 接线 + 线程池 + 单测 + Pixel Fold 全清单验证；按惯例每构建递增版本号 | 线程池/生命周期与快速重启交互 |

## 验证清单（Pixel Fold 真机）

出口分流真生效（**对端回显出口 IP 非 VPN**，含 lockdown）· 线程数与隧道数解耦（selector 个位数线程，对比现状 2×relayParallelism）· 无 CPU 自旋 · 无内存涨（慢消费下背压生效）· 大文件 GB 级双向零丢/零损 · 视频/SSE 半关续传不截断 · HTTPS CONNECT · SOCKS5（IPv4/IPv6/域名 ATYP）· HTTP 明文 keep-alive 未回归 · idle 超时拆隧道释放 FD · 取消传播（stop 即时拆、active 归零无泄漏）· 流量计量 NIO 与阻塞路径数值一致 · flag 回退等价 · stop→start 快速重启。

## 回退（三层，从轻到重）

1. **Feature flag 默认关** → CONNECT 走保留的旧阻塞 `RelayEngine`，功能等价改造前（做成运行时设置则一键切回不发版）。
2. **反射取 fd 失败** → `connectChannel` 自动回退 `network.socketFactory.createSocket()` 阻塞 Socket + 阻塞 relay，出口分流仍由 socketFactory 保障（仅放弃该连接的非阻塞收益）。
3. **整体 revert** → 改造全在分支增量提交，`RelayEngine`/`OutboundConnector.connect()`/明文路径均保留未删，`git revert` reactor + 接线 commit 即回纯阻塞架构。

灰度：先只对 CONNECT 开 flag（覆盖 HTTPS/视频/SOCKS 主流量），HTTP 明文始终阻塞保底，真机观察稳定后放量。

## 工作量

**5~8 个工作日**（含真机验证，不含 phase2 非阻塞 Happy Eyeballs——不建议第一版）。不确定性集中在 **P0（反射 fd 跨 ROM 可达性）** 与 **P3（NIO 状态机调试）**，二者各可能溢出 0.5–1 天。**P0 不过则止损在 0.5–1 天**。
