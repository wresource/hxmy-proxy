package com.mzstd.hxmyproxy

import android.content.Context
import android.content.res.AssetManager
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.data.repository.*
import com.mzstd.hxmyproxy.ui.MainViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReviewRulesRegressionTest {
    private lateinit var state: MutableStateFlow<ProxySettings>
    private lateinit var vm: MainViewModel
    private val engine=RuleEngine()
    private lateinit var repo: RuleRepository
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        state=MutableStateFlow(ProxySettings(enabledRuleGroups=emptySet()))
        val settings=mockk<SettingsRepository>(relaxed=true)
        every { settings.settings } returns state
        every { settings.onboardingCompleted } returns flowOf(true)
        every { settings.domainHistory } returns flowOf(emptySet())
        every { settings.ruleChangeHistory } returns flowOf(com.mzstd.hxmyproxy.core.rules.RuleChangeJournal())
        coEvery { settings.update(any()) } answers { state.value=firstArg<(ProxySettings)->ProxySettings>()(state.value) }
        val proxy=mockk<ProxyServerRepository>(relaxed=true) {
            every { this@mockk.state } returns MutableStateFlow(ShareState())
            every { resetState } returns MutableStateFlow(ManualResetPhase.IDLE)
        }
        val history=mockk<EndpointHistoryRepository> { every { this@mockk.history } returns flowOf(emptyList()) }
        val creds=mockk<CredentialStore> { every { credentials } returns flowOf(CredentialStore.Credentials()) }
        vm=MainViewModel(settings,proxy,history,creds,engine)
        val assets=mockk<AssetManager> {
            every { open(any()) } answers { File("src/main/assets",firstArg<String>()).inputStream() }
        }
        val context=mockk<Context> { every { this@mockk.assets } returns assets }
        repo=RuleRepository(context,engine)
    }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun stoppedSessionWaitingOnBuildLockCannotOverwriteNewerSnapshot() {
        val active=java.util.concurrent.atomic.AtomicBoolean(true)
        val waiting=java.util.concurrent.CountDownLatch(1)
        val finished=java.util.concurrent.CompletableFuture<Boolean>()
        val old=state.value.copy(userRejectRules=listOf(RuleEntry("old.example")))
        val fresh=state.value.copy(userRejectRules=listOf(RuleEntry("fresh.example")))
        synchronized(repo) {
            Thread({
                waiting.countDown()
                try { finished.complete(repo.rebuild(old) { active.get() }) }
                catch(t:Throwable) { finished.completeExceptionally(t) }
            },"old-rule-request").apply { isDaemon=true; start() }
            assertTrue(waiting.await(5,java.util.concurrent.TimeUnit.SECONDS))
            active.set(false)
            assertTrue(repo.rebuild(fresh)) // New request can acquire a reentrant monitor first.
        }
        assertFalse(finished.get(5,java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1,engine.version.value)
        assertEquals(RuleAction.REJECT,engine.decide("fresh.example"))
        assertEquals(RuleAction.PROXY,engine.decide("old.example"))
    }

    @Test fun quickActionKeepsVisibleListScopeAndUnrelatedParentRule() {
        state.value=state.value.copy(hostOverrides=mapOf("other.example" to RuleAction.REJECT))
        vm.setDomainDirect("api.example.com")
        assertEquals("*.api.example.com", state.value.userDirectRules.single().value)
        assertEquals(mapOf("other.example" to RuleAction.REJECT),state.value.hostOverrides)
        repo.rebuild(state.value)
        assertEquals(RuleAction.DIRECT,engine.decide("child.api.example.com"))
        assertEquals(RuleAction.PROXY,engine.decide("deep.child.api.example.com"))
    }

    @Test fun sameAnchorScopeDoesNotBecomeAPriorityAndOverridesStillOutrankLists() {
        state.value=state.value.copy(
            hostOverrides=mapOf("example.com" to RuleAction.DIRECT,"=example.com" to RuleAction.REJECT),
            userRejectRules=listOf(RuleEntry("=child.example.com")),
        )
        repo.rebuild(state.value)
        assertEquals(RuleAction.DIRECT,engine.decide("example.com"))
        assertEquals(RuleDecision(RuleAction.DIRECT,RuleSrc.OVERRIDE),engine.decideDetailed("child.example.com"))
    }

    @Test fun invalidNamedSetSaveCannotSilentlyReplaceWithAPartialList() {
        state.value=state.value.copy(userRuleSets=listOf(UserRuleSet("set","test",RuleAction.REJECT,listOf("saved.example"))))
        vm.setRuleSetDomains("set",listOf("valid.example","not a domain.com"))
        assertEquals(listOf("saved.example"),state.value.userRuleSets.single().domains)
        val parsed=RuleInput.parseLines("*.example.com\nnot a domain.com\n2001:db8::/32")
        assertEquals(listOf(2),parsed.invalidLines)
        assertEquals(listOf("*.example.com","2001:db8::/32"),parsed.rules)
    }

    @Test fun quickActionsReactivateDisabledEntries() {
        state.value=state.value.copy(userDirectRules=listOf(RuleEntry("*.example.com",enabled=false)))
        vm.setDomainDirect("example.com"); repo.rebuild(state.value)
        assertTrue(state.value.userDirectRules.single().enabled); assertEquals(RuleAction.DIRECT,engine.decide("example.com"))
        state.value=state.value.copy(userRejectRules=listOf(RuleEntry("*.example.com",enabled=false)),userDirectRules=emptyList())
        vm.setDomainReject("example.com"); repo.rebuild(state.value)
        assertTrue(state.value.userRejectRules.single().enabled); assertEquals(RuleAction.REJECT,engine.decide("example.com"))
    }
    @Test fun quickActionReplacesSameHostOverride() {
        state.value=state.value.copy(hostOverrides=mapOf("example.com" to RuleAction.REJECT))
        vm.setDomainDirect("example.com"); repo.rebuild(state.value)
        assertTrue(state.value.userDirectRules.single().enabled)
        assertEquals(RuleDecision(RuleAction.DIRECT,RuleSrc.USER_ALLOW),engine.decideDetailed("example.com"))
        assertTrue(state.value.hostOverrides.isEmpty())
    }
    @Test fun builtinRuleHasNoClearButExplicitProxyIsAvailable() {
        val builtin=RuleCatalog.all.first { it.kind==RuleGroupKind.DIRECT }
        state.value=state.value.copy(enabledRuleGroups=setOf(builtin.id),ruleSetOverrides=mapOf(builtin.id to listOf("example.com")))
        repo.rebuild(state.value); val before=engine.decideDetailed("example.com")
        vm.clearDomainRule("example.com"); repo.rebuild(state.value); val after=engine.decideDetailed("example.com")
        assertEquals(RuleAction.DIRECT,before.action); assertEquals(before,after); assertEquals(RuleSrc.BUILTIN_APP,after.src)
        assertFalse(vm.hasDomainRule("example.com", state.value))
        vm.setDomainProxy("example.com"); repo.rebuild(state.value)
        assertEquals(RuleAction.PROXY,engine.decide("example.com"))
        assertTrue(vm.hasDomainRule("example.com",state.value))
        vm.clearDomainRule("example.com"); repo.rebuild(state.value)
        assertEquals(RuleAction.DIRECT,engine.decide("example.com"))
    }
    @Test fun specificOverrideBeatsBroaderOverride() {
        state.value=state.value.copy(hostOverrides=mapOf("example.com" to RuleAction.DIRECT,"=secret.example.com" to RuleAction.REJECT,"=proxy.example.com" to RuleAction.PROXY))
        repo.rebuild(state.value)
        assertEquals(RuleAction.REJECT,engine.decide("secret.example.com"))
        assertEquals(RuleAction.PROXY,engine.decide("proxy.example.com"))
    }
    @Test fun namedSetPreservesScopeAndIpv6AndRejectsWhitespace() {
        state.value=state.value.copy(userRuleSets=listOf(UserRuleSet("set","test",RuleAction.REJECT)))
        listOf("*.example.com","2001:db8::/32","not a domain.com").forEach { vm.addDomainToSet("set",it) }
        val domains=state.value.userRuleSets.single().domains
        assertEquals(listOf("*.example.com","2001:db8::/32"),domains)
        repo.rebuild(state.value); assertEquals(RuleAction.PROXY,engine.decide("a.b.example.com"))
        val parser=Class.forName("com.mzstd.hxmyproxy.ui.rules.RuleSetEditScreenKt").getDeclaredMethod("parseDomains",String::class.java).apply { isAccessible=true }
        val parsed=parser.invoke(null,"*.example.com\n2001:db8::/32\nnot a domain.com")
        assertEquals(domains,parsed)
    }
}
