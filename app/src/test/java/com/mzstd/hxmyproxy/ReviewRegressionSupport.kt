package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.network.*
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.EgressGuard
import io.mockk.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

internal suspend fun invokeSuspend(target: Any, method: String, vararg args: Any?): Any? =
    suspendCoroutineUninterceptedOrReturn { c ->
        val m = target.javaClass.declaredMethods.single { it.name == method && it.parameterCount == args.size + 1 }
        m.isAccessible = true
        try { m.invoke(target, *args, c) } catch (e: InvocationTargetException) { throw e.targetException }
    }

internal fun evidence(name: String, text: String) {
    System.getProperty("review.evidence")?.let { File(it, "$name.txt").writeText(text + "\n") }
    println("REGRESSION $name: $text")
}
