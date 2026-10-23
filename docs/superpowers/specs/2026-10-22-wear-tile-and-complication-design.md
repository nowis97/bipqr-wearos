# Wear OS Tile & Watch Face Complication Design Document

**Date:** 2026-10-22  
**Status:** Approved  
**Target:** Wear OS (Min SDK 26, Target/Compile SDK 34, Java 17)  
**Project:** `bipqr-wearos`

---

## 1. Problem & Goal

### Problem
Opening the Wear OS app launcher and scrolling to `bip!QR` while walking towards a Metro turnstile or boarding a bus takes several seconds. Users need an instant, glanceable way to check their balance, monitor their "Dale QR" monthly progress, and launch directly into an active Aztec ticket with a single wrist tap.

### Goal
Provide native Wear OS quick-access surfaces:
1. **Wear OS Tile (`BipTileService`):** A swipeable quick-view card showing the outer "Dale QR" progress ring, current CLP balance, offline seed status, and a prominent "Generar QR" action button.
2. **Watch Face Complication (`BipComplicationService`):** A native complication compatible with circular/slot watch faces supporting both `SHORT_TEXT` (balance `$X.XXX`) and `RANGED_VALUE` (Dale QR progress `$0` to `$42.000` + balance text).
3. **Turnstile Fast-Path:** Tapping either the Tile button or the Complication launches `MainActivity` with `EXTRA_AUTO_GENERATE = true`, jumping directly into [`TicketScreen.kt`](file:///C:/Users/NOWIS/Documents/bipqr-wearos/wear/src/main/java/com/nowis/bipwear/ui/TicketScreen.kt) with screen brightness maximized and the Aztec QR code ready to scan.

---

## 2. Architecture & Components

```
                +-----------------------------------------+
                |     Wear OS System (Watch Face / Tiles) |
                +--------------------+--------------------+
                                     |
              +----------------------+----------------------+
              |                                             |
              v                                             v
     +-----------------+                           +-------------------+
     | BipTileService  |                           | BipComplication   |
     | (Wear Tiles 1.3)|                           | Service           |
     +--------+--------+                           +---------+---------+
              |                                              |
              +----------------------+-----------------------+
                                     |
                                     v
                        +-------------------------+
                        |   BipC2dEngine.loadConfig|
                        |   (SharedPreferences)   |
                        +------------+------------+
                                     |
              Tap Action: Intent(EXTRA_AUTO_GENERATE = true)
                                     |
                                     v
                        +-------------------------+
                        |       MainActivity      |
                        |  (Direct Ticket Launch) |
                        +------------+------------+
                                     |
                                     v
                        +-------------------------+
                        |      TicketScreen       |
                        | (Max Brightness + Aztec)|
                        +-------------------------+
```

### Component Details

#### 1. `BipTileService` (`wear/src/main/java/com/nowis/bipwear/tile/BipTileService.kt`)
- Inherits from `androidx.wear.tiles.TileService`.
- Uses `ProtoLayout Material` (`androidx.wear.protolayout:protolayout-material:1.2.0`).
- **Visual Design:**
  - **Outer Arc (`Arc`):** Circular progress indicator representing monthly Dale QR accumulation (cyan `#00E5FF` while accumulating, neon green `#00E676` if `$42.000` is reached, track `#3300E5FF`).
  - **Header:** "bip!QR" in `#E50914`.
  - **Central Info:** Current balance in Chilean Pesos (e.g., `"$4.500"`), plus small offline status indicator (`"Offline listo"` or `"Online"`).
  - **Dale QR Status Text:** Small caption indicating progress (e.g. `"Dale QR: 35%"` or `"🎉 ¡Viajes Gratis!"`).
  - **Action Button (`CompactChip` / `Button`):** "Generar QR" with red background (`#E50914`) which sends an `ActionBuilders.LaunchAction` targeting `MainActivity` with `EXTRA_AUTO_GENERATE = true`.
  - **Unprovisioned State:** If account is not linked, shows `"bip!QR"` and a button `"Vincular ClaveÚnica"` opening `MainActivity`.

#### 2. `BipComplicationService` (`wear/src/main/java/com/nowis/bipwear/complication/BipComplicationService.kt`)
- Inherits from `androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService`.
- **Supported Complication Types:**
  1. `ComplicationType.SHORT_TEXT`:
     - Text: `$X.XXX` (or `"$0"`).
     - Title: `"bip!"`.
     - Icon: bip! card / QR icon vector.
  2. `ComplicationType.RANGED_VALUE`:
     - Min: `0f`, Max: `42000f`, Value: `daleQrAcumulado.toFloat()`.
     - Text: `$X.XXX` (balance).
     - Title: `"bip!"`.
- **Tap Action:**
  - `PendingIntent.getActivity` launching `MainActivity` with `EXTRA_AUTO_GENERATE = true` and `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP`.

#### 3. Fast-Path Launch in `MainActivity.kt`
- In `onCreate(savedInstanceState)` and `onNewIntent(intent)`:
  - If `intent.getBooleanExtra(EXTRA_AUTO_GENERATE, false) == true` and account is provisioned:
    - Trigger `generarPasajeStandalone()` immediately.
    - Set up back navigation so pressing the physical back button returns to `MainUiState.Idle` rather than exiting the app.

#### 4. Automatic Update Requester (`BipNotificationHelper.kt` / `BipC2dEngine.kt`)
- When new balance or Dale QR progress is saved (`saveConfig` in `BipC2dEngine` or after background sync):
  - Request Tile update via `TileService.getUpdater(context).requestUpdate(BipTileService::class.java)`.
  - Request Complication update via `ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, BipComplicationService::class.java)).requestUpdateAll()`.

---

## 3. Manifest Declarations

In `wear/src/main/AndroidManifest.xml`:
```xml
<!-- Tile Service -->
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

<!-- Complication Service -->
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

---

## 4. Dependencies

In `wear/build.gradle`:
```groovy
    // Wear OS Tiles & ProtoLayout
    implementation 'androidx.wear.tiles:tiles:1.3.0'
    implementation 'androidx.wear.protolayout:protolayout:1.2.0'
    implementation 'androidx.wear.protolayout:protolayout-material:1.2.0'

    // Watch Face Complications Data Source
    implementation 'androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1'
```

---

## 5. Testing & Verification

1. **Unit Tests:**
   - Test `BipTileService` layout builder produces valid tile response for both provisioned and unprovisioned configs.
   - Test `BipComplicationService` creates valid `ShortTextComplicationData` and `RangedValueComplicationData`.
   - Test intent construction verifies `EXTRA_AUTO_GENERATE = true`.
2. **CI Pipeline:**
   - `./gradlew :wear:testDebugUnitTest :wear:assembleDebug` runs and passes in GitHub Actions.
