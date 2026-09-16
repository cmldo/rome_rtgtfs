package io.github.cmldo.romertgtfs

import android.database.sqlite.SQLiteDatabase
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Il fixture è un estratto reale del feed di Roma (16/09/2026) per una fermata trafficata;
 * expected.csv è il tabellone calcolato in modo indipendente (Python + gtfs-realtime-bindings).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GtfsTest {
    private val dir = File(javaClass.classLoader!!.getResource("fixture/expected.csv")!!.toURI()).parentFile!!

    @Test
    fun splitCsvHandlesQuotes() {
        assertEquals(listOf("a", "b,c", "d\"e", ""), splitCsv("a,\"b,c\",\"d\"\"e\","))
        assertEquals(listOf("05000", "TERMINI (MA-MB-FS)", ""), splitCsv("05000,\"TERMINI (MA-MB-FS)\","))
    }

    @Test
    fun fixtureBoardMatchesReference() {
        val zip = File.createTempFile("gtfs", ".zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for (name in listOf("stops.txt", "routes.txt", "trips.txt", "calendar_dates.txt", "stop_times.txt")) {
                out.putNextEntry(ZipEntry(name))
                File(dir, name).inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        checkBoard(zip)
    }

    /** Import del feed completo: `GTFS_ZIP=/percorso/rome_static_gtfs.zip ./gradlew testDebugUnitTest`. */
    @Test
    fun fullFeed() {
        val zip = System.getenv("GTFS_ZIP")?.let(::File)
        assumeTrue("GTFS_ZIP non impostata", zip != null)
        checkBoard(zip!!)
    }

    private fun checkBoard(zip: File) {
        val dbFile = File.createTempFile("gtfs", ".db")
        var steps = 0
        val t = System.nanoTime()
        importGtfs(zip, dbFile) { steps++ }
        println("import ${(System.nanoTime() - t) / 1_000_000} ms, db ${dbFile.length() / 1024} KB, $steps progressi")

        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val lines = File(dir, "expected.csv").readLines()
        val header = lines[0].removePrefix("# ").split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
        val now = header.getValue("now").toLong()
        val stop = stopById(db, header.getValue("stop"))!!
        val tu = FeedMessage.parseFrom(File(dir, "trip_updates.pb").readBytes())
        val vp = FeedMessage.parseFrom(File(dir, "vehicle_positions.pb").readBytes())
        val al = FeedMessage.parseFrom(File(dir, "service_alerts.pb").readBytes())

        val t2 = System.nanoTime()
        val sched = scheduled(db, stop, Instant.ofEpochSecond(now).atZone(ROME))
        println("query ${(System.nanoTime() - t2) / 1_000_000} ms, ${sched.size} passaggi programmati")
        val board = applyRealtime(sched, stop.id, tu, vp, now)
        val got = board.sortedWith(compareBy({ it.time }, { it.tripId }, { it.seq }))
            .map { "${it.tripId},${it.seq},${it.scheduled},${it.predicted ?: ""},${it.stopsAway ?: ""}" }
        assertEquals(lines.drop(1), got)
        assertTrue(board.zipWithNext().all { (a, b) -> a.time <= b.time })
        assertTrue(board.all { it.line.isNotEmpty() && it.headsign.isNotEmpty() })

        assertEquals(stop.id, searchStops(db, stop.code).first().id)
        val word = stop.name.split(' ', '/', '(').first { it.length >= 3 }.lowercase()
        val found = searchStops(db, " $word ")
        assertTrue(found.any { it.id == stop.id })
        assertTrue(found.first().name.startsWith(word, ignoreCase = true))
        assertTrue(searchStops(db, "%").isEmpty() || searchStops(db, "%").all { '%' in it.name })
        assertTrue(stop.lines.isNotEmpty() && stop.routes.isNotEmpty())
        val near = nearbyStops(db, stop.lat, stop.lon)
        assertEquals(0f, near.first().distance)
        assertTrue(near.any { it.id == stop.id })

        val alerts = activeAlerts(al, now)
        assertTrue(alerts.isNotEmpty() && alerts.all { it.header.isNotEmpty() })
        val forStop = alertsForStop(alerts, stop)
        assertTrue(forStop.all { a -> a.routes.any { it in stop.routes } || stop.id in a.stops })
        println("avvisi attivi ${alerts.size}, per la fermata ${forStop.size}; live ${board.count { it.predicted != null }}/${board.size}")
        db.close()
    }
}
