package com.example.domain.parser

import android.net.Uri
import android.util.Base64
import com.example.data.preferences.UserPreferences
import com.example.domain.model.VpnProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLDecoder

/**
 * Compiles supported sing-box protocols (VLESS/Reality/TLS, VMess, Trojan, Shadowsocks,
 * Hysteria2, TUIC, WireGuard, SOCKS, HTTP) into a sing-box 1.12+ compliant JSON configuration.
 * Never accepts OpenVPN configurations (OpenVPN is strictly dispatched to OpenVpnEngine).
 */
object SingBoxConfigGenerator {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; isLenient = true }

    fun generateSingBoxJson(
        protocol: VpnProtocol,
        rawConfig: String,
        prefs: UserPreferences
    ): Result<String> {
        if (protocol == VpnProtocol.OPENVPN) {
            return Result.failure(IllegalArgumentException("OpenVPN profiles cannot be executed by sing-box."))
        }
        val trimmed = rawConfig.trim()
        if (protocol == VpnProtocol.SINGBOX_JSON && trimmed.startsWith("{")) {
            return runCatching {
                json.parseToJsonElement(trimmed).jsonObject
                trimmed
            }
        }

        val outbound = buildOutbound(protocol, trimmed).getOrElse { return Result.failure(it) }
        val dnsPrimary = sanitizeIpLiteral(prefs.customDnsPrimary, "1.1.1.1")
        val dnsSecondary = sanitizeIpLiteral(prefs.customDnsSecondary, "9.9.9.9")

        val fullDoc = buildJsonObject {
            putJsonObject("log") {
                put("level", "info")
                put("timestamp", true)
            }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(buildJsonObject {
                        put("tag", "remote_dns")
                        put("type", "udp")
                        put("server", dnsPrimary)
                        put("server_port", 53)
                        put("detour", "proxy")
                    })
                    add(buildJsonObject {
                        put("tag", "local_dns")
                        put("type", "udp")
                        put("server", dnsSecondary)
                        put("server_port", 53)
                    })
                }
                put("final", "remote_dns")
                put("strategy", if (prefs.ipv6Enabled) "prefer_ipv4" else "ipv4_only")
            }
            putJsonArray("inbounds") {
                add(buildJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("interface_name", "tun0")
                    putJsonArray("address") {
                        if (prefs.ipv4Enabled || !prefs.ipv6Enabled) {
                            add(JsonPrimitive("172.19.0.1/30"))
                        }
                        if (prefs.ipv6Enabled) {
                            add(JsonPrimitive("fdfe:dcba:9876::1/126"))
                        }
                    }
                    put("mtu", 1500)
                    put("auto_route", true)
                    put("strict_route", true)
                    put("stack", "gvisor")
                })
            }
            putJsonArray("outbounds") {
                add(outbound)
                add(buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                })
            }
            putJsonObject("route") {
                putJsonArray("rules") {
                    add(buildJsonObject {
                        put("action", "sniff")
                    })
                    add(buildJsonObject {
                        put("protocol", "dns")
                        put("action", "hijack-dns")
                    })
                    add(buildJsonObject {
                        put("ip_is_private", true)
                        put("outbound", "direct")
                    })
                }
                put("auto_detect_interface", true)
                put("default_domain_resolver", "local_dns")
                put("final", "proxy")
            }
        }
        return Result.success(json.encodeToString(JsonObject.serializer(), fullDoc))
    }

    private fun buildOutbound(protocol: VpnProtocol, raw: String): Result<JsonObject> = runCatching {
        when (protocol) {
            VpnProtocol.VLESS -> buildVlessOutbound(raw)
            VpnProtocol.VMESS -> buildVmessOutbound(raw)
            VpnProtocol.TROJAN -> buildTrojanOutbound(raw)
            VpnProtocol.SHADOWSOCKS -> buildShadowsocksOutbound(raw)
            VpnProtocol.HYSTERIA2 -> buildHysteria2Outbound(raw)
            VpnProtocol.TUIC -> buildTuicOutbound(raw)
            VpnProtocol.SOCKS -> buildSocksOutbound(raw)
            VpnProtocol.HTTP -> buildHttpOutbound(raw)
            VpnProtocol.WIREGUARD -> buildWireGuardOutbound(raw)
            else -> throw IllegalArgumentException("Unsupported sing-box outbound protocol: ${protocol.displayName}")
        }
    }

    private fun buildVlessOutbound(raw: String): JsonObject {
        val uri = Uri.parse(raw)
        val uuid = uri.userInfo.orEmpty().trim()
        val host = uri.host.orEmpty().trim()
        val port = uri.port.takeIf { it > 0 } ?: 443
        val security = uri.getQueryParameter("security")?.trim()?.lowercase().orEmpty()
        val rawSni = uri.getQueryParameter("sni").orEmpty().trim()
        val effectiveSni = resolveSni(rawSni, host)
        val fp = sanitizeUtlsFingerprint(uri.getQueryParameter("fp").orEmpty())
        val rawFlow = uri.getQueryParameter("flow").orEmpty().trim()
        val flow = if (rawFlow == "xtls-rprx-vision") rawFlow else ""
        val pbk = uri.getQueryParameter("pbk").orEmpty().trim()
        val sid = uri.getQueryParameter("sid").orEmpty().trim()
        val alpn = parseAlpnList(uri.getQueryParameter("alpn").orEmpty())
        val insecure = uri.getQueryParameter("allowInsecure") == "1" || uri.getQueryParameter("insecure") == "1"
        val netType = uri.getQueryParameter("type")?.trim()?.lowercase().orEmpty()
        val headerType = uri.getQueryParameter("headerType")?.trim()?.lowercase().orEmpty()
        val path = uri.getQueryParameter("path").orEmpty().trim()
        val wsHost = uri.getQueryParameter("host").orEmpty().trim().ifBlank { effectiveSni }
        val serviceName = uri.getQueryParameter("serviceName").orEmpty().trim()

        if (security == "reality" && pbk.isBlank()) {
            throw IllegalArgumentException("VLESS Reality configuration is missing public key (pbk)")
        }

        return buildJsonObject {
            put("type", "vless")
            put("tag", "proxy")
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            if (flow.isNotBlank()) put("flow", flow)
            if (security == "tls" || security == "reality") {
                putJsonObject("tls") {
                    put("enabled", true)
                    if (effectiveSni.isNotBlank()) put("server_name", effectiveSni)
                    if (insecure && security != "reality") put("insecure", true)
                    if (alpn.isNotEmpty()) {
                        putJsonArray("alpn") { alpn.forEach { add(JsonPrimitive(it)) } }
                    }
                    if (fp.isNotBlank() || security == "reality") {
                        putJsonObject("utls") {
                            put("enabled", true)
                            put("fingerprint", fp.ifBlank { "chrome" })
                        }
                    }
                    if (security == "reality") {
                        putJsonObject("reality") {
                            put("enabled", true)
                            put("public_key", pbk)
                            if (sid.isNotBlank()) put("short_id", sid)
                        }
                    }
                }
            }
            val effectiveNet = if (netType == "tcp" && headerType == "http") "http" else netType
            addTransportIfNeeded(this, effectiveNet, path, wsHost, serviceName)
        }
    }

    private fun buildVmessOutbound(raw: String): JsonObject {
        val encoded = raw.removePrefix("vmess://").removePrefix("VMESS://").trim()
        val decoded = decodeBase64Safe(encoded)
            ?: throw IllegalArgumentException("Malformed VMess Base64 payload")
        val obj = json.parseToJsonElement(decoded).jsonObject
        val host = obj["add"]?.jsonPrimitive?.content?.trim().orEmpty()
        val port = obj["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 443
        val uuid = obj["id"]?.jsonPrimitive?.content?.trim().orEmpty()
        val alterId = (obj["aid"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceIn(0, 65535)
        val security = normalizeVmessSecurity(obj["scy"]?.jsonPrimitive?.content?.trim().orEmpty())
        val tlsField = obj["tls"]?.jsonPrimitive?.content?.trim().orEmpty()
        val tlsEnabled = tlsField.equals("tls", true) || tlsField == "1" || tlsField.equals("true", true)
        val rawSni = obj["sni"]?.jsonPrimitive?.content?.trim().orEmpty()
            .ifBlank { obj["host"]?.jsonPrimitive?.content?.trim().orEmpty() }
        val effectiveSni = resolveSni(rawSni, host)
        val fp = sanitizeUtlsFingerprint(obj["fp"]?.jsonPrimitive?.content?.trim().orEmpty())
        val alpn = parseAlpnList(obj["alpn"]?.jsonPrimitive?.content?.trim().orEmpty())
        val insecureField = obj["allowInsecure"]?.jsonPrimitive?.content?.trim()
            ?: obj["insecure"]?.jsonPrimitive?.content?.trim()
            ?: ""
        val insecure = insecureField == "1" || insecureField.equals("true", true)
        val net = obj["net"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
        val headerType = obj["type"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
        val path = obj["path"]?.jsonPrimitive?.content?.trim().orEmpty()
        val wsHost = obj["host"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { effectiveSni }

        return buildJsonObject {
            put("type", "vmess")
            put("tag", "proxy")
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            put("alter_id", alterId)
            put("security", security)
            if (tlsEnabled) {
                putJsonObject("tls") {
                    put("enabled", true)
                    if (effectiveSni.isNotBlank()) put("server_name", effectiveSni)
                    if (insecure) put("insecure", true)
                    if (alpn.isNotEmpty()) {
                        putJsonArray("alpn") { alpn.forEach { add(JsonPrimitive(it)) } }
                    }
                    if (fp.isNotBlank()) {
                        putJsonObject("utls") {
                            put("enabled", true)
                            put("fingerprint", fp)
                        }
                    }
                }
            }
            val effectiveNet = if (net == "tcp" && headerType == "http") "http" else net
            addTransportIfNeeded(this, effectiveNet, path, wsHost, path)
        }
    }

    private fun buildTrojanOutbound(raw: String): JsonObject {
        val uri = Uri.parse(raw)
        val host = uri.host.orEmpty().trim()
        val effectiveSni = resolveSni(uri.getQueryParameter("sni").orEmpty().trim(), host)
        val fp = sanitizeUtlsFingerprint(uri.getQueryParameter("fp").orEmpty())
        val alpn = parseAlpnList(uri.getQueryParameter("alpn").orEmpty())
        val insecure = uri.getQueryParameter("allowInsecure") == "1" || uri.getQueryParameter("insecure") == "1"
        val netType = uri.getQueryParameter("type")?.trim()?.lowercase().orEmpty()
        val path = uri.getQueryParameter("path").orEmpty().trim()
        val wsHost = uri.getQueryParameter("host").orEmpty().trim().ifBlank { effectiveSni }
        val serviceName = uri.getQueryParameter("serviceName").orEmpty().trim()

        return buildJsonObject {
            put("type", "trojan")
            put("tag", "proxy")
            put("server", host)
            put("server_port", uri.port.takeIf { it > 0 } ?: 443)
            put("password", urlDecode(uri.userInfo.orEmpty()))
            putJsonObject("tls") {
                put("enabled", true)
                if (effectiveSni.isNotBlank()) put("server_name", effectiveSni)
                if (insecure) put("insecure", true)
                if (alpn.isNotEmpty()) {
                    putJsonArray("alpn") { alpn.forEach { add(JsonPrimitive(it)) } }
                }
                if (fp.isNotBlank()) {
                    putJsonObject("utls") {
                        put("enabled", true)
                        put("fingerprint", fp)
                    }
                }
            }
            addTransportIfNeeded(this, netType, path, wsHost, serviceName)
        }
    }

    private fun buildShadowsocksOutbound(raw: String): JsonObject {
        val core = raw.removePrefix("ss://").removePrefix("SS://").substringBefore("#").trim()
        val decodedUserInfo: String
        val hostPort: String
        if (core.contains("@")) {
            val rawUser = urlDecode(core.substringBefore("@"))
            decodedUserInfo = if (rawUser.contains(":")) rawUser else {
                decodeBase64Safe(rawUser) ?: rawUser
            }
            hostPort = core.substringAfter("@").substringBefore("?").substringBefore("/")
        } else {
            val full = decodeBase64Safe(core) ?: throw IllegalArgumentException("Invalid Shadowsocks Base64")
            decodedUserInfo = full.substringBeforeLast("@")
            hostPort = full.substringAfterLast("@")
        }
        val method = decodedUserInfo.substringBefore(":").trim()
        val password = decodedUserInfo.substringAfter(":", "").trim()
        if (method.isBlank()) throw IllegalArgumentException("Shadowsocks cipher method is missing")
        val host = hostPort.substringBeforeLast(":").removePrefix("[").removeSuffix("]").trim()
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 8388
        return buildJsonObject {
            put("type", "shadowsocks")
            put("tag", "proxy")
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }
    }

    private fun buildHysteria2Outbound(raw: String): JsonObject {
        val uri = Uri.parse(raw)
        val host = uri.host.orEmpty()
        val sni = uri.getQueryParameter("sni").orEmpty().ifBlank { host }
        val insecure = uri.getQueryParameter("insecure") == "1" || uri.getQueryParameter("allowInsecure") == "1"
        return buildJsonObject {
            put("type", "hysteria2")
            put("tag", "proxy")
            put("server", host)
            put("server_port", uri.port.takeIf { it > 0 } ?: 443)
            put("password", urlDecode(uri.userInfo.orEmpty()))
            putJsonObject("tls") {
                put("enabled", true)
                if (sni.isNotBlank()) put("server_name", sni)
                if (insecure) put("insecure", true)
            }
        }
    }

    private fun buildTuicOutbound(raw: String): JsonObject {
        val uri = Uri.parse(raw)
        val userParts = urlDecode(uri.userInfo.orEmpty()).split(":", limit = 2)
        val host = uri.host.orEmpty()
        return buildJsonObject {
            put("type", "tuic")
            put("tag", "proxy")
            put("server", host)
            put("server_port", uri.port.takeIf { it > 0 } ?: 443)
            put("uuid", userParts.getOrElse(0) { "" })
            put("password", userParts.getOrElse(1) { "" })
            put("congestion_control", uri.getQueryParameter("congestion_control") ?: "bbr")
            putJsonObject("tls") {
                put("enabled", true)
                put("server_name", uri.getQueryParameter("sni").orEmpty().ifBlank { host })
            }
        }
    }

    private fun buildSocksOutbound(raw: String): JsonObject {
        val uri = Uri.parse(raw)
        return buildJsonObject {
            put("type", "socks")
            put("tag", "proxy")
            put("server", uri.host.orEmpty())
            put("server_port", uri.port.takeIf { it > 0 } ?: 1080)
            put("version", "5")
        }
    }

    private fun buildHttpOutbound(raw: String): JsonObject {
        val uri = Uri.parse(raw)
        return buildJsonObject {
            put("type", "http")
            put("tag", "proxy")
            put("server", uri.host.orEmpty())
            put("server_port", uri.port.takeIf { it > 0 } ?: 8080)
        }
    }

    private fun buildWireGuardOutbound(raw: String): JsonObject {
        var privateKey = ""
        var peerKey = ""
        var endpoint = ""
        var address = "10.0.0.2/32"
        for (line in raw.lineSequence()) {
            val clean = line.substringBefore("#").trim()
            if (!clean.contains("=")) continue
            val k = clean.substringBefore("=").trim().lowercase()
            val v = clean.substringAfter("=").trim()
            when (k) {
                "privatekey" -> privateKey = v
                "publickey" -> peerKey = v
                "endpoint" -> endpoint = v
                "address" -> address = v
            }
        }
        val host = endpoint.substringBeforeLast(":")
        val port = endpoint.substringAfterLast(":").toIntOrNull() ?: 51820
        return buildJsonObject {
            put("type", "wireguard")
            put("tag", "proxy")
            put("server", host)
            put("server_port", port)
            put("private_key", privateKey)
            put("peer_public_key", peerKey)
            put("local_address", buildJsonArray {
                address.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { add(JsonPrimitive(it)) }
            })
        }
    }

    private fun addTransportIfNeeded(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        netType: String,
        path: String,
        hostHeader: String,
        serviceName: String
    ) {
        when (netType) {
            "ws" -> builder.putJsonObject("transport") {
                put("type", "ws")
                put("path", path.ifBlank { "/" })
                if (hostHeader.isNotBlank()) {
                    putJsonObject("headers") { put("Host", hostHeader) }
                }
            }
            "grpc" -> builder.putJsonObject("transport") {
                put("type", "grpc")
                put("service_name", serviceName.ifBlank { path.removePrefix("/") })
            }
            "http", "h2" -> builder.putJsonObject("transport") {
                put("type", "http")
                if (hostHeader.isNotBlank()) {
                    putJsonArray("host") { add(JsonPrimitive(hostHeader)) }
                }
                put("path", path.ifBlank { "/" })
            }
            "httpupgrade" -> builder.putJsonObject("transport") {
                put("type", "httpupgrade")
                if (hostHeader.isNotBlank()) put("host", hostHeader)
                put("path", path.ifBlank { "/" })
            }
        }
    }

    private val validUtlsFingerprints = setOf(
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized"
    )

    private val ipv4LiteralRegex = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")

    private fun isIpLiteral(value: String): Boolean {
        val clean = value.trim().removePrefix("[").removeSuffix("]")
        if (clean.isBlank()) return false
        if (ipv4LiteralRegex.matches(clean)) {
            return clean.split(".").all { it.toIntOrNull() in 0..255 }
        }
        return clean.contains(":") && clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }

    private fun sanitizeIpLiteral(input: String, fallback: String): String {
        val clean = input.trim()
        return if (isIpLiteral(clean)) clean else fallback
    }

    private fun resolveSni(explicitSni: String, host: String): String {
        val cleanSni = explicitSni.trim()
        if (cleanSni.isNotBlank()) return cleanSni
        val cleanHost = host.trim()
        return if (cleanHost.isNotBlank() && !isIpLiteral(cleanHost)) cleanHost else ""
    }

    private fun sanitizeUtlsFingerprint(fp: String): String {
        val clean = fp.trim().lowercase()
        return if (clean in validUtlsFingerprints) clean else ""
    }

    private fun parseAlpnList(raw: String): List<String> =
        urlDecode(raw).split(",").map { it.trim() }.filter { it.isNotBlank() }

    private fun normalizeVmessSecurity(raw: String): String = when (raw.trim().lowercase()) {
        "auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305" -> raw.trim().lowercase()
        "chacha20-ietf-poly1305" -> "chacha20-poly1305"
        else -> "auto"
    }

    private fun decodeBase64Safe(input: String): String? {
        val padded = input.replace('-', '+').replace('_', '/').let { s ->
            val rem = s.length % 4
            if (rem == 0) s else s + "=".repeat(4 - rem)
        }
        return runCatching {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
