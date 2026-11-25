package com.example.domain.selector

import com.example.data.local.entity.ServerWithConfiguration
import com.example.domain.model.EuropeanCountries
import com.example.domain.model.SelectionMode
import com.example.domain.model.ServerStatus
import com.example.domain.model.ValidationStatus
import com.example.domain.model.VpnProtocol

object SmartServerSelector {

    /**
     * Scores a server based on real success rate, measured TCP/TLS latency, throughput,
     * stability, and European priority (Sweden & Switzerland preferred on ties).
     */
    fun computeCompositeScore(item: ServerWithConfiguration): Float {
        val s = item.server
        if (!s.protocol.isSupported || item.configuration?.validationStatus == ValidationStatus.INVALID) {
            return -1f
        }
        val latencyScore = when {
            s.latency <= 0 -> 0.15f
            s.latency <= 60 -> 1.0f
            s.latency <= 120 -> 0.85f
            s.latency <= 220 -> 0.65f
            s.latency <= 400 -> 0.40f
            else -> 0.18f
        }
        val reliability = s.reliabilityScore.coerceIn(0f, 1f)
        val totalAttempts = (s.successCount + s.failureCount).coerceAtLeast(1)
        val successRate = if (s.successCount == 0 && s.failureCount == 0) {
            if (s.status == ServerStatus.ONLINE) 0.7f else 0.3f
        } else {
            s.successCount.toFloat() / totalAttempts.toFloat()
        }
        val speedNorm = ((s.downloadSpeedMbps + s.uploadSpeedMbps * 0.5f) / 100f).coerceIn(0f, 1f)
        val countryBonus = when (s.countryCode.uppercase()) {
            "SE", "CH" -> 0.08f
            in EuropeanCountries.europeanCodes -> 0.03f
            else -> 0f
        }
        val onlineBonus = if (s.status == ServerStatus.ONLINE) 0.15f else 0f

        return (successRate * 0.35f +
            latencyScore * 0.30f +
            speedNorm * 0.15f +
            reliability * 0.20f +
            countryBonus +
            onlineBonus).coerceIn(0f, 1.3f)
    }

    fun selectBest(
        servers: List<ServerWithConfiguration>,
        mode: SelectionMode,
        preferredProtocol: VpnProtocol? = null,
        onlyApproved: Boolean = false,
        excludeServerId: Long? = null
    ): ServerWithConfiguration? {
        val eligible = servers.filter { item ->
            item.server.id != excludeServerId &&
                item.server.protocol.isSupported &&
                item.configuration != null &&
                item.configuration.validationStatus != ValidationStatus.INVALID &&
                (!onlyApproved || item.isTrustedOrApproved)
        }
        if (eligible.isEmpty()) return null

        val protocolFiltered = if (preferredProtocol != null) {
            eligible.filter { it.server.protocol == preferredProtocol }.ifEmpty { eligible }
        } else {
            eligible
        }

        val onlineFirst = protocolFiltered.filter { it.server.status == ServerStatus.ONLINE }
            .ifEmpty { protocolFiltered.filter { it.server.status != ServerStatus.UNREACHABLE } }
            .ifEmpty { protocolFiltered }

        return when (mode) {
            SelectionMode.BEST_LATENCY -> onlineFirst.minByOrNull {
                if (it.server.latency > 0) it.server.latency else Int.MAX_VALUE
            }
            SelectionMode.BEST_STABILITY -> onlineFirst.maxByOrNull {
                it.server.reliabilityScore * 100f + it.server.successCount
            }
            SelectionMode.BEST_SPEED -> onlineFirst.maxByOrNull {
                it.server.downloadSpeedMbps * 10f + computeCompositeScore(it)
            }
            SelectionMode.AUTO, SelectionMode.MANUAL -> onlineFirst.maxByOrNull {
                computeCompositeScore(it)
            }
        }
    }
}
