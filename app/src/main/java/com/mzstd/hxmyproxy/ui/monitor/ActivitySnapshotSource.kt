package com.mzstd.hxmyproxy.ui.monitor

import com.mzstd.hxmyproxy.core.model.ShareState
import com.mzstd.hxmyproxy.core.proxy.RequestActivitySnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

internal data class ActivityInput(val share: ShareState, val recording: Boolean)

/**
 * Sample request details AFTER the published counters arrive, never combine counters with a
 * separately cached one-second-old request sample. Rule events precede bindHost in both proxies,
 * so a newly visible domain can use its already recorded route immediately. Eviction and disabled
 * recording still remain genuine missing evidence. This does not compare the two data sources'
 * counts or change their bounded storage/transport lifetimes.
 */
internal fun activitySnapshots(
    input: Flow<ActivityInput>,
    frozen: Flow<ActivitySnapshot?>,
    readRequests: () -> RequestActivitySnapshot,
    refresh: Flow<Unit> = flow { while (true) { emit(Unit); delay(1000) } },
    clock: () -> Long = System::currentTimeMillis,
): Flow<ActivitySnapshot> = combine(input, refresh, frozen) { current, _, paused ->
    val evidence = readRequests()
    val share = current.share
    if (paused != null && paused.session == share.sessionStartedAtMs &&
        paused.evidenceGeneration == evidence.generation && current.recording == evidence.enabled) {
        paused.copy(paused = true)
    } else ActivitySnapshot(
        session = share.sessionStartedAtMs, evidenceGeneration = evidence.generation,
        clients = share.clients, domains = share.topDomains,
        requests = if (share.running && current.recording && evidence.enabled) evidence.records else emptyList(),
        totalBytes = share.totalBytes, downRate = share.downloadRateBps, upRate = share.uploadRateBps,
        capturedAt = clock(),
    )
}
