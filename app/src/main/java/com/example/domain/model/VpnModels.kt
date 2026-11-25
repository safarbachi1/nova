package com.example.domain.model

enum class EngineType {
    SING_BOX,
    OPENVPN,
    WIREGUARD,
    UNSUPPORTED
}

enum class VpnProtocol(
    val displayName: String,
    val primaryEngine: EngineType,
    val supportsSingBox: Boolean = false
) {
    VLESS("VLESS", EngineType.SING_BOX, true),
    VMESS("VMess", EngineType.SING_BOX, true),
    TROJAN("Trojan", EngineType.SING_BOX, true),
    SHADOWSOCKS("Shadowsocks", EngineType.SING_BOX, true),
    HYSTERIA("Hysteria", EngineType.SING_BOX, true),
    HYSTERIA2("Hysteria2", EngineType.SING_BOX, true),
    TUIC("TUIC", EngineType.SING_BOX, true),
    WIREGUARD("WireGuard", EngineType.WIREGUARD, true),
    OPENVPN("OpenVPN", EngineType.OPENVPN, false),
    SOCKS("SOCKS5", EngineType.SING_BOX, true),
    HTTP("HTTP", EngineType.SING_BOX, true),
    SINGBOX_JSON("Sing-box JSON", EngineType.SING_BOX, true),
    XRAY_JSON("Xray JSON", EngineType.SING_BOX, true),
    CLASH("Clash", EngineType.SING_BOX, true),
    UNKNOWN("Unknown", EngineType.UNSUPPORTED, false);

    val isSupported: Boolean get() = primaryEngine != EngineType.UNSUPPORTED
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTING,
    ERROR
}

enum class VpnErrorType {
    INVALID_CONFIGURATION,
    UNSUPPORTED_PROTOCOL,
    DNS_FAILURE,
    AUTHENTICATION_FAILURE,
    SERVER_TIMEOUT,
    TLS_HANDSHAKE_FAILURE,
    VPN_PERMISSION_DENIED,
    ENGINE_INITIALIZATION_FAILURE,
    NETWORK_UNAVAILABLE,
    TUNNEL_ESTABLISHMENT_FAILURE,
    UNTRUSTED_SERVER_BLOCKED
}

data class VpnError(
    val type: VpnErrorType,
    val technicalDetail: String = "",
    val suggestedFallbackServerId: Long? = null
)

enum class ServerStatus {
    UNKNOWN,
    ONLINE,
    UNREACHABLE,
    UNSUPPORTED
}

enum class ValidationStatus {
    VALID,
    WARNING,
    INVALID,
    UNSUPPORTED
}

enum class SourceType(val displayName: String) {
    OPENVPN_FEED("OpenVPN Directory"),
    SUBSCRIPTION("Subscription"),
    SINGBOX_JSON("Sing-box JSON"),
    CLASH_YAML("Clash YAML"),
    MIXED_LINKS("URI Links")
}

enum class SelectionMode {
    AUTO,
    BEST_LATENCY,
    BEST_STABILITY,
    BEST_SPEED,
    MANUAL
}

enum class ThemeMode {
    DARK_NAVY,
    MIDNIGHT_VIOLET,
    LIGHT,
    SYSTEM
}

enum class SplitTunnelMode {
    DISABLED,
    BYPASS_SELECTED,
    ONLY_SELECTED
}

enum class ServerSortMode {
    LATENCY,
    RELIABILITY,
    SPEED,
    PRIORITY_COUNTRY
}

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
    ENGINE
}

data class EngineLogEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String
)

data class EngineStatistics(
    val bytesReceived: Long = 0L,
    val bytesSent: Long = 0L,
    val downloadSpeedBps: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val connectedSinceMs: Long = 0L,
    val durationMs: Long = 0L
)

data class EngineRuntimeInfo(
    val engineType: EngineType,
    val name: String,
    val isCoreAvailable: Boolean,
    val version: String,
    val statusNoteFa: String,
    val statusNoteEn: String,
    val license: String
)

data class EuropeanCountry(
    val code: String,
    val flag: String,
    val nameFa: String,
    val nameEn: String,
    val priority: Int
)

