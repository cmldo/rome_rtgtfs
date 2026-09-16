package io.github.cmldo.romertgtfs

import android.Manifest
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Prova completa contro i server di Roma Mobilità (scarica il GTFS da 50 MB):
 * `E2E=1 ./gradlew testDebugUnitTest --tests '*EndToEnd*'`. Gli screenshot finiscono in app/build/screens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EndToEndTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun liveData() {
        assumeTrue("E2E non impostata", System.getenv("E2E") != null)
        val ctx = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)

        var t = System.nanoTime()
        val first = runBlocking { TestListenableWorkerBuilder<StaticUpdateWorker>(ctx).build().doWork() }
        assertEquals(ListenableWorker.Result.success(), first)
        assertTrue(Gtfs.file(ctx).exists())
        println("primo aggiornamento ${(System.nanoTime() - t) / 1_000_000} ms, db ${Gtfs.file(ctx).length() / 1024} KB")
        t = System.nanoTime()
        val second = runBlocking { TestListenableWorkerBuilder<StaticUpdateWorker>(ctx).build().doWork() }
        assertEquals(ListenableWorker.Result.success(), second)
        val skipMs = (System.nanoTime() - t) / 1_000_000
        println("secondo controllo (md5 invariato) $skipMs ms")
        assertTrue(skipMs < 10_000)

        var page by mutableStateOf("home")
        var dark by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme(dark = dark) {
                when (page) {
                    "stop" -> StopScreen("70240") {}
                    "alerts" -> AlertsScreen {}
                    "info" -> InfoScreen {}
                    else -> HomeScreen(open = {}, nav = {})
                }
            }
        }
        fun waitText(text: String) = compose.waitUntil(60_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        fun shot(name: String) {
            Snapshot.sendApplyNotifications()
            repeat(60) { compose.mainClock.advanceTimeByFrame() }
            val dir = File("build/screens").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }

        // Posizione simulata davanti alla stazione Termini.
        shadowOf(ctx).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = ctx.getSystemService(LocationManager::class.java)
        for (p in listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            shadowOf(lm).setProviderEnabled(p, true)
            shadowOf(lm).simulateLocation(
                Location(p).apply {
                    latitude = 41.90075
                    longitude = 12.50130
                    time = System.currentTimeMillis()
                },
            )
        }
        compose.onNodeWithText("Fermate vicine").performClick()
        waitText(" m")
        shot("0-vicine")

        compose.onNode(hasSetTextAction()).performTextInput("termini")
        waitText("Linee:")
        shot("1-ricerca")

        page = "stop"
        waitText("Tempo reale aggiornato")
        shot("2-fermata")

        dark = true
        shot("2-fermata-scuro")
        dark = false

        page = "alerts"
        waitText("avvisi attivi")
        shot("3-avvisi")

        page = "info"
        waitText("md5")
        shot("4-info")
    }
}
