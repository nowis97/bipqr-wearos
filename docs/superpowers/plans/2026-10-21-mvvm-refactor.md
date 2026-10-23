# MVVM Architecture Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the `bipqr-wearos` Wear OS app from its monolithic Activity and singleton structure into a clean, testable MVVM (Model-View-ViewModel) architecture with Unidirectional Data Flow (UDF) and StateFlow.

**Architecture:** 
- **Model / Data Layer:** A cohesive `BipRepository` abstracts cryptography/Aztec ticket generation (`BipC2dEngine`), Red Movilidad REST API / Dale QR / movements endpoints (`RedApiClient`), and local SharedPreferences / Wearable synchronization.
- **ViewModel Layer:** `MainViewModel` and `MovementsViewModel` hold UI state in `StateFlow`, process user intentions, and survive configuration changes without coupling to Android UI context.
- **View Layer:** `MainActivity` and decoupled Compose screens (`IdleScreen`, `TicketScreen`, `MovementsScreen`, `LoadingScreen`) are 100% stateless, consuming immutable UI state models and emitting events via lambdas.

**Tech Stack:** Kotlin 1.9.22, Jetpack Compose for Wear OS 1.3.0, AndroidX Lifecycle & ViewModel Compose 2.7.0, Coroutines & StateFlow 1.7.3, JUnit 4, Kotlinx Coroutines Test.

**Spec:** Inherent requirements from conversation: eliminate monolithic 1026-line `MainActivity.kt`, decouple `TicketManager` global singleton, provide testable ViewModels, and retain 100% existing functionality (Aztec generation, Dale QR ring/badge, movements list with bus/metro/tren/carga icons, and ClaveÚnica remote flow).

## Global Constraints
- Target SDK 34, Min SDK 26, Java 17.
- Zero external architecture bloat: no Dagger/Hilt or complex DI frameworks needed (Ponytail principle: standard factory / repository injection).
- Preserve all existing business logic: Sonda libc2d curve `secp224r1` Aztec generation, Dale QR $42.000 limit, and transactions format.
- All code changes must compile and pass `./gradlew :wear:assembleDebug`.

---

### Task 1: Add MVVM & Unit Testing Dependencies

**Files:**
- Modify: `wear/build.gradle:47-73`

**Interfaces:**
- Consumes: Existing Gradle configuration
- Produces: `androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0`, `junit:junit:4.13.2`, `kotlinx-coroutines-test:1.7.3`

- [ ] **Step 1: Update dependencies in `wear/build.gradle`**

Add `lifecycle-viewmodel-compose` and unit test libraries to the `dependencies` block in `wear/build.gradle`:

```groovy
    // ViewModel Compose
    implementation 'androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0'

    // Unit Testing
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3'
```

- [ ] **Step 2: Verify Gradle build configuration**

Run dry-run or verification:
```powershell
cd C:\Users\NOWIS\Documents\bipqr-wearos
git diff wear/build.gradle
```

- [ ] **Step 3: Commit**

```bash
git add wear/build.gradle
git commit -m "build: add lifecycle-viewmodel-compose and test dependencies"
```

---

### Task 2: Define UI State Contracts (`UiStates.kt`)

**Files:**
- Create: `wear/src/main/java/com/nowis/bipwear/ui/state/UiStates.kt`

**Interfaces:**
- Consumes: `TicketData`, `BipMovement`, `StandaloneConfig`
- Produces: `MainUiState`, `MovementsUiState`, `DaleQrUiState`

- [ ] **Step 1: Create `UiStates.kt` with state models**

```kotlin
package com.nowis.bipwear.ui.state

import com.nowis.bipwear.BipMovement
import com.nowis.bipwear.TicketData

sealed interface MainUiState {
    object Idle : MainUiState
    data class Loading(val message: String) : MainUiState
    data class Active(val ticket: TicketData) : MainUiState
    data class Error(val errorMessage: String) : MainUiState
    object Movements : MainUiState
}

data class MovementsUiState(
    val isLoading: Boolean = false,
    val movements: List<BipMovement> = emptyList(),
    val errorMessage: String? = null
)

data class DaleQrUiState(
    val acumulado: Int = 0,
    val meta: Int = 42000,
    val isReached: Boolean = false,
    val progress: Float = 0f
)
```

- [ ] **Step 2: Commit**

```bash
git add wear/src/main/java/com/nowis/bipwear/ui/state/UiStates.kt
git commit -m "feat: define UI state contracts for MVVM architecture"
```

---

### Task 3: Create `BipRepository` (Single Source of Truth)

