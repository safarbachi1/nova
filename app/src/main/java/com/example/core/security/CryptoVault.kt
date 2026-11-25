package com.example.core.security

import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts sensitive VPN configurations, private keys, UUIDs, and credentials at rest
 * using Android Keystore AES-256-GCM.
 */
class CryptoVault {
    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "nova_vpn_config_vault_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PREFIX = "NVENC1:"
    }

    private val fallbackKey: SecretKey by lazy {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        SecretKeySpec(bytes, "AES")
    }

    private fun getOrCreateSecretKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) {
                existing
            } else {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                generator.init(spec)
                generator.generateKey()
            }
        } catch (_: Throwable) {
            // Only reached in non-Android JVM unit tests where AndroidKeyStore is unavailable.
            fallbackKey
        }
    }

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
        return PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val payload = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (payload.size < 14) return ""
            val ivLen = payload[0].toInt() and 0xFF
            if (ivLen <= 0 || payload.size <= 1 + ivLen) return ""
            val iv = payload.copyOfRange(1, 1 + ivLen)
            val ciphertext = payload.copyOfRange(1 + ivLen, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)
}

/**
 * Redacts private keys, passwords, UUIDs, auth tokens, and inline PEM secrets before logging.
 */
object SecretRedactor {
    private val pemBlockRegex = Regex(
        "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
        RegexOption.IGNORE_CASE
    )
    private val uuidRegex = Regex(
        "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
    )
    private val keyValueSecretRegex = Regex(
        "(?i)(PrivateKey|PresharedKey|password|passwd|secret|token|auth|pbk|sid|uuid)\\s*([=:])\\s*([^\\s&,;\"'\\]}]+)"
    )
    private val uriCredentialRegex = Regex(
        "(?i)(vless|vmess|trojan|ss|hysteria2|hy2|tuic|socks5?|https?)://([^@\\s/]+)@"
    )

    fun redact(input: String): String {
        if (input.isBlank()) return input
        var out = pemBlockRegex.replace(input, "[REDACTED_PRIVATE_KEY]")
        out = uriCredentialRegex.replace(out) { m -> "${m.groupValues[1]}://[REDACTED]@" }
        out = keyValueSecretRegex.replace(out) { m -> "${m.groupValues[1]}${m.groupValues[2]}[REDACTED]" }
        out = uuidRegex.replace(out, "[REDACTED_UUID]")
        return out
    }
}

/**
 * Validates hostnames, ports, DNS servers, and public discovery HTTPS URLs while blocking
 * loopback, private, and link-local addresses.
 */
object NetworkSecurityValidator {
    private val hostnameRegex = Regex(
        "^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)*[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$"
    )
    private val ipv4Regex = Regex(
        "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    )
    private val ipv6Regex = Regex("^[0-9a-fA-F:]+$")

    fun isValidPort(port: Int): Boolean = port in 1..65535

    fun isValidHost(host: String): Boolean {
        val cleaned = host.trim().removePrefix("[").removeSuffix("]")
        if (cleaned.isEmpty() || cleaned.length > 253) return false
        if (cleaned.contains("..") || cleaned.contains(" ")) return false
        if (ipv4Regex.matches(cleaned)) return true
        if (cleaned.contains(":") && ipv6Regex.matches(cleaned)) return true
        return hostnameRegex.matches(cleaned)
    }

    fun isBlockedInternalHost(host: String): Boolean {
        val lower = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (lower == "localhost" || lower.endsWith(".local") || lower.endsWith(".internal")) return true
        if (lower == "::1" || lower == "0:0:0:0:0:0:0:1" || lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80:")) {
            return true
        }
        if (ipv4Regex.matches(lower)) {
            val parts = lower.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.size == 4) {
                val (a, b) = parts[0] to parts[1]
                if (a == 0 || a == 10 || a == 127) return true
                if (a == 169 && b == 254) return true
                if (a == 172 && b in 16..31) return true
                if (a == 192 && b == 168) return true
                if (a == 100 && b in 64..127) return true
            }
        }
        return false
    }

    fun validateHttpsSourceUrl(url: String): Result<String> {
        val trimmed = url.trim()
        val uri = try {
            Uri.parse(trimmed)
        } catch (_: Throwable) {
            return Result.failure(IllegalArgumentException("Malformed URL"))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") {
            return Result.failure(IllegalArgumentException("Only HTTPS source URLs are allowed"))
        }
        val host = uri.host?.trim().orEmpty()
        if (!isValidHost(host) || isBlockedInternalHost(host)) {
            return Result.failure(IllegalArgumentException("Host is invalid or restricted"))
        }
        return Result.success(trimmed)
    }

    fun isValidDnsAddress(dns: String): Boolean {
        val trimmed = dns.trim()
        if (trimmed.isEmpty()) return true
        return ipv4Regex.matches(trimmed) || (trimmed.contains(":") && ipv6Regex.matches(trimmed))
    }

    fun isPublicResolvedAddress(address: InetAddress): Boolean {
        return !(address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress)
    }
}
