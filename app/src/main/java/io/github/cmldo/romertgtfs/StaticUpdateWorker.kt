package io.github.cmldo.romertgtfs

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest

/**
 * Scarica e importa il GTFS statico solo se l'md5 pubblicato è diverso da quello già importato.
 * Il DB in uso resta disponibile durante l'import: quello nuovo lo sostituisce solo a lavoro finito.
 * ponytail: niente foreground service. WorkManager ferma i lavori dopo 10 minuti e questo riparte da capo;
 * se succede su telefoni lenti, aggiungere setForeground con una notifica.
 */
class StaticUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val zip = File(ctx.cacheDir, "rome_static_gtfs.zip")
        val tmpDb = ctx.getDatabasePath("gtfs-new.db")
        try {
            val remote = http(URL(STATIC_MD5_URL)) { it.inputStream.readBytes() }
                .decodeToString().trim().substringBefore(' ').lowercase()
            if (!remote.matches(Regex("[0-9a-f]{32}"))) return@withContext fail("md5 pubblicato non valido")
            if (remote == prefs.getString(KEY_MD5, null) && Gtfs.file(ctx).exists()) return@withContext Result.success()

            report("Download", 0)
            val md = MessageDigest.getInstance("MD5")
            http(URL(STATIC_ZIP_URL)) { c ->
                val total = c.contentLengthLong
                var done = 0L
                c.inputStream.use { input ->
                    zip.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            md.update(buf, 0, n)
                            done += n
                            if (total > 0) report("Download", (100 * done / total).toInt())
                        }
                    }
                }
            }
            val local = md.digest().joinToString("") { "%02x".format(it) }
            // Il file può essere stato ripubblicato durante il download: si riprova più tardi.
            if (local != remote) return@withContext Result.retry()

            importGtfs(zip, tmpDb) { report("Elaborazione", it) }
            Gtfs.install(ctx, tmpDb)
            prefs.edit { putString(KEY_MD5, remote).putLong(KEY_UPDATED, System.currentTimeMillis()) }
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount < 5) Result.retry() else fail("rete non disponibile (${e.message})")
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        } finally {
            zip.delete()
            tmpDb.delete()
        }
    }

    private var lastReport = ""

    private fun report(phase: String, pct: Int) {
        if ("$phase$pct" == lastReport) return
        lastReport = "$phase$pct"
        setProgressAsync(workDataOf(KEY_PHASE to phase, KEY_PCT to pct))
    }

    private fun fail(msg: String) = Result.failure(workDataOf(KEY_ERROR to msg))

    companion object {
        const val NAME = "static-gtfs"
        const val PREFS = "gtfs"
        const val KEY_MD5 = "md5"
        const val KEY_UPDATED = "updated"
        const val KEY_PHASE = "phase"
        const val KEY_PCT = "pct"
        const val KEY_ERROR = "error"

        /** Da chiamare all'avvio: se un controllo è già in corso non ne parte un altro. */
        fun enqueue(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<StaticUpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
