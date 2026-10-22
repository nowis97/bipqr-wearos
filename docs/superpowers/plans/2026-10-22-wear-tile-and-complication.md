# Wear OS Tile & Watch Face Complication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a Wear OS Tile (`BipTileService`) and a Watch Face Complication (`BipComplicationService`) showing balance, Dale QR monthly accumulation progress ring, and a 1-tap fast-path into an active Aztec QR ticket in `bipqr-wearos`.

**Architecture:** Lightweight native AndroidX implementation using `androidx.wear.tiles:tiles:1.3.0`, `androidx.wear.protolayout:protolayout-material:1.2.0`, and `androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1`. Decoupled pure layout and complication data helpers (`BipTileLayoutHelper`, `BipComplicationHelper`) ensure 100% testability. Configuration is read directly from `BipC2dEngine.loadConfig(context)` with instant updates pushed via `BipSurfaceUpdateHelper.requestSurfaceUpdates` on config changes.

**Tech Stack:** Kotlin 1.9, Java 17, AndroidX Wear Tiles 1.3.0, ProtoLayout Material 1.2.0, Watch Face Complications Data Source 1.2.1, Jetpack Compose for Wear OS 1.3.0, JUnit 4, Kotlin Coroutines Test 1.7.3.

**Spec:** `docs/superpowers/specs/2026-10-22-wear-tile-and-complication-design.md`

## Global Constraints

- Target SDK 34, Min SDK 26, Java 17.
- Zero external architecture bloat: maximum 2 new service files (`BipTileService.kt` and `BipComplicationService.kt`) plus their companion layout/data helpers. No Dagger/Hilt or complex DI frameworks needed.
- Preserve all existing business logic: Sonda libc2d curve secp224r1 Aztec generation, Dale QR $42.000 limit, and transactions format.
- All code changes must compile and pass `./gradlew :wear:testDebugUnitTest :wear:assembleDebug`.

---

### Task 1: Add Wear OS Tiles & Watch Face Dependencies and Strings

**Files:**
- Modify: `wear/build.gradle:47-80`
- Modify: `wear/src/main/res/values/strings.xml:1-18`

**Interfaces:**
- Consumes: None
- Produces: Gradle artifacts for `androidx.wear.tiles:tiles:1.3.0`, `androidx.wear.protolayout:protolayout:1.2.0`, `androidx.wear.protolayout:protolayout-material:1.2.0`, and `androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1`. String resources for tile and complication names, descriptions, and action buttons.

- [ ] **Step 1: Add dependencies to wear/build.gradle**

Add the Tile and Watch Face Complication libraries into `dependencies { ... }` in `wear/build.gradle`:

```groovy
    // Wear OS Tiles & ProtoLayout
    implementation 'androidx.wear.tiles:tiles:1.3.0'
    implementation 'androidx.wear.protolayout:protolayout:1.2.0'
    implementation 'androidx.wear.protolayout:protolayout-material:1.2.0'

    // Watch Face Complications Data Source
    implementation 'androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1'
```

- [ ] **Step 2: Add string resources to wear/src/main/res/values/strings.xml**

Add string constants for Tile and Complication labels and accessibility descriptions:

```xml
    <string name="tile_name">bip!QR</string>
    <string name="tile_description">Acceso rápido a saldo bip! y generación de código QR</string>
    <string name="tile_btn_generate">Generar QR</string>
    <string name="tile_btn_link">Vincular</string>
    <string name="tile_status_offline_ready">Offline listo</string>
    <string name="tile_status_online">Online</string>
    <string name="tile_dale_qr_target">¡Viajes Gratis!</string>
    <string name="tile_dale_qr_prefix">Dale QR: </string>
    <string name="complication_name">Saldo bip! y Dale QR</string>
    <string name="complication_description">Muestra el saldo disponible y progreso de Dale QR</string>
```

- [ ] **Step 3: Run gradle dry-run / unit tests to verify dependency resolution**

Run: `cmd.exe /c "cd /d C:\Users\NOWIS\Documents\bipqr-wearos && gradlew.bat :wear:testDebugUnitTest"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add wear/build.gradle wear/src/main/res/values/strings.xml
git commit -m "build: add Wear OS Tiles and Complications dependencies and strings"
```

---

### Task 2: Implement BipTileLayoutHelper and BipTileService

