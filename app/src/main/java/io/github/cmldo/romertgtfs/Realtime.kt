package io.github.cmldo.romertgtfs

import com.google.transit.realtime.GtfsRealtime.FeedMessage
import com.google.transit.realtime.GtfsRealtime.TranslatedString
import com.google.transit.realtime.GtfsRealtime.TripDescriptor
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate
import com.google.transit.realtime.GtfsRealtime.VehiclePosition.OccupancyStatus
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val SRC = "https://romamobilita.it/sites/default/files/"
const val STATIC_ZIP_URL = SRC + "rome_static_gtfs.zip"
const val STATIC_MD5_URL = SRC + "rome_static_gtfs.zip.md5"
private const val USER_AGENT = "rome_rtgtfs-android (+https://github.com/cmldo/rome_rtgtfs)"

/** GET con redirect; accetta solo 200 e 304. */
fun <T> http(url: URL, ifModifiedSince: String? = null, block: (HttpURLConnection) -> T): T {
    val c = url.openConnection() as HttpURLConnection
    c.connectTimeout = 15_000
    c.readTimeout = 30_000
    c.setRequestProperty("User-Agent", USER_AGENT)
    ifModifiedSince?.let { c.setRequestProperty("If-Modified-Since", it) }
    try {
        val code = c.responseCode
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
            throw IOException("HTTP $code da ${url.host}")
        }
        return block(c)
    } finally {
        c.disconnect()
    }
}

/**
 * Un feed GTFS-Realtime. Ogni [get] interroga il server con If-Modified-Since,
 * così se il file non è cambiato la risposta è un 304 senza corpo.
 * [minAgeMs] evita richieste ravvicinate (tap ripetuti, rotazione dello schermo).
 */
class Feed(private val src: String, private val minAgeMs: Long) {
    private var url = URL(src)
    private var lastModified: String? = null
    private var message: FeedMessage? = null
    private var fetchedAt = 0L

    @Synchronized
    fun get(): FeedMessage {
        val cached = message
        if (cached != null && System.nanoTime() - fetchedAt < minAgeMs * 1_000_000) return cached
        http(url, if (cached != null) lastModified else null) { c ->
            if (c.responseCode == HttpURLConnection.HTTP_OK) {
                message = c.inputStream.use { FeedMessage.parseFrom(it) }
                lastModified = c.getHeaderField("Last-Modified")
            }
            url = c.url // dopo il primo redirect si va diretti alla destinazione
        }
        fetchedAt = System.nanoTime()
        return message ?: throw IOException("Feed vuoto: $src")
    }
}

object Realtime {
    // Il server rigenera i feed ogni 60 secondi circa; gli avvisi cambiano molto più di rado.
    val tripUpdates = Feed(SRC + "rome_rtgtfs_trip_updates_feed.pb", 15_000)
    val vehicles = Feed(SRC + "rome_rtgtfs_vehicle_positions_feed.pb", 15_000)
    val alerts = Feed(SRC + "rome_rtgtfs_service_alerts_feed.pb", 5 * 60_000)
}

private fun StopTimeUpdate.estimate(scheduled: Long): Long? = when {
    hasArrival() && arrival.hasTime() -> arrival.time
    hasDeparture() && departure.hasTime() -> departure.time
    hasArrival() && arrival.hasDelay() -> scheduled + arrival.delay
    hasDeparture() && departure.hasDelay() -> scheduled + departure.delay
    else -> null
}

private fun StopTimeUpdate.delay(): Int? = when {
    hasArrival() && arrival.hasDelay() -> arrival.delay
    hasDeparture() && departure.hasDelay() -> departure.delay
    else -> null
}

/**
 * Applica trip updates e posizioni ai passaggi programmati [deps] della fermata [stopId].
 * Restituisce i passaggi tra now-30s e now+[ahead], in ordine di orario previsto.
 */
