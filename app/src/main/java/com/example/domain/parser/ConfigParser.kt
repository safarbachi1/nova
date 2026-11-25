package com.example.domain.parser

import android.net.Uri
import android.util.Base64
import com.example.core.security.NetworkSecurityValidator
import com.example.domain.model.EuropeanCountries
import com.example.domain.model.ValidationStatus
import com.example.domain.model.VpnProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.security.MessageDigest

data class ParsedConfig(
    val hostname: String,
    val port: Int,
    val protocol: VpnProtocol,
    val displayName: String,
    val rawConfigData: String,
    val endpointHash: String,
    val validationStatus: ValidationStatus,
    val validationMessage: String,
    val countryCodeHint: String = "",
    val countryNameHint: String = ""
)

object ConfigParser {
    private const val MAX_INPUT_BYTES = 8 * 1024 * 1024 // 8 MB safety limit for full VPNGate CSV feeds
    private const val MAX_ITEMS_PER_BATCH = 400

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val dangerousOvpnDirectives = setOf(
        "script-security",
        "up",
        "down",
        "route-up",
        "route-pre-down",
        "ipchange",
        "client-connect",
        "client-disconnect",
        "learn-address",
        "auth-user-pass-verify",
        "tls-verify",
        "plugin"
    )

    fun parseBatch(rawInput: String): List<ParsedConfig> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_INPUT_BYTES) return emptyList()

        // 1. VPNGate CSV feed check
        if (trimmed.startsWith("*vpn_servers") || trimmed.contains("#HostName,IP,Score")) {
            return parseVpnGateCsv(trimmed).take(MAX_ITEMS_PER_BATCH)
        }

        // 2. WireGuard INI config
        if (trimmed.contains("[Interface]", ignoreCase = true) && trimmed.contains("[Peer]", ignoreCase = true)) {
            return listOfNotNull(parseWireGuardConf(trimmed))
        }

        // 3. OpenVPN .ovpn profile
        if (looksLikeOpenVpn(trimmed)) {
            return listOfNotNull(parseOpenVpnProfile(trimmed))
        }

        // 4. Sing-box or Xray JSON
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val fromJson = parseJsonConfig(trimmed)
            if (fromJson.isNotEmpty()) return fromJson.take(MAX_ITEMS_PER_BATCH)
        }

        // 5. Clash YAML
        if (trimmed.contains("proxies:") && trimmed.contains("server:")) {
            val fromClash = parseClashYaml(trimmed)
            if (fromClash.isNotEmpty()) return fromClash.take(MAX_ITEMS_PER_BATCH)
        }

        // 6. Possibly Base64-encoded subscription list
        val linesSource = maybeDecodeBase64Subscription(trimmed)
        val results = mutableListOf<ParsedConfig>()
        val seen = HashSet<String>()

        for (rawLine in linesSource.lineSequence()) {
            if (results.size >= MAX_ITEMS_PER_BATCH) break
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            val parsed = parseSingleUri(line) ?: continue
            if (seen.add(parsed.endpointHash)) {
                results += parsed
            }
        }
        return results
    }

    fun parseSingleUri(uriString: String): ParsedConfig? {
        val line = uriString.trim()
        val scheme = line.substringBefore("://", "").lowercase()
        return when (scheme) {
            "vless" -> parseStandardProxyUri(line, VpnProtocol.VLESS)
            "vmess" -> parseVmessUri(line)
            "trojan" -> parseStandardProxyUri(line, VpnProtocol.TROJAN)
            "ss" -> parseShadowsocksUri(line)
            "hysteria" -> parseStandardProxyUri(line, VpnProtocol.HYSTERIA)
            "hysteria2", "hy2" -> parseStandardProxyUri(line, VpnProtocol.HYSTERIA2)
            "tuic" -> parseStandardProxyUri(line, VpnProtocol.TUIC)
            "wireguard", "wg" -> parseStandardProxyUri(line, VpnProtocol.WIREGUARD)
            "socks", "socks5" -> parseStandardProxyUri(line, VpnProtocol.SOCKS)
            "http", "https" -> if (line.contains("@")) parseStandardProxyUri(line, VpnProtocol.HTTP) else null
            else -> null
        }
    }

    private fun parseStandardProxyUri(raw: String, protocol: VpnProtocol): ParsedConfig {
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val host = uri?.host?.trim().orEmpty()
        val port = uri?.port?.takeIf { it > 0 } ?: defaultPortFor(protocol)
        val fragment = decodeFragment(uri?.fragment.orEmpty())
        val name = fragment.ifBlank { "${protocol.displayName} • $host" }
        val countryHint = detectCountryCodeFromText(fragment)

        val (status, message) = validateEndpoint(host, port, protocol)
        val extraWarning = if (protocol == VpnProtocol.VLESS && raw.contains("security=reality", true) && !raw.contains("pbk=", true)) {
            "VLESS Reality missing pbk public key"
        } else ""

        val finalStatus = if (extraWarning.isNotEmpty() && status == ValidationStatus.VALID) ValidationStatus.WARNING else status
        val finalMsg = extraWarning.ifEmpty { message }

        return ParsedConfig(
            hostname = host,
            port = port,
            protocol = protocol,
            displayName = name.take(64),
            rawConfigData = raw,
            endpointHash = hashEndpoint(protocol, host, port, raw.substringBefore("#")),
            validationStatus = finalStatus,
            validationMessage = finalMsg,
            countryCodeHint = countryHint,
            countryNameHint = EuropeanCountries.findByCode(countryHint)?.nameEn.orEmpty()
        )
    }

    private fun parseVmessUri(raw: String): ParsedConfig? {
        val encoded = raw.removePrefix("vmess://").removePrefix("VMESS://").trim()
        val decoded = decodeBase64Safe(encoded) ?: return ParsedConfig(
            hostname = "",
            port = 0,
            protocol = VpnProtocol.VMESS,
            displayName = "Invalid VMess",
            rawConfigData = raw,
            endpointHash = hashEndpoint(VpnProtocol.VMESS, raw.take(32), 0, raw),
            validationStatus = ValidationStatus.INVALID,
            validationMessage = "Malformed VMess Base64 payload"
        )
        val obj = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull() ?: return null
        val host = obj["add"]?.jsonPrimitive?.content?.trim().orEmpty()
        val port = obj["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 443
        val ps = obj["ps"]?.jsonPrimitive?.content?.trim().orEmpty()
        val id = obj["id"]?.jsonPrimitive?.content?.trim().orEmpty()
        val (status, msg) = when {
            id.isBlank() -> ValidationStatus.INVALID to "Missing VMess UUID"
            else -> validateEndpoint(host, port, VpnProtocol.VMESS)
        }
        val hint = detectCountryCodeFromText(ps)
        return ParsedConfig(
            hostname = host,
            port = port,
            protocol = VpnProtocol.VMESS,
            displayName = ps.ifBlank { "VMess • $host" }.take(64),
            rawConfigData = raw,
            endpointHash = hashEndpoint(VpnProtocol.VMESS, host, port, id),
            validationStatus = status,
            validationMessage = msg,
            countryCodeHint = hint,
            countryNameHint = EuropeanCountries.findByCode(hint)?.nameEn.orEmpty()
        )
    }

    private fun parseShadowsocksUri(raw: String): ParsedConfig? {
        val withoutScheme = raw.removePrefix("ss://").removePrefix("SS://").trim()
        val fragment = decodeFragment(withoutScheme.substringAfter("#", ""))
        val core = withoutScheme.substringBefore("#")

        var host = ""
        var port = 0
        if (core.contains("@")) {
            val hostPort = core.substringAfter("@").substringBefore("?").substringBefore("/")
            val extracted = extractHostAndPort(hostPort)
            host = extracted.first
            port = extracted.second
        } else {
            val decoded = decodeBase64Safe(core) ?: return null
            val hostPort = decoded.substringAfterLast("@")
            val extracted = extractHostAndPort(hostPort)
            host = extracted.first
            port = extracted.second
        }

        val (status, msg) = validateEndpoint(host, port, VpnProtocol.SHADOWSOCKS)
        val hint = detectCountryCodeFromText(fragment)
        return ParsedConfig(
            hostname = host,
            port = port,
            protocol = VpnProtocol.SHADOWSOCKS,
            displayName = fragment.ifBlank { "Shadowsocks • $host" }.take(64),
            rawConfigData = raw,
            endpointHash = hashEndpoint(VpnProtocol.SHADOWSOCKS, host, port, core),
            validationStatus = status,
            validationMessage = msg,
            countryCodeHint = hint,
            countryNameHint = EuropeanCountries.findByCode(hint)?.nameEn.orEmpty()
        )
    }

    fun parseWireGuardConf(raw: String): ParsedConfig {
        var privateKey = ""
        var address = ""
        var publicKey = ""
        var endpoint = ""
        for (line in raw.lineSequence()) {
            val clean = line.substringBefore("#").trim()
            if (!clean.contains("=")) continue
            val key = clean.substringBefore("=").trim().lowercase()
            val value = clean.substringAfter("=").trim()
            when (key) {
                "privatekey" -> privateKey = value
                "address" -> address = value
                "publickey" -> publicKey = value
                "endpoint" -> endpoint = value
            }
        }
        val (host, port) = extractHostAndPort(endpoint)
        val (status, msg) = when {
            privateKey.isBlank() -> ValidationStatus.INVALID to "WireGuard missing PrivateKey"
            publicKey.isBlank() -> ValidationStatus.INVALID to "WireGuard missing Peer PublicKey"
            address.isBlank() -> ValidationStatus.INVALID to "WireGuard missing Interface Address"
            else -> validateEndpoint(host, port, VpnProtocol.WIREGUARD)
        }
        return ParsedConfig(
            hostname = host,
            port = port,
            protocol = VpnProtocol.WIREGUARD,
            displayName = "WireGuard • ${host.ifBlank { "Profile" }}",
            rawConfigData = raw,
            endpointHash = hashEndpoint(VpnProtocol.WIREGUARD, host, port, publicKey),
            validationStatus = status,
            validationMessage = msg,
            countryCodeHint = detectCountryCodeFromText(raw.lineSequence().firstOrNull().orEmpty())
        )
    }

    fun parseOpenVpnProfile(raw: String, countryHint: String = "", countryName: String = "", label: String = ""): ParsedConfig {
        var host = ""
        var port = 1194
        val blockedDirectivesFound = mutableListOf<String>()
        val sanitizedLines = mutableListOf<String>()

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            val firstToken = trimmed.substringBefore(" ").substringBefore("\t").lowercase()
            if (firstToken in dangerousOvpnDirectives) {
                blockedDirectivesFound += firstToken
                continue
            }
            sanitizedLines += line
            if (firstToken == "remote" && host.isBlank()) {
                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size >= 2) host = parts[1]
                if (parts.size >= 3) port = parts[2].toIntOrNull() ?: 1194
            } else if (firstToken == "port" && port == 1194) {
                port = trimmed.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: port
            }
        }

        val (baseStatus, baseMsg) = validateEndpoint(host, port, VpnProtocol.OPENVPN)
        val hasCryptoOrCert = raw.contains("<ca>", true) || raw.contains("ca ", true) || raw.contains("<tls-crypt>", true)
        val (finalStatus, finalMsg) = when {
            baseStatus == ValidationStatus.INVALID -> baseStatus to baseMsg
            blockedDirectivesFound.isNotEmpty() -> ValidationStatus.WARNING to
                "Sanitized blocked script directives: ${blockedDirectivesFound.distinct().joinToString(", ")}"
            !hasCryptoOrCert -> ValidationStatus.WARNING to "OpenVPN profile missing inline CA certificate"
            else -> ValidationStatus.VALID to "Valid OpenVPN profile"
        }

        val sanitizedProfile = sanitizedLines.joinToString("\n")
        return ParsedConfig(
            hostname = host,
            port = port,
            protocol = VpnProtocol.OPENVPN,
            displayName = label.ifBlank { "OpenVPN • $host" }.take(64),
            rawConfigData = sanitizedProfile,
            endpointHash = hashEndpoint(VpnProtocol.OPENVPN, host, port, "ovpn-$host-$port"),
            validationStatus = finalStatus,
            validationMessage = finalMsg,
            countryCodeHint = countryHint,
            countryNameHint = countryName
        )
    }

    private fun parseVpnGateCsv(csv: String): List<ParsedConfig> {
        data class ScoredOvpn(val config: ParsedConfig, val isEuropean: Boolean, val euPriority: Int, val score: Long)
        val parsed = mutableListOf<ScoredOvpn>()
        for (line in csv.lineSequence()) {
            if (line.startsWith("*") || line.startsWith("#") || line.isBlank()) continue
            val cols = line.split(",")
            if (cols.size < 15) continue
            val hostName = cols[0].trim()
            val ip = cols[1].trim()
            val score = cols[2].trim().toLongOrNull() ?: 0L
            val countryLong = cols[5].trim()
            val countryShort = cols[6].trim().uppercase()
            val ovpnBase64 = cols[14].trim()
            if (ovpnBase64.isBlank()) continue
            val ovpnText = decodeBase64Safe(ovpnBase64) ?: continue
            val item = parseOpenVpnProfile(
                raw = ovpnText,
                countryHint = countryShort,
                countryName = countryLong,
                label = "VPNGate ${countryShort.ifBlank { "GL" }} • ${hostName.ifBlank { ip }}"
            )
            if (item.validationStatus != ValidationStatus.INVALID) {
                val isEu = countryShort.isNotEmpty() && EuropeanCountries.isEuropean(countryShort)
                val euPrio = if (isEu) EuropeanCountries.priorityOf(countryShort) else 100
                parsed += ScoredOvpn(item, isEu, euPrio, score)
            }
        }
        // Always place European relays (Sweden/Switzerland first) at the top, followed by highest-score global relays
        return parsed
            .sortedWith(compareBy<ScoredOvpn> { !it.isEuropean }.thenBy { it.euPriority }.thenByDescending { it.score })
            .map { it.config }
    }

    private fun parseJsonConfig(rawJson: String): List<ParsedConfig> {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return emptyList()
        val outbounds = root["outbounds"] as? JsonArray ?: return emptyList()
        val isSingBox = root.containsKey("inbounds") && outbounds.any {
            (it as? JsonObject)?.containsKey("server") == true
        }
        val list = mutableListOf<ParsedConfig>()
        for (elem in outbounds) {
            val obj = elem as? JsonObject ?: continue
            val typeStr = (obj["type"] ?: obj["protocol"])?.jsonPrimitive?.content?.lowercase().orEmpty()
            if (typeStr in setOf("direct", "block", "dns", "freedom", "blackhole", "selector", "urltest")) continue
            val protocol = mapProtocolName(typeStr)
            var host = obj["server"]?.jsonPrimitive?.content.orEmpty()
            var port = obj["server_port"]?.jsonPrimitive?.intOrNull ?: 0

            // Xray vnext/servers extraction
            if (host.isBlank()) {
                val settings = obj["settings"] as? JsonObject
                val vnext = (settings?.get("vnext") as? JsonArray)?.firstOrNull() as? JsonObject
                val servers = (settings?.get("servers") as? JsonArray)?.firstOrNull() as? JsonObject
                val target = vnext ?: servers
                if (target != null) {
                    host = target["address"]?.jsonPrimitive?.content.orEmpty()
                    port = target["port"]?.jsonPrimitive?.intOrNull ?: 0
                }
            }
            if (host.isBlank()) continue
            val tag = obj["tag"]?.jsonPrimitive?.content.orEmpty()
            val (status, msg) = validateEndpoint(host, port, protocol)
            list += ParsedConfig(
                hostname = host,
                port = port,
                protocol = if (protocol != VpnProtocol.UNKNOWN) protocol else if (isSingBox) VpnProtocol.SINGBOX_JSON else VpnProtocol.XRAY_JSON,
                displayName = tag.ifBlank { "${protocol.displayName} • $host" }.take(64),
                rawConfigData = rawJson,
                endpointHash = hashEndpoint(protocol, host, port, "$tag-$typeStr"),
                validationStatus = status,
                validationMessage = msg,
                countryCodeHint = detectCountryCodeFromText(tag)
            )
        }
        return list
    }

    private fun parseClashYaml(yaml: String): List<ParsedConfig> {
        val results = mutableListOf<ParsedConfig>()
        val proxyBlock = yaml.substringAfter("proxies:", "")
        if (proxyBlock.isBlank()) return emptyList()

        // Handles both inline `{name: ..., type: ..., server: ..., port: ...}` and multi-line `- name:` entries
        val inlineRegex = Regex("""-\s*\{([^}]+)\}""")
        for (match in inlineRegex.findAll(proxyBlock)) {
            val fields = match.groupValues[1].split(",").associate { pair ->
                val k = pair.substringBefore(":").trim().lowercase().trim('"', '\'')
                val v = pair.substringAfter(":", "").trim().trim('"', '\'')
                k to v
            }
            buildClashItem(fields, match.value)?.let { results += it }
        }
        if (results.isNotEmpty()) return results

        val chunks = proxyBlock.split(Regex("""\n\s*-\s+name:"""))
        for (idx in 1 until chunks.size) {
            val block = "name:" + chunks[idx].substringBefore("\nproxy-groups:")
            val map = mutableMapOf<String, String>()
            for (l in block.lineSequence()) {
                val clean = l.trim()
                if (clean.contains(":")) {
                    val k = clean.substringBefore(":").trim().lowercase()
                    val v = clean.substringAfter(":").trim().trim('"', '\'')
                    map[k] = v
                }
            }
            buildClashItem(map, block)?.let { results += it }
        }
        return results
    }

    private fun buildClashItem(fields: Map<String, String>, rawBlock: String): ParsedConfig? {
        val host = fields["server"].orEmpty()
        val port = fields["port"]?.toIntOrNull() ?: 0
        val type = fields["type"].orEmpty()
        val name = fields["name"].orEmpty()
        if (host.isBlank()) return null
        val protocol = mapProtocolName(type)
        val (status, msg) = validateEndpoint(host, port, protocol)
        val hint = detectCountryCodeFromText(name)
        return ParsedConfig(
            hostname = host,
            port = port,
            protocol = if (protocol == VpnProtocol.UNKNOWN) VpnProtocol.CLASH else protocol,
            displayName = name.ifBlank { "${protocol.displayName} • $host" }.take(64),
            rawConfigData = rawBlock,
            endpointHash = hashEndpoint(protocol, host, port, rawBlock),
            validationStatus = status,
            validationMessage = msg,
            countryCodeHint = hint,
            countryNameHint = EuropeanCountries.findByCode(hint)?.nameEn.orEmpty()
        )
    }

    private fun mapProtocolName(type: String): VpnProtocol = when (type.lowercase()) {
        "vless" -> VpnProtocol.VLESS
        "vmess" -> VpnProtocol.VMESS
        "trojan" -> VpnProtocol.TROJAN
        "ss", "shadowsocks" -> VpnProtocol.SHADOWSOCKS
        "hysteria" -> VpnProtocol.HYSTERIA
        "hysteria2", "hy2" -> VpnProtocol.HYSTERIA2
        "tuic" -> VpnProtocol.TUIC
        "wireguard", "wg" -> VpnProtocol.WIREGUARD
        "openvpn", "ovpn" -> VpnProtocol.OPENVPN
        "socks", "socks5" -> VpnProtocol.SOCKS
        "http" -> VpnProtocol.HTTP
        else -> VpnProtocol.UNKNOWN
    }

    private fun validateEndpoint(host: String, port: Int, protocol: VpnProtocol): Pair<ValidationStatus, String> {
        if (!protocol.isSupported) return ValidationStatus.UNSUPPORTED to "Unsupported protocol"
        if (!NetworkSecurityValidator.isValidHost(host)) return ValidationStatus.INVALID to "Invalid hostname or IP"
        if (NetworkSecurityValidator.isBlockedInternalHost(host)) {
            return ValidationStatus.INVALID to "Loopback or private network host blocked"
        }
        if (!NetworkSecurityValidator.isValidPort(port)) return ValidationStatus.INVALID to "Invalid port ($port)"
        return ValidationStatus.VALID to "Syntax validated (connectivity untested)"
    }

    private fun looksLikeOpenVpn(text: String): Boolean {
        val lower = text.lowercase()
        return (lower.contains("\nremote ") || lower.startsWith("remote ")) &&
            (lower.contains("dev tun") || lower.contains("client") || lower.contains("<ca>"))
    }

    private fun maybeDecodeBase64Subscription(text: String): String {
        if (text.contains("://")) return text
        val compact = text.replace("\n", "").replace("\r", "").trim()
        if (compact.length < 16) return text
        val decoded = decodeBase64Safe(compact) ?: return text
        return if (decoded.contains("://")) decoded else text
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

    private fun decodeFragment(fragment: String): String {
        if (fragment.isBlank()) return ""
        return runCatching { URLDecoder.decode(fragment, "UTF-8") }.getOrDefault(fragment).trim()
    }

    private fun extractHostAndPort(hostPort: String): Pair<String, Int> {
        val cleaned = hostPort.trim()
        if (cleaned.startsWith("[")) {
            val host = cleaned.substringAfter("[").substringBefore("]")
            val port = cleaned.substringAfter("]:", "").toIntOrNull() ?: 0
            return host to port
        }
        val lastColon = cleaned.lastIndexOf(':')
        if (lastColon <= 0) return cleaned to 0
        val host = cleaned.substring(0, lastColon)
        val port = cleaned.substring(lastColon + 1).toIntOrNull() ?: 0
        return host to port
    }

    private fun defaultPortFor(protocol: VpnProtocol): Int = when (protocol) {
        VpnProtocol.WIREGUARD -> 51820
        VpnProtocol.OPENVPN -> 1194
        VpnProtocol.SOCKS -> 1080
        VpnProtocol.HTTP -> 8080
        else -> 443
    }

    fun detectCountryCodeFromText(text: String): String {
        if (text.isBlank()) return ""
        val upper = text.uppercase()
        for (c in EuropeanCountries.all) {
            if (text.contains(c.flag)) return c.code
            if (upper.contains(c.nameEn.uppercase())) return c.code
            if (Regex("""(^|[^A-Z])${c.code}([^A-Z]|$)""").containsMatchIn(upper)) return c.code
        }
        return ""
    }

    fun hashEndpoint(protocol: VpnProtocol, host: String, port: Int, discriminator: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${protocol.name}|${host.lowercase()}|$port|$discriminator".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