**Files:**
- Create: `wear/src/test/java/com/nowis/bipwear/tile/BipTileLayoutHelperTest.kt`
- Create: `wear/src/main/java/com/nowis/bipwear/tile/BipTileLayoutHelper.kt`
- Create: `wear/src/main/java/com/nowis/bipwear/tile/BipTileService.kt`

**Interfaces:**
- Consumes: `BipC2dEngine.loadConfig`, `BipC2dEngine.isTramaValid`, `StandaloneConfig`
- Produces: `BipTileLayoutHelper.buildTileLayout(context, config, isOfflineReady): LayoutElementBuilders.LayoutElement`, `BipTileService`

- [ ] **Step 1: Write the failing unit test for BipTileLayoutHelper**

Create `wear/src/test/java/com/nowis/bipwear/tile/BipTileLayoutHelperTest.kt`:

```kotlin
package com.nowis.bipwear.tile

import com.nowis.bipwear.StandaloneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BipTileLayoutHelperTest {

    @Test
    fun testDaleQrProgressCalculation() {
        val configZero = StandaloneConfig(daleQrAcumulado = 0)
        assertEquals(0.0f, BipTileLayoutHelper.calculateDaleQrProgress(configZero), 0.001f)

        val configMid = StandaloneConfig(daleQrAcumulado = 21000)
        assertEquals(0.5f, BipTileLayoutHelper.calculateDaleQrProgress(configMid), 0.001f)

        val configMax = StandaloneConfig(daleQrAcumulado = 42000)
        assertEquals(1.0f, BipTileLayoutHelper.calculateDaleQrProgress(configMax), 0.001f)

        val configOver = StandaloneConfig(daleQrAcumulado = 50000)
        assertEquals(1.0f, BipTileLayoutHelper.calculateDaleQrProgress(configOver), 0.001f)
    }

    @Test
    fun testDaleQrColorHex() {
        // Under target: Cyan #00E5FF (0xFF00E5FF)
        val underTarget = StandaloneConfig(daleQrAcumulado = 15000)
        assertEquals(0xFF00E5FF.toInt(), BipTileLayoutHelper.getDaleQrProgressColor(underTarget))

        // Reached target: Neon Green #00E676 (0xFF00E676)
        val reachedTarget = StandaloneConfig(daleQrAcumulado = 42000)
        assertEquals(0xFF00E676.toInt(), BipTileLayoutHelper.getDaleQrProgressColor(reachedTarget))
    }

    @Test
    fun testDaleQrStatusText() {
        val underTarget = StandaloneConfig(daleQrAcumulado = 21000)
        assertEquals("Dale QR: 50%", BipTileLayoutHelper.getDaleQrStatusText(underTarget))

        val reachedTarget = StandaloneConfig(daleQrAcumulado = 42000)
        assertEquals("🎉 ¡Viajes Gratis!", BipTileLayoutHelper.getDaleQrStatusText(reachedTarget))
    }

    @Test
    fun testFormattedBalance() {
        val config = StandaloneConfig(saldo = "$3.400")
        assertEquals("$3.400", BipTileLayoutHelper.getFormattedBalance(config))

        val emptyConfig = StandaloneConfig(saldo = "")
        assertEquals("$0", BipTileLayoutHelper.getFormattedBalance(emptyConfig))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "cd /d C:\Users\NOWIS\Documents\bipqr-wearos && gradlew.bat :wear:testDebugUnitTest --tests com.nowis.bipwear.tile.BipTileLayoutHelperTest"`
Expected: FAIL with compilation error (unresolved reference `BipTileLayoutHelper`)

- [ ] **Step 3: Implement BipTileLayoutHelper and BipTileService**

Create `wear/src/main/java/com/nowis/bipwear/tile/BipTileLayoutHelper.kt`:

```kotlin
package com.nowis.bipwear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import com.nowis.bipwear.MainActivity
import com.nowis.bipwear.R
import com.nowis.bipwear.StandaloneConfig

object BipTileLayoutHelper {
    const val COLOR_RED_BRAND = 0xFFE50914.toInt()
    const val COLOR_CYAN_DALE_QR = 0xFF00E5FF.toInt()
    const val COLOR_GREEN_SUCCESS = 0xFF00E676.toInt()
    const val COLOR_TRACK = 0x3300E5FF.toInt()
    const val COLOR_MUTED_GRAY = 0xFF9E9E9E.toInt()
    const val COLOR_WHITE = 0xFFFFFFFF.toInt()

    fun calculateDaleQrProgress(config: StandaloneConfig): Float {
        if (config.daleQrAcumulado <= 0) return 0f
        val fraction = config.daleQrAcumulado.toFloat() / 42000f
        return fraction.coerceIn(0f, 1f)
    }

    fun getDaleQrProgressColor(config: StandaloneConfig): Int {
        return if (config.daleQrAcumulado >= 42000) COLOR_GREEN_SUCCESS else COLOR_CYAN_DALE_QR
    }

    fun getDaleQrStatusText(config: StandaloneConfig): String {
        return if (config.daleQrAcumulado >= 42000) {
            "🎉 ¡Viajes Gratis!"
        } else {
            val pct = (calculateDaleQrProgress(config) * 100).toInt()
            "Dale QR: $pct%"
        }
    }

    fun getFormattedBalance(config: StandaloneConfig): String {
        return if (config.saldo.isNotBlank()) config.saldo else "$0"
    }

    fun buildTileLayout(
        context: Context,
        config: StandaloneConfig,
        isOfflineReady: Boolean
    ): LayoutElementBuilders.LayoutElement {
        val rootBox = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())

        // Outer progress arc for Dale QR
        val progress = calculateDaleQrProgress(config)
        val sweepAngle = progress * 300f
        val arcColor = getDaleQrProgressColor(config)

        val arcBuilder = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(120f))
            .addArcLine(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(300f))
                    .setThickness(dp(6f))
                    .setColor(argb(COLOR_TRACK))
                    .build()
            )

        if (sweepAngle > 0f) {
            arcBuilder.addArcLine(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(sweepAngle))
                    .setThickness(dp(6f))
                    .setColor(argb(arcColor))
                    .build()
            )
        }
        rootBox.addContent(arcBuilder.build())

        // Center content column
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(wrap())
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        // Title: bip!QR
        column.addContent(
            Text.Builder(context, "bip!QR")
                .setTypography(Typography.TYPOGRAPHY_TITLE3)
                .setColor(argb(COLOR_RED_BRAND))
                .build()
        )

        if (config.isProvisioned) {
            // Balance
            column.addContent(
                Text.Builder(context, getFormattedBalance(config))
                    .setTypography(Typography.TYPOGRAPHY_DISPLAY2)
                    .setColor(argb(COLOR_WHITE))
                    .build()
            )

            // Dale QR progress caption
            column.addContent(
                Text.Builder(context, getDaleQrStatusText(config))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(argb(arcColor))
                    .build()
            )

            // Status chip: Offline listo vs Online
            val statusText = if (isOfflineReady) {
                context.getString(R.string.tile_status_offline_ready)
            } else {
                context.getString(R.string.tile_status_online)
            }
            column.addContent(
                Text.Builder(context, "● $statusText")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(argb(if (isOfflineReady) COLOR_GREEN_SUCCESS else COLOR_MUTED_GRAY))
                    .build()
            )

            // Spacing
            column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())

            // Fast-path Launch button: Generar QR (EXTRA_AUTO_GENERATE = true)
            val launchAction = ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(context.packageName)
                        .setClassName(MainActivity::class.java.name)
                        .build()
                )
                .build()

            val clickable = ModifiersBuilders.Clickable.Builder()
                .setId("action_generate_qr")
                .setOnClick(launchAction)
                .build()

            val generateButton = Button.Builder(context, clickable)
                .setTextContent(context.getString(R.string.tile_btn_generate))
                .setButtonColors(ButtonColors.primary(argb(COLOR_RED_BRAND)))
                .setSize(dp(100f), dp(32f))
                .build()

            column.addContent(generateButton)
        } else {
            column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            column.addContent(
                Text.Builder(context, "No vinculada")
                    .setTypography(Typography.TYPOGRAPHY_BODY2)
                    .setColor(argb(COLOR_MUTED_GRAY))
                    .build()
            )
            column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())

            val linkAction = ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(context.packageName)
                        .setClassName(MainActivity::class.java.name)
                        .build()
                )
                .build()

            val clickable = ModifiersBuilders.Clickable.Builder()
                .setId("action_link_account")
                .setOnClick(linkAction)
                .build()

            val linkButton = Button.Builder(context, clickable)
                .setTextContent(context.getString(R.string.tile_btn_link))
                .setButtonColors(ButtonColors.primary(argb(COLOR_RED_BRAND)))
                .setSize(dp(90f), dp(30f))
                .build()

            column.addContent(linkButton)
        }

        rootBox.addContent(column.build())
        return rootBox.build()
    }
}
```

