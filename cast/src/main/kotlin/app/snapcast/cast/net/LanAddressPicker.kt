package app.snapcast.cast.net

import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

/** Picks the network and the address a receiver on the same LAN can reach the phone at. */
internal object LanAddressPicker {
    /** The transports [isLanTransport] looks at. */
    val RELEVANT_TRANSPORTS: List<Int> = listOf(
        NetworkCapabilities.TRANSPORT_WIFI,
        NetworkCapabilities.TRANSPORT_ETHERNET,
        NetworkCapabilities.TRANSPORT_VPN,
    )

    /**
     * True for a Wi-Fi or Ethernet network that is not a VPN. Since Android 9 a VPN also reports the
     * transports of its underlying network (a VPN over Wi-Fi has TRANSPORT_WIFI), but its address is a
     * tunnel address the receiver cannot reach.
     */
    fun isLanTransport(transports: Set<Int>): Boolean =
        NetworkCapabilities.TRANSPORT_VPN !in transports &&
            (NetworkCapabilities.TRANSPORT_WIFI in transports || NetworkCapabilities.TRANSPORT_ETHERNET in transports)

    /** First usable IPv4 address (private ranges preferred), ignoring loopback, link-local and wildcard. */
    fun pick(addresses: List<InetAddress>): String? = addresses
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress && !it.isMulticastAddress }
        .sortedByDescending { it.isSiteLocalAddress }
        .firstNotNullOfOrNull { it.hostAddress }
}
