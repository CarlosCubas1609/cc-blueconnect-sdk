package com.ccubas.blueconnect

import android.content.Context
import com.ccubas.blueconnect.core.BlueConnectClient
import com.ccubas.blueconnect.core.storage.BluetoothSessionStorage
import com.ccubas.blueconnect.internal.BluetoothManagerFactory
import com.ccubas.blueconnect.storage.InMemorySessionStorage

/**
 * Public entry point of the SDK.
 *
 * Typical usage:
 * ```kotlin
 * val client = BlueConnect.create(applicationContext)
 * // …or with persistent sessions:
 * val client = BlueConnect.create(
 *     context = applicationContext,
 *     storage = DataStoreSessionStorage(applicationContext), // from :storage-datastore
 * )
 * ```
 *
 * Apps using Hilt can wrap this in their own `@Module @InstallIn(SingletonComponent::class)`.
 */
object BlueConnect {

    /**
     * Build a [BlueConnectClient]. Always uses the application context internally so the SDK
     * is safe to keep around for the process lifetime.
     *
     * @param context Any Context — `applicationContext` is used internally.
     * @param storage Persistence backend for the auto-reconnect feature. Defaults to
     *                [InMemorySessionStorage]; pass a real implementation (e.g.
     *                `DataStoreSessionStorage` from `:storage-datastore`) to survive process death.
     */
    fun create(
        context: Context,
        storage: BluetoothSessionStorage = InMemorySessionStorage(),
    ): BlueConnectClient {
        val appContext = context.applicationContext
        return BlueConnectClientImpl(
            context = appContext,
            managerFactory = BluetoothManagerFactory(appContext),
            sessionStorage = storage,
        )
    }
}
