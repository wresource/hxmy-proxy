package com.mzstd.hxmyproxy.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Authenticator] 是代理的**唯一真安全边界**——准入（AccessController）按类注释自己承认
 * 只是「便利过滤」，同网段来源 IP 可伪造；真正拦住陌生人的只有这里的凭据比对。
 * 所以这一个 `verify` 写歪了，用户看到的现象是：代理照常工作、日志一切正常、
 * 只是任何知道端口的人都能白嫖流量甚至借道内网，而这在设备上完全没有可观测信号。
 *
 * 本文件守护三组语义：
 * 1. **fail-closed**：密码没配（空串）时一律拒绝——「开了认证但没设密码」不能退化成免密。
 * 2. **精确比对**：不裁空白、不忽略大小写、不接受前缀/超集。任何一条放松都等于缩小密码空间。
 * 3. **职责边界**：`verify` 不看 `enabled`，开关由调用方把守
 *    （见 HttpProxyServer.kt:75 与 Socks5ProxyServer.kt:59）。这条如果被"优化"成
 *    「没开认证就 return true」，任何漏判 enabled 的新调用方都会瞬间变成免密放行。
 *
 * 全部是纯 Kotlin 逻辑，不碰 android.*，JVM 里断言结果可信。
 */
class AuthenticatorTest {

    // ---------------- NoAuthAuthenticator：默认不认证 ----------------

    /**
     * 默认实现必须 enabled=false 且无条件放行。它代表「用户没开认证」这个状态，
     * 若 enabled 误成 true，协议层会向客户端索要凭据，表现为所有客户端突然连不上。
     */
    @Test fun `不启用认证时开关为关且任何凭据都放行`() {
        assertFalse(NoAuthAuthenticator.enabled)
        assertTrue(NoAuthAuthenticator.verify("", ""))
        assertTrue(NoAuthAuthenticator.verify("anyone", "anything"))
    }

    // ---------------- SingleCredentialAuthenticator：fail-closed ----------------

    /**
     * 默认构造出来的实例是「关闭 + 空凭据」。即便某个调用方漏判了 enabled 直接调 verify，
     * 也必须拒绝——这是最后一道兜底。
     */
    @Test fun `默认构造是关闭且空凭据时一律拒绝`() {
        val a = SingleCredentialAuthenticator()
        assertFalse(a.enabled)
        assertFalse(a.verify("", ""))
        assertFalse(a.verify("admin", "admin"))
    }

    /**
     * 核心 fail-closed：开了认证却没设密码时，**任何**凭据都不通过，包括空凭据。
     * 若这里写成「密码为空就不校验密码」，用户在设置页打开认证但还没填密码的那几秒，
     * 代理就是完全敞开的——而 UI 会显示「认证已开启」，用户以为自己受保护。
     */
    @Test fun `空密码一律拒绝——开了认证却没设密码不能退化成免密`() {
        val a = SingleCredentialAuthenticator(username = "user", password = "", enabled = true)
        assertFalse("空凭据不该通过", a.verify("", ""))
        assertFalse("用户名对但密码为空不该通过", a.verify("user", ""))
        assertFalse(a.verify("user", "anything"))
        assertFalse(a.verify("", "anything"))
    }

    /** 用户名与密码都对才放行——正向基线。 */
    @Test fun `用户名与密码都对才放行`() {
        val a = SingleCredentialAuthenticator(username = "alice", password = "s3cret", enabled = true)
        assertTrue(a.verify("alice", "s3cret"))
    }

    /** 只错一个字段就拒——避免出现「用户名对就放行」或「密码对就放行」的短路实现。 */
    @Test fun `用户名或密码只错一个也拒绝`() {
        val a = SingleCredentialAuthenticator(username = "alice", password = "s3cret", enabled = true)
        assertFalse("用户名错", a.verify("bob", "s3cret"))
        assertFalse("密码错", a.verify("alice", "wrong"))
        assertFalse("两个都错", a.verify("bob", "wrong"))
    }

    /**
     * 大小写敏感——实现是 `==` 精确比对，不做归一。
     * 这条是**先读实现再定断言**的结果：项目别处（域名规则）确实会归一到小写，
     * 但凭据绝不能归一，否则密码空间凭空缩小一个数量级。
     */
    @Test fun `用户名与密码都区分大小写`() {
        val a = SingleCredentialAuthenticator(username = "Alice", password = "S3cret", enabled = true)
        assertTrue(a.verify("Alice", "S3cret"))
        assertFalse("用户名大小写被忽略了", a.verify("alice", "S3cret"))
        assertFalse("密码大小写被忽略了", a.verify("Alice", "s3cret"))
        assertFalse(a.verify("ALICE", "S3CRET"))
    }

    /**
     * 不做首尾空白裁剪：空格是凭据的一部分。
     * 若哪天有人「顺手」加 trim（输入框常见做法），会出现两个后果——
     * 用户设了含空格的密码后再也登不上，以及带多余空格的错误凭据被当成正确的。
     */
    @Test fun `不裁剪首尾空白——空格是凭据的一部分`() {
        val a = SingleCredentialAuthenticator(username = "alice", password = "s3cret", enabled = true)
        assertFalse(a.verify(" alice", "s3cret"))
        assertFalse(a.verify("alice ", "s3cret"))
        assertFalse(a.verify("alice", " s3cret"))
        assertFalse(a.verify("alice", "s3cret "))

        val spaced = SingleCredentialAuthenticator(username = "a b", password = "p q ", enabled = true)
        assertTrue("含空格的凭据必须原样可用", spaced.verify("a b", "p q "))
        assertFalse(spaced.verify("ab", "pq"))
    }