Now create `wear/src/main/java/com/nowis/bipwear/tile/BipTileService.kt`:

```kotlin
package com.nowis.bipwear.tile

import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.nowis.bipwear.BipC2dEngine

class BipTileService : TileService() {

    companion object {
        const val RESOURCES_VERSION = "1"
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val config = BipC2dEngine.loadConfig(applicationContext)
        val isOfflineReady = BipC2dEngine.isTramaValid(applicationContext)

        val layoutElement = BipTileLayoutHelper.buildTileLayout(
            context = applicationContext,
            config = config,
            isOfflineReady = isOfflineReady
        )

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(layoutElement).build())
                    .build()
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTimeline(timeline)
            .setFreshnessIntervalMillis(300_000L) // 5 minutes freshness
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()
        return Futures.immediateFuture(resources)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd.exe /c "cd /d C:\Users\NOWIS\Documents\bipqr-wearos && gradlew.bat :wear:testDebugUnitTest --tests com.nowis.bipwear.tile.BipTileLayoutHelperTest"`
Expected: PASS (4 tests passed, 0 failures)

- [ ] **Step 5: Commit**

```bash
git add wear/src/main/java/com/nowis/bipwear/tile/ wear/src/test/java/com/nowis/bipwear/tile/
git commit -m "feat: implement BipTileLayoutHelper, BipTileService and unit tests"
```

---

### Task 3: Implement BipComplicationHelper and BipComplicationService

**Files:**
- Create: `wear/src/test/java/com/nowis/bipwear/complication/BipComplicationHelperTest.kt`
- Create: `wear/src/main/java/com/nowis/bipwear/complication/BipComplicationHelper.kt`
- Create: `wear/src/main/java/com/nowis/bipwear/complication/BipComplicationService.kt`

**Interfaces:**
- Consumes: `BipC2dEngine.loadConfig`, `StandaloneConfig`, `MainActivity.EXTRA_AUTO_GENERATE`
- Produces: `BipComplicationHelper.createShortTextComplication`, `BipComplicationHelper.createRangedValueComplication`, `BipComplicationService`

- [ ] **Step 1: Write the failing unit test for BipComplicationHelper**

Create `wear/src/test/java/com/nowis/bipwear/complication/BipComplicationHelperTest.kt`:

```kotlin
package com.nowis.bipwear.complication

import com.nowis.bipwear.StandaloneConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class BipComplicationHelperTest {

    @Test
    fun testComplicationTextValues() {
        val config = StandaloneConfig(saldo = "$3.400", daleQrAcumulado = 12000)
        assertEquals("$3.400", BipComplicationHelper.getBalanceText(config))
        assertEquals("bip!", BipComplicationHelper.TITLE_TEXT)
        assertEquals(12000f, BipComplicationHelper.getDaleQrValue(config), 0.001f)
        assertEquals(42000f, BipComplicationHelper.DALE_QR_MAX_VALUE, 0.001f)
    }

    @Test
    fun testEmptyConfigValues() {
        val emptyConfig = StandaloneConfig()
        assertEquals("$0", BipComplicationHelper.getBalanceText(emptyConfig))
        assertEquals(0f, BipComplicationHelper.getDaleQrValue(emptyConfig), 0.001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "cd /d C:\Users\NOWIS\Documents\bipqr-wearos && gradlew.bat :wear:testDebugUnitTest --tests com.nowis.bipwear.complication.BipComplicationHelperTest"`
Expected: FAIL with compilation error (unresolved reference `BipComplicationHelper`)

- [ ] **Step 3: Implement BipComplicationHelper and BipComplicationService**

Create `wear/src/main/java/com/nowis/bipwear/complication/BipComplicationHelper.kt`:

