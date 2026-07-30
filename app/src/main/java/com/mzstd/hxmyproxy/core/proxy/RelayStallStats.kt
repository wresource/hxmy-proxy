package com.mzstd.hxmyproxy.core.proxy

import java.util.concurrent.atomic.AtomicLong

/**
 * 背压归因的聚合器：回答「慢，到底卡在哪一段」。
 *
 * hxmy 是双跳——客户端 →(入口段)→ 手机 →(出口段)→ 互联网。用户报「慢」时，此前没有任何证据
 * 能区分是入口段(手机→客户端那条 Wi-Fi)扛不住，还是出口段(手机→互联网)慢，只能猜。
 *
 * 信号本来就躺在 relay 里：`drain()` 写不进 dst 的 socket 发送缓冲，就说明**那一端**堵了。
 * 两个方向含义相反，这正是归因的关键：
 * - `down` 管道(upstream→client)写不动 ⇒ 写客户端写不动 ⇒ **入口段**瓶颈
 * - `up` 管道(client→upstream)写不动 ⇒ 写上游写不动 ⇒ **出口段**瓶颈
 *
 * **计时只在状态翻转时取 `nanoTime`**（进入/退出 stall 各一次），不是每次 drain。`drain` 每秒可能
 * 走几千次，而拥塞的开始与结束才是稀疏事件——否则就是在 relay 热路径上加常驻成本。
 *
 * 窗口语义：[snapshotAndReset] 每次取走并清零，调用方(60s 心跳)拿到的即「过去一个窗口」的聚合。
 * 会话边界另行 [reset]，与其它会话计量口径一致。
 */
object RelayStallStats {

    private val liveNs = AtomicLong()      // 隧道存活时长之和（分母）
    private val stallInNs = AtomicLong()   // 写客户端写不动的时长之和（入口段）
    private val stallOutNs = AtomicLong()  // 写上游写不动的时长之和（出口段）
    private val tunnels = AtomicLong()
    /** 窗口内单条隧道的最高 stall 占比（百分比）。时长加权的均值会被大量短连接稀释，极值不会。 */
    private val maxPct = AtomicLong()

    /** 隧道关闭时调用一次（在 selector 线程）。[liveNanos] 为隧道存活总时长。 */
    fun record(liveNanos: Long, stallInNanos: Long, stallOutNanos: Long) {
        if (liveNanos <= 0) return
        liveNs.addAndGet(liveNanos)
        stallInNs.addAndGet(stallInNanos)
        stallOutNs.addAndGet(stallOutNanos)
        tunnels.incrementAndGet()
        val pct = ((stallInNanos + stallOutNanos) * 100 / liveNanos).coerceIn(0, 100)
        // 单调抬高窗口内最大值（CAS 循环：selector 线程可能有多个）
        while (true) {
            val cur = maxPct.get()
            if (pct <= cur || maxPct.compareAndSet(cur, pct)) break
        }
    }

    /** 取走并清零；无样本返回 null（心跳据此省略这几个字段，不打 0% 的噪音）。 */
    fun snapshotAndReset(): Snapshot? {
        val live = liveNs.getAndSet(0)
        val si = stallInNs.getAndSet(0)
        val so = stallOutNs.getAndSet(0)
        val n = tunnels.getAndSet(0)
        val mx = maxPct.getAndSet(0)
        if (live <= 0 || n <= 0) return null
        return Snapshot(
            tunnels = n,
            inPct = (si * 100 / live).toInt(),
            outPct = (so * 100 / live).toInt(),
            maxPct = mx.toInt(),
        )
    }

    fun reset() {
        liveNs.set(0); stallInNs.set(0); stallOutNs.set(0); tunnels.set(0); maxPct.set(0)
    }

    /**
     * [inPct]/[outPct] 是**时长加权**：窗口内所有隧道累计有多大比例的存活时间在等待写入。
     * 两者可同时为低（真的不堵）、其一为高（该段是瓶颈），也可都高（两头都堵，通常是设备整体过载）。
     */
    class Snapshot(val tunnels: Long, val inPct: Int, val outPct: Int, val maxPct: Int)
}
