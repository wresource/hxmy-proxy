# 代理性能优化评估：拉满崩溃根因 + 瓶颈 + 修复方案

> 来源：用户反馈「release 下开 google 搜索 + stripe，代理直接不工作」+「性能滑块拉满容易崩溃」。
> 多 agent 排查（含 grounded kotlinx 线程语义 + 读源码）结论。核心方向（用户提出、grounded 证实）：**当前是「协程裹阻塞 IO + 堆线程」反模式，应转向协程非阻塞**。

## 崩溃根因：原生线程爆炸 → native OOM / pthread_create failed（不是引用泄漏）

**关键纠正**：`Dispatchers.IO.limitedParallelism(N)` 是**弹性视图，不受 `kotlinx.coroutines.io.parallelism`（HxmyProxyApp 设的 192）约束**——192 只管「直接派到 Dispatchers.IO」的任务（accept 循环 + `OutboundConnector.connect` + rebuild/probe）。两个视图 `acceptDispatcher=limitedParallelism(maxGlobal)`、`relayDispatcher=limitedParallelism(2×relayParallelism)`（`ProxyServerRepository.kt:229-230`）底层是近无界调度池。

- relay/handle 全是**阻塞 IO**（`RelayEngine.pump` 阻塞 read/write；HTTP keep-alive 两请求间阻塞 `readAsciiLine` 15s），**每条隧道占 2 个阻塞线程**。
- 用户把 `maxGlobal` 拉满 1024，再开 Google 搜索 + Stripe（几十域名、上百连接）：acceptDispatcher 可弹到 ~1024 线程 + relayDispatcher 128 + 直接 IO 192 ≈ **峰值上千条阻塞线程**，每条 ART/pthread 栈 0.5-1MB → **0.5-1GB+ 原生栈内存 → pthread_create 失败 / native OOM → 被系统杀（= 用户说的"崩溃"）**。
- **所以「拉满」本身就是崩溃的直接触发器**。`HxmyProxyApp.kt:15-18` 注释「抬到 192 就够、防 relay 饿死 accept」是对弹性语义的**误读**（192 根本管不住那两个视图）。
- 次要放大：真实 FD ≈ 2×maxGlobal（上游 socket 未计数），1024 → ~2048 FD，软上限 1024 的设备触发 **EMFILE**；而 accept 循环 catch 里 `if(isActive) continue`（`ProxyServer.kt:119`）无退避无日志，FD 耗尽时 **100% CPU 紧凑自旋**。

## 真瓶颈排序（什么导致慢）

1. **VPN 出口延迟**：RTT(~200ms) × 每域名建链(DNS+TCP+TLS)。复杂页引用几十域名，这是首屏快慢主导项，**任何 maxGlobal/buffer/parallelism/idle 都治不了**（只能靠已有 DNS 缓存/Happy Eyeballs/keep-alive 缓解）。
2. **relayParallelism**：真正「能同时全速搬字节的隧道数」天花板（relayDispatcher=2×N 槽，每隧道双向占 2 槽整条生命周期）。浏览器对 Google+Stripe 轻松开 >64 条隧道，**第 65 条起 pump 排队拿不到线程 = 连接建好却零字节传输 = 页面转圈/"代理不工作"**。最直接、最值得调。
3. **阻塞 connect 与 relay/accept 共用同一 192 直接池**：`connectAny` 每尝试 `launch(Dispatchers.IO)` 阻塞 connect(5s)，Happy Eyeballs 对几十域名扇出上百慢连接，短时打满 192。
4. **准入(maxGlobal) 与 relay 能力错配**：默认 256、拉满 1024，但 relay 只能服务 ~relayParallelism(64) 条——8:1~16:1 过订，「先接受后饿死」。
5. **架构性**：阻塞「每隧道 2 线程」模型 + 共享池。`limitedParallelism` 只隔离派发计数、不隔离物理线程，扩不到浏览器级并发——复杂页崩/卡的根因。
6. VPN 带宽物理上限：提并行只把同一带宽切碎，聚合吞吐封顶。

## 配置建议（别全拉满）

| 参数 | 当前上限 | **推荐** | 为何 |
|---|---|---|---|
| relayParallelism | 64 | **64（唯一可拉满）** | 同时搬字节隧道数，32→64 缓解复杂页卡死；**前提：配合调短 idle** |
| maxGlobalConnections | 1024 | **256–384** | 拉满=崩溃直接推手（线程逼近 1024 + FD~2048 EMFILE）；relay 只服务 ~64，过订只会排队饿死，不提速 |
| maxPerClientConnections | 512 | **≈maxGlobal（256–384）** | 默认 128 偏低，单浏览器多 host 上百连接会先于 maxGlobal 被拒（隐形瓶颈）；非崩溃项 |
| relayBufferBytes | 256KB | **32–64KB** | 拉满对吞吐≈0（盲转，窗口由端 socket 决定）+ 4× 内存 + 大对象 GC 抖动；要提吞吐应设 socket SO_RCVBUF/SO_SNDBUF |
| idleTimeoutSeconds | 1800 | **60–120** | relay 把它当 socket soTimeout，空闲 keep-alive 隧道 squat 2 槽直到超时；拉满=单条空闲隧道占槽 30 分钟，越用越卡 |

## 代码修复