fun applyRealtime(
    deps: List<Departure>, stopId: String, tu: FeedMessage?, vp: FeedMessage?, now: Long, ahead: Int = 5400,
): List<Departure> {
    val byTrip = deps.groupBy { it.tripId }
    tu?.entityList?.forEach { e ->
        if (!e.hasTripUpdate()) return@forEach
        val u = e.tripUpdate
        val list = byTrip[u.trip.tripId] ?: return@forEach
        val stus = u.stopTimeUpdateList
        for (d in list) {
            if (u.trip.scheduleRelationship == TripDescriptor.ScheduleRelationship.CANCELED) {
                d.canceled = true
                continue
            }
            val exact = stus.firstOrNull {
                if (it.hasStopSequence()) it.stopSequence == d.seq else it.stopId == stopId
            }
            val first = stus.firstOrNull()
            when {
                exact != null ->
                    if (exact.scheduleRelationship == StopTimeUpdate.ScheduleRelationship.SKIPPED) d.canceled = true
                    else d.predicted = exact.estimate(d.scheduled)
                // Il feed elenca solo le fermate ancora da servire: se la nostra viene prima, il mezzo è già passato.
                first != null && first.hasStopSequence() && first.stopSequence > d.seq -> d.passed = true
                else -> {
                    // Ritardo propagato dall'ultima fermata aggiornata prima della nostra (specifica GTFS-RT).
                    val prev = stus.lastOrNull { it.hasStopSequence() && it.stopSequence < d.seq }?.delay()
                    val delay = prev ?: if (u.hasDelay()) u.delay else null
                    d.predicted = delay?.let { d.scheduled + it }
                }
            }
        }
    }
    vp?.entityList?.forEach { e ->
        if (!e.hasVehicle()) return@forEach
        val v = e.vehicle
        val list = byTrip[v.trip.tripId] ?: return@forEach
        for (d in list) {
            d.vehicle = v.vehicle.label.ifEmpty { v.vehicle.id }.ifEmpty { null }
            if (v.hasCurrentStopSequence()) {
                val away = d.seq - v.currentStopSequence
                if (away < 0) d.passed = true else d.stopsAway = away
            }
            if (v.hasOccupancyStatus() && v.occupancyStatus != OccupancyStatus.NO_DATA_AVAILABLE) {
                d.occupancy = v.occupancyStatus.number
            }
        }
    }
    return deps.filter { !it.passed && it.time in (now - 30)..(now + ahead) }.sortedBy { it.time }
}

fun occupancyLabel(status: Int): String? = when (status) {
    0 -> "Vuoto"
    1 -> "Molti posti liberi"
    2 -> "Pochi posti liberi"
    3 -> "Solo posti in piedi"
    4 -> "Molto affollato"
    5 -> "Pieno"
    6 -> "Non accetta passeggeri"
    8 -> "Non accessibile"
    else -> null
}

class AlertItem(
    val id: String, val header: String, val description: String,
    val routes: List<String>, val stops: Set<String>, val start: Long?, val end: Long?,
)

private fun TranslatedString.text(): String =
    (translationList.firstOrNull { it.language == "it" } ?: translationList.firstOrNull())?.text.orEmpty().trim()

/** Avvisi attivi in questo momento, i più recenti per primi. */
fun activeAlerts(feed: FeedMessage, now: Long): List<AlertItem> = feed.entityList
    .filter { it.hasAlert() && !it.isDeleted }
    .mapNotNull { e ->
        val a = e.alert
        val period = a.activePeriodList.firstOrNull { p ->
            (!p.hasStart() || p.start <= now) && (!p.hasEnd() || p.end == 0L || p.end >= now)
        }
        if (a.activePeriodCount > 0 && period == null) return@mapNotNull null
        AlertItem(
            id = e.id,
            header = a.headerText.text(),
            description = a.descriptionText.text(),
            routes = a.informedEntityList.map { it.routeId }.filter { it.isNotEmpty() }.distinct().sortedWith(
                compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }),
            ),
            stops = a.informedEntityList.map { it.stopId }.filter { it.isNotEmpty() }.toSet(),
            start = period?.takeIf { it.hasStart() }?.start,
            end = period?.takeIf { it.hasEnd() && it.end > 0 }?.end,
        )
    }
    .sortedByDescending { it.start ?: 0 }

fun alertsForStop(alerts: List<AlertItem>, stop: Stop): List<AlertItem> =
    alerts.filter { a -> stop.id in a.stops || a.routes.any { it in stop.routes } }
