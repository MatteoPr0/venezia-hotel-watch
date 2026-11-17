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
    private var google: Snapshot? = repo.cached()
    /** risultati di ogni sito letto dal telefono (chiave = Site.key) */
    var sites by mutableStateOf(loadSites(app))
    var snap by mutableStateOf(google?.let { mergeAll(it, sites) })
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
        val g = google ?: return
        val s = mergeAll(g, sites)
        snap = s
        // allinea il "visto" delle notifiche: ciò che vedi in app non ti viene ri-notificato
        s.events.firstOrNull()?.time?.let { if (it > repo.lastNotifiedEvent) repo.lastNotifiedEvent = it }
    }

    fun refresh(forceBooking: Boolean) {
        if (loading) return
        loading = true
        viewModelScope.launch {
            runCatching { repo.fetch() }
                .onSuccess { s -> google = s; error = null; publish() }
                .onFailure { error = it.message ?: "errore di rete" }
            runCatching { repo.fetchCurated(getApplication()) }.onSuccess { curated = it }
            // annunci della Mostra (feed RSS della Biennale); nel collaudo si può usare un feed finto
            val testRss = File(getApplication<Application>().filesDir, "festival_test_rss.xml").takeIf { BuildConfig.DEBUG && it.exists() }?.readText()
            runCatching { FestivalWatch.check(getApplication(), festivalYear(google), rssOverride = testRss) }.onSuccess { festival = it }
            // collaudo: sveglia "30 minuti prima" fra un minuto
            if (BuildConfig.DEBUG && File(getApplication<Application>().filesDir, "festival_test_alarm").exists())
                FestivalAlarm.schedule(getApplication(), System.currentTimeMillis() + 31 * 60_000L)
            loading = false
            val g = google ?: return@launch
            if (siteLoading != null) return@launch
            for (site in SITES) {
                siteLoading = site.key
                val st = checkSite(getApplication(), g, site, force = forceBooking)
                sites = sites + (site.key to st)
                publish()
            }
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