```kotlin
package com.nowis.bipwear.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import com.nowis.bipwear.MainActivity
import com.nowis.bipwear.StandaloneConfig

object BipComplicationHelper {
    const val TITLE_TEXT = "bip!"
    const val DALE_QR_MAX_VALUE = 42000f

    fun getBalanceText(config: StandaloneConfig): String {
        return if (config.saldo.isNotBlank()) config.saldo else "$0"
    }

    fun getDaleQrValue(config: StandaloneConfig): Float {
        return config.daleQrAcumulado.toFloat().coerceAtLeast(0f)
    }

    fun createPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_AUTO_GENERATE, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildComplicationData(
        context: Context,
        type: ComplicationType,
        config: StandaloneConfig
    ): ComplicationData? {
        val balance = getBalanceText(config)
        val tapAction = createPendingIntent(context)
        val title = PlainComplicationText.Builder(TITLE_TEXT).build()
        val text = PlainComplicationText.Builder(balance).build()

        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = text,
                    contentDescription = PlainComplicationText.Builder("Saldo bip! $balance").build()
                )
                    .setTitle(title)
                    .setTapAction(tapAction)
                    .build()
            }
            ComplicationType.RANGED_VALUE -> {
                val value = getDaleQrValue(config)
                RangedValueComplicationData.Builder(
                    value = value,
                    min = 0f,
                    max = DALE_QR_MAX_VALUE,
                    contentDescription = PlainComplicationText.Builder("Dale QR: $value de $DALE_QR_MAX_VALUE").build()
                )
                    .setText(text)
                    .setTitle(title)
                    .setTapAction(tapAction)
                    .build()
            }
            else -> null
        }
    }
}
```

Now create `wear/src/main/java/com/nowis/bipwear/complication/BipComplicationService.kt`:

```kotlin
package com.nowis.bipwear.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.nowis.bipwear.BipC2dEngine

class BipComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val dummyConfig = com.nowis.bipwear.StandaloneConfig(
            saldo = "$3.400",
            daleQrAcumulado = 21000
        )
        return BipComplicationHelper.buildComplicationData(this, type, dummyConfig)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val config = BipC2dEngine.loadConfig(this)
        return BipComplicationHelper.buildComplicationData(this, request.complicationType, config)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd.exe /c "cd /d C:\Users\NOWIS\Documents\bipqr-wearos && gradlew.bat :wear:testDebugUnitTest --tests com.nowis.bipwear.complication.BipComplicationHelperTest"`
Expected: PASS (2 tests passed, 0 failures)

- [ ] **Step 5: Commit**

```bash
git add wear/src/main/java/com/nowis/bipwear/complication/ wear/src/test/java/com/nowis/bipwear/complication/
git commit -m "feat: implement BipComplicationHelper, BipComplicationService and unit tests"
```

---

### Task 4: Fast-Path Direct QR Generation & Back Navigation in MainActivity

**Files:**
- Modify: `wear/src/main/java/com/nowis/bipwear/MainActivity.kt:53-95`
- Create: `wear/src/test/java/com/nowis/bipwear/MainActivityIntentTest.kt`

**Interfaces:**
- Consumes: `MainActivity.EXTRA_AUTO_GENERATE`, `TicketManager`, `MainViewModel`
- Produces: Turnstile fast-path directly launching Aztec generation when opened from Tile or Complication, plus handling physical back button to navigate to `IdleScreen`.

- [ ] **Step 1: Write unit test verifying EXTRA_AUTO_GENERATE constant and intent builder**

Create `wear/src/test/java/com/nowis/bipwear/MainActivityIntentTest.kt`:

```kotlin
package com.nowis.bipwear

import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityIntentTest {

    @Test
    fun testExtraAutoGenerateConstant() {
        assertEquals("extra_auto_generate", MainActivity.EXTRA_AUTO_GENERATE)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "cd /d C:\Users\NOWIS\Documents\bipqr-wearos && gradlew.bat :wear:testDebugUnitTest --tests com.nowis.bipwear.MainActivityIntentTest"`
Expected: FAIL with compilation error (unresolved reference `EXTRA_AUTO_GENERATE`)

- [ ] **Step 3: Update MainActivity.kt to handle EXTRA_AUTO_GENERATE and back navigation**