**Files:**
- Create: `wear/src/main/java/com/nowis/bipwear/data/BipRepository.kt`
- Test: `wear/src/test/java/com/nowis/bipwear/data/BipRepositoryTest.kt`

**Interfaces:**
- Consumes: `Context`, `BipC2dEngine`, `RedApiClient`, `StandaloneConfig`
- Produces: `BipRepository` interface and default implementation `DefaultBipRepository`

- [ ] **Step 1: Write unit test for `BipRepository`**

Create `wear/src/test/java/com/nowis/bipwear/data/BipRepositoryTest.kt`:

```kotlin
package com.nowis.bipwear.data

import com.nowis.bipwear.BipMovement
import com.nowis.bipwear.StandaloneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeBipRepository(
    var config: StandaloneConfig = StandaloneConfig(),
    var isTramaValid: Boolean = true,
    var movements: List<BipMovement> = emptyList()
) : BipRepository {
    override fun getConfig(): StandaloneConfig = config
    override fun isTramaValid(): Boolean = isTramaValid
    override fun getCachedMovements(): List<BipMovement> = movements
    override suspend fun fetchMovements(): Result<List<BipMovement>> = Result.success(movements)
    override suspend fun refreshDaleQr(): Result<StandaloneConfig> = Result.success(config)
    override fun importConfigJson(jsonStr: String): Boolean = true
}

class BipRepositoryTest {
    @Test
    fun testFakeRepositoryReturnsCorrectData() {
        val repo = FakeBipRepository(
            config = StandaloneConfig(rut = "12345678-9", saldo = 5000),
            movements = listOf(BipMovement(id = "1", amount = 830, transportType = "BUS"))
        )
        assertEquals("12345678-9", repo.getConfig().rut)
        assertEquals(5000, repo.getConfig().saldo)
        assertEquals(1, repo.getCachedMovements().size)
        assertTrue(repo.isTramaValid())
    }
}
```

- [ ] **Step 2: Implement `BipRepository.kt`**

Create `wear/src/main/java/com/nowis/bipwear/data/BipRepository.kt`:

```kotlin
package com.nowis.bipwear.data

import android.content.Context
import com.nowis.bipwear.BipC2dEngine
import com.nowis.bipwear.BipMovement
import com.nowis.bipwear.ClaveUnicaStartInfo
import com.nowis.bipwear.ClaveUnicaUserProfile
import com.nowis.bipwear.RedApiClient
import com.nowis.bipwear.StandaloneConfig
import com.nowis.bipwear.TicketData
import com.nowis.bipwear.TicketManager

interface BipRepository {
    fun getConfig(): StandaloneConfig
    fun isTramaValid(): Boolean
    fun getCachedMovements(): List<BipMovement>
    suspend fun fetchMovements(): Result<List<BipMovement>>
    suspend fun refreshDaleQr(): Result<StandaloneConfig>
    fun importConfigJson(jsonStr: String): Boolean
}

class DefaultBipRepository(private val context: Context) : BipRepository {

    override fun getConfig(): StandaloneConfig {
        return BipC2dEngine.loadConfig(context)
    }

    override fun isTramaValid(): Boolean {
        return BipC2dEngine.isTramaValid(context)
    }

    override fun getCachedMovements(): List<BipMovement> {
        return RedApiClient.loadCachedMovements(context)
    }

    override suspend fun fetchMovements(): Result<List<BipMovement>> {
        return try {
            val fresh = RedApiClient.fetchTransactions(context)
            Result.success(fresh)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun refreshDaleQr(): Result<StandaloneConfig> {
        return try {
            val updated = RedApiClient.fetchDaleQrBenefitInfo(context)
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun importConfigJson(jsonStr: String): Boolean {
        return TicketManager.importStandaloneConfigJson(context, jsonStr)
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add wear/src/main/java/com/nowis/bipwear/data/BipRepository.kt wear/src/test/java/com/nowis/bipwear/data/BipRepositoryTest.kt
git commit -m "feat: implement BipRepository and unit tests"
```

---

### Task 4: Implement `MovementsViewModel`

**Files:**
- Create: `wear/src/main/java/com/nowis/bipwear/viewmodel/MovementsViewModel.kt`
- Test: `wear/src/test/java/com/nowis/bipwear/viewmodel/MovementsViewModelTest.kt`

**Interfaces:**
- Consumes: `BipRepository`, `MovementsUiState`, `BipMovement`
- Produces: `MovementsViewModel` exposing `StateFlow<MovementsUiState>` and `fun refresh()`

- [ ] **Step 1: Write failing test for `MovementsViewModel`**

Create `wear/src/test/java/com/nowis/bipwear/viewmodel/MovementsViewModelTest.kt`:

