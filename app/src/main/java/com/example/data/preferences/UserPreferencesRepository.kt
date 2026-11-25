package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.SelectionMode
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.ThemeMode
import com.example.domain.model.VpnProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.novaVpnDataStore: DataStore<Preferences> by preferencesDataStore(name = "nova_vpn_prefs")

data class UserPreferences(
    val autoConnect: Boolean = false,
    val reconnectOnNetworkChange: Boolean = true,
    val killSwitchEnabled: Boolean = false,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val splitTunnelPackages: Set<String> = emptySet(),
    val customDnsPrimary: String = "1.1.1.1",
    val customDnsSecondary: String = "9.9.9.9",
    val ipv4Enabled: Boolean = true,
    val ipv6Enabled: Boolean = false,
    val preferredProtocol: VpnProtocol? = null,
    val connectionTimeoutSec: Int = 12,
    val autoServerRefresh: Boolean = true,
    val refreshIntervalHours: Int = 6,
    val themeMode: ThemeMode = ThemeMode.DARK_NAVY,
    val languageCode: String = "fa",
    val requireUntrustedApproval: Boolean = true,
    val selectedServerId: Long? = null,
    val selectionMode: SelectionMode = SelectionMode.AUTO
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val RECONNECT_NETWORK = booleanPreferencesKey("reconnect_network_change")
        val KILL_SWITCH = booleanPreferencesKey("kill_switch")
        val SPLIT_TUNNEL_MODE = stringPreferencesKey("split_tunnel_mode")
        val SPLIT_TUNNEL_PKGS = stringSetPreferencesKey("split_tunnel_packages")
        val DNS_PRIMARY = stringPreferencesKey("dns_primary")
        val DNS_SECONDARY = stringPreferencesKey("dns_secondary")
        val IPV4 = booleanPreferencesKey("ipv4_enabled")
        val IPV6 = booleanPreferencesKey("ipv6_enabled")
        val PREFERRED_PROTOCOL = stringPreferencesKey("preferred_protocol")
        val TIMEOUT_SEC = intPreferencesKey("connection_timeout_sec")
        val AUTO_REFRESH = booleanPreferencesKey("auto_server_refresh")
        val REFRESH_HOURS = intPreferencesKey("refresh_interval_hours")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language_code")
        val REQUIRE_UNTRUSTED_APPROVAL = booleanPreferencesKey("require_untrusted_approval")
        val SELECTED_SERVER_ID = longPreferencesKey("selected_server_id")
        val SELECTION_MODE = stringPreferencesKey("selection_mode")
    }

    val preferencesFlow: Flow<UserPreferences> = context.novaVpnDataStore.data.map { p ->
        UserPreferences(
            autoConnect = p[Keys.AUTO_CONNECT] ?: false,
            reconnectOnNetworkChange = p[Keys.RECONNECT_NETWORK] ?: true,
            killSwitchEnabled = p[Keys.KILL_SWITCH] ?: false,
            splitTunnelMode = p[Keys.SPLIT_TUNNEL_MODE]?.let {
                runCatching { SplitTunnelMode.valueOf(it) }.getOrNull()
            } ?: SplitTunnelMode.DISABLED,
            splitTunnelPackages = p[Keys.SPLIT_TUNNEL_PKGS] ?: emptySet(),
            customDnsPrimary = p[Keys.DNS_PRIMARY] ?: "1.1.1.1",
            customDnsSecondary = p[Keys.DNS_SECONDARY] ?: "9.9.9.9",
            ipv4Enabled = p[Keys.IPV4] ?: true,
            ipv6Enabled = p[Keys.IPV6] ?: false,
            preferredProtocol = p[Keys.PREFERRED_PROTOCOL]?.takeIf { it.isNotBlank() }?.let {
                runCatching { VpnProtocol.valueOf(it) }.getOrNull()
            },
            connectionTimeoutSec = (p[Keys.TIMEOUT_SEC] ?: 12).coerceIn(5, 60),
            autoServerRefresh = p[Keys.AUTO_REFRESH] ?: true,
            refreshIntervalHours = (p[Keys.REFRESH_HOURS] ?: 6).coerceIn(1, 48),
            themeMode = p[Keys.THEME_MODE]?.let {
                runCatching { ThemeMode.valueOf(it) }.getOrNull()
            } ?: ThemeMode.DARK_NAVY,
            languageCode = p[Keys.LANGUAGE] ?: "fa",
            requireUntrustedApproval = p[Keys.REQUIRE_UNTRUSTED_APPROVAL] ?: true,
            selectedServerId = p[Keys.SELECTED_SERVER_ID]?.takeIf { it > 0L },
            selectionMode = p[Keys.SELECTION_MODE]?.let {
                runCatching { SelectionMode.valueOf(it) }.getOrNull()
            } ?: SelectionMode.AUTO
        )
    }

    suspend fun setAutoConnect(value: Boolean) = edit { it[Keys.AUTO_CONNECT] = value }
    suspend fun setReconnectOnNetworkChange(value: Boolean) = edit { it[Keys.RECONNECT_NETWORK] = value }
    suspend fun setKillSwitchEnabled(value: Boolean) = edit { it[Keys.KILL_SWITCH] = value }
    suspend fun setSplitTunnelMode(mode: SplitTunnelMode) = edit { it[Keys.SPLIT_TUNNEL_MODE] = mode.name }
    suspend fun toggleSplitTunnelPackage(packageName: String) = edit { prefs ->
        val current = prefs[Keys.SPLIT_TUNNEL_PKGS] ?: emptySet()
        prefs[Keys.SPLIT_TUNNEL_PKGS] = if (packageName in current) current - packageName else current + packageName
    }
    suspend fun setCustomDns(primary: String, secondary: String) = edit {
        it[Keys.DNS_PRIMARY] = primary.trim()
        it[Keys.DNS_SECONDARY] = secondary.trim()
    }
    suspend fun setIpv4Enabled(value: Boolean) = edit { it[Keys.IPV4] = value }
    suspend fun setIpv6Enabled(value: Boolean) = edit { it[Keys.IPV6] = value }
    suspend fun setPreferredProtocol(protocol: VpnProtocol?) = edit {
        it[Keys.PREFERRED_PROTOCOL] = protocol?.name ?: ""
    }
    suspend fun setConnectionTimeout(seconds: Int) = edit { it[Keys.TIMEOUT_SEC] = seconds.coerceIn(5, 60) }
    suspend fun setAutoServerRefresh(enabled: Boolean) = edit { it[Keys.AUTO_REFRESH] = enabled }
    suspend fun setRefreshIntervalHours(hours: Int) = edit { it[Keys.REFRESH_HOURS] = hours.coerceIn(1, 48) }
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setLanguageCode(code: String) = edit { it[Keys.LANGUAGE] = code }
    suspend fun setRequireUntrustedApproval(value: Boolean) = edit { it[Keys.REQUIRE_UNTRUSTED_APPROVAL] = value }
    suspend fun setSelectedServerId(serverId: Long?) = edit { it[Keys.SELECTED_SERVER_ID] = serverId ?: -1L }
    suspend fun setSelectionMode(mode: SelectionMode) = edit { it[Keys.SELECTION_MODE] = mode.name }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.novaVpnDataStore.edit(block)
    }
}