### 止血（低风险，建议先做）
1. **acceptDispatcher/relayDispatcher 换有界线程池**：`Executors.newFixedThreadPool(...).asCoroutineDispatcher()`（类比 `OutboundConnector.kt:203` 的 DNS 池）——唯一能对阻塞线程数设**真实硬上限、不受用户「拉满」影响**的办法。`ProxyServerRepository.kt:229-230`。
2. acceptDispatcher 用小固定并发（32–64，握手是短工），不要 `limitedParallelism(maxGlobal=1024)`。
3. 砍 `RANGE_GLOBAL` 上限（1024 → 阻塞模型撑得住的量级）。`Performance.kt:22`。
4. idle 默认降 60–120s；给 relay 槽加独立的短空闲回收(30–60s)，与用户 idle 分离。`RelayEngine.kt:33-35`。
5. accept 循环异常加退避(短 delay)+日志，识别 EMFILE，消除 100% CPU 自旋。`ProxyServer.kt:118-119`。
6. `stop()` 主动关闭所有在途 client+upstream socket → 阻塞 read 立刻抛错、线程解阻塞、FD 立即释放（而非残留到 idle 超时）。`ProxyServer.kt:159`。
7. 隔离 connect 到独立有界池 + Happy Eyeballs 扇出设上限 + 高 RTT 下调 `CONNECT_TIMEOUT_MS`。`OutboundConnector.kt:117`、`ProxyTuning.kt:8`。
8. 准入计入上游 socket（按 2×FD 评估上限），避免 EMFILE。`ConnectionRegistry.kt`。
9. relayParallelism/buffer 移出 serverKey，避免拖滑块触发热重启把活跃隧道变孤儿。`ProxyServerRepository.kt:286`。
10. 修正 `HxmyProxyApp.kt:15-18` 关于 io.parallelism=192 的错误注释。

### 根治（大改，符合协程非阻塞理念）
**relay 改 NIO Selector / ktor-network 非阻塞事件循环**（`io.ktor:ktor-network`：`aSocket(SelectorManager(Dispatchers.IO)).tcp()` + `ByteReadChannel`/`ByteWriteChannel` suspend 读写）——**线程数与隧道数解耦**：空闲/低速隧道挂起、不再各占 2 个阻塞线程，少量 selector 线程支撑浏览器级上千连接。这是唯一能真正消除「拉满崩溃」和「relay 槽饿死」的架构改法。`RelayEngine.kt` 整体。

## 实施记录（1.1.6 / versionCode 7）

**已实施止血**（改→编译→模拟器验证）：
1. **有界线程池**：`acceptDispatcher`/`relayDispatcher` 由 `Dispatchers.IO.limitedParallelism(N)` 弹性视图 → `Executors.newFixedThreadPool(...).asCoroutineDispatcher()`（accept 固定 `ACCEPT_THREADS=64`；relay `2×relayParallelism` ≤128 硬顶）；会话级，`stopServers` 用 `shutdownNow` 回收。`ProxyServerRepository`。
2. **配置收口**：`RANGE_GLOBAL` 1024→512、`RANGE_BUFFER_BYTES` 256K→128K、`RANGE_IDLE_SECONDS` 1800→600；默认 `maxPerClient` 128→256、`idle` 300→120；preset idle 降至 60/120、HIGH_THROUGHPUT buffer 128K→64K。`Performance.kt`。
3. **accept 退避 + EMFILE 日志**：FD 耗尽时退避 `ACCEPT_ERROR_BACKOFF_MS=100ms` + 记一条，消除 100% CPU 自旋。`ProxyServer.kt`。
4. **stop() 主动关在途 socket**：`inFlight = ConcurrentHashMap.newKeySet<Socket>()`，stop 遍历 `closeQuietly` → 阻塞 read 立刻抛错、线程归还、FD 立即释放（不再残留到 idle 超时）。`ProxyServer.kt`。
5. **serverKey 移除 relayParallelism**：拖性能滑块不再触发热重启把活跃隧道孤儿化（其变更在下次启动生效）。`ProxyServerRepository`。
6. **修正 io.parallelism=192 注释**：澄清它只约束直接派到 `Dispatchers.IO` 的任务，`limitedParallelism` 视图不受其钳制——故已弃用该视图。`HxmyProxyApp.kt`。

**延后**（仍属止血清单，非崩溃主因）：connect 独立有界池 + Happy Eyeballs 扇出上限 + 降 `CONNECT_TIMEOUT_MS`（第7条）；准入计入上游 socket 按 2×FD（第8条）；relay 槽独立短空闲回收。**根治**（relay 改 NIO/ktor-network）仍延后。

**验证**（release 1.1.6，模拟器 loopback，**High throughput 拉满档**；Pixel Fold 真机因 PIN 锁屏待解锁后复测）：
- 拉满档**启动不崩**（状态 Sharing，HTTP :8080 起来）。
- **google 搜索 → stripe.com** 经代理转发 **997 KB / 9 活跃连接** 正常加载——直接驳斥「开 google 再开 stripe 代理就不 work」。
- **10 个复杂站点并发加压**：进程 PID 不变（不崩），**线程峰值 230**（有界：accept 64 + relay 128 + DNS 16 + connect 池 + 基础线程；旧弹性视图同场景会弹到上千 → native OOM）。
- **Stop sharing**：连接归 0、线程回落 67（`shutdownNow` 正确回收会话池；旧视图线程会滞留底层 IO 池）。

## 结论（verdict）
**别全部拉满**——只把 `relayParallelism` 拉到 64（唯一有真实收益且基本无害的项），`maxGlobal`/`maxPerClient` 设中等（256–384、perClient≈maxGlobal）、`buffer` 用 32–64KB、`idle` 降到 60–120s。**拉满 maxGlobal/buffer/idle 不仅不提速，正是导致复杂页"卡死/不工作"和 native OOM 崩溃的元凶**。真正的根治是把 relay 阻塞模型换成协程非阻塞（NIO/ktor-network）。
