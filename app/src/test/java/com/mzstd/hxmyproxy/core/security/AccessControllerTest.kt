package com.mzstd.hxmyproxy.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * [AllowAllAccessController] 是 [AccessController] 的「不设限」默认实现。
 *
 * 为什么它值得有测试：它和真实实现 [SubnetAccessController] 在同一个接口后面，
 * 且默认值恰好是**最危险**的那一侧。接线接错（DI 里注了 AllowAll、
 * 或某条分支忘了换成 SubnetAccessController）在设备上毫无异常表现——
 * 代理照常工作，只是「一个网段都没勾选」时全网段仍可连，
 * 正是 1.8.7 修掉的那个 fail-open 洞的症状。
 *
 * 所以这里做两件事：把 AllowAll 的「无条件放行」钉成显式契约（免得有人误以为它带了什么隐含过滤），
 * 并用一个对照用例把两种实现在同一输入下的相反结论摆出来，作为接线错误的判别依据。
 */
class AccessControllerTest {

    private fun addr(s: String) = InetAddress.getByName(s)

    /**
     * AllowAll 就是字面意思：不看本地接口、不看远端、连 loopback 也放行。
     * 这不是缺陷而是它的定义——但正因为如此，它只应出现在测试与显式「不设限」的场景里。
     */
    @Test fun `AllowAll 对任意本地与远端组合都放行`() {
        val locals = listOf("192.168.1.10", "127.0.0.1", "0.0.0.0", "10.0.0.5", "::1")
        val remotes = listOf("192.168.1.99", "8.8.8.8", "127.0.0.1", "::1")
        for (l in locals) {
            for (r in remotes) {
                assertTrue(
                    "AllowAll 拒绝了 local=$l remote=$r，它的定义就是无条件放行",
                    AllowAllAccessController.admit(addr(l), addr(r)),
                )
            }
        }
    }

    /**
     * 对照用例：同一个「未选任何网段」的输入下，两种实现给出相反结论。
     * 若哪天 SubnetAccessController 的空集分支被改回放行，这条会和它自己的
     * emptyAllowSetRefusesAll 一起变红——两个不同角度的用例同时守一条语义，
     * 因为这条语义是用户拍板过的（不开=全拒）且真出过洞。
     */
    @Test fun `未选任何网段时真实准入与不设限实现结论相反`() {
        val local = addr("192.168.1.10")
        val remote = addr("192.168.1.99")
        val real: AccessController = SubnetAccessController()   // 未调用 update：允许集为空

        assertFalse("空集必须全拒，否则就是 1.8.7 修掉的 fail-open 洞", real.admit(local, remote))
        assertTrue(AllowAllAccessController.admit(local, remote))
    }
}