```kotlin
package com.nowis.bipwear.viewmodel

import com.nowis.bipwear.BipMovement
import com.nowis.bipwear.data.FakeBipRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovementsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialStateLoadsCachedMovements() = runTest {
        val cached = listOf(
            BipMovement(id = "1", description = "Metro Baquedano", amount = 830, transportType = "METRO")
        )
        val repo = FakeBipRepository(movements = cached)
        val viewModel = MovementsViewModel(repo)

        assertEquals(1, viewModel.uiState.value.movements.size)
        assertEquals("Metro Baquedano", viewModel.uiState.value.movements[0].description)
        assertFalse(viewModel.uiState.value.isLoading)
    }
}
```

- [ ] **Step 2: Implement `MovementsViewModel.kt`**

Create `wear/src/main/java/com/nowis/bipwear/viewmodel/MovementsViewModel.kt`:

```kotlin
package com.nowis.bipwear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nowis.bipwear.data.BipRepository
import com.nowis.bipwear.ui.state.MovementsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MovementsViewModel(
    private val repository: BipRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MovementsUiState(
            isLoading = false,
            movements = repository.getCachedMovements()
        )
    )
    val uiState: StateFlow<MovementsUiState> = _uiState.asStateFlow()

    init {
        refresh(forceNetwork = false)
    }

    fun refresh(forceNetwork: Boolean = true) {
        val currentList = _uiState.value.movements
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.fetchMovements()
            result.onSuccess { fresh ->
                _uiState.update { it.copy(isLoading = false, movements = fresh, errorMessage = null) }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        movements = if (currentList.isNotEmpty()) currentList else it.movements,
                        errorMessage = err.localizedMessage ?: "Error de red"
                    )
                }
            }
        }
    }

    companion object {
        fun provideFactory(repository: BipRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MovementsViewModel(repository) as T
                }
            }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add wear/src/main/java/com/nowis/bipwear/viewmodel/MovementsViewModel.kt wear/src/test/java/com/nowis/bipwear/viewmodel/MovementsViewModelTest.kt
git commit -m "feat: implement MovementsViewModel with StateFlow and unit test"
```

---

### Task 5: Implement `MainViewModel`

**Files:**
- Create: `wear/src/main/java/com/nowis/bipwear/viewmodel/MainViewModel.kt`
- Test: `wear/src/test/java/com/nowis/bipwear/viewmodel/MainViewModelTest.kt`

**Interfaces:**
- Consumes: `BipRepository`, `MainUiState`, `StandaloneConfig`, `TicketData`
- Produces: `MainViewModel` exposing `StateFlow<MainUiState>`, `StateFlow<StandaloneConfig>`, and intent methods

- [ ] **Step 1: Write failing test for `MainViewModel`**

Create `wear/src/test/java/com/nowis/bipwear/viewmodel/MainViewModelTest.kt`:

```kotlin
package com.nowis.bipwear.viewmodel

import com.nowis.bipwear.StandaloneConfig
import com.nowis.bipwear.data.FakeBipRepository
import com.nowis.bipwear.ui.state.MainUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialStateIsIdle() = runTest {
        val repo = FakeBipRepository(config = StandaloneConfig(rut = "12345678-9"))
        val viewModel = MainViewModel(repo)

        assertTrue(viewModel.uiState.value is MainUiState.Idle)
        assertEquals("12345678-9", viewModel.config.value.rut)
    }

    @Test
    fun testResetToIdleSetsStateToIdle() = runTest {
        val repo = FakeBipRepository()
        val viewModel = MainViewModel(repo)
        viewModel.setError("Some error")
        assertTrue(viewModel.uiState.value is MainUiState.Error)

        viewModel.resetToIdle()
        assertTrue(viewModel.uiState.value is MainUiState.Idle)
    }
}
```

- [ ] **Step 2: Implement `MainViewModel.kt`**

Create `wear/src/main/java/com/nowis/bipwear/viewmodel/MainViewModel.kt`:

