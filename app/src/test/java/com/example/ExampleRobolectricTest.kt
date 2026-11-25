package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CryptoVault
import com.example.data.local.NovaVpnDatabase
import com.example.data.repository.NovaVpnRepository
import com.example.domain.model.ServerStatus
import com.example.domain.model.ValidationStatus
import com.example.domain.model.VpnProtocol
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var db: NovaVpnDatabase
    private lateinit var repository: NovaVpnRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NovaVpnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NovaVpnRepository(db, CryptoVault())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `app name string resource matches Nova VPN`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Nova VPN", context.getString(R.string.app_name))
    }

    @Test
    fun `repository seeds curated HTTPS allowlist and encrypts stored configurations`() = runTest {
        repository.ensureDefaultSourcesSeeded()
        val sources = repository.sources.first()
        assertTrue(sources.isNotEmpty())
        assertTrue(sources.all { it.sourceUrl.startsWith("https://") })

        val serverId = repository.upsertDiscoveredOrImportedServer(
            hostname = "se-stockholm.example.org",
            port = 51820,
            countryCode = "SE",
            countryName = "Sweden",
            protocol = VpnProtocol.WIREGUARD,
            displayName = "Sweden Test",
            rawConfigData = "[Interface]\nPrivateKey = TEST_SECRET_KEY\n",
            endpointHash = "hash-se-1",
            sourceId = null,
            validationStatus = ValidationStatus.VALID,
            validationMessage = "Valid",
            trustedSource = true,
            latencyMs = 42,
            status = ServerStatus.ONLINE
        )
        assertTrue(serverId > 0L)

        val storedPair = db.serverDao().getServerWithConfiguration(serverId)
        assertNotNull(storedPair?.configuration)
        assertTrue(storedPair!!.configuration!!.configurationData.startsWith("NVENC1:"))

        val decrypted = repository.getDecryptedProfile(serverId)
        assertEquals("[Interface]\nPrivateKey = TEST_SECRET_KEY\n", decrypted?.rawConfigData)
    }
}
