package app.snapcast.cast.net

import java.net.Inet4Address
import java.net.InetAddress

/** Picks the address a receiver on the same LAN can reach the phone at. */
internal object LanAddressPicker {
    /** First usable IPv4 address (private ranges preferred), ignoring loopback, link-local and wildcard. */
    fun pick(addresses: List<InetAddress>): String? = addresses
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress && !it.isMulticastAddress }
        .sortedByDescending { it.isSiteLocalAddress }
        .firstNotNullOfOrNull { it.hostAddress }
}