```kotlin
package com.nowis.bipwear.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nowis.bipwear.StandaloneConfig
import com.nowis.bipwear.TicketData
import com.nowis.bipwear.data.BipRepository
import com.nowis.bipwear.ui.state.MainUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: BipRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Idle)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _config = MutableStateFlow(repository.getConfig())
    val config: StateFlow<StandaloneConfig> = _config.asStateFlow()

    fun refreshConfig() {
        _config.value = repository.getConfig()
    }

    fun setState(state: MainUiState) {
        _uiState.value = state
    }

    fun resetToIdle() {
        _uiState.value = MainUiState.Idle
    }

    fun setError(message: String) {
        _uiState.value = MainUiState.Error(message)
    }

    fun setTicketActive(ticket: TicketData) {
        _uiState.value = MainUiState.Active(ticket)
    }

    fun refreshDaleQr(onResult: (String) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.refreshDaleQr()
            result.onSuccess { updated ->
                _config.value = updated
                onResult("Dale QR: $${updated.daleQrAcumulado} / $${updated.daleQrMeta}")
            }.onFailure { err ->
                onResult("Error Dale QR: ${err.message}")
            }
        }
    }

    fun importConfigJson(jsonStr: String): Boolean {
        val success = repository.importConfigJson(jsonStr)
        if (success) {
            refreshConfig()
        }
        return success
    }

    companion object {
        fun provideFactory(repository: BipRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(repository) as T
                }
            }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add wear/src/main/java/com/nowis/bipwear/viewmodel/MainViewModel.kt wear/src/test/java/com/nowis/bipwear/viewmodel/MainViewModelTest.kt
git commit -m "feat: implement MainViewModel with state management and unit test"
```

---

### Task 6: Extract Modular Stateless Composables

**Files:**
- Create: `wear/src/main/java/com/nowis/bipwear/ui/IdleScreen.kt`
- Create: `wear/src/main/java/com/nowis/bipwear/ui/TicketScreen.kt`
- Create: `wear/src/main/java/com/nowis/bipwear/ui/MovementsScreen.kt`
- Create: `wear/src/main/java/com/nowis/bipwear/ui/LoadingScreen.kt`

**Interfaces:**
- Consumes: `StandaloneConfig`, `TicketData`, `MovementsUiState`, callbacks
- Produces: Pure, stateless composable screens

- [ ] **Step 1: Extract `LoadingScreen.kt`**

Create `wear/src/main/java/com/nowis/bipwear/ui/LoadingScreen.kt`:

```kotlin
package com.nowis.bipwear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text

@Composable
fun LoadingScreen(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        CircularProgressIndicator(
            indicatorColor = Color(0xFFE50914),
            modifier = Modifier.size(36.dp),
            strokeWidth = 3.dp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = message,
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            lineHeight = 15.sp
        )
    }
}
```

- [ ] **Step 2: Extract `IdleScreen.kt`**

Create `wear/src/main/java/com/nowis/bipwear/ui/IdleScreen.kt`:

```kotlin
package com.nowis.bipwear.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.nowis.bipwear.R
import com.nowis.bipwear.StandaloneConfig
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IdleScreen(
    cfg: StandaloneConfig,
    isOfflineReady: Boolean,
    onGenerate: () -> Unit,
    onRelink: () -> Unit,
    onRefreshDaleQr: () -> Unit,
    onViewMovements: () -> Unit
) {
    val isProvisioned = cfg.isProvisioned

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (isProvisioned) {
            val rawProgress = cfg.daleQrProgress
            val progress = when {
                cfg.isDaleQrReached -> 1f
                rawProgress > 0f -> rawProgress.coerceIn(0.035f, 1f)
                else -> 0f
            }
            val ringColor = if (cfg.isDaleQrReached) Color(0xFF00E676) else Color(0xFF00E5FF)
            val ringTrackColor = if (cfg.isDaleQrReached) Color(0x3300E676) else Color(0x3300E5FF)

            CircularProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp),
                startAngle = 270f,
                endAngle = 270f,
                indicatorColor = ringColor,
                trackColor = ringTrackColor,
                strokeWidth = 4.dp
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = "bip!QR",
                color = Color(0xFFE50914),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(2.dp))

            val subtitleText = if (isProvisioned) {
                val clp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(cfg.saldo)
                val tramaStatus = if (isOfflineReady) "Offline listo" else "Online 2h"
                "${cfg.rut} · $$clp ($tramaStatus)"
            } else {
                stringResource(R.string.status_not_provisioned)
            }

            Text(
                text = subtitleText,
                color = Color(0xFFAAAAAA),
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )

            if (isProvisioned) {
                Spacer(modifier = Modifier.height(6.dp))
                DaleQrBadge(
                    acumulado = cfg.daleQrAcumulado,
                    meta = cfg.daleQrMeta,
                    isReached = cfg.isDaleQrReached,
                    onClick = onRefreshDaleQr
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFE50914))
                    .combinedClickable(
                        onClick = { if (isProvisioned) onGenerate() else onRelink() },
                        onLongClick = { if (isProvisioned) onRelink() }
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isProvisioned) stringResource(R.string.btn_generate) else stringResource(R.string.btn_link_clave_unica),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }

            if (isProvisioned) {
                Spacer(modifier = Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E1E1E))
                        .clickable { onViewMovements() }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "📋 Movimientos",
                        color = Color(0xFFDDDDDD),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun DaleQrBadge(
    acumulado: Int,
    meta: Int,
    isReached: Boolean,
    onClick: () -> Unit
) {
    val metaClp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(meta)
    val acumClp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(acumulado)

    if (isReached) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0D3818))
                .clickable { onClick() }
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "🎉 ¡Viajes Gratis este mes!",
                    color = Color(0xFF00E676),
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Dale QR: $$acumClp superado",
                    color = Color(0xFFB9F6CA),
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        val progress = if (meta > 0) (acumulado.toFloat() / meta.toFloat()).coerceIn(0f, 1f) else 0f
        val pct = (progress * 100).toInt()

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A1A1A))
                .clickable { onClick() }
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Dale QR: $$acumClp / $$metaClp ($pct%)",
                color = Color(0xFF00E5FF),
                fontWeight = FontWeight.SemiBold,
                fontSize = 9.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
```