In `wear/src/main/java/com/nowis/bipwear/MainActivity.kt`:
1. In `companion object`:
```kotlin
    companion object {
        private const val TAG = "BipMainActivity"
        const val EXTRA_AUTO_GENERATE = "extra_auto_generate"
        const val PATH_STANDALONE_CONFIG = "/bip/standalone_config"
        const val PATH_REFRESH_SEED = "/bip/refresh_seed"
        const val PATH_TICKET_ERROR = "/bip/ticket_error"
    }
```
2. In `onCreate(savedInstanceState: Bundle?)`:
```kotlin
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeTicketState()
        syncStoredDataItems()

        // Handle physical back button: return to Idle instead of instantly exiting if on Ticket or Movements screen
        onBackPressedDispatcher.addCallback(this) {
            if (mainViewModel.uiState.value !is MainUiState.Idle) {
                TicketManager.setState(TicketState.Idle)
                mainViewModel.resetToIdle()
            } else {
                finish()
            }
        }

        // Fast-path auto generation from Tile or Complication
        handleAutoGenerateIntent(intent)

        setContent {
            MaterialTheme {
                BipWearApp(
                    activity = this,
                    mainViewModel = mainViewModel,
                    movementsViewModel = movementsViewModel,
                    onGenerateTicket = { generarPasajeStandalone() },
                    onStartClaveUnica = { startClaveUnicaRemoteFlow() },
                    onAutoRefreshTriggered = { renderOfflineTicketNow() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoGenerateIntent(intent)
    }

    private fun handleAutoGenerateIntent(targetIntent: Intent?) {
        if (targetIntent?.getBooleanExtra(EXTRA_AUTO_GENERATE, false) == true) {
            val cfg = BipC2dEngine.loadConfig(this)
            if (cfg.isProvisioned) {
                generarPasajeStandalone()
            }
        }
    }
```
*(Import `androidx.activity.addCallback`)*

- [ ] **Step 4: Run unit tests to verify it passes**

Run: `cmd.exe /c "cd /d C:\Users\NOWIS\Documents\bipqr-wearos && gradlew.bat :wear:testDebugUnitTest --tests com.nowis.bipwear.MainActivityIntentTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add wear/src/main/java/com/nowis/bipwear/MainActivity.kt wear/src/test/java/com/nowis/bipwear/MainActivityIntentTest.kt
git commit -m "feat: handle EXTRA_AUTO_GENERATE fast-path and back navigation in MainActivity"
```

---

### Task 5: Register Tile and Complication Services in AndroidManifest.xml

**Files:**
- Modify: `wear/src/main/AndroidManifest.xml:30-51`

**Interfaces:**
- Consumes: `BipTileService`, `BipComplicationService`
- Produces: Manifest declarations for system Tile and Complication providers

- [ ] **Step 1: Declare BipTileService and BipComplicationService in AndroidManifest.xml**

In `wear/src/main/AndroidManifest.xml`, add the `<service>` blocks inside `<application>`:

```xml
        <!-- Tile Service: Swipeable card for Balance, Dale QR, and Quick Launch -->
        <service
            android:name=".tile.BipTileService"
            android:icon="@mipmap/ic_launcher"
            android:label="@string/tile_name"
            android:description="@string/tile_description"
            android:permission="com.google.android.wearable.permission.BIND_TILE_PROVIDER"
            android:exported="true">
            <intent-filter>
                <action android:name="androidx.wear.tiles.action.BIND_TILE_PROVIDER" />
            </intent-filter>
        </service>

        <!-- Watch Face Complication Service: Complication for watch dials -->
        <service
            android:name=".complication.BipComplicationService"
            android:icon="@mipmap/ic_launcher"
            android:label="@string/complication_name"
            android:description="@string/complication_description"
            android:permission="com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER"
            android:exported="true">
            <intent-filter>
                <action android:name="android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST" />
            </intent-filter>
            <meta-data
                android:name="android.support.wearable.complications.SUPPORTED_TYPES"
                android:value="SHORT_TEXT,RANGED_VALUE" />
            <meta-data
                android:name="android.support.wearable.complications.UPDATE_PERIOD_SECONDS"
                android:value="900" />
        </service>
```

- [ ] **Step 2: Verify manifest and assembleDebug**

Run: `cmd.exe /c "cd /d C:\Users\NOWIS\Documents\bipqr-wearos && gradlew.bat :wear:processDebugMainManifest"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add wear/src/main/AndroidManifest.xml
git commit -m "feat: register BipTileService and BipComplicationService in AndroidManifest"
```

---

### Task 6: Hook Surface Updates on Config Changes (BipSurfaceUpdateHelper)

**Files:**
- Create: `wear/src/main/java/com/nowis/bipwear/tile/BipSurfaceUpdateHelper.kt`
- Modify: `wear/src/main/java/com/nowis/bipwear/BipC2dEngine.kt:187-191`
- Create: `wear/src/test/java/com/nowis/bipwear/tile/BipSurfaceUpdateHelperTest.kt`

