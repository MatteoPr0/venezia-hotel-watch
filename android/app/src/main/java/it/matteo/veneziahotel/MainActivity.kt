package it.matteo.veneziahotel

import android.Manifest
import android.app.Application
import java.io.File
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

enum class ZoneFilter { ALL, LIDO, CENTRO }
enum class SortBy { PRICE, RATING }

class MainVm(app: Application) : AndroidViewModel(app) {
    val repo = Repo(app)
    /** fotografie Google per ogni weekend (0 = principale, poi gli alternativi) */
    private var googles: List<Snapshot> = repo.cachedAll()
    /** weekend mostrato */
    var stayIdx by mutableStateOf(repo.stayIdx.coerceIn(0, maxOf(0, googles.size - 1)))
        private set
    /** risultati di ogni sito letto dal telefono per il weekend mostrato (chiave = Site.key) */
    var sites by mutableStateOf(loadSites(app, stayIdx))
    var snap by mutableStateOf(googles.getOrNull(stayIdx)?.let { mergeAll(it, sites) })
    /** i weekend controllati, per il selettore in alto */
    var stays by mutableStateOf(googles)
    var showAirbnb by mutableStateOf(true)
    var loading by mutableStateOf(false)
    /** il sito che si sta controllando adesso */
    var siteLoading by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var zone by mutableStateOf(ZoneFilter.ALL)
    var sort by mutableStateOf(SortBy.PRICE)
    var onlyWithinMax by mutableStateOf(false)
    var onlyRefundable by mutableStateOf(false)
    var showGone by mutableStateOf(false)
    var curated by mutableStateOf(repo.cachedCurated(app))
    var checkedTick by mutableStateOf(0)
    var tab by mutableStateOf(0)
    var selected by mutableStateOf<Hotel?>(null)
    var festival by mutableStateOf(FestivalWatch.load(app))

    init { refresh(forceBooking = false) }

    fun refresh() = refresh(forceBooking = true)

    private fun publish() {
        stays = googles
        if (stayIdx >= googles.size) stayIdx = 0
        val g = googles.getOrNull(stayIdx) ?: return
        sites = loadSites(getApplication(), stayIdx)
        val s = mergeAll(g, sites)
        snap = s
        // allinea il "visto" delle notifiche: ciò che vedi in app non ti viene ri-notificato
        s.events.firstOrNull()?.time?.let { if (it > repo.lastNotified(stayIdx)) repo.setLastNotified(stayIdx, it) }
    }

    /** Cambia weekend; se i siti del telefono non l'hanno ancora controllato di recente, parte un controllo. */
    fun selectStay(i: Int) {
        if (i == stayIdx || i !in googles.indices) return
        stayIdx = i
        repo.stayIdx = i
        publish()
        checkSites(force = false)
    }

    fun refresh(forceBooking: Boolean) {
        if (loading) return
        loading = true
        viewModelScope.launch {
            runCatching { repo.fetchAll() }
                .onSuccess { all -> googles = all; error = null; publish() }
                .onFailure { error = it.message ?: "errore di rete" }
            runCatching { repo.fetchCurated(getApplication()) }.onSuccess { curated = it }
            // annunci della Mostra (feed RSS della Biennale); nel collaudo si può usare un feed finto
            val testRss = File(getApplication<Application>().filesDir, "festival_test_rss.xml").takeIf { BuildConfig.DEBUG && it.exists() }?.readText()
            runCatching { FestivalWatch.check(getApplication(), festivalYear(googles.firstOrNull()), rssOverride = testRss) }.onSuccess { festival = it }
            // collaudo: sveglia "30 minuti prima" fra un minuto
            if (BuildConfig.DEBUG && File(getApplication<Application>().filesDir, "festival_test_alarm").exists())
                FestivalAlarm.schedule(getApplication(), System.currentTimeMillis() + 31 * 60_000L)
            loading = false
            runSiteChecks(forceBooking)
        }
    }

    private fun checkSites(force: Boolean) {
        viewModelScope.launch { runSiteChecks(force) }
    }

    /** Siti letti dal telefono: prima il weekend mostrato, poi gli altri (senza forzare, max 1 volta l'ora). */
    private var checkingSites = false

    private suspend fun runSiteChecks(force: Boolean) {
        if (checkingSites) return
        checkingSites = true
        try {
            val first = stayIdx
            val order = listOf(first) + googles.indices.filter { it != first }
            for (i in order) {
                val g = googles.getOrNull(i) ?: continue
                for (site in SITES) {
                    siteLoading = if (i == stayIdx) site.key else null
                    checkSite(getApplication(), g, site, force = force && i == first, minMinutes = if (i == first) 30 else 60, stayIdx = i)
                    publish()
                }
            }
        } finally {
            checkingSites = false
            siteLoading = null
        }
    }

    fun visibleHotels(): List<Hotel> {
        val s = snap ?: return emptyList()
        return s.hotels
            .filter { showGone || it.available }
            .filter {
                when (zone) {
                    ZoneFilter.ALL -> true
                    ZoneFilter.LIDO -> it.zone == "lido"
                    ZoneFilter.CENTRO -> it.zone == "centro"
                }
            }
            .filter { !onlyWithinMax || (it.total != null && it.total <= s.maxFor(it.zone)) }
            .filter { !onlyRefundable || it.refundable == true || it.refundableTotal != null }
            .filter { showAirbnb || it.source != "airbnb" }
            .sortedWith(
                when (sort) {
                    SortBy.PRICE -> compareBy<Hotel>({ !it.available }, { it.total == null }, { it.total ?: 0 })
                    SortBy.RATING -> compareBy<Hotel>({ !it.available }, { -(it.rating ?: 0.0) })
                }
            )
    }
}

class MainActivity : ComponentActivity() {
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CheckWorker.ensureChannel(this)
        CheckWorker.schedule(this)
        FestivalWorker.schedule(this)
        if (BuildConfig.DEBUG && java.io.File(filesDir, "probe").exists()) startProbe(this)
        if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            VeneziaTheme {
                val vm: MainVm = viewModel()
                AppScreen(vm)
            }
        }
    }
}