- [ ] **Step 3: Extract `TicketScreen.kt`**

Create `wear/src/main/java/com/nowis/bipwear/ui/TicketScreen.kt`:

```kotlin
package com.nowis.bipwear.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.nowis.bipwear.R
import com.nowis.bipwear.TicketData
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.NumberFormat
import java.util.Locale

@Composable
fun TicketScreen(
    ticket: TicketData,
    onRefresh: () -> Unit,
    onAutoRefreshTriggered: () -> Unit
) {
    LaunchedEffect(ticket.expiryTimestampMs) {
        while (isActive) {
            val intervalMs = (ticket.refreshIntervalSec * 1000L).coerceAtLeast(3000L)
            delay(intervalMs)
            onAutoRefreshTriggered()
        }
    }

    var remainingMs by remember(ticket.expiryTimestampMs) {
        mutableStateOf((ticket.expiryTimestampMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    LaunchedEffect(ticket.expiryTimestampMs) {
        while (isActive && remainingMs > 0) {
            delay(1000)
            remainingMs = (ticket.expiryTimestampMs - System.currentTimeMillis()).coerceAtLeast(0L)
        }
    }

    val isExpired = remainingMs <= 0
    val totalSeconds = remainingMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    val timerText = if (isExpired) {
        stringResource(R.string.status_expired)
    } else if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
    val timerColor = if (isExpired) Color(0xFFE50914) else Color(0xFF00E676)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .clickable { onRefresh() }
            .padding(bottom = 4.dp)
    ) {
        Text(
            text = timerText,
            color = timerColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(3.dp))

        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(3.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = ticket.bitmap.asImageBitmap(),
                contentDescription = "bip!QR Ticket",
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        val clp = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(ticket.saldo)
        val instructionsText = if (ticket.saldo > 0) {
            "Saldo: $$clp · Toca para actualizar"
        } else {
            stringResource(R.string.status_ticket_ready)
        }

        Text(
            text = instructionsText,
            color = Color(0xFFAAAAAA),
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )
    }
}
```

- [ ] **Step 4: Extract `MovementsScreen.kt`**

Create `wear/src/main/java/com/nowis/bipwear/ui/MovementsScreen.kt`:

