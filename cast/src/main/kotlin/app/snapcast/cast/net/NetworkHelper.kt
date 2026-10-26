package app.snapcast.cast.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Finds the phone's LAN address, which receivers use to reach the local proxy. */
@Singleton
internal class NetworkHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * IPv4 address of the phone on its Wi-Fi (or Ethernet) network, or null when not on such a network.
     * The active network comes first; VPNs are skipped and the other networks checked, so a VPN on top of
     * Wi-Fi still yields the Wi-Fi address (a tunnel address is unreachable from the LAN).
     */
    fun lanIpv4(): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return try {
            candidateNetworks(connectivity).firstNotNullOfOrNull { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
                val transports = LanAddressPicker.RELEVANT_TRANSPORTS.filterTo(HashSet()) { capabilities.hasTransport(it) }
                if (!LanAddressPicker.isLanTransport(transports)) return@firstNotNullOfOrNull null
                val addresses = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address }
                LanAddressPicker.pick(addresses)
            }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun candidateNetworks(connectivity: ConnectivityManager): List<Network> {
        val active = connectivity.activeNetwork
        @Suppress("DEPRECATION")
        val all = connectivity.allNetworks.toList()
        return listOfNotNull(active) + all.filter { it != active }
    }
}
