package app.opentv.core

import app.opentv.data.provider.ProviderRuntimeRules
import app.opentv.data.provider.RemoteRuleSet
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProviderRemoteConfigManagerTest {
    private val manifest = AtomicReference("""{"schema":1,"version":1,"providers":{}}""")
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(manifest.get().toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    @Before fun prepare() {
        RuntimeEnvironment.getApplication().getSharedPreferences(
            "remote_provider_rules", android.content.Context.MODE_PRIVATE,
        ).edit().clear().commit()
        ProviderRuntimeRules.install(RemoteRuleSet())
    }

    @After fun cleanup() { ProviderRuntimeRules.install(RemoteRuleSet()) }

    private fun manager() = ProviderRemoteConfigManager(
        RuntimeEnvironment.getApplication(),
        manifestUrl = "https://test.example/config/providers.json",
        testClient = client,
    )

    @Test fun versionUpgradeValidationRollbackAndReapplyBlock() = runBlocking {
        val config = manager()
        assertTrue(config.refresh().contains("v1"))
        assertEquals(1, config.status().version)
        assertEquals(null, ProviderRuntimeRules.remapHost("ddizibox.com"))

        manifest.set("""{"schema":1,"version":2,"providers":{"ddizibox":{"domain":"https://ddizibox.net"}}}""")
        assertTrue(config.refresh().contains("v2"))
        assertEquals(2, config.status().version)
        assertTrue(config.status().rollbackAvailable)
        assertEquals("ddizibox.net", ProviderRuntimeRules.remapHost("ddizibox.com"))

        manifest.set("""{"schema":1,"version":3,"providers":{"ddizibox":{"domain":"http://127.0.0.1"}}}""")
        assertTrue(config.refresh().contains("uygulanmadı"))
        assertEquals(2, config.status().version)
        assertEquals("ddizibox.net", ProviderRuntimeRules.remapHost("ddizibox.com"))

        assertTrue(config.rollback().contains("v2"))
        assertEquals(1, config.status().version)
        assertFalse(config.status().rollbackAvailable)
        assertEquals(null, ProviderRuntimeRules.remapHost("ddizibox.com"))

        manifest.set("""{"schema":1,"version":2,"providers":{"ddizibox":{"domain":"https://ddizibox.net"}}}""")
        assertTrue(config.refresh().contains("Güncelleme yok"))
        assertEquals(1, config.status().version)

        manifest.set("""{"schema":1,"version":3,"providers":{"ddizibox":{"domain":"https://ddizibox.net"}}}""")
        assertTrue(config.refresh().contains("v3"))
        assertEquals(3, config.status().version)
        assertEquals("ddizibox.net", ProviderRuntimeRules.remapHost("ddizibox.com"))

        val restarted = manager()
        assertEquals(3, restarted.status().version)
        assertEquals("ddizibox.net", ProviderRuntimeRules.remapHost("ddizibox.com"))
    }
}