```kotlin
package com.nowis.bipwear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Text
import com.nowis.bipwear.BipMovement
import com.nowis.bipwear.ui.state.MovementsUiState
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun MovementsScreen(
    uiState: MovementsUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val listState = rememberScalingLazyListState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (uiState.isLoading && uiState.movements.isEmpty()) {
            LoadingScreen(message = "Cargando movimientos...")
        } else if (uiState.movements.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
            ) {
                Text(text = "📋", fontSize = 24.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Sin movimientos",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tus viajes en bus, metro y tren aparecerán aquí.",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF222222))
                        .clickable { onBack() }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Volver",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clickable { onRefresh() }
                    ) {
                        Text(
                            text = "Últimos movimientos",
                            color = Color(0xFFE50914),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = if (uiState.isLoading) "Actualizando..." else "${uiState.movements.size} registrados · Toca para recargar",
                            color = Color.Gray,
                            fontSize = 8.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                items(uiState.movements) { item ->
                    MovementItemRow(item = item)
                    Spacer(modifier = Modifier.height(4.dp))
                }

                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF222222))
                            .clickable { onBack() }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Volver al inicio",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
fun MovementItemRow(item: BipMovement) {
    val (icon, label, accentColor) = getMovementVisuals(item)
    val formattedDate = formatMovementDate(item.dateTimeStr)

    val amountText = when {
        item.isFreeTrip -> "$0"
        item.isCarga -> "+$${NumberFormat.getIntegerInstance(Locale("es", "CL")).format(item.amount.coerceAtLeast(0))}"
        item.amount > 0 -> "-$${NumberFormat.getIntegerInstance(Locale("es", "CL")).format(item.amount)}"
        else -> "$0"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF161616))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, fontSize = 16.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    maxLines = 1
                )
                if (formattedDate.isNotBlank()) {
                    Text(
                        text = formattedDate,
                        color = Color(0xFFAAAAAA),
                        fontSize = 8.sp
                    )
                }
            }
            Text(
                text = amountText,
                color = accentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
        }
    }
}

fun getMovementVisuals(item: BipMovement): Triple<String, String, Color> {
    val descLower = item.description.lowercase(Locale.ROOT)
    val typeLower = item.transportType.lowercase(Locale.ROOT)

    return when {
        item.isFreeTrip || descLower.contains("gratis") || descLower.contains("dale") -> {
            Triple("🎉", if (item.description.isNotBlank()) item.description else "Viaje gratis Dale QR", Color(0xFF00E676))
        }
        item.isCarga || descLower.contains("carga") -> {
            Triple("💳", if (item.description.isNotBlank()) item.description else "Carga de saldo", Color(0xFF4CAF50))
        }
        descLower.contains("metro") || typeLower.contains("metro") || descLower.contains("estaci") -> {
            Triple("🚇", if (item.description.isNotBlank()) item.description else "Viaje en Metro", Color(0xFFFF5252))
        }
        descLower.contains("tren") || typeLower.contains("tren") -> {
            Triple("🚆", if (item.description.isNotBlank()) item.description else "Viaje en Tren", Color(0xFFFF9800))
        }
        else -> {
            Triple("🚌", if (item.description.isNotBlank()) item.description else "Viaje en Bus", Color(0xFF00E5FF))
        }
    }
}

fun formatMovementDate(raw: String): String {
    if (raw.isBlank()) return ""
    val inputPatterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "dd-MM-yyyy HH:mm:ss",
        "dd-MM-yyyy HH:mm",
        "dd/MM/yyyy HH:mm"
    )
    for (pat in inputPatterns) {
        try {
            val sdf = SimpleDateFormat(pat, Locale.US)
            val date = sdf.parse(raw)
            if (date != null) {
                val outSdf = SimpleDateFormat("dd MMM, HH:mm", Locale("es", "CL"))
                return outSdf.format(date)
            }
        } catch (_: Exception) {}
    }
    return raw
}
```

- [ ] **Step 5: Commit**

```bash
git add wear/src/main/java/com/nowis/bipwear/ui/*.kt
git commit -m "feat: extract stateless composable screens for Idle, Ticket, Loading and Movements"
```

---

### Task 7: Refactor `MainActivity` and `BipWearListenerService`

**Files:**
- Modify: `wear/src/main/java/com/nowis/bipwear/MainActivity.kt`
- Modify: `wear/src/main/java/com/nowis/bipwear/BipWearListenerService.kt`

**Interfaces:**
- Consumes: `MainViewModel`, `MovementsViewModel`, `BipRepository`, UI screens
- Produces: Streamlined `MainActivity` (~150 lines) binding ViewModels to UI and system events.

- [ ] **Step 1: Refactor `MainActivity.kt`**

Rewrite `wear/src/main/java/com/nowis/bipwear/MainActivity.kt` to:
1. Initialize `DefaultBipRepository(applicationContext)`.
2. Provide `MainViewModel` and `MovementsViewModel` via factory.
3. Observe `mainViewModel.uiState` and render `BipWearApp`.
4. Delegate Wearable `DataClient` and `MessageClient` events directly to `mainViewModel`.

