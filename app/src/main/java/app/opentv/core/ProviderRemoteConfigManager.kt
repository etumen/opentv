package app.opentv.core

import android.content.Context
import app.opentv.data.provider.ProviderMediaType
import app.opentv.data.provider.ProviderRuntimeRules
import app.opentv.data.provider.RemoteListingRule
import app.opentv.data.provider.RemoteProviderRule
import app.opentv.data.provider.RemoteRuleSet
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.select.Selector

/**
 * Versioned, data-only provider settings from the repository we control.
 * No executable code, arbitrary source URL, background scheduler, or private endpoint.
 * The source file is published separately, after manual validation and approval.
 */
class ProviderRemoteConfigManager(
    context: Context,
    private val manifestUrl: String = SOURCE_URL,
    testClient: OkHttpClient? = null,
) {
    private val prefs = context.applicationContext.getSharedPreferences("remote_provider_rules", Context.MODE_PRIVATE)
    private val http = testClient ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    companion object {
        const val SOURCE_URL = "https://raw.githubusercontent.com/etumen/opentv-mobile/main/config/providers.json"
        private const val MAX_BYTES = 65_536
    }

    data class Status(
        val version: Int,
        val source: String,
        val hash: String?,
        val lastResult: String,
        val rollbackAvailable: Boolean,
    )

    @Volatile private var lastResult = "Henüz uzaktan kontrol edilmedi"

    init {
        val saved = prefs.getString("active", null)
        val current = saved?.let { runCatching { parse(it) }.getOrNull() }
        ProviderRuntimeRules.install(current ?: RemoteRuleSet())
        if (saved != null && current == null) lastResult = "Yerel kayıt geçersiz, varsayılan ayarlar korundu"
    }

    fun status(): Status {
        val raw = prefs.getString("active", null)
        return Status(
            version = ProviderRuntimeRules.snapshot().version,
            source = manifestUrl,
            hash = raw?.let(::sha256),
            lastResult = lastResult,
            rollbackAvailable = prefs.contains("previous"),
        )
    }

    /** Returns only applied changes; a missing/unpublished JSON is never treated as success. */
    suspend fun refresh(): String = withContext(Dispatchers.IO) {
        val result = runCatching {
            val response = http.newCall(Request.Builder().url(manifestUrl)
                .header("Cache-Control", "no-cache").build()).execute()
            val raw = response.use {
                if (!it.isSuccessful) error("HTTP ${it.code}")
                val input = it.body?.byteStream() ?: error("Boş yanıt")
                input.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n == -1) break
                        if (out.size() + n > MAX_BYTES) error("JSON boyutu sınırı aşıldı")
                        out.write(buffer, 0, n)
                    }
                    out.toString(Charsets.UTF_8.name())
                }
            }
            val rules = parse(raw)
            val active = ProviderRuntimeRules.snapshot().version
            val blocked = prefs.getInt("blockedVersion", 0)
            if (rules.version <= active || rules.version <= blocked) {
                "Güncelleme yok (aktif v$active, uzak v${rules.version})"
            } else {
                val old = prefs.getString("active", null)
                // Validate the whole document before touching any active settings.
                val editor = prefs.edit().putString("active", raw)
                if (old != null) editor.putString("previous", old) else editor.remove("previous")
                check(editor.commit()) { "Ayarlar diske yazılamadı" }
                ProviderRuntimeRules.install(rules)
                "v${rules.version} uygulandı · SHA-256 ${sha256(raw).take(12)}"
            }
        }.getOrElse { "Güncelleme uygulanmadı: ${it.message}" }
        lastResult = result
        result
    }

    /** Explicit developer action. Suppress automatic re-application of the reverted version. */
    @Synchronized
    fun rollback(): String {
        val previousRaw = prefs.getString("previous", null)
        val oldVersion = ProviderRuntimeRules.snapshot().version
        val previous = (if (previousRaw != null) runCatching { parse(previousRaw) }.getOrNull()
            else null) ?: RemoteRuleSet()
        val editor = prefs.edit()
            .putInt("blockedVersion", maxOf(prefs.getInt("blockedVersion", 0), oldVersion))
        if (previousRaw != null && previous.version > 0) editor.putString("active", previousRaw)
        else editor.remove("active")
        editor.remove("previous")
        if (!editor.commit()) return "Geri alma yazılamadı"
        ProviderRuntimeRules.install(previous)
        lastResult = "v$oldVersion geri alındı → v${previous.version}"
        return lastResult
    }

    private fun parse(raw: String): RemoteRuleSet {
        require(raw.length in 2..MAX_BYTES) { "JSON boyutu geçersiz" }
        val root = JSONObject(raw)
        require(keys(root) == setOf("schema", "version", "providers")) { "Bilinmeyen/eksik kök alanı" }
        require(root.getInt("schema") == 1) { "Desteklenmeyen schema" }
        val version = root.getInt("version")
        require(version in 1..1_000_000) { "Sürüm geçersiz" }
        val providersJson = root.getJSONObject("providers")
        require(providersJson.length() <= 9) { "Provider sınırı aşıldı" }
        val providers = linkedMapOf<String, RemoteProviderRule>()
        for (id in keys(providersJson)) {
            require(id in ProviderRuntimeRules.supportedIds) { "Bilinmeyen provider: $id" }
            val entry = providersJson.getJSONObject(id)
            require(keys(entry).all { it in setOf("domain", "listings") }) { "Bilinmeyen provider alanı: $id" }
            val domain = if (entry.has("domain")) normaliseDomain(entry.getString("domain")) else null
            val listings = linkedMapOf<String, RemoteListingRule>()
            if (entry.has("listings")) {
                val l = entry.getJSONObject("listings")
                require(l.length() <= 32) { "Kategori sınırı" }
                for (sectionId in keys(l)) {
                    require(sectionId.matches(Regex("[a-zA-Z0-9_-]{1,50}"))) { "Section ID geçersiz" }
                    val item = l.getJSONObject(sectionId)
                    require(keys(item).all { it in setOf("type", "path", "item", "link", "title", "image", "next") }) {
                        "Bilinmeyen liste alanı"
                    }
                    val type = ProviderMediaType.valueOf(item.getString("type"))
                    require(type != ProviderMediaType.OTHER) { "Desteklenmeyen medya türü" }
                    val path = item.getString("path")
                    require(path.startsWith("/") && !path.startsWith("//") &&
                        ".." !in path && "://" !in path && path.length <= 250) { "Geçersiz liste yolu" }
                    fun selector(name: String, optional: Boolean = false): String? {
                        if (optional && !item.has(name)) return null
                        val value = item.getString(name).trim()
                        require(value.length in 1..160) { "Geçersiz CSS selector" }
                        if (value != ":self") {
                            try { Selector.select(value, Jsoup.parse("")) } catch (e: Exception) {
                                throw IllegalArgumentException("CSS selector geçersiz: $name", e)
                            }
                        }
                        return value
                    }
                    listings[sectionId] = RemoteListingRule(
                        type, path, selector("item")!!, selector("link")!!,
                        selector("title")!!, selector("image", true),
                        selector("next", true),
                    )
                }
            }
            providers[id] = RemoteProviderRule(domain, listings)
        }
        return RemoteRuleSet(version, providers)
    }

    private fun normaliseDomain(value: String): String {
        val uri = URI(value.trim())
        val host = uri.host?.lowercase() ?: error("Domain host geçersiz")
        require(uri.scheme.equals("https", true) && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/") &&
            host.length in 4..253 && host.contains('.') &&
            !host.endsWith(".local") && !host.endsWith(".localhost") &&
            !host.endsWith(".internal") && !host.matches(Regex("[0-9.]+")) &&
            host.split('.').all { it.matches(Regex("[a-z0-9-]{1,63}")) && !it.startsWith('-') && !it.endsWith('-') }
        ) { "Yalnızca genel HTTPS alan adı kullanılabilir" }
        return "https://$host"
    }

    private fun keys(obj: JSONObject): Set<String> = obj.keys().asSequence().toSet()
    private fun sha256(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
