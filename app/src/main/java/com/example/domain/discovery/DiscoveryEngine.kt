package com.example.domain.discovery

import com.example.core.logging.DiagnosticLogger
import com.example.core.security.NetworkSecurityValidator
import com.example.data.local.entity.SourceEntity
import com.example.data.repository.NovaVpnRepository
import com.example.domain.model.EuropeanCountries
import com.example.domain.model.ServerStatus
import com.example.domain.model.SourceType
import com.example.domain.model.ValidationStatus
import com.example.domain.network.NetworkDiagnosticsService
import com.example.domain.parser.ConfigParser
import com.example.domain.parser.ParsedConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class DiscoverySummary(
    val sourcesScanned: Int,
    val totalParsed: Int,
    val savedValidated: Int,
    val reachableCount: Int,
    val errors: List<String> = emptyList()
)

class DiscoveryEngine(
    private val repository: NovaVpnRepository,
    private val networkDiagnostics: NetworkDiagnosticsService,
    private val httpClient: OkHttpClient,
    private val logger: DiagnosticLogger
) {
    companion object {
        private const val MAX_FETCH_BYTES = 8 * 1024 * 1024L
        private const val MAX_CANDIDATES_STANDARD = 30
        private const val MAX_CANDIDATES_OPENVPN = 40
        private const val MAX_CONCURRENT_PROBES = 14
    }

    suspend fun refreshAllEnabledSources(
        onStatusText: (String) -> Unit = {}
    ): DiscoverySummary = coroutineScope {
        repository.ensureDefaultSourcesSeeded()
        val sources = repository.getEnabledSources()
        if (sources.isEmpty()) {
            return@coroutineScope DiscoverySummary(0, 0, 0, 0, listOf("No enabled sources in allowlist"))
        }
        onStatusText(sources.joinToString(" • ") { it.name.take(20) })

        val results = sources.map { source ->
            async { refreshSingleSource(source) }
        }.awaitAll()

        DiscoverySummary(
            sourcesScanned = sources.size,
            totalParsed = results.sumOf { it.totalParsed },
            savedValidated = results.sumOf { it.savedValidated },
            reachableCount = results.sumOf { it.reachableCount },
            errors = results.flatMap { it.errors }
        )
    }

    suspend fun refreshSingleSource(source: SourceEntity): DiscoverySummary = withContext(Dispatchers.IO) {
        val urlValidation = NetworkSecurityValidator.validateHttpsSourceUrl(source.sourceUrl)
        if (urlValidation.isFailure) {
            val err = urlValidation.exceptionOrNull()?.message ?: "Invalid HTTPS source URL"
            logger.error("Discovery", "Rejected source ${source.name}: $err")
            repository.updateSourceStats(source, success = false, discovered = 0, errorMsg = err)
            return@withContext DiscoverySummary(1, 0, 0, 0, listOf(err))
        }

        logger.info("Discovery", "Fetching public allowlist source: ${source.name}")
        val bodyText = try {
            val client = httpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(urlValidation.getOrThrow())
                .header("User-Agent", "NovaVPN-Android/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}")
                }
                val contentLength = resp.body?.contentLength() ?: -1L
                if (contentLength > MAX_FETCH_BYTES) {
                    throw IllegalStateException("Source payload exceeds 8MB safety cap")
                }
                val rawBytes = resp.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    val out = java.io.ByteArrayOutputStream()
                    var total = 0L
                    while (true) {
                        val r = input.read(buffer)
                        if (r == -1) break
                        total += r
                        if (total > MAX_FETCH_BYTES) break
                        out.write(buffer, 0, r)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                String(rawBytes, Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            val err = t.message ?: "Network fetch failed"
            logger.error("Discovery", "Source ${source.name} failed: $err")
            repository.updateSourceStats(source, success = false, discovered = 0, errorMsg = err)
            return@withContext DiscoverySummary(1, 0, 0, 0, listOf("${source.name}: $err"))
        }

        val parsedAll = ConfigParser.parseBatch(bodyText)
            .filter { it.validationStatus != ValidationStatus.INVALID }
            .distinctBy { it.endpointHash }

        val isOpenVpnFeed = source.sourceType == SourceType.OPENVPN_FEED
        val candidateLimit = if (isOpenVpnFeed) MAX_CANDIDATES_OPENVPN else MAX_CANDIDATES_STANDARD

        // Prioritize Sweden (SE), Switzerland (CH), and European hinted servers first
        val prioritized = parsedAll
            .sortedBy { candidatePriority(it) }
            .take(candidateLimit)

        val semaphore = Semaphore(MAX_CONCURRENT_PROBES)
        val savedCount = AtomicInteger(0)
        val reachableCount = AtomicInteger(0)

        coroutineScope {
            prioritized.map { candidate ->
                async {
                    semaphore.withPermit {
                        // Fast TCP RTT probe first (2s timeout) so unreachable endpoints skip slow GeoIP lookups
                        val probe = networkDiagnostics.probeServer(
                            hostname = candidate.hostname,
                            port = candidate.port,
                            useTlsHandshake = false,
                            timeoutMs = 2000
                        )
                        if (!probe.reachable && !isOpenVpnFeed) {
                            return@withPermit
                        }
                        val (cc, cname) = resolveCountryForCandidate(candidate)
                        // Focus standard proxy feeds on European regions when country is known;
                        // for OpenVPN feeds, keep Europe prioritized AND retain reachable global relays
                        if (!isOpenVpnFeed && cc.isNotBlank() && !EuropeanCountries.isEuropean(cc)) {
                            return@withPermit
                        }
                        val serverStatus = when {
                            !candidate.protocol.isSupported -> ServerStatus.UNSUPPORTED
                            probe.reachable -> ServerStatus.ONLINE
                            else -> ServerStatus.UNREACHABLE
                        }
                        val effectiveCode = cc.ifBlank { "EU" }
                        val effectiveName = cname.ifBlank { EuropeanCountries.findByCode(effectiveCode)?.nameEn ?: "Europe" }
                        val id = repository.upsertDiscoveredOrImportedServer(
                            hostname = candidate.hostname,
                            port = candidate.port,
                            countryCode = effectiveCode,
                            countryName = effectiveName,
                            protocol = candidate.protocol,
                            displayName = candidate.displayName,
                            rawConfigData = candidate.rawConfigData,
                            endpointHash = candidate.endpointHash,
                            sourceId = source.id,
                            validationStatus = candidate.validationStatus,
                            validationMessage = candidate.validationMessage,
                            trustedSource = false, // Public sources are ALWAYS untrusted by default
                            latencyMs = probe.latencyMs,
                            status = serverStatus
                        )
                        if (id > 0) {
                            savedCount.incrementAndGet()
                            if (probe.reachable) reachableCount.incrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }

        val finalSaved = savedCount.get()
        val finalReachable = reachableCount.get()
        repository.updateSourceStats(source, success = true, discovered = finalSaved)
        logger.info(
            "Discovery",
            "Source '${source.name}': parsed=${parsedAll.size}, saved=$finalSaved, reachable=$finalReachable"
        )
        DiscoverySummary(1, parsedAll.size, finalSaved, finalReachable)
    }

    private fun candidatePriority(c: ParsedConfig): Int {
        return if (c.countryCodeHint.isNotBlank()) EuropeanCountries.priorityOf(c.countryCodeHint) else 50
    }

    private suspend fun resolveCountryForCandidate(candidate: ParsedConfig): Pair<String, String> {
        if (candidate.countryCodeHint.isNotBlank()) {
            val found = EuropeanCountries.findByCode(candidate.countryCodeHint)
            return candidate.countryCodeHint to (found?.nameEn ?: candidate.countryNameHint)
        }
        return networkDiagnostics.resolveServerCountry(candidate.hostname)
    }
}