    /**
     * 密码是前缀或超集都不算通过——防止实现退化成 startsWith / contains。
     * 这类退化在正向用例下完全看不出来（正确密码照样通过），只有反向用例能抓。
     */
    @Test fun `密码是前缀或超集都不算通过`() {
        val a = SingleCredentialAuthenticator(username = "alice", password = "abc", enabled = true)
        assertFalse("前缀被当成通过，说明用了 startsWith 之类", a.verify("alice", "ab"))
        assertFalse("超集被当成通过，说明用了 contains 之类", a.verify("alice", "abcd"))
        assertFalse(a.verify("alice", "xabcx"))
    }

    /**
     * 空用户名是**合法配置**：只要密码非空就放行。SOCKS5 RFC1929 允许零长用户名，
     * 部分客户端也确实只填密码。此时必须仍然拒绝任何非空用户名——
     * 若实现变成「用户名为空就不比对用户名」，就等于用户名字段整体失效。
     */
    @Test fun `允许空用户名只要密码非空`() {
        val a = SingleCredentialAuthenticator(username = "", password = "s3cret", enabled = true)
        assertTrue(a.verify("", "s3cret"))
        assertFalse("配置的是空用户名，非空用户名不该通过", a.verify("someone", "s3cret"))
        assertFalse(a.verify("", "wrong"))
    }

    /**
     * `verify` 与 `enabled` 正交：开关由调用方把守，verify 只回答「凭据对不对」。
     * 把这条钉死是为了防一个很有诱惑力的"优化"——在 verify 开头写
     * `if (!enabled) return true`。那样一来，任何忘了先判 enabled 的新协议实现
     * 都会在认证关闭时变成无条件放行，而在认证开启时才暴露问题，测试极难覆盖。
     */
    @Test fun `verify 不看开关——开关由调用方把守`() {
        val off = SingleCredentialAuthenticator(username = "alice", password = "s3cret", enabled = false)
        assertTrue("关闭状态下 verify 仍应如实回答凭据正确", off.verify("alice", "s3cret"))
        assertFalse("关闭状态下 verify 不该无条件返回 true", off.verify("bob", "wrong"))
    }

    /**
     * 凭据是 @Volatile var，设置页改密码时热更新到同一个实例上
     * （见 ProxyServerRepository 里对 authenticator.username/password 的直接赋值）。
     * 改完必须立刻生效：旧密码当场失效、新密码当场可用。
     * 若被改成构造期快照，用户改了密码却发现旧密码还能用——而且要重启代理才修复。
     */
    @Test fun `运行期改凭据后旧密码立即失效`() {
        val a = SingleCredentialAuthenticator(username = "alice", password = "old", enabled = true)
        assertTrue(a.verify("alice", "old"))

        a.password = "new"
        assertFalse("改密码后旧密码仍通过", a.verify("alice", "old"))
        assertTrue(a.verify("alice", "new"))

        a.username = "bob"
        assertFalse("改用户名后旧用户名仍通过", a.verify("alice", "new"))
        assertTrue(a.verify("bob", "new"))

        // 把密码清空（用户删掉了密码框内容）→ 立刻回到 fail-closed
        a.password = ""
        assertFalse("清空密码后没有回到全拒", a.verify("bob", ""))
        assertFalse(a.verify("bob", "new"))
    }

    /**
     * SOCKS5 的用户名/密码是按 UTF-8 字节解码后交进来的（Socks5ProxyServer.kt:143），
     * 所以多字节与超长凭据必须按原样比对，不能因为编码往返或长度截断而误判。
     */
    @Test fun `多字节与超长凭据按原样比对`() {
        val unicodePwd = "密码🔑ÄÖ"
        val u = SingleCredentialAuthenticator(username = "用户", password = unicodePwd, enabled = true)
        assertTrue(u.verify("用户", unicodePwd))
        assertFalse(u.verify("用户", "密码🔑ÄÔ"))

        val long = "x".repeat(511) + "y"
        val l = SingleCredentialAuthenticator(username = "u", password = long, enabled = true)
        assertTrue(l.verify("u", long))
        assertFalse("只比对前缀会让这条变绿", l.verify("u", "x".repeat(511) + "z"))
    }

    /** 多个实例互不干扰——凭据是实例状态而非全局单例（曾把 Authenticator 写成 object 的诱惑）。 */
    @Test fun `不同实例的凭据互不干扰`() {
        val a = SingleCredentialAuthenticator(username = "a", password = "pa", enabled = true)
        val b = SingleCredentialAuthenticator(username = "b", password = "pb", enabled = true)
        assertTrue(a.verify("a", "pa"))
        assertTrue(b.verify("b", "pb"))
        assertFalse(a.verify("b", "pb"))
        assertFalse(b.verify("a", "pa"))
    }
}