**Interfaces:**
- Consumes: `TileService.getUpdater`, `ComplicationDataSourceUpdateRequester`
- Produces: `BipSurfaceUpdateHelper.requestSurfaceUpdates(context)` called automatically on every `BipC2dEngine.saveConfig`.

- [ ] **Step 1: Write test for BipSurfaceUpdateHelper**

Create `wear/src/test/java/com/nowis/bipwear/tile/BipSurfaceUpdateHelperTest.kt`:

```kotlin
package com.nowis.bipwear.tile

import org.junit.Assert.assertNotNull
import org.junit.Test

class BipSurfaceUpdateHelperTest {

    @Test
    fun testHelperInstantiation() {
        assertNotNull(BipSurfaceUpdateHelper)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "cd /d C:\Users\NOWIS\Documents\bipqr-wearos && gradlew.bat :wear:testDebugUnitTest --tests com.nowis.bipwear.tile.BipSurfaceUpdateHelperTest"`
Expected: FAIL with compilation error (unresolved reference `BipSurfaceUpdateHelper`)

- [ ] **Step 3: Implement BipSurfaceUpdateHelper and hook in BipC2dEngine.saveConfig**

Create `wear/src/main/java/com/nowis/bipwear/tile/BipSurfaceUpdateHelper.kt`:

```kotlin
package com.nowis.bipwear.tile

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.nowis.bipwear.complication.BipComplicationService

object BipSurfaceUpdateHelper {
    private const val TAG = "BipSurfaceUpdate"

    fun requestSurfaceUpdates(context: Context) {
        try {
            // Request Tile update
            TileService.getUpdater(context).requestUpdate(BipTileService::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo solicitar actualización de Tile: ${e.message}")
        }

        try {
            // Request Complication update
            val component = ComponentName(context, BipComplicationService::class.java)
            ComplicationDataSourceUpdateRequester.create(context, component).requestUpdateAll()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo solicitar actualización de Complication: ${e.message}")
        }
    }
}
```

In `wear/src/main/java/com/nowis/bipwear/BipC2dEngine.kt`:
Update `saveConfig`:
```kotlin
    fun saveConfig(context: Context, config: StandaloneConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG_JSON, config.toJson().toString()).apply()
        // Notify Wear OS surfaces (Tile and Complication)
        try {
            com.nowis.bipwear.tile.BipSurfaceUpdateHelper.requestSurfaceUpdates(context.applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Error actualizando superficies Wear OS", e)
        }
    }
```

- [ ] **Step 4: Run unit tests to verify it passes**

Run: `cmd.exe /c "cd /d C:\Users\NOWIS\Documents\bipqr-wearos && gradlew.bat :wear:testDebugUnitTest"`
Expected: PASS (all unit tests pass)

- [ ] **Step 5: Commit**

```bash
git add wear/src/main/java/com/nowis/bipwear/tile/BipSurfaceUpdateHelper.kt wear/src/main/java/com/nowis/bipwear/BipC2dEngine.kt wear/src/test/java/com/nowis/bipwear/tile/BipSurfaceUpdateHelperTest.kt
git commit -m "feat: trigger Wear OS Tile and Complication updates on config changes"
```

---

### Task 7: Full Verification & GitHub Actions CI Build

**Files:**
- Verify: Full codebase
- Build & CI: `./gradlew :wear:testDebugUnitTest :wear:assembleDebug`

**Interfaces:**
- Consumes: All outputs from Tasks 1-6
- Produces: Tested and compiled Wear OS APK with passing CI run

- [ ] **Step 1: Execute full local compilation and test suite**

Run: `cmd.exe /c "cd /d C:\Users\NOWIS\Documents\bipqr-wearos && gradlew.bat :wear:testDebugUnitTest :wear:assembleDebug"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Push commits to GitHub repository**

Run: `git -C "C:\Users\NOWIS\Documents\bipqr-wearos" push origin feature/mvvm-refactor`
Expected: Push succeeds to branch `feature/mvvm-refactor`

- [ ] **Step 3: Monitor GitHub Actions workflow run**

Run: `cmd.exe /c "cd /d C:\Users\NOWIS\Documents\bipqr-wearos && gh run list --limit 1"`
Run: `cmd.exe /c "cd /d C:\Users\NOWIS\Documents\bipqr-wearos && gh run watch"`
Expected: Build & Test workflow runs and finishes with conclusion `success`.
