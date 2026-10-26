package app.snapcast.cast.net

import android.net.NetworkCapabilities
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.InetAddress

class LanAddressPickerTest {

    private fun address(literal: String): InetAddress = InetAddress.getByName(literal)

    @Test
    fun `picks the private IPv4 address`() {
        val addresses = listOf(address("fe80::1"), address("169.254.10.2"), address("192.168.1.20"))
        assertThat(LanAddressPicker.pick(addresses)).isEqualTo("192.168.1.20")
    }

    @Test
    fun `prefers site local over public addresses`() {
        val addresses = listOf(address("100.70.1.2"), address("10.0.0.7"))
        assertThat(LanAddressPicker.pick(addresses)).isEqualTo("10.0.0.7")
    }

    @Test
    fun `ignores loopback and IPv6 only links`() {
        assertThat(LanAddressPicker.pick(listOf(address("127.0.0.1"), address("2001:db8::5")))).isNull()
        assertThat(LanAddressPicker.pick(emptyList())).isNull()
    }

    @Test
    fun `Wi-Fi and Ethernet networks are LAN networks`() {
        assertThat(LanAddressPicker.isLanTransport(setOf(NetworkCapabilities.TRANSPORT_WIFI))).isTrue()
        assertThat(LanAddressPicker.isLanTransport(setOf(NetworkCapabilities.TRANSPORT_ETHERNET))).isTrue()
    }

    @Test
    fun `a VPN over Wi-Fi is not a LAN network`() {
        val vpnOverWifi = setOf(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI)
        assertThat(LanAddressPicker.isLanTransport(vpnOverWifi)).isFalse()
        assertThat(LanAddressPicker.isLanTransport(setOf(NetworkCapabilities.TRANSPORT_VPN))).isFalse()
        assertThat(LanAddressPicker.isLanTransport(emptySet())).isFalse()
    }
}
