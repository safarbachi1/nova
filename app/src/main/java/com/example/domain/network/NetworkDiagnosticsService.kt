package com.example.domain.network

import com.example.core.logging.DiagnosticLogger
import com.example.core.security.NetworkSecurityValidator
import com.example.domain.model.EuropeanCountries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.abs
import kotlin.math.roundToInt

data class ProbeResult(
    val reachable: Boolean,
    val latencyMs: Int,
    val error: String = ""
)

data class PublicIpInfo(
    val ip: String,
    val countryCode: String = "",
    val countryName: String = "",
    val isp: String = ""
)

data class LiveSpeedProgress(
    val phase: String,
    val progressFraction: Float,
    val currentDownloadMbps: Float = 0f,
    val currentUploadMbps: Float = 0f,
    val latencyMs: Int = 0,
    val jitterMs: Int = 0,
    val packetLossPercent: Float = 0f
)

data class SpeedTestMeasurement(
    val downloadMbps: Float,
    val uploadMbps: Float,
    val latencyMs: Int,
    val jitterMs: Int,
    val packetLossPercent: Float,
    val stabilityScore: Float
)

class NetworkDiagnosticsService(
    private val httpClient: OkHttpClient,
    private val logger: DiagnosticLogger
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val geoCache = ConcurrentHashMap<String, Pair<String, String>>()

    fun evictConnections() {
        runCatching { httpClient.connectionPool.evictAll() }
    }

    /**
     * Real TCP/TLS handshake latency measurement (does not rely on ICMP ping).
     */
    suspend fun probeServer(
        hostname: String,
        port: Int,
        useTlsHandshake: Boolean = false,
        timeoutMs: Int = 2500
    ): ProbeResult = withContext(Dispatchers.IO) {
        if (!NetworkSecurityValidator.isValidHost(hostname) || !NetworkSecurityValidator.isValidPort(port)) {
            return@withContext ProbeResult(false, -1, "Invalid endpoint")
        }
        val startNs = System.nanoTime()
        try {
            val address = InetAddress.getByName(hostname)
            if (!NetworkSecurityValidator.isPublicResolvedAddress(address)) {
                return@withContext ProbeResult(false, -1, "Private IP blocked")
            }
            val socket = Socket()
            socket.use { s ->
                s.connect(InetSocketAddress(address, port), timeoutMs)
                if (useTlsHandshake && port == 443) {
                    val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val sslSocket = sslFactory.createSocket(s, hostname, port, true) as SSLSocket
                    sslSocket.soTimeout = timeoutMs
                    runCatching { sslSocket.startHandshake() }
                }
            }
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
            ProbeResult(true, elapsedMs)
        } catch (t: Throwable) {
            ProbeResult(false, -1, t.message ?: "Unreachable")
        }
    }

    /**
     * Queries real public IP / exit IP via a fresh non-pooled HTTPS socket so keep-alive
     * connections from before VPN activation can NEVER leak the pre-VPN IP address.
     */
    suspend fun fetchPublicIpInfo(): PublicIpInfo? = withContext(Dispatchers.IO) {
        val freshClient = OkHttpClient.Builder()
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val urls = listOf(
            "https://api.ipify.org?format=json",
            "https://ipwho.is/",
            "https://ifconfig.co/json"
        )
        for (url in urls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "NovaVPN-Android/1.0")
                    .header("Connection", "close")
                    .header("Cache-Control", "no-cache")
                    .build()
                freshClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) continue
                    val body = resp.body?.string().orEmpty()
                    val obj = json.parseToJsonElement(body).jsonObject
                    val ip = obj["ip"]?.jsonPrimitive?.content.orEmpty()
                    if (ip.isNotBlank()) {
                        val cc = obj["country_code"]?.jsonPrimitive?.content
                            ?: obj["country_iso"]?.jsonPrimitive?.content
                            ?: ""
                        val cname = obj["country"]?.jsonPrimitive?.content.orEmpty()
                        return@withContext PublicIpInfo(ip = ip, countryCode = cc, countryName = cname)
                    }
                }
            } catch (_: Throwable) {
                // Try fallback endpoint
            }
        }
        null
    }

    /**
     * Resolves real server geolocation via DNS + HTTPS ipwho.is with fast timeout and in-memory cache.
     */
    suspend fun resolveServerCountry(hostname: String): Pair<String, String> = withContext(Dispatchers.IO) {
        geoCache[hostname]?.let { return@withContext it }
        try {
            val resolvedIp = InetAddress.getByName(hostname).hostAddress ?: return@withContext "" to ""
            geoCache[resolvedIp]?.let {
                geoCache[hostname] = it
                return@withContext it
            }
            val geoClient = httpClient.newBuilder()
                .connectTimeout(2500, TimeUnit.MILLISECONDS)
                .readTimeout(2500, TimeUnit.MILLISECONDS)
                .build()
            val req = Request.Builder()
                .url("https://ipwho.is/$resolvedIp")
                .header("User-Agent", "NovaVPN-Android/1.0")
                .build()
            geoClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "" to ""
                val body = resp.body?.string().orEmpty()
                val obj = json.parseToJsonElement(body).jsonObject
                val code = obj["country_code"]?.jsonPrimitive?.content?.uppercase().orEmpty()
                val name = obj["country"]?.jsonPrimitive?.content.orEmpty()
                val pair = code to (EuropeanCountries.findByCode(code)?.nameEn ?: name)
                if (code.isNotBlank()) {
                    geoCache[hostname] = pair
                    geoCache[resolvedIp] = pair
                }
                pair
            }
        } catch (_: Throwable) {
            "" to ""
        }
    }

    /**
     * Executes a real, user-initiated HTTPS speed test (latency, jitter, packet/request loss,
     * download Mbps, upload Mbps).
     */
    suspend fun runSpeedTest(
        onProgress: (LiveSpeedProgress) -> Unit
    ): Result<SpeedTestMeasurement> = withContext(Dispatchers.IO) {
        logger.info("SpeedTest", "Starting user-initiated HTTPS speed test")
        val speedClient = httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        // 1. Latency, jitter, and request loss (5 HTTPS probes)
        val probeLatencies = mutableListOf<Int>()
        var failures = 0
        val probeCount = 5
        for (i in 1..probeCount) {
            onProgress(LiveSpeedProgress("latency", i / 15f))
            val start = System.nanoTime()
            val ok = try {
                val req = Request.Builder().url("https://speed.cloudflare.com/__down?bytes=1024").build()
                speedClient.newCall(req).execute().use { it.isSuccessful }
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                probeLatencies += ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
            } else {
                failures++
            }
        }

        if (probeLatencies.isEmpty()) {
            logger.error("SpeedTest", "All latency probes failed; network unavailable")
            return@withContext Result.failure(IllegalStateException("Network unreachable during speed test"))
        }

        val avgLatency = probeLatencies.average().roundToInt()
        val jitter = if (probeLatencies.size >= 2) {
            probeLatencies.zipWithNext { a, b -> abs(a - b) }.average().roundToInt()
        } else 0
        val lossPercent = (failures.toFloat() / probeCount.toFloat()) * 100f

        onProgress(
            LiveSpeedProgress(
                phase = "download",
                progressFraction = 0.35f,
                latencyMs = avgLatency,
                jitterMs = jitter,
                packetLossPercent = lossPercent
            )
        )

        // 2. Real HTTPS Download measurement (4 MB)
        var downloadMbps = 0f
        try {
            val dlStart = System.nanoTime()
            val dlReq = Request.Builder().url("https://speed.cloudflare.com/__down?bytes=4000000").build()
            speedClient.newCall(dlReq).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Download HTTP ${resp.code}")
                val stream = resp.body?.byteStream() ?: throw IllegalStateException("Empty download body")
                val buffer = ByteArray(16 * 1024)
                var totalBytes = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    totalBytes += read
                    val elapsedSec = ((System.nanoTime() - dlStart) / 1_000_000_000.0).coerceAtLeast(0.05)
                    downloadMbps = ((totalBytes * 8.0) / (elapsedSec * 1_000_000.0)).toFloat()
                    val frac = 0.35f + (totalBytes.toFloat() / 4_000_000f).coerceIn(0f, 1f) * 0.35f
                    onProgress(
                        LiveSpeedProgress(
                            phase = "download",
                            progressFraction = frac,
                            currentDownloadMbps = downloadMbps,
                            latencyMs = avgLatency,
                            jitterMs = jitter,
                            packetLossPercent = lossPercent
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            logger.warn("SpeedTest", "Download phase error: ${t.message}")
        }

        // 3. Real HTTPS Upload measurement (1 MB)
        var uploadMbps = 0f
        onProgress(
            LiveSpeedProgress(
                phase = "upload",
                progressFraction = 0.72f,
                currentDownloadMbps = downloadMbps,
                latencyMs = avgLatency,
                jitterMs = jitter,
                packetLossPercent = lossPercent
            )
        )
        try {
            val payload = ByteArray(1_000_000) { 0x4E }
            val ulStart = System.nanoTime()
            val body = payload.toRequestBody("application/octet-stream".toMediaType())
            val ulReq = Request.Builder().url("https://speed.cloudflare.com/__up").post(body).build()
            speedClient.newCall(ulReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val elapsedSec = ((System.nanoTime() - ulStart) / 1_000_000_000.0).coerceAtLeast(0.05)
                    uploadMbps = ((payload.size * 8.0) / (elapsedSec * 1_000_000.0)).toFloat()
                }
            }
        } catch (t: Throwable) {
            logger.warn("SpeedTest", "Upload phase error: ${t.message}")
        }

        val stability = (100f - (lossPercent * 2.5f) - (jitter * 0.6f)).coerceIn(0f, 100f)
        onProgress(
            LiveSpeedProgress(
                phase = "complete",
                progressFraction = 1f,
                currentDownloadMbps = downloadMbps,
                currentUploadMbps = uploadMbps,
                latencyMs = avgLatency,
                jitterMs = jitter,
                packetLossPercent = lossPercent
            )
        )
        logger.info(
            "SpeedTest",
            "Completed: DL=%.2f Mbps, UL=%.2f Mbps, Latency=%dms, Jitter=%dms, Loss=%.1f%%"
                .format(downloadMbps, uploadMbps, avgLatency, jitter, lossPercent)
        )
        Result.success(
            SpeedTestMeasurement(
                downloadMbps = downloadMbps,
                uploadMbps = uploadMbps,
                latencyMs = avgLatency,
                jitterMs = jitter,
                packetLossPercent = lossPercent,
                stabilityScore = stability
            )
        )
    }
}