object EuropeanCountries {
    val all: List<EuropeanCountry> = listOf(
        EuropeanCountry("SE", "\uD83C\uDDF8\uD83C\uDDEA", "سوئد", "Sweden", 1),
        EuropeanCountry("CH", "\uD83C\uDDE8\uD83C\uDDED", "سوئیس", "Switzerland", 2),
        EuropeanCountry("DE", "\uD83C\uDDE9\uD83C\uDDEA", "آلمان", "Germany", 3),
        EuropeanCountry("NL", "\uD83C\uDDF3\uD83C\uDDF1", "هلند", "Netherlands", 4),
        EuropeanCountry("FR", "\uD83C\uDDEB\uD83C\uDDF7", "فرانسه", "France", 5),
        EuropeanCountry("FI", "\uD83C\uDDEB\uD83C\uDDEE", "فنلاند", "Finland", 6),
        EuropeanCountry("NO", "\uD83C\uDDF3\uD83C\uDDF4", "نروژ", "Norway", 7),
        EuropeanCountry("DK", "\uD83C\uDDE9\uD83C\uDDF0", "دانمارک", "Denmark", 8),
        EuropeanCountry("AT", "\uD83C\uDDE6\uD83C\uDDF9", "اتریش", "Austria", 9),
        EuropeanCountry("BE", "\uD83C\uDDE7\uD83C\uDDEA", "بلژیک", "Belgium", 10),
        EuropeanCountry("PL", "\uD83C\uDDF5\uD83C\uDDF1", "لهستان", "Poland", 11),
        EuropeanCountry("CZ", "\uD83C\uDDE8\uD83C\uDDFF", "جمهوری چک", "Czech Republic", 12),
        EuropeanCountry("RO", "\uD83C\uDDF7\uD83C\uDDF4", "رومانی", "Romania", 13),
        EuropeanCountry("GB", "\uD83C\uDDEC\uD83C\uDDE7", "بریتانیا", "United Kingdom", 14),
        EuropeanCountry("IE", "\uD83C\uDDEE\uD83C\uDDEA", "ایرلند", "Ireland", 15),
        EuropeanCountry("ES", "\uD83C\uDDEA\uD83C\uDDF8", "اسپانیا", "Spain", 16),
        EuropeanCountry("IT", "\uD83C\uDDEE\uD83C\uDDF9", "ایتالیا", "Italy", 17),
        EuropeanCountry("PT", "\uD83C\uDDF5\uD83C\uDDF9", "پرتغال", "Portugal", 18),
        EuropeanCountry("LU", "\uD83C\uDDF1\uD83C\uDDFA", "لوکزامبورگ", "Luxembourg", 19),
        EuropeanCountry("EE", "\uD83C\uDDEA\uD83C\uDDEA", "استونی", "Estonia", 20),
        EuropeanCountry("LV", "\uD83C\uDDF1\uD83C\uDDFB", "لتونی", "Latvia", 21),
        EuropeanCountry("LT", "\uD83C\uDDF1\uD83C\uDDF9", "لیتوانی", "Lithuania", 22),
        EuropeanCountry("IS", "\uD83C\uDDEE\uD83C\uDDF8", "ایسلند", "Iceland", 23),
        EuropeanCountry("BG", "\uD83C\uDDE7\uD83C\uDDEC", "بلغارستان", "Bulgaria", 24),
        EuropeanCountry("HU", "\uD83C\uDDED\uD83C\uDDFA", "مجارستان", "Hungary", 25),
        EuropeanCountry("GR", "\uD83C\uDDEC\uD83C\uDDF7", "یونان", "Greece", 26),
        EuropeanCountry("SK", "\uD83C\uDDF8\uD83C\uDDF0", "اسلواکی", "Slovakia", 27),
        EuropeanCountry("SI", "\uD83C\uDDF8\uD83C\uDDEE", "اسلوونی", "Slovenia", 28),
        EuropeanCountry("HR", "\uD83C\uDDED\uD83C\uDDF7", "کرواسی", "Croatia", 29),
        EuropeanCountry("EU", "\uD83C\uDDEA\uD83C\uDDFA", "سایر اروپا", "Other Europe", 30)
    )

    private val byCode = all.associateBy { it.code.uppercase() }

    val europeanCodes: Set<String> = byCode.keys

    fun findByCode(code: String?): EuropeanCountry? {
        if (code.isNullOrBlank()) return null
        return byCode[code.uppercase()]
    }

    fun isEuropean(code: String?): Boolean = code != null && europeanCodes.contains(code.uppercase())

    fun priorityOf(code: String?): Int = findByCode(code)?.priority ?: 999

    fun flagFor(code: String?): String {
        val found = findByCode(code)
        if (found != null) return found.flag
        if (code.isNullOrBlank() || code.length != 2) return "\uD83C\uDF10"
        val upper = code.uppercase()
        if (upper.any { it !in 'A'..'Z' }) return "\uD83C\uDF10"
        val first = Character.codePointAt(upper, 0) - 0x41 + 0x1F1E6
        val second = Character.codePointAt(upper, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    fun localizedName(code: String?, fallbackName: String, isPersian: Boolean): String {
        val found = findByCode(code)
        return when {
            found != null -> if (isPersian) found.nameFa else found.nameEn
            fallbackName.isNotBlank() -> fallbackName
            else -> if (isPersian) "نامشخص" else "Unknown"
        }
    }
}
