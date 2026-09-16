package io.github.cmldo.romertgtfs

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter.BASIC_ISO_DATE
import java.util.zip.ZipFile

val ROME: ZoneId = ZoneId.of("Europe/Rome")

/** DB locale con il GTFS statico. Tutte le letture passano da [read], così lo swap dopo un import è sicuro. */
object Gtfs {
    private var db: SQLiteDatabase? = null

    /** Cambia a ogni nuovo import: la UI lo osserva per ricaricare. */
    val version = MutableStateFlow(0)

    fun file(ctx: Context): File = ctx.getDatabasePath("gtfs.db")

    fun <T> read(ctx: Context, block: (SQLiteDatabase) -> T): T? = synchronized(this) {
        val f = file(ctx)
        if (db == null && f.exists()) {
            db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
        }
        db?.let(block)
    }

    fun install(ctx: Context, newDb: File) {
        synchronized(this) {
            db?.close()
            db = null
            check(newDb.renameTo(file(ctx))) { "Impossibile installare il nuovo database" }
        }
        version.value++
    }
}

// ---------------------------------------------------------------- import

private val SCHEMA = listOf(
    "CREATE TABLE stop(idx INTEGER PRIMARY KEY, id TEXT NOT NULL, code TEXT NOT NULL, name TEXT NOT NULL, lat REAL, lon REAL, lines TEXT NOT NULL DEFAULT '', routes TEXT NOT NULL DEFAULT '')",
    "CREATE TABLE route(idx INTEGER PRIMARY KEY, id TEXT NOT NULL, name TEXT NOT NULL, type INTEGER, color TEXT)",
    // Le corse con la stessa sequenza di fermate e gli stessi intervalli condividono un "pattern":
    // 5,5 milioni di righe di stop_times diventano circa 600 mila.
    "CREATE TABLE trip(idx INTEGER PRIMARY KEY, id TEXT NOT NULL, route INTEGER NOT NULL, service INTEGER NOT NULL, headsign TEXT NOT NULL, pattern INTEGER NOT NULL, t0 INTEGER NOT NULL)",
    "CREATE TABLE pattern_stop(pattern INTEGER NOT NULL, seq INTEGER NOT NULL, stop INTEGER NOT NULL, dt INTEGER NOT NULL, headsign TEXT, last INTEGER NOT NULL, PRIMARY KEY(pattern, seq)) WITHOUT ROWID",
    "CREATE TABLE service_date(date INTEGER NOT NULL, service INTEGER NOT NULL, PRIMARY KEY(date, service)) WITHOUT ROWID",
)
private val INDEXES = listOf(
    "CREATE INDEX pattern_stop_stop ON pattern_stop(stop)",
    "CREATE INDEX trip_pattern ON trip(pattern, t0)",
    "CREATE UNIQUE INDEX trip_id ON trip(id)",
    "CREATE INDEX stop_id ON stop(id)",
    "CREATE INDEX stop_code ON stop(code)",
)

/** Una riga CSV (RFC 4180). ponytail: niente campi quotati su più righe, il feed di Roma non ne ha. */
fun splitCsv(line: String): List<String> {
    val out = ArrayList<String>(12)
    val sb = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { out.add(sb.toString()); sb.setLength(0) }
            else -> sb.append(c)
        }
        i++
    }
    out.add(sb.toString())
    return out
}

private class Row(private val header: Map<String, Int>, private val fields: List<String>) {
    operator fun get(key: String): String = header[key]?.let { fields.getOrNull(it) }?.trim() ?: ""
}

/** Scorre un file del GTFS; [onBytes] riceve i byte letti finora (per la percentuale). */
private fun ZipFile.forEachRow(name: String, onBytes: (Long) -> Unit = {}, block: (Row) -> Unit): Boolean {
    val entry = getEntry(name) ?: return false
    getInputStream(entry).bufferedReader().use { r ->
        val header = splitCsv(r.readLine()?.removePrefix("\uFEFF") ?: return true)
            .withIndex().associate { it.value.trim() to it.index }
        var bytes = 0L
        var n = 0
        while (true) {
            val line = r.readLine() ?: break
            bytes += line.length + 1
            if (++n % 50_000 == 0) onBytes(bytes)
            if (line.isNotBlank()) block(Row(header, splitCsv(line)))
        }
    }
    return true
}

