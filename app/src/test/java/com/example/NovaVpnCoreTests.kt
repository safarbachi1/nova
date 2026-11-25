package com.example

import com.example.core.security.CryptoVault
import com.example.core.security.NetworkSecurityValidator
import com.example.core.security.SecretRedactor
import com.example.data.local.entity.ConfigurationEntity
import com.example.data.local.entity.ServerEntity
import com.example.data.local.entity.ServerWithConfiguration
import com.example.data.preferences.UserPreferences
import com.example.domain.model.SelectionMode
import com.example.domain.model.ServerStatus
import com.example.domain.model.ValidationStatus
import com.example.domain.model.VpnProtocol
import com.example.domain.parser.ConfigParser
import com.example.domain.parser.SingBoxConfigGenerator
import com.example.domain.selector.SmartServerSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NovaVpnCoreTests {

    @Test
    fun `parses VLESS Reality URI and compiles sing-box JSON`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@se-node.example.org:443?encryption=none&security=reality&sni=example.org&fp=chrome&pbk=TESTPUBKEY&sid=ab12&type=tcp#Sweden-SE-Node"
        val parsed = ConfigParser.parseBatch(uri)
        assertEquals(1, parsed.size)
        val item = parsed.first()
        assertEquals(VpnProtocol.VLESS, item.protocol)
        assertEquals("se-node.example.org", item.hostname)
        assertEquals(443, item.port)
        assertEquals("SE", item.countryCodeHint)
        assertEquals(ValidationStatus.VALID, item.validationStatus)

        val singBoxResult = SingBoxConfigGenerator.generateSingBoxJson(
            protocol = item.protocol,
            rawConfig = item.rawConfigData,
            prefs = UserPreferences()
        )
        assertTrue(singBoxResult.isSuccess)
        val json = singBoxResult.getOrThrow()
        assertTrue(json.contains("\"type\": \"vless\""))
        assertTrue(json.contains("\"reality\""))
        assertTrue(json.contains("\"tun-in\""))
        assertTrue(json.contains("\"hijack-dns\""))
        assertTrue(json.contains("\"default_domain_resolver\""))
    }

    @Test
    fun `parses WireGuard INI configuration and rejects loopback hosts`() {
        val wg = """
            [Interface]
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Address = 10.66.66.2/32
            DNS = 1.1.1.1

            [Peer]
            PublicKey = BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=
            Endpoint = ch-zurich.example.net:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()
        val parsed = ConfigParser.parseBatch(wg)
        assertEquals(1, parsed.size)
        assertEquals(VpnProtocol.WIREGUARD, parsed.first().protocol)
        assertEquals("ch-zurich.example.net", parsed.first().hostname)
        assertEquals(51820, parsed.first().port)
        assertEquals(ValidationStatus.VALID, parsed.first().validationStatus)

        val loopbackVless = "vless://11111111-2222-3333-4444-555555555555@127.0.0.1:443#Local"
        val blocked = ConfigParser.parseBatch(loopbackVless)
        assertEquals(ValidationStatus.INVALID, blocked.first().validationStatus)
    }

    @Test
    fun `sanitizes dangerous OpenVPN script directives and refuses passing OpenVPN to sing-box`() {
        val ovpn = """
            client
            dev tun
            proto udp
            remote de-fra.example.org 1194
            script-security 2
            up /system/bin/sh -c evil
            plugin /tmp/evil.so
            <ca>
            -----BEGIN CERTIFICATE-----
            TESTCERT
            -----END CERTIFICATE-----
            </ca>
        """.trimIndent()
        val parsed = ConfigParser.parseOpenVpnProfile(ovpn)
        assertEquals(VpnProtocol.OPENVPN, parsed.protocol)
        assertEquals("de-fra.example.org", parsed.hostname)
        assertEquals(1194, parsed.port)
        assertEquals(ValidationStatus.WARNING, parsed.validationStatus)
        assertFalse(parsed.rawConfigData.contains("script-security"))
        assertFalse(parsed.rawConfigData.contains("up /system"))
        assertFalse(parsed.rawConfigData.contains("plugin "))

        val rejectedBySingBox = SingBoxConfigGenerator.generateSingBoxJson(
            protocol = VpnProtocol.OPENVPN,
            rawConfig = parsed.rawConfigData,
            prefs = UserPreferences()
        )
        assertTrue(rejectedBySingBox.isFailure)
    }

    @Test
    fun `encrypts and decrypts secrets cleanly and redacts sensitive logs`() {
        val vault = CryptoVault()
        val secret = "PrivateKey = SUPER_SECRET_KEY_12345"
        val cipher = vault.encrypt(secret)
        assertTrue(vault.isEncrypted(cipher))
        assertNotEquals(secret, cipher)
        assertEquals(secret, vault.decrypt(cipher))

        val logLine = "Connecting vless://11111111-2222-3333-4444-555555555555@node.example.org:443?pbk=SECRET_PBK password=topsecret"
        val redacted = SecretRedactor.redact(logLine)
        assertFalse(redacted.contains("11111111-2222"))
        assertFalse(redacted.contains("SECRET_PBK"))
        assertFalse(redacted.contains("topsecret"))
    }

    @Test
    fun `enforces HTTPS-only public discovery URLs and blocks private targets`() {
        assertTrue(NetworkSecurityValidator.validateHttpsSourceUrl("https://www.vpngate.net/api/iphone/").isSuccess)
        assertTrue(NetworkSecurityValidator.validateHttpsSourceUrl("http://www.vpngate.net/api/iphone/").isFailure)
        assertTrue(NetworkSecurityValidator.validateHttpsSourceUrl("https://127.0.0.1/configs").isFailure)
        assertTrue(NetworkSecurityValidator.validateHttpsSourceUrl("https://192.168.1.1/sub").isFailure)
        assertTrue(NetworkSecurityValidator.validateHttpsSourceUrl("https://169.254.169.254/latest").isFailure)
    }

    @Test
    fun `smart server selector picks best latency, stability, and speed accurately`() {
        fun mk(id: Long, cc: String, latency: Int, reliability: Float, dl: Float, approved: Boolean): ServerWithConfiguration {
            val s = ServerEntity(
                id = id,
                hostname = "node$id.example.org",
                port = 443,
                countryCode = cc,
                countryName = cc,
                protocol = VpnProtocol.WIREGUARD,
                latency = latency,
                reliabilityScore = reliability,
                status = ServerStatus.ONLINE,
                downloadSpeedMbps = dl,
                successCount = (reliability * 10).toInt(),
                failureCount = 10 - (reliability * 10).toInt(),
                endpointHash = "h$id"
            )
            val c = ConfigurationEntity(
                id = id,
                serverId = id,
                protocol = VpnProtocol.WIREGUARD,
                configurationData = "enc",
                validationStatus = ValidationStatus.VALID,
                trustedSource = approved,
                userApprovedUntrusted = approved
            )
            return ServerWithConfiguration(s, c)
        }

        val list = listOf(
            mk(1, "SE", latency = 45, reliability = 0.9f, dl = 55f, approved = true),
            mk(2, "CH", latency = 28, reliability = 0.6f, dl = 30f, approved = true),
            mk(3, "DE", latency = 95, reliability = 0.98f, dl = 140f, approved = true),
            mk(4, "NL", latency = 15, reliability = 1.0f, dl = 200f, approved = false)
        )

        assertEquals(2L, SmartServerSelector.selectBest(list, SelectionMode.BEST_LATENCY, onlyApproved = true)?.server?.id)
        assertEquals(3L, SmartServerSelector.selectBest(list, SelectionMode.BEST_SPEED, onlyApproved = true)?.server?.id)
        assertEquals(3L, SmartServerSelector.selectBest(list, SelectionMode.BEST_STABILITY, onlyApproved = true)?.server?.id)
        assertNotNull(SmartServerSelector.selectBest(list, SelectionMode.AUTO, onlyApproved = true))
    }

    @Test
    fun `compiles VMess WebSocket TLS config and normalizes legacy fields for sing-box 1_12`() {
        val vmessJson = """{"v":"2","ps":"DE-Node","add":"de-node.example.org","port":"443","id":"11111111-2222-3333-4444-555555555555","aid":"0","scy":"chacha20-ietf-poly1305","net":"ws","type":"none","host":"cdn.example.org","path":"/ws","tls":"tls","sni":"cdn.example.org","fp":"chrome","alpn":"h2,http/1.1"}"""
        val b64 = android.util.Base64.encodeToString(vmessJson.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val uri = "vmess://$b64"
        val parsed = ConfigParser.parseBatch(uri)
        assertEquals(1, parsed.size)
        val item = parsed.first()
        assertEquals(VpnProtocol.VMESS, item.protocol)
        assertEquals(ValidationStatus.VALID, item.validationStatus)

        val compiled = SingBoxConfigGenerator.generateSingBoxJson(
            protocol = VpnProtocol.VMESS,
            rawConfig = item.rawConfigData,
            prefs = UserPreferences(customDnsPrimary = "invalid-hostname")
        ).getOrThrow()

        assertTrue(compiled.contains("\"type\": \"vmess\""))
        assertTrue(compiled.contains("\"security\": \"chacha20-poly1305\""))
        assertTrue(compiled.contains("\"type\": \"ws\""))
        assertTrue(compiled.contains("\"server\": \"1.1.1.1\""))
        assertTrue(compiled.contains("\"server_port\": 53"))
    }
}
