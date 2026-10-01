package it.matteo.veneziahotel

import android.Manifest
import android.app.Application
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
    var snap by mutableStateOf(repo.cached())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var zone by mutableStateOf(ZoneFilter.ALL)
    var sort by mutableStateOf(SortBy.PRICE)
    var onlyWithinMax by mutableStateOf(false)
    var showGone by mutableStateOf(false)
    var tab by mutableStateOf(0)
    var selected by mutableStateOf<Hotel?>(null)

    init { refresh() }

    fun refresh() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            runCatching { repo.fetch() }
                .onSuccess { s ->
                    snap = s; error = null
                    // allinea il "visto" delle notifiche: ciò che vedi in app non ti viene ri-notificato
                    s.events.firstOrNull()?.time?.let { if (it > repo.lastNotifiedEvent) repo.lastNotifiedEvent = it }
                }
                .onFailure { error = it.message ?: "errore di rete" }
            loading = false
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
            .filter { !onlyWithinMax || (it.total != null && it.total <= s.budgetMax) }
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
        if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            VeneziaTheme {
                val vm: MainVm = viewModel()
                AppScreen(vm)
            }
        }
    }
}