private fun gtfsTime(t: String): Int? {
    val p = t.split(':')
    if (p.size != 3) return null
    return p[0].toInt() * 3600 + p[1].toInt() * 60 + p[2].toInt()
}

private val lineOrder = compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it })

/** Converte lo zip GTFS in un DB SQLite in [out]. [progress] riceve 0..100. */
fun importGtfs(zipFile: File, out: File, progress: (Int) -> Unit = {}) {
    out.parentFile?.mkdirs()
    out.delete()
    File(out.path + "-journal").delete()
    val db = SQLiteDatabase.openOrCreateDatabase(out, null)
    try {
        db.beginTransaction()
        try {
            SCHEMA.forEach(db::execSQL)
            ZipFile(zipFile).use { load(db, it, progress) }
            INDEXES.forEach(db::execSQL)
            fillStopLines(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.execSQL("ANALYZE")
        progress(100)
    } catch (e: Throwable) {
        db.close()
        out.delete()
        throw e
    }
    db.close()
}

private fun load(db: SQLiteDatabase, z: ZipFile, progress: (Int) -> Unit) {
    val stops = HashMap<String, Int>()
    db.compileStatement("INSERT INTO stop(idx,id,code,name,lat,lon) VALUES(?,?,?,?,?,?)").use { s ->
        check(z.forEachRow("stops.txt") { r ->
            val idx = stops.size
            stops[r["stop_id"]] = idx
            s.bindLong(1, idx.toLong())
            s.bindString(2, r["stop_id"])
            s.bindString(3, r["stop_code"].ifEmpty { r["stop_id"] })
            s.bindString(4, r["stop_name"])
            s.bindDouble(5, r["stop_lat"].toDoubleOrNull() ?: 0.0)
            s.bindDouble(6, r["stop_lon"].toDoubleOrNull() ?: 0.0)
            s.executeInsert()
        }) { "stops.txt mancante" }
    }

    val routes = HashMap<String, Int>()
    db.compileStatement("INSERT INTO route(idx,id,name,type,color) VALUES(?,?,?,?,?)").use { s ->
        check(z.forEachRow("routes.txt") { r ->
            val idx = routes.size
            routes[r["route_id"]] = idx
            s.clearBindings()
            s.bindLong(1, idx.toLong())
            s.bindString(2, r["route_id"])
            s.bindString(3, r["route_short_name"].ifEmpty { r["route_long_name"] }.ifEmpty { r["route_id"] })
            s.bindLong(4, r["route_type"].toLongOrNull() ?: 3)
            r["route_color"].takeIf { it.length == 6 }?.let { s.bindString(5, it) }
            s.executeInsert()
        }) { "routes.txt mancante" }
    }

    val services = HashMap<String, Int>()
    fun service(id: String) = services.getOrPut(id) { services.size }
    val add = db.compileStatement("INSERT OR IGNORE INTO service_date VALUES(?,?)")
    val remove = db.compileStatement("DELETE FROM service_date WHERE date=? AND service=?")
    fun exec(s: android.database.sqlite.SQLiteStatement, date: LocalDate, svc: Int) {
        s.bindLong(1, date.format(BASIC_ISO_DATE).toLong())
        s.bindLong(2, svc.toLong())
        s.execute()
    }
    val hasCalendar = z.forEachRow("calendar.txt") { r ->
        val svc = service(r["service_id"])
        var d = LocalDate.parse(r["start_date"], BASIC_ISO_DATE)
        val end = LocalDate.parse(r["end_date"], BASIC_ISO_DATE)
        while (!d.isAfter(end)) {
            if (r[d.dayOfWeek.name.lowercase()] == "1") exec(add, d, svc)
            d = d.plusDays(1)
        }
    }
    val hasDates = z.forEachRow("calendar_dates.txt") { r ->
        val d = LocalDate.parse(r["date"], BASIC_ISO_DATE)
        exec(if (r["exception_type"] == "2") remove else add, d, service(r["service_id"]))
    }
    check(hasCalendar || hasDates) { "calendar.txt e calendar_dates.txt mancanti" }
    add.close()
    remove.close()

    class TripRow(val route: Int, val service: Int, val headsign: String)
    val strings = HashMap<String, String>()
    val trips = HashMap<String, TripRow>()
    check(z.forEachRow("trips.txt") { r ->
        val route = routes[r["route_id"]] ?: return@forEachRow
        val h = r["trip_headsign"]
        trips[r["trip_id"]] = TripRow(route, service(r["service_id"]), strings.getOrPut(h) { h })
    }) { "trips.txt mancante" }
    progress(5)

    class StopTime(val seq: Int, val stop: Int, val time: Int, val headsign: String)
    val patterns = HashMap<String, Int>()
    val seen = HashSet<String>()
    val group = ArrayList<StopTime>()
    var current: String? = null
    val insTrip = db.compileStatement("INSERT INTO trip(id,route,service,headsign,pattern,t0) VALUES(?,?,?,?,?,?)")
    val insStop = db.compileStatement("INSERT INTO pattern_stop(pattern,seq,stop,dt,headsign,last) VALUES(?,?,?,?,?,?)")

    fun flush() {
        val id = current ?: return
        // Il file va letto in streaming: servono le righe raggruppate per corsa (lo sono nel feed di Roma).
        check(seen.add(id)) { "stop_times.txt non raggruppato per trip_id ($id)" }
        val trip = trips[id]
        if (trip != null && group.isNotEmpty()) {
            group.sortBy { it.seq }
            val t0 = group[0].time
            val key = buildString {
                for (st in group) append(st.seq).append('').append(st.stop).append('')
                    .append(st.time - t0).append('').append(st.headsign).append('')
            }
            val pattern = patterns[key] ?: patterns.size.also { p ->
                patterns[key] = p
                group.forEachIndexed { i, st ->
                    insStop.clearBindings()
                    insStop.bindLong(1, p.toLong())
                    insStop.bindLong(2, st.seq.toLong())
                    insStop.bindLong(3, st.stop.toLong())
                    insStop.bindLong(4, (st.time - t0).toLong())
                    if (st.headsign.isNotEmpty()) insStop.bindString(5, st.headsign)
                    insStop.bindLong(6, if (i == group.lastIndex) 1 else 0)
                    insStop.executeInsert()
                }
            }
            insTrip.bindString(1, id)
            insTrip.bindLong(2, trip.route.toLong())
            insTrip.bindLong(3, trip.service.toLong())
            insTrip.bindString(4, trip.headsign)
            insTrip.bindLong(5, pattern.toLong())
            insTrip.bindLong(6, t0.toLong())
            insTrip.executeInsert()
        }
        group.clear()
    }

    val total = z.getEntry("stop_times.txt")?.size?.coerceAtLeast(1) ?: error("stop_times.txt mancante")
    var lastTime = 0
    z.forEachRow("stop_times.txt", onBytes = { progress(5 + (85 * it / total).toInt()) }) { r ->
        val id = r["trip_id"]
        if (id != current) {
            flush()
            current = id
        }
        val stop = stops[r["stop_id"]] ?: return@forEachRow
        // ponytail: gli orari vuoti (fermate non "timepoint") prendono l'ultimo orario noto invece di essere interpolati.
        val time = gtfsTime(r["departure_time"]) ?: gtfsTime(r["arrival_time"]) ?: lastTime
        lastTime = time
        val h = r["stop_headsign"]
        group.add(StopTime(r["stop_sequence"].toInt(), stop, time, strings.getOrPut(h) { h }))
    }
    flush()
    insTrip.close()
    insStop.close()
    progress(90)
}

/** Per ogni fermata, le linee che la servono (per la ricerca e per filtrare gli avvisi). */
private fun fillStopLines(db: SQLiteDatabase) {
    val byStop = HashMap<Long, MutableMap<String, String>>()
    db.rawQuery(
        """SELECT DISTINCT ps.stop, r.id, r.name FROM pattern_stop ps
           JOIN (SELECT DISTINCT pattern, route FROM trip) pr ON pr.pattern = ps.pattern
           JOIN route r ON r.idx = pr.route""", null,
    ).use { c ->
        while (c.moveToNext()) byStop.getOrPut(c.getLong(0)) { HashMap() }[c.getString(1)] = c.getString(2)
    }
    db.compileStatement("UPDATE stop SET lines=?, routes=? WHERE idx=?").use { s ->
        for ((stop, routes) in byStop) {
            s.bindString(1, routes.values.distinct().sortedWith(lineOrder).joinToString(" "))
            s.bindString(2, routes.keys.joinToString("\n"))
            s.bindLong(3, stop)
            s.execute()
        }
    }
}

// ---------------------------------------------------------------- query

class Stop(
    val idx: Int, val id: String, val code: String, val name: String,
    val lat: Double, val lon: Double, val lines: String, val routes: Set<String>,
) {
    var distance: Float? = null
}

private const val STOP_COLS = "idx, id, code, name, lat, lon, lines, routes"

private fun Cursor.stops(): List<Stop> = use {
    buildList {
        while (moveToNext()) add(
            Stop(
                getInt(0), getString(1), getString(2), getString(3), getDouble(4), getDouble(5),
                getString(6), getString(7).split('\n').filter { it.isNotEmpty() }.toSet(),
            ),
        )
    }
}

fun stopById(db: SQLiteDatabase, id: String): Stop? =
    db.rawQuery("SELECT $STOP_COLS FROM stop WHERE id = ?", arrayOf(id)).stops().firstOrNull()

/** Per codice fermata esatto oppure per nome (tutte le parole, in qualsiasi ordine); prima i nomi che iniziano con la ricerca. */
fun searchStops(db: SQLiteDatabase, query: String): List<Stop> {
    val q = query.trim()
    val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    fun escape(w: String) = w.replace(Regex("[\\\\%_]")) { "\\" + it.value }
    val like = words.joinToString(" AND ") { "name LIKE ? ESCAPE '\\'" }
    val args = listOf(q) + words.map { "%" + escape(it) + "%" } + listOf(q, escape(q) + "%")
    return db.rawQuery(
        """SELECT $STOP_COLS FROM stop WHERE lines <> '' AND (code = ? OR ($like))
           ORDER BY code = ? DESC, name LIKE ? ESCAPE '\' DESC, name LIMIT 100""",
        args.toTypedArray(),
    ).stops()
}

/** Fermate servite entro circa 1 km, dalla più vicina. */
fun nearbyStops(db: SQLiteDatabase, lat: Double, lon: Double): List<Stop> {
    val d = doubleArrayOf(lat - 0.009, lat + 0.009, lon - 0.012, lon + 0.012).map { it.toString() }
    val out = FloatArray(1)
    return db.rawQuery(
        "SELECT $STOP_COLS FROM stop WHERE lines <> '' AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
        d.toTypedArray(),
    ).stops().onEach {
        Location.distanceBetween(lat, lon, it.lat, it.lon, out)
        it.distance = out[0]
    }.sortedBy { it.distance }.take(40)
}

class Departure(
    val tripId: String, val line: String, val lineColor: String?, val routeType: Int,
    val headsign: String, val seq: Int, val scheduled: Long,
) {
    var predicted: Long? = null
    var canceled = false
    var passed = false
    var vehicle: String? = null
    var stopsAway: Int? = null
    var occupancy: Int? = null
    val time get() = predicted ?: scheduled
}

/**
 * Passaggi programmati alla fermata tra now-[back] e now+[ahead] secondi.
 * Considera anche il giorno di servizio precedente (orari GTFS oltre le 24:00).
 */
fun scheduled(db: SQLiteDatabase, stop: Stop, now: ZonedDateTime, back: Int = 7200, ahead: Int = 5400): List<Departure> {
    val out = ArrayList<Departure>()
    for (daysAgo in 0L..1L) {
        val day = now.toLocalDate().minusDays(daysAgo)
        // Da specifica GTFS gli orari partono da "mezzogiorno meno 12 ore" (corretto anche nei giorni del cambio d'ora).
        val base = day.atTime(12, 0).atZone(now.zone).minusHours(12).toEpochSecond()
        val s = now.toEpochSecond() - base
        db.rawQuery(
            """SELECT t.id, r.name, r.color, r.type, COALESCE(ps.headsign, t.headsign), ps.seq, t.t0 + ps.dt
               FROM pattern_stop ps
               JOIN trip t ON t.pattern = ps.pattern AND t.t0 BETWEEN ${s - back} - ps.dt AND ${s + ahead} - ps.dt
               JOIN service_date sd ON sd.date = ${day.format(BASIC_ISO_DATE)} AND sd.service = t.service
               JOIN route r ON r.idx = t.route
               WHERE ps.stop = ${stop.idx} AND ps.last = 0""",
            null,
        ).use { c ->
            while (c.moveToNext()) out.add(
                Departure(
                    c.getString(0), c.getString(1), c.getString(2), c.getInt(3),
                    c.getString(4), c.getInt(5), base + c.getLong(6),
                ),
            )
        }
    }
    return out
}
