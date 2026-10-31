package app.opentv.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.core.ServiceLocator
import app.opentv.data.provider.Provider
import app.opentv.data.provider.ProviderCatalogRequest
import app.opentv.data.provider.ProviderCatalogSection
import app.opentv.data.provider.ProviderMediaType
import app.opentv.data.provider.ProviderResult
import java.net.URI
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Debug-only read-only probe. It never changes provider configuration or health scores. */
internal data class DiagnosticProvider(
    val id: String,
    val name: String,
    val types: Set<ProviderMediaType>,
    val type: ProviderMediaType,
    val sections: List<ProviderCatalogSection> = emptyList(),
    val sectionId: String? = null,
    val page: Int? = null,
    val count: Int? = null,
    val nextPage: Int? = null,
    val domain: String? = null,
    val elapsedMs: Long? = null,
    val checkedAt: Long? = null,
    val error: String? = null,
    val note: String? = null,
    val busy: Boolean = false,
)

internal class ProviderDiagnosticsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val repository = graph.providerRepository
    private val providers = repository.providers().associateBy { it.id }
    private val jobs = mutableMapOf<String, Job>()
    private val _rows = MutableStateFlow(
        providers.values.sortedBy { it.name.lowercase() }.mapNotNull { provider ->
            val type = when {
                ProviderMediaType.MOVIE in provider.supportedMediaTypes -> ProviderMediaType.MOVIE
                ProviderMediaType.SERIES in provider.supportedMediaTypes -> ProviderMediaType.SERIES
                else -> null
            } ?: return@mapNotNull null
            DiagnosticProvider(
                id = provider.id,
                name = provider.name,
                types = provider.supportedMediaTypes,
                type = type,
                sections = provider.catalogSections.filter { it.mediaType == type },
            )
        },
    )
    val rows = _rows.asStateFlow()
    private val _configStatus = MutableStateFlow(graph.remoteProviderConfig.status())
    val configStatus = _configStatus.asStateFlow()
    private val _configBusy = MutableStateFlow(false)
    val configBusy = _configBusy.asStateFlow()

    fun refreshRemoteConfig() {
        if (_configBusy.value) return
        _configBusy.value = true
        viewModelScope.launch {
            try {
                graph.remoteProviderConfig.refresh()
                _configStatus.value = graph.remoteProviderConfig.status()
            } finally {
                _configBusy.value = false
            }
        }
    }

    fun rollbackRemoteConfig() {
        if (_configBusy.value) return
        graph.remoteProviderConfig.rollback()
        _configStatus.value = graph.remoteProviderConfig.status()
    }

    fun score(id: String): Pair<Int, Int> =
        ((graph.providerHealth.score(id) * 100).toInt()) to graph.providerHealth.samples(id)

    private fun update(id: String, transform: (DiagnosticProvider) -> DiagnosticProvider) {
        _rows.value = _rows.value.map { if (it.id == id) transform(it) else it }
    }

    fun changeType(id: String, type: ProviderMediaType) {
        val provider = providers[id] ?: return
        if (type !in provider.supportedMediaTypes || jobs[id]?.isActive == true) return
        update(id) {
            it.copy(
                type = type,
                sections = provider.catalogSections.filter { s -> s.mediaType == type },
                sectionId = null, page = null, count = null, nextPage = null,
                error = null, note = null, checkedAt = null, domain = null, elapsedMs = null,
            )
        }
    }

    fun changeSection(id: String, sectionId: String) {
        if (jobs[id]?.isActive == true) return
        update(id) {
            if (it.sections.none { s -> s.id == sectionId }) it
            else it.copy(
                sectionId = sectionId, page = null, count = null, nextPage = null,
                error = null, note = null, checkedAt = null, elapsedMs = null,
            )
        }
    }

    fun check(id: String, following: Boolean = false) {
        if (jobs[id]?.isActive == true) return
        val row = _rows.value.firstOrNull { it.id == id } ?: return
        if (following && row.nextPage == null) return
        val requestedPage = if (following) row.nextPage!! else 1
        update(id) { it.copy(busy = true) }
        jobs[id] = viewModelScope.launch {
            val begin = android.os.SystemClock.elapsedRealtime()
            try {
                val outcome = withContext(Dispatchers.IO) {
                    withTimeout(18_000L) {
                        probe(row, requestedPage, following)
                    }
                }
                update(id) {
                    outcome.copy(
                        busy = false,
                        elapsedMs = android.os.SystemClock.elapsedRealtime() - begin,
                        checkedAt = System.currentTimeMillis(),
                    )
                }
            } catch (e: CancellationException) {
                // A timed-out provider is a failed diagnostic check, not a healthy source.
                if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    update(id) {
                        it.copy(
                            busy = false, error = "Zaman aşımı (18 sn)", nextPage = null,
                            elapsedMs = android.os.SystemClock.elapsedRealtime() - begin,
                            checkedAt = System.currentTimeMillis(),
                        )
                    }
                } else throw e
            } catch (e: Exception) {
                update(id) {
                    it.copy(
                        busy = false, error = e.javaClass.simpleName + ": " + (e.message ?: "Bilinmeyen hata"),
                        nextPage = null,
                        elapsedMs = android.os.SystemClock.elapsedRealtime() - begin,
                        checkedAt = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    private suspend fun probe(
        row: DiagnosticProvider,
        page: Int,
        following: Boolean,
    ): DiagnosticProvider {
        val provider = providers[row.id] ?: return row.copy(error = "Provider bulunamadı")
        var sections = row.sections
        var warning: String? = null
        if (!following) {
            when (val discovery = repository.catalogSections(row.id, row.type)) {
                is ProviderResult.Success -> {
                    sections = discovery.value.filter { it.mediaType == row.type }
                }
                is ProviderResult.Failure -> {
                    warning = "Kategori keşfi: ${discovery.error.code}"
                    // Registered sections are a usable fallback, but discovery failure is still disclosed.
                    sections = provider.catalogSections.filter { it.mediaType == row.type }
                }
            }
        }
        val selected = sections.firstOrNull { it.id == row.sectionId }
            ?: sections.firstOrNull { it.showOnHome }
            ?: sections.firstOrNull()
        val sectionId = if (following) row.sectionId else selected?.id
        val response = repository.catalog(
            row.id,
            ProviderCatalogRequest(row.type, sectionId, page),
        )
        return when (response) {
            is ProviderResult.Success -> {
                val result = response.value
                val discoveredDomain = result.items.asSequence()
                    .mapNotNull { item ->
                        runCatching {
                            val uri = URI(item.id)
                            if (uri.scheme == "http" || uri.scheme == "https") uri.host else null
                        }.getOrNull()
                    }.firstOrNull()
                row.copy(
                    sections = sections, sectionId = sectionId, page = page,
                    count = result.items.size,
                    nextPage = result.nextPage?.takeIf { it > page },
                    error = if (result.items.isEmpty()) "Bu sayfa boş döndü" else null,
                    note = warning,
                    domain = discoveredDomain ?: row.domain,
                )
            }
            is ProviderResult.Failure -> row.copy(
                sections = sections, sectionId = sectionId, page = page,
                count = null, nextPage = null, note = warning,
                error = "${response.error.code} (${response.error.operation}): ${response.error.message}",
            )
        }
    }
}

@Composable
fun ProviderDiagnosticsScreen(onBack: () -> Unit) {
    val model: ProviderDiagnosticsViewModel = viewModel()
    val rows by model.rows.collectAsState()
    val config by model.configStatus.collectAsState()
    val busyConfig by model.configBusy.collectAsState()
    var confirmRollback by remember { mutableStateOf(false) }
    if (confirmRollback) {
        AlertDialog(
            onDismissRequest = { confirmRollback = false },
            title = { Text("Provider yapılandırmasını geri al") },
            text = { Text("Önceki sürüme dönülecek. Bu sürüm yeni bir JSON sürümü yayınlanana kadar tekrar uygulanmayacak.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRollback = false
                    model.rollbackRemoteConfig()
                }) { Text("Geri al") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRollback = false }) { Text("İptal") }
            },
        )
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row {
            Text("Geliştirici · Provider Durumu", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text("Geri") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Yalnızca geliştirici APK'sı. Kontroller elle çalışır; içerik veya ayar değiştirilmez.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "remote-config") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Uzaktan JSON Yapılandırması", style = MaterialTheme.typography.titleMedium)
                        Text("Aktif sürüm: v${config.version}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "SHA-256: ${config.hash?.take(16) ?: "Varsayılan ayarlar"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            config.lastResult,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Kaynak: ${config.source}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = model::refreshRemoteConfig,
                                enabled = !busyConfig,
                            ) { Text(if (busyConfig) "Kontrol ediliyor" else "Güncellemeyi Kontrol Et") }
                            OutlinedButton(
                                onClick = { confirmRollback = true },
                                enabled = !busyConfig && config.version > 0,
                            ) { Text("Geri Al") }
                        }
                    }
                }
            }
            items(rows, key = { it.id }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(row.id, style = MaterialTheme.typography.bodySmall)
                        val (score, samples) = model.score(row.id)
                        Text(
                            "Geçmiş oynatma puanı: %$score · $samples örnek (canlı test değildir)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (ProviderMediaType.MOVIE in row.types) {
                                TextButton(onClick = { model.changeType(row.id, ProviderMediaType.MOVIE) }) {
                                    Text(if (row.type == ProviderMediaType.MOVIE) "● Film" else "Film")
                                }
                            }
                            if (ProviderMediaType.SERIES in row.types) {
                                TextButton(onClick = { model.changeType(row.id, ProviderMediaType.SERIES) }) {
                                    Text(if (row.type == ProviderMediaType.SERIES) "● Dizi" else "Dizi")
                                }
                            }
                        }
                        if (row.sections.isNotEmpty()) {
                            var menuOpen by remember(row.id, row.type) { mutableStateOf(false) }
                            val chosen = row.sections.firstOrNull { it.id == row.sectionId }
                            OutlinedButton(onClick = { menuOpen = true }, enabled = !row.busy) {
                                Text("Kategori: ${chosen?.title ?: row.sections.first().title}")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                row.sections.forEach { section ->
                                    DropdownMenuItem(
                                        text = { Text(section.title) },
                                        onClick = {
                                            menuOpen = false
                                            model.changeSection(row.id, section.id)
                                        },
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { model.check(row.id) }, enabled = !row.busy) {
                                Text(if (row.busy) "Kontrol ediliyor" else "1. Sayfayı Test Et")
                            }
                            OutlinedButton(
                                onClick = { model.check(row.id, following = true) },
                                enabled = !row.busy && row.nextPage != null,
                            ) { Text("Sonraki Sayfa") }
                        }
                        if (row.checkedAt == null) {
                            Text("Henüz kontrol edilmedi", style = MaterialTheme.typography.bodySmall)
                        } else {
                            val time = remember(row.checkedAt) {
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                                    .format(Date(row.checkedAt))
                            }
                            val result = if (row.error != null) "HATA" else "BAŞARILI"
                            Text(
                                "$result · Sayfa ${row.page ?: "-"} · İçerik ${row.count ?: "-"} · " +
                                    "Sonraki ${row.nextPage ?: "yok"} · ${row.elapsedMs ?: "-"} ms",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (row.error != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                            )
                            Text("Son kontrol: $time", style = MaterialTheme.typography.bodySmall)
                            if (row.error != null) Text(row.error, color = MaterialTheme.colorScheme.error)
                            if (row.note != null) Text(row.note, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Domain: ${row.domain ?: "Provider API'si açıklamıyor"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
