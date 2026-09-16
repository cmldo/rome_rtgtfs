package io.github.cmldo.romertgtfs

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

const val REPO_URL = "https://github.com/cmldo/rome_rtgtfs"
const val DATASET_URL = "https://dati.comune.roma.it/catalog/dataset/c_h501-d-9000"
const val OPEN_DATA_URL = "https://romamobilita.it/sistemi-e-tecnologie/open-data/"
const val ATTRIBUTION =
    "Dati: Roma Servizi per la Mobilità, Roma Capitale. Licenza Creative Commons Attribuzione, " +
        "Condividi allo stesso modo (CC BY-SA)."
const val DISCLAIMER =
    "App non ufficiale, non affiliata a Roma Servizi per la Mobilità né ad Atac. I dati sono forniti " +
        "esclusivamente a titolo di supporto al viaggio: non sono esaustivi del servizio e possono risentire " +
        "delle condizioni del traffico e di malfunzionamenti del sistema di localizzazione dei mezzi (AVM). " +
        "I dati sull'affollamento dei mezzi sono sperimentali. La possibilità di salire a bordo va sempre " +
        "verificata al passaggio del mezzo."

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
private val HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DATE_TIME = DateTimeFormatter.ofPattern("d/M/yyyy HH:mm")
private fun hhmm(epoch: Long, f: DateTimeFormatter = HHMM) = f.format(Instant.ofEpochSecond(epoch).atZone(ROME))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        StaticUpdateWorker.enqueue(this)
        setContent { AppTheme { App() } }
    }
}

private val Amaranto = Color(0xFF8E1F2F)
private val Oro = Color(0xFFF2C94C)

/** Verde "tempo reale", più chiaro sui fondi scuri. */
private val Live @Composable get() =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF6FD08C) else Color(0xFF1B873F)

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFFFFB3B5), onPrimary = Color(0xFF561D22), secondary = Oro,
            primaryContainer = Color(0xFF6D0F1F), onPrimaryContainer = Color(0xFFFFDADA),
            secondaryContainer = Color(0xFF5A4300), onSecondaryContainer = Color(0xFFFFDF9A),
        )
    } else {
        lightColorScheme(
            primary = Amaranto, secondary = Color(0xFF7A5900),
            primaryContainer = Color(0xFFFFDADA), onPrimaryContainer = Color(0xFF3B0710),
            secondaryContainer = Color(0xFFFFDF9A), onSecondaryContainer = Color(0xFF261A00),
            background = Color(0xFFFFFBFA), surface = Color(0xFFFFFBFA),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App() {
    var page by rememberSaveable { mutableStateOf("home") }
    var stopId by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = page != "home") { page = "home" }
    val home = { page = "home" }
    when (page) {
        "stop" -> StopScreen(stopId, home)
        "alerts" -> AlertsScreen(home)
        "info" -> InfoScreen(home)
        else -> HomeScreen(open = { stopId = it; page = "stop" }, nav = { page = it })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    back: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (back != null) IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = actions,
            )
        },
    ) { pad -> Column(Modifier.padding(pad).fillMaxSize(), content = content) }
}

// ---------------------------------------------------------------- home

