package com.mzstd.hxmyproxy.di

import android.content.Context
import com.mzstd.hxmyproxy.core.network.ConnectivityObserver
import com.mzstd.hxmyproxy.core.network.InterfaceScanner
import com.mzstd.hxmyproxy.core.network.LocalNetworkPermissionManager
import com.mzstd.hxmyproxy.core.network.MdnsPublisher
import com.mzstd.hxmyproxy.core.security.DefaultEgressGuard
import com.mzstd.hxmyproxy.core.security.SingleCredentialAuthenticator
import com.mzstd.hxmyproxy.core.security.SubnetAccessController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 接线（单例）。把需要 Context / 接口实现的组件集中提供，
 * 其余有 @Inject 构造器的（Repository）由 Hilt 直接装配。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun interfaceScanner(): InterfaceScanner = InterfaceScanner()

    @Provides @Singleton
    fun connectivityObserver(@ApplicationContext context: Context): ConnectivityObserver =
        ConnectivityObserver(context)

    @Provides @Singleton
    fun mdnsPublisher(@ApplicationContext context: Context): MdnsPublisher =
        MdnsPublisher(context)

    @Provides @Singleton
    fun localNetworkPermissionManager(@ApplicationContext context: Context): LocalNetworkPermissionManager =
        LocalNetworkPermissionManager(context)

    @Provides @Singleton
    fun egressGuard(): DefaultEgressGuard = DefaultEgressGuard()

    @Provides @Singleton
    fun authenticator(): SingleCredentialAuthenticator = SingleCredentialAuthenticator()

    @Provides @Singleton
    fun subnetAccessController(): SubnetAccessController = SubnetAccessController()
}
