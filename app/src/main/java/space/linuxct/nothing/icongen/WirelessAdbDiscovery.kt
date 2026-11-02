package space.linuxct.nothing.icongen

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import java.net.InetAddress
import java.util.concurrent.Executor

/** Session-scoped mDNS only: advertised addresses must belong to this phone before use. */
internal class WirelessAdbDiscovery(
    context: Context,
    private val handler: Handler,
    private val onEndpoint: (Kind, WirelessAdbEndpoint?) -> Unit,
    private val onFailure: () -> Unit,
) {
    enum class Kind(val serviceType: String) {
        PAIRING("_adb-tls-pairing._tcp."),
        CONNECT("_adb-tls-connect._tcp."),
    }

    private data class Key(val kind: Kind, val name: String)

    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val executor = Executor { handler.post(it) }
    private val listeners = mutableMapOf<Kind, NsdManager.DiscoveryListener>()
    private val found = mutableMapOf<Key, NsdServiceInfo>()
    private val endpoints = linkedMapOf<Key, WirelessAdbEndpoint>()
    private val selected = mutableMapOf<Kind, WirelessAdbEndpoint?>()
    private val subscriptions = mutableMapOf<Key, NsdManager.ServiceInfoCallback>()
    private var generation = 0
    private var running = false

    fun start() {
        stop()
        running = true
        val token = generation
        Kind.entries.forEach { kind ->
            val listener = object : NsdManager.DiscoveryListener {
                private var stopRetried = false

                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    dispatch(token) {
                        listeners.remove(kind)
                        onFailure()
                    }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    if (!stopRetried) {
                        stopRetried = true
                        runCatching { manager.stopServiceDiscovery(this) }
                    }
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    dispatch(token) {
                        val key = Key(kind, serviceInfo.serviceName)
                        if (found.put(key, serviceInfo) == null) {
                            subscribe(key, serviceInfo, token)
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    dispatch(token) {
                        val key = Key(kind, serviceInfo.serviceName)
                        found.remove(key)
                        unsubscribe(key)
                        endpoints.remove(key)
                        publish(kind)
                    }
                }
            }
            listeners[kind] = listener
            try {
                manager.discoverServices(kind.serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (_: RuntimeException) {
                listeners.remove(kind)
                onFailure()
            }
        }
    }

    fun stop() {
        running = false
        generation++
        listeners.values.forEach { runCatching { manager.stopServiceDiscovery(it) } }
        listeners.clear()
        subscriptions.values.forEach { runCatching { manager.unregisterServiceInfoCallback(it) } }
        subscriptions.clear()
        found.clear()
        endpoints.clear()
        selected.clear()
        Kind.entries.forEach { onEndpoint(it, null) }
    }

    private fun dispatch(token: Int, action: () -> Unit) {
        handler.post { if (running && generation == token) action() }
    }

    private fun subscribe(key: Key, info: NsdServiceInfo, token: Int) {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                if (running && generation == token) subscriptions.remove(key)
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                if (!running || generation != token || key !in found) return
                val endpoint = localEndpoint(serviceInfo)
                if (endpoint == null) {
                    endpoints.remove(key)
                } else {
                    endpoints[key] = endpoint
                }
                publish(key.kind)
            }

            override fun onServiceLost() {
                if (!running || generation != token) return
                endpoints.remove(key)
                publish(key.kind)
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        subscriptions[key] = callback
        try {
            manager.registerServiceInfoCallback(info, executor, callback)
        } catch (_: RuntimeException) {
            subscriptions.remove(key)
        }
    }

    private fun unsubscribe(key: Key) {
        subscriptions.remove(key)?.let { runCatching { manager.unregisterServiceInfoCallback(it) } }
    }


    private fun localEndpoint(info: NsdServiceInfo): WirelessAdbEndpoint? {
        val addresses: List<InetAddress> = info.hostAddresses
        for (address in addresses) {
            val host = address.hostAddress ?: continue
            val endpoint = runCatching { WirelessAdbEndpoint.parse(host, info.port.toString()) }.getOrNull()
            if (endpoint != null) return endpoint
        }
        return null
    }

    private fun publish(kind: Kind) {
        val endpoint = endpoints.entries.firstOrNull { it.key.kind == kind }?.value
        if (selected[kind] != endpoint) {
            selected[kind] = endpoint
            onEndpoint(kind, endpoint)
        }
    }
}