```kotlin
package com.nowis.bipwear

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.nowis.bipwear.data.DefaultBipRepository
import com.nowis.bipwear.ui.IdleScreen
import com.nowis.bipwear.ui.LoadingScreen
import com.nowis.bipwear.ui.MovementsScreen
import com.nowis.bipwear.ui.TicketScreen
import com.nowis.bipwear.ui.state.MainUiState
import com.nowis.bipwear.viewmodel.MainViewModel
import com.nowis.bipwear.viewmodel.MovementsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {

    companion object {
        private const val TAG = "BipMainActivity"
        const val PATH_STANDALONE_CONFIG = "/bip/standalone_config"
        const val PATH_REFRESH_SEED = "/bip/refresh_seed"
        const val PATH_TICKET_ERROR = "/bip/ticket_error"
    }

    private val repository by lazy { DefaultBipRepository(applicationContext) }
    private val mainViewModel: MainViewModel by viewModels { MainViewModel.provideFactory(repository) }
    private val movementsViewModel: MovementsViewModel by viewModels { MovementsViewModel.provideFactory(repository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncStoredDataItems()

        setContent {
            MaterialTheme {
                BipWearApp(
                    activity = this,
                    mainViewModel = mainViewModel,
                    movementsViewModel = movementsViewModel,
                    onGenerateTicket = { generarPasajeStandalone() },
                    onStartClaveUnica = { startClaveUnicaRemoteFlow() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        Wearable.getMessageClient(this).addListener(this)
        mainViewModel.refreshConfig()
        mainViewModel.refreshDaleQr()
    }

    override fun onPause() {
        super.onPause()
        Wearable.getDataClient(this).removeListener(this)
        Wearable.getMessageClient(this).removeListener(this)
    }

    private fun syncStoredDataItems() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dataClient = Wearable.getDataClient(this@MainActivity)
                val uri = Uri.parse("wear://*$PATH_STANDALONE_CONFIG")
                val buffer = dataClient.getDataItems(uri).await()
                buffer.use { items ->
                    for (item in items) {
                        val dataMap = DataMapItem.fromDataItem(item).dataMap
                        val configJson = dataMap.getString("config_json")
                        if (!configJson.isNullOrEmpty()) {
                            mainViewModel.importConfigJson(configJson)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo consultar DataItems iniciales: ${e.message}")
            }
        }
    }

    private fun startClaveUnicaRemoteFlow() {
        mainViewModel.setState(MainUiState.Loading(getString(R.string.status_opening_phone)))

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val startInfo = RedApiClient.startClaveUnica(this@MainActivity)
                val remoteIntent = Intent(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setData(Uri.parse(startInfo.authUrl))

                val remoteActivityHelper = RemoteActivityHelper(this@MainActivity)
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                val phoneNode = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()

                withContext(Dispatchers.Main) {
                    mainViewModel.setState(MainUiState.Loading(getString(R.string.status_waiting_phone_login)))
                }

                try {
                    if (phoneNode != null) {
                        remoteActivityHelper.startRemoteActivity(remoteIntent, phoneNode.id)
                    } else {
                        remoteActivityHelper.startRemoteActivity(remoteIntent)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallo al enviar RemoteActivity, intentando local", e)
                    try {
                        startActivity(remoteIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {}
                }

                var profile: ClaveUnicaUserProfile? = null
                var attempts = 0
                while (isActive && attempts < 90) {
                    delay(2000)
                    attempts++
                    try {
                        val p = RedApiClient.checkClaveUnicaLogged(this@MainActivity, startInfo.sessionToken)
                        if (p != null) {
                            profile = p
                            break
                        }
                    } catch (_: Exception) {}
                }

                if (profile == null) {
                    withContext(Dispatchers.Main) {
                        mainViewModel.setError("Tiempo de espera agotado. Intenta vincular de nuevo.")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    mainViewModel.setState(MainUiState.Loading(getString(R.string.status_activating_keys)))
                }

                RedApiClient.signInWithClaveUnica(this@MainActivity, startInfo.sessionToken, profile)
                RedApiClient.enrollOrUpdateAccount(this@MainActivity)
                RedApiClient.requestC2dSeed(this@MainActivity)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "¡Cuenta activada con éxito!", Toast.LENGTH_SHORT).show()
                    mainViewModel.refreshConfig()
                    generarPasajeStandalone()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en flujo ClaveÚnica", e)
                withContext(Dispatchers.Main) {
                    mainViewModel.setError("Error: ${e.localizedMessage}")
                }
            }
        }
    }

    fun generarPasajeStandalone() {
        val cfg = mainViewModel.config.value
        if (!cfg.isProvisioned) {
            startClaveUnicaRemoteFlow()
            return
        }

        if (BipC2dEngine.isTramaValid(this)) {
            renderOfflineTicketNow()
            return
        }

        mainViewModel.setState(MainUiState.Loading(getString(R.string.status_requesting)))
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                RedApiClient.requestC2dSeed(this@MainActivity)
                withContext(Dispatchers.Main) {
                    mainViewModel.refreshConfig()
                    renderOfflineTicketNow()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    mainViewModel.setError("Error renovando semilla C2D: ${e.localizedMessage ?: "Sin red"}")
                }
            }
        }
    }

    fun renderOfflineTicketNow() {
        try {
            val result = BipC2dEngine.generateOfflineTicket(this)
            mainViewModel.setTicketActive(
                TicketData(
                    bitmap = result.bitmap,
                    expiryTimestampMs = result.tramaExpiryEpochMs,
                    refreshIntervalSec = result.refreshIntervalSec,
                    saldo = result.saldo
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error generando pasaje", e)
            mainViewModel.setError("Error C2D: ${e.localizedMessage}")
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val dataClient = Wearable.getDataClient(this)
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path ?: ""
                if (path.startsWith("/qr") || path.startsWith("/bip")) {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    lifecycleScope.launch(Dispatchers.IO) {
                        TicketManager.processDataMap(applicationContext, dataClient, dataMap)
                        mainViewModel.refreshConfig()
                    }
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val data = messageEvent.data

        if (path == PATH_STANDALONE_CONFIG && data != null && data.isNotEmpty()) {
            val jsonStr = String(data, StandardCharsets.UTF_8)
            if (mainViewModel.importConfigJson(jsonStr)) {
                Toast.makeText(this, "Cuenta bip!QR sincronizada", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (path == PATH_TICKET_ERROR) {
            val errorMsg = if (data != null && data.isNotEmpty()) {
                String(data, StandardCharsets.UTF_8)
            } else {
                getString(R.string.status_error)
            }
            mainViewModel.setError(errorMsg)
            return
        }

        if ((path.startsWith("/qr") || path.startsWith("/bip")) && data != null && data.isNotEmpty()) {
            TicketManager.processPayload(applicationContext, data)
            mainViewModel.refreshConfig()
        }
    }
}

@Composable
fun BipWearApp(
    activity: ComponentActivity,
    mainViewModel: MainViewModel,
    movementsViewModel: MovementsViewModel,
    onGenerateTicket: () -> Unit,
    onStartClaveUnica: () -> Unit
) {
    val context = LocalContext.current
    val ticketState by mainViewModel.uiState.collectAsState()
    val cfg by mainViewModel.config.collectAsState()
    val movementsState by movementsViewModel.uiState.collectAsState()

    val isTicketActive = ticketState is MainUiState.Active

    DisposableEffect(isTicketActive) {
        if (isTicketActive) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = activity.window.attributes
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            activity.window.attributes = params
        }
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = activity.window.attributes
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            activity.window.attributes = params
        }
    }

    Scaffold(timeText = { TimeText() }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            when (val state = ticketState) {
                is MainUiState.Idle -> {
                    val isOfflineReady = remember(cfg) { BipC2dEngine.isTramaValid(context) }
                    IdleScreen(
                        cfg = cfg,
                        isOfflineReady = isOfflineReady,
                        onGenerate = onGenerateTicket,
                        onRelink = onStartClaveUnica,
                        onRefreshDaleQr = {
                            mainViewModel.refreshDaleQr { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onViewMovements = { mainViewModel.setState(MainUiState.Movements) }
                    )
                }
                is MainUiState.Loading -> {
                    LoadingScreen(message = state.message)
                }
                is MainUiState.Active -> {
                    TicketScreen(
                        ticket = state.ticket,
                        onRefresh = onGenerateTicket,
                        onAutoRefreshTriggered = {
                            if (activity is MainActivity) activity.renderOfflineTicketNow()
                        }
                    )
                }
                is MainUiState.Movements -> {
                    MovementsScreen(
                        uiState = movementsState,
                        onRefresh = { movementsViewModel.refresh(forceNetwork = true) },
                        onBack = { mainViewModel.resetToIdle() }
                    )
                }
                is MainUiState.Error -> {
                    LaunchedEffect(state.errorMessage) {
                        Toast.makeText(context, state.errorMessage, Toast.LENGTH_LONG).show()
                        mainViewModel.resetToIdle()
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add wear/src/main/java/com/nowis/bipwear/MainActivity.kt
git commit -m "refactor: simplify MainActivity using MainViewModel and MovementsViewModel"
```

---

### Task 8: Verification & CI Integration

**Files:**
- Modify: `.github/workflows/build-apk.yml:35-37`

**Interfaces:**
- Consumes: All tests and Android build
- Produces: Verified unit tests and APK artifact on CI

- [ ] **Step 1: Update `.github/workflows/build-apk.yml` to run unit tests**

```yaml
      - name: Run Unit Tests and Build Wear OS Debug APK
        run: ./gradlew :wear:testDebugUnitTest :wear:assembleDebug --stacktrace
```

- [ ] **Step 2: Commit and push**

```bash
git add .github/workflows/build-apk.yml
git commit -m "ci: include testDebugUnitTest in build workflow"
git push origin main
```

- [ ] **Step 3: Monitor GitHub Actions run**

Check run status via GitHub CLI or web to ensure all tests pass and APK artifact is built cleanly.
