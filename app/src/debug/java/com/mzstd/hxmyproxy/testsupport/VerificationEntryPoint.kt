package com.mzstd.hxmyproxy.testsupport

import com.mzstd.hxmyproxy.data.repository.ProxyServerRepository
import com.mzstd.hxmyproxy.data.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only access to the actual application singletons for service lifecycle verification. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VerificationEntryPoint {
    fun repository(): ProxyServerRepository
    fun settings(): SettingsRepository
}