@Composable
fun HomeScreen(open: (String) -> Unit, nav: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by Gtfs.version.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var nearby by remember { mutableStateOf<List<Stop>?>(null) }
    var nearbyMsg by remember { mutableStateOf<String?>(null) }
    val results by produceState(emptyList<Stop>(), query, version) {
        value = withContext(Dispatchers.IO) { Gtfs.read(ctx) { searchStops(it, query) }.orEmpty() }
    }

    fun locate() {
        nearbyMsg = "Ricerca della posizione…"
        currentLocation(ctx) { loc ->
            if (loc == null) {
                nearbyMsg = "Posizione non disponibile: verifica che la localizzazione sia attiva."
                return@currentLocation
            }
            scope.launch {
                val list = withContext(Dispatchers.IO) { Gtfs.read(ctx) { nearbyStops(it, loc.latitude, loc.longitude) } }
                nearby = list
                nearbyMsg = when {
                    list == null -> "Orari non ancora disponibili."
                    list.isEmpty() -> "Nessuna fermata entro 1 km."
                    else -> null
                }
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.any { granted -> granted }) locate() else nearbyMsg = "Permesso di localizzazione negato."
    }

    Page(
        title = "Roma in Tempo Reale",
        back = null,
        actions = {
            IconButton(onClick = { nav("alerts") }) { Icon(Icons.Default.Warning, contentDescription = "Avvisi") }
            IconButton(onClick = { nav("info") }) { Icon(Icons.Default.Info, contentDescription = "Informazioni") }
        },
    ) {
        StaticStatus()
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Nome o codice della fermata") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )
        if (query.isBlank()) {
            OutlinedButton(
                onClick = {
                    val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (perms.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) {
                        locate()
                    } else {
                        permission.launch(perms)
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Fermate vicine")
            }
            nearbyMsg?.let { Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
        }
        val list = if (query.isBlank()) nearby.orEmpty() else results
        if (query.isNotBlank() && results.isEmpty()) {
            Text("Nessuna fermata trovata.", Modifier.padding(16.dp))
        }
        LazyColumn {
            items(list, key = { it.id }) { s ->
                ListItem(
                    overlineContent = {
                        Text("Fermata ${s.code}" + (s.distance?.let { " · ${it.toInt()} m" } ?: ""))
                    },
                    headlineContent = { Text(s.name) },
                    supportingContent = {
                        Text("Linee: ${s.lines}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    modifier = Modifier.clickable { open(s.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

@SuppressLint("MissingPermission") // chiamata solo dopo la verifica del permesso
fun currentLocation(ctx: Context, callback: (Location?) -> Unit) {
    val lm = ctx.getSystemService(LocationManager::class.java)
    val candidates = buildList {
        if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
    }
    val provider = candidates.firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        ?: return callback(null)
    // Con la sola posizione approssimativa il GPS lancia SecurityException.
    try {
        val recent = lm.getLastKnownLocation(provider)
        if (recent != null && System.currentTimeMillis() - recent.time < 60_000) return callback(recent)
        val noCancel: android.os.CancellationSignal? = null
        LocationManagerCompat.getCurrentLocation(lm, provider, noCancel, ContextCompat.getMainExecutor(ctx)) {
            callback(it ?: recent)
        }
    } catch (e: SecurityException) {
        callback(null)
    }
}

@Composable
fun StaticStatus() {
    val ctx = LocalContext.current
    val infos by remember { WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(StaticUpdateWorker.NAME) }
        .collectAsState(emptyList())
    val version by Gtfs.version.collectAsState()
    val hasDb = remember(version) { Gtfs.file(ctx).exists() }
    val w = infos.firstOrNull()
    val running = w?.state == WorkInfo.State.RUNNING
    val text = when {
        running -> w.progress.getString(StaticUpdateWorker.KEY_PHASE)
            ?.let { "Aggiornamento orari. $it: ${w.progress.getInt(StaticUpdateWorker.KEY_PCT, 0)}%" }
            ?: if (hasDb) null else "Controllo degli orari disponibili…"
        w?.state == WorkInfo.State.ENQUEUED && w.runAttemptCount > 0 -> "Download degli orari non riuscito, nuovo tentativo a breve."
        w?.state == WorkInfo.State.ENQUEUED && !hasDb -> "In attesa della connessione per scaricare gli orari."
        w?.state == WorkInfo.State.FAILED ->
            "Aggiornamento orari non riuscito: ${w.outputData.getString(StaticUpdateWorker.KEY_ERROR)}"
        !hasDb -> "Orari non ancora disponibili."
        else -> null
    } ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (running && !hasDb) Text(
                "Il primo download pesa circa 50 MB e l'elaborazione può richiedere qualche minuto.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (running) {
                val pct = w.progress.getInt(StaticUpdateWorker.KEY_PCT, 0)
                LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- fermata

class Board(
    val stop: Stop?,
    val departures: List<Departure>,
    val alerts: List<AlertItem>,
    val realtimeAt: Long?,
    val error: String?,
)

suspend fun loadBoard(ctx: Context, stopId: String): Board = withContext(Dispatchers.IO) {
    val now = ZonedDateTime.now(ROME)
    val static = Gtfs.read(ctx) { db -> stopById(db, stopId)?.let { it to scheduled(db, it, now) } }
        ?: return@withContext Board(null, emptyList(), emptyList(), null, "Fermata non trovata negli orari.")
    val (stop, sched) = static
    coroutineScope {
        val tu = async { runCatching { Realtime.tripUpdates.get() } }
        val vp = async { runCatching { Realtime.vehicles.get() } }
        val al = async { runCatching { Realtime.alerts.get() } }
        val trips = tu.await()
        val alerts = al.await().getOrNull()?.let { alertsForStop(activeAlerts(it, now.toEpochSecond()), stop) }
        Board(
            stop = stop,
            departures = applyRealtime(sched, stop.id, trips.getOrNull(), vp.await().getOrNull(), now.toEpochSecond()),
            alerts = alerts.orEmpty(),
            realtimeAt = trips.getOrNull()?.header?.timestamp,
            error = trips.exceptionOrNull()?.let { "Tempo reale non disponibile, sono mostrati gli orari programmati." },
        )
    }
}

@Composable
fun StopScreen(stopId: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val version by Gtfs.version.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var board by remember { mutableStateOf<Board?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showAlerts by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(stopId, refresh, version) {
        // Solo con l'app in primo piano: una interrogazione ogni 60 secondi, la cadenza del feed.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                loading = true
                board = loadBoard(ctx, stopId)
                loading = false
                delay(60_000)
            }
        }
    }
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val b = board
    Page(
        title = b?.stop?.name ?: "Fermata",
        back = back,
        actions = {
            IconButton(onClick = { refresh++ }, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = "Aggiorna")
            }
        },
    ) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.size(4.dp))
        if (b == null) return@Page
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    b.stop?.let { Text("Fermata ${it.code} · Linee: ${it.lines}", style = MaterialTheme.typography.bodyMedium) }
                    b.realtimeAt?.let {
                        Text("Tempo reale aggiornato alle ${hhmm(it, HHMMSS)}", style = MaterialTheme.typography.bodySmall)
                    }
                    b.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (b.alerts.isNotEmpty()) item {
                Card(
                    onClick = { showAlerts = !showAlerts },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Text(
                            (if (b.alerts.size == 1) "1 avviso" else "${b.alerts.size} avvisi") +
                                " sulle linee di questa fermata",
                            Modifier.weight(1f).padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(if (showAlerts) "Nascondi" else "Mostra", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (showAlerts) items(b.alerts, key = { "a" + it.id }) { AlertCard(it) }
            if (b.stop != null && b.departures.isEmpty()) item {
                Text("Nessun passaggio previsto nei prossimi 90 minuti.", Modifier.padding(16.dp))
            }
            items(b.departures, key = { it.tripId + "#" + it.seq }) { d ->
                DepartureRow(d, now)
                HorizontalDivider()
            }
            item {
                Text(
                    "Orari e tempo reale a solo supporto del viaggio. $ATTRIBUTION",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun LineBadge(name: String, hex: String?, type: Int) {
    val bg = hex?.let { Color(("FF$it").toLong(16)) } ?: when (type) {
        0 -> Color(0xFF2E7D32) // tram
        1 -> Color(0xFFC62828) // metro
        2 -> Color(0xFF37474F) // ferrovia
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp), modifier = Modifier.widthIn(min = 52.dp)) {
        Text(
            name,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            color = if (bg.luminance() > 0.5f) Color.Black else Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
fun DepartureRow(d: Departure, now: Long) {
    val live = d.predicted != null && !d.canceled
    val details = buildList {
        when {
            d.canceled -> add("Corsa soppressa")
            live -> {
                val min = Math.round((d.predicted!! - d.scheduled) / 60.0)
                add(
                    when {
                        min > 0 -> "Tempo reale, +$min min"
                        min < 0 -> "Tempo reale, $min min"
                        else -> "Tempo reale, in orario"
                    },
                )
            }
            else -> add("Programmato")
        }
        d.stopsAway?.let { add(if (it == 0) "alla fermata o in arrivo" else if (it == 1) "a 1 fermata" else "a $it fermate") }
        d.vehicle?.let { add("vettura $it") }
        d.occupancy?.let(::occupancyLabel)?.let { add(it) }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineBadge(d.line, d.lineColor, d.routeType)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                d.headsign,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (d.canceled) TextDecoration.LineThrough else null,
            )
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            val min = (d.time - now) / 60
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (live) Surface(color = Live, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
                Text(
                    when {
                        d.canceled -> "-"
                        min <= 0 -> "ora"
                        min < 60 -> "$min min"
                        else -> hhmm(d.time)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (live) Live else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(hhmm(d.time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- avvisi

@Composable
fun AlertCard(a: AlertItem) {
    var open by rememberSaveable(a.id) { mutableStateOf(false) }
    Card(
        onClick = { open = !open },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(a.header, style = MaterialTheme.typography.titleSmall)
            if (a.routes.isNotEmpty()) {
                Text("Linee: " + a.routes.joinToString(", "), style = MaterialTheme.typography.labelMedium)
            }
            if (open) {
                if (a.description.isNotEmpty()) Text(a.description, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
                val period = listOfNotNull(
                    a.start?.let { "dal " + hhmm(it, DATE_TIME) },
                    a.end?.let { "al " + hhmm(it, DATE_TIME) },
                )
                if (period.isNotEmpty()) Text(period.joinToString(" "), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall)
            } else if (a.description.isNotEmpty()) {
                Text("Tocca per i dettagli", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun AlertsScreen(back: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val state by produceState<Result<List<AlertItem>>?>(null, refresh) {
        value = withContext(Dispatchers.IO) {
            runCatching { activeAlerts(Realtime.alerts.get(), System.currentTimeMillis() / 1000) }
        }
    }
    Page(
        title = "Avvisi di servizio",
        back = back,
        actions = { IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, contentDescription = "Aggiorna") } },
    ) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Filtra per linea o parola") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )
        val result = state
        when {
            result == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            result.isFailure -> Text("Avvisi non disponibili: controlla la connessione.", Modifier.padding(16.dp))
            else -> {
                val f = filter.trim()
                val list = result.getOrThrow().filter { a ->
                    f.isEmpty() || a.routes.any { it.equals(f, ignoreCase = true) } ||
                        a.header.contains(f, ignoreCase = true) || a.description.contains(f, ignoreCase = true)
                }
                LazyColumn {
                    item { Text("${list.size} avvisi attivi", Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    items(list, key = { it.id }) { AlertCard(it) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- info

@Composable
fun InfoScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val version by Gtfs.version.collectAsState()
    val prefs = remember { ctx.getSharedPreferences(StaticUpdateWorker.PREFS, Context.MODE_PRIVATE) }
    val updated = remember(version) { prefs.getLong(StaticUpdateWorker.KEY_UPDATED, 0L) }
    val md5 = remember(version) { prefs.getString(StaticUpdateWorker.KEY_MD5, null) }
    Page(title = "Informazioni", back = back) {
        StaticStatus()
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Fonte dei dati", style = MaterialTheme.typography.titleMedium)
            Text(ATTRIBUTION)
            Text(
                "Gli orari programmati vengono scaricati solo quando Roma Mobilità pubblica una nuova versione " +
                    "(controllo tramite md5). Tempo reale e avvisi vengono letti direttamente dai file pubblicati, " +
                    "senza server intermedi.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { uri.openUri(DATASET_URL) }) { Text("Dataset e licenza") }
                TextButton(onClick = { uri.openUri(OPEN_DATA_URL) }) { Text("Open data Roma Mobilità") }
            }
            Text("Orari in uso", style = MaterialTheme.typography.titleMedium)
            Text(
                if (updated > 0) {
                    "Importati il " + hhmm(updated / 1000, DATE_TIME) +
                        "\nmd5 $md5"
                } else {
                    "Nessun orario importato."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { StaticUpdateWorker.enqueue(ctx) }) { Text("Controlla aggiornamenti") }
            Text("Avvertenze", style = MaterialTheme.typography.titleMedium)
            Text(DISCLAIMER, style = MaterialTheme.typography.bodyMedium)
            Text("Codice sorgente", style = MaterialTheme.typography.titleMedium)
            Text("Software libero con licenza MIT.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { uri.openUri(REPO_URL) }) { Text("github.com/cmldo/rome_rtgtfs") }
        }
    }
}
