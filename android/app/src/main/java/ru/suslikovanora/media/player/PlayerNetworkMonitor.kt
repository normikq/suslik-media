package ru.suslikovanora.media.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import ru.suslikovanora.media.AppLog
import ru.suslikovanora.media.core.BurstProxy

class PlayerNetworkMonitor(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun hasPlayer(): Boolean
        fun isViaReserve(): Boolean
        fun shouldReconnectViaReserve(wasLost: Boolean): Boolean
        fun onScheduleReconnect(delayMs: Long)
        fun onNetworkLost()
        fun onNetworkRestoredWithoutReconnect()
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    var lastNetwork: Network? = null
        private set

    var isNetworkLost = false
        private set

    var awaitingValidation = false
        private set

    private var kickWhenValidated = false

    fun activeNetwork(): Network? =
        runCatching { connectivityManager?.activeNetwork }.getOrNull()

    fun isValidated(network: Network): Boolean {
        val cm = connectivityManager ?: return true
        return runCatching { cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            .getOrNull() ?: true
    }

    fun register() {
        val cm = connectivityManager ?: return
        if (networkCallback != null) return

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post { handleNetworkAvailable(network) }
            }

            override fun onLost(network: Network) {
                mainHandler.post { handleNetworkLost(network) }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // A freshly attached mobile/Wi-Fi network is often not validated yet: reconnect once it is.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    mainHandler.post { handleNetworkValidated(network) }
                }
            }
        }

        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: Exception) {
            networkCallback = null
        }
    }

    fun unregister() {
        networkCallback?.let { cb ->
            connectivityManager?.let { runCatching { it.unregisterNetworkCallback(cb) } }
        }
        networkCallback = null
        lastNetwork = null
        isNetworkLost = false
        awaitingValidation = false
        kickWhenValidated = false
    }
    
    fun resetState() {
        lastNetwork = null
        isNetworkLost = false
        awaitingValidation = false
        kickWhenValidated = false
    }

    private fun handleNetworkAvailable(network: Network) {
        val previous = lastNetwork
        val wasLost = isNetworkLost
        lastNetwork = network
        isNetworkLost = false

        // The device really switched networks (not the first callback after registering): the reserve's requests in
        // flight belong to the old one. 2.0.0.4 journal: after Wi-Fi → mobile the film hung ~36 s on a 30 s timeout.
        if (previous != network && (previous != null || wasLost)) {
            AppLog.write("network changed (${if (isValidated(network)) "validated" else "not validated yet"}): restarting the reserve's requests")
            BurstProxy.networkChanged()
            kickWhenValidated = !isValidated(network)
        }

        if (!callback.hasPlayer()) return
        if (previous == null && !wasLost) return // initial callback right after registration

        if (callback.isViaReserve() && !callback.shouldReconnectViaReserve(wasLost)) {
            // VLC's connection is local and still alive; the reserve reconnects to the server by itself.
            if (wasLost) callback.onNetworkRestoredWithoutReconnect()
            return
        }

        // Otherwise (2.0.2 journal): the network was gone long enough for the reserve to run dry and VLC to stop —
        // nothing will restart it, so reconnect like for a plain stream.
        if (previous == network && !wasLost) return

        // Wi-Fi <-> mobile switch or connectivity is back: old sockets are dead.
        if (isValidated(network)) {
            awaitingValidation = false
            callback.onScheduleReconnect(500L)
        } else {
            // Not validated yet: wait for onCapabilitiesChanged, but never longer than a few seconds.
            awaitingValidation = true
            callback.onScheduleReconnect(4_000L)
        }
    }

    private fun handleNetworkValidated(network: Network) {
        if (kickWhenValidated && network == lastNetwork) {
            kickWhenValidated = false
            BurstProxy.networkChanged() // the connections made before validation may hang
        }
        if (!awaitingValidation || network != lastNetwork || !callback.hasPlayer() || callback.isViaReserve()) return
        awaitingValidation = false
        callback.onScheduleReconnect(300L)
    }

    private fun handleNetworkLost(network: Network) {
        if (network != lastNetwork) return // an old network went away after we already switched
        lastNetwork = null
        isNetworkLost = true
        BurstProxy.networkLost()
        callback.onNetworkLost()
    }
}
