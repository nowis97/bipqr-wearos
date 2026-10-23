# bip!QR Wear OS (Red Movilidad) ⌚🚇

[![Build and Release Watch APK](https://github.com/nowis97/bipqr-wearos/actions/workflows/build-apk.yml/badge.svg)](https://github.com/nowis97/bipqr-wearos/actions/workflows/build-apk.yml)
[![Latest Release](https://img.shields.io/github/v/release/nowis97/bipqr-wearos?logo=github)](https://github.com/nowis97/bipqr-wearos/releases/latest)
![Wear OS](https://img.shields.io/badge/Platform-Wear%20OS%20(API%2026%2B)-blue?logo=wearos)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20Wear-green?logo=jetpackcompose)
![Architecture](https://img.shields.io/badge/Architecture-MVVM%20%7C%20100%25%20Standalone-success)
![Cryptography](https://img.shields.io/badge/Crypto-ECDSA%20secp224r1%20%7C%20SHA224-orange)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Aplicación **100% Autónoma y Standalone** para generar pasajes dinámicos **bip!QR** de la red de transporte público de Santiago de Chile (Metro, micros Red y Tren Nos) directamente en tu reloj inteligente con **Wear OS**.

> [!IMPORTANT]
> **Sin aplicaciones complementarias en el teléfono.** El reloj gestiona la autenticación, la criptografía y la generación de pasajes de manera totalmente independiente.

---

## ✨ Características Principales

* **Generación 100% Offline (<50 ms):** Una vez sincronizada la semilla de 2 horas, el reloj genera y firma el código Aztec localmente en milisegundos, sin requerir conexión a internet ni tener el teléfono cerca.
* **Criptografía Nativa Sonda `libc2d`:** Implementación exacta en curva elíptica **`secp224r1` (`P-224`)** con firmas **`SHA224withECDSA`**, compatible con los validadores y torniquetes físicos de la red.
* **Modo Torniquete Rápido:** Al abrir la aplicación desde el menú del reloj, salta directamente a la pantalla con el código Aztec listo para escanear, con navegación intuitiva hacia atrás (`IdleScreen`) con el botón físico.
* **Feedback Háptico en la Muñeca:** Emite un pulso táctil sutil (`EFFECT_CLICK` / 45 ms) exactamente cuando el código QR termina de renderizarse para saber que puedes pasar por el lector sin mirar la pantalla.
* **Wear OS Tile (Tarjeta Deslizable):** Tarjeta nativa en el carrusel de Wear OS con saldo disponible en CLP, anillo exterior de 300° con el progreso mensual de **Dale QR** (meta de $42.000 con viajes gratis) y botón de acceso rápido.
* **Complicaciones de Carátula:** Atajos directos para la esfera de tu reloj en formatos `SHORT_TEXT` (saldo CLP) y `RANGED_VALUE` (progreso Dale QR 0..$42.000).
* **Rotación Dinámica cada 5 segundos:** Rota la firma telemétrica en tiempo real mientras el pasaje está visible en pantalla, con brillo al 100% para una lectura óptica inmediata.
* **Vinculación con ClaveÚnica vía Remote Intent:** El reloj solicita la apertura oficial de ClaveÚnica en el navegador de tu teléfono (`RemoteActivityHelper`). Escribes tus datos en la pantalla grande del móvil y el reloj detecta el inicio de sesión automáticamente.
* **Historial de Últimos Movimientos:** Lista optimizada (`ScalingLazyColumn`) que muestra tus viajes recientes en bus (🚌), metro (🚇), tren (🚆) y cargas (💳), con montos, fechas formateadas y claves estables.
* **Compilación Automatizada:** GitHub Actions compila automáticamente la APK y ejecuta los 31 tests unitarios con cada commit y release.

---

## 🔍 ¿Cómo Funciona por Debajo? (Deep Dive Criptográfico)

El sistema de validación bip!QR de Santiago está diseñado bajo una arquitectura de **criptografía asimétrica de doble nivel** desarrollada por Sonda:

```
┌─────────────────────────────────────────────────────────────┐
│ 1. AUTENTICACIÓN (Navegador Móvil + OAuth)                  │
│    El usuario inicia sesión en ClaveÚnica en el teléfono.   │
│    El reloj obtiene el Session-Token oficial de Red.        │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ 2. DESCARGA DE SEMILLA CENTRAL (Internet)                   │
│    El reloj solicita /payment/qr/account/generatec2d.       │
│    Sonda emite una "tramaSistemaCentral" (62 caracteres hex)│
│    firmada digitalmente por el HSM de Sonda (dura 2 horas). │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ 3. FIRMA LOCAL EN EL RELOJ (100% Offline)                   │
│    Cada 5 segundos, el reloj construye el bloque:           │
│    [tramaSC (15B)] + [Lat/Lon/Epoch/Acc (22B)]              │
│    Firma con llave secp224r1 local -> Código Aztec.         │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ 4. LECTURA EN TORNIQUETE (Validador Físico)                 │
│    El tótem lee el código Aztec:                            │
│    - Valida firma de Sonda (SAM del validador).             │
│    - Valida firma del reloj (llave pública del usuario).    │
│    - ¡Luz verde y torniquete abierto!                       │
│    Sonda descuenta el pasaje en su backend central.         │
└─────────────────────────────────────────────────────────────┘
```

### 1. La Anatomía del Código Aztec
El código de barras Aztec generado por el reloj contiene exactamente:
1. **Prefijo de la Semilla Central (15 bytes / 30 caracteres hex):** Identificador de la cuenta (`nuAbt`), número de operador (29), aplicación (1) y vigencia temporal.
2. **Bloque de Telemetría (22 bytes / 44 caracteres hex):**
   * Latitud y Longitud truncadas a 6 decimales en formato IEEE-754 `double` little-endian (8 bytes c/u).
   * Marca de tiempo UNIX (`epochSec`) en formato little-endian de 4 bytes (actualizado cada 5s).
   * Precisión del posicionamiento en entero little-endian de 2 bytes.
3. **Firma Digital ECDSA (~56 bytes):** Firma generada con la curva elíptica **`secp224r1`** (`SHA224withECDSA`) sobre los 53 bytes totales de la trama.

### 2. ¿Por qué es Imposible Falsificar Pasajes?
* **Firma del Servidor:** La `tramaSistemaCentral` está firmada por el HSM de Sonda. El validador físico del Metro o bus verifica esta firma con su llave pública interna. Modificar un solo bit de la trama hace que la validación falle inmediatamente (*"Código Inválido"*).
* **El saldo no va en el QR:** El valor `saldo` en el reloj es meramente informativo para la pantalla. El cobro monetario (\$800-\$870 CLP) no se descuenta en el código; el torniquete registra la transacción y Sonda la descuenta de tu saldo real en su base de datos central.
* **Caducidad estricta:** La semilla tiene una validez fija de 2 horas. Pasado ese tiempo, el validador la rechaza.

### 3. La Llave de Contingencia (`ALTERNATIVE_PRIV_KEY_B64`)
En el archivo `BipC2dEngine.kt` existe una llave privada hardcodeada extraída directamente de la librería oficial de Sonda (`com.sonda.libc2d.alternativo.e`). Esta llave fue diseñada por Sonda para casos en que validadores en terreno no hayan alcanzado a sincronizar las llaves públicas de usuarios recién creados:
* El **byte 14** de la semilla central (`tramaSC.substring(28, 30)`) actúa como selector: si el bit 0 está activo (`(keySelector and 1) > 0`), el sistema firma con la llave alternativa genérica que todos los validadores conocen de fábrica.
* En condiciones normales, el sistema utiliza la llave privada única generada localmente por el reloj (`cfg.privKeyBase64`).

---

## 📱 Compatibilidad

Compatible con cualquier smartwatch con **Wear OS 2.0 / 3.0 / 4.0 / 5.0 (Android 8.0+ / API 26+)**:
* **Samsung:** Galaxy Watch 4, Watch 5, Watch 6, Watch 7 y Watch Ultra (todos los tamaños y variantes Bluetooth/LTE).
* **Google:** Pixel Watch 1, Pixel Watch 2 y Pixel Watch 3.
* **Xiaomi:** Xiaomi Watch 2, Xiaomi Watch 2 Pro.
* **Mobvoi:** TicWatch Pro 3, Pro 5, E3.
* **OnePlus:** OnePlus Watch 2 / 2R.

---

## 📥 Descarga de la APK

Puedes descargar la última versión compilada:

* **GitHub Releases (Recomendado):**  
  Descarga el archivo [**`bipqr-watch.apk`** en la página de Releases](https://github.com/nowis97/bipqr-wearos/releases/latest).

---

## 🛠️ Instalación en tu Reloj

Para instalar el archivo `bipqr-watch.apk` en tu smartwatch:

### Opción A: Desde tu teléfono con "GeminiMan Wear OS Manager" o "Wear Installer 2" (Recomendada)
1. Instala en tu teléfono una app para sideload como **GeminiMan Wear OS Manager** o **Wear Installer 2** (disponibles en Google Play Store).
2. En tu reloj, ve a **Ajustes > Opciones de desarrollador** y activa **Depuración ADB** y **Depuración inalámbrica (Wi-Fi)**.
3. Conecta la app del teléfono al reloj mediante la IP y puerto que muestra el reloj.
4. Selecciona el archivo `bipqr-watch.apk` descargado en tu teléfono y presiona **Instalar**.

### Opción B: Mediante ADB por línea de comandos (PC)
1. Activa la depuración Wi-Fi en el reloj (**Ajustes > Opciones de desarrollador > Depuración inalámbrica**).
2. En tu PC, conecta ADB al reloj:
   ```bash
   adb connect <IP_DEL_RELOJ>:<PUERTO>
   ```
3. Instala la APK:
   ```bash
   adb install -r bipqr-watch.apk
   ```

---

## 🚀 Guía de Uso

```
[ Primera Vez ]
  1. Abre bip!QR en tu reloj.
  2. Toca "Vincular ClaveÚnica".
  3. Tu teléfono abrirá automáticamente Chrome/navegador con la página oficial de ClaveÚnica.
  4. Ingresa tu RUT y contraseña en el navegador del teléfono.
  5. ¡Listo! El reloj detecta la sesión automáticamente y sincroniza tu cuenta.

[ Uso Diario en Torniquetes ]
  * Opción 1 (Modo Rápido): Abre bip!QR en el reloj -> El código se genera al instante -> Sientes la vibración en la muñeca -> ¡Acerca el reloj al lector!
  * Opción 2 (Wear OS Tile): Desliza a la izquierda en tu reloj hasta ver la tarjeta bip!QR y toca "Generar QR".
  * Opción 3 (Complicación): Toca el atajo de saldo en la carátula de tu reloj.
```

---

## 📂 Arquitectura del Código

El proyecto sigue una arquitectura **MVVM limpia y desacoplada** sin frameworks de inyección pesados:

```
wear/src/main/
├── AndroidManifest.xml          # Permisos (VIBRATE, INTERNET) y declaración de servicios Tile/Complication
├── java/com/nowis/bipwear/
│   ├── MainActivity.kt          # Punto de entrada, fast-path de apertura y botón físico
│   ├── BipC2dEngine.kt          # Motor criptográfico ECDSA secp224r1 y generador Aztec
│   ├── RedApiClient.kt          # Cliente HTTP para APIs de Red Movilidad y ClaveÚnica
│   ├── TicketManager.kt         # Gestor de ciclo de vida del ticket en memoria
│   ├── complication/            # Servicio y helper de Complicaciones para Watch Faces
│   │   ├── BipComplicationService.kt
│   │   └── BipComplicationHelper.kt
│   ├── data/                    # Abstracción de repositorio (Data layer)
│   │   └── BipRepository.kt
│   ├── tile/                    # Servicio Wear OS Tile, layout y disparador de actualizaciones
│   │   ├── BipTileService.kt
│   │   ├── BipTileLayoutHelper.kt
│   │   └── BipSurfaceUpdateHelper.kt
│   ├── ui/                      # Pantallas Jetpack Compose modulares y sin estado
│   │   ├── IdleScreen.kt
│   │   ├── TicketScreen.kt
│   │   ├── MovementsScreen.kt
│   │   ├── LoadingScreen.kt
│   │   └── state/UiStates.kt
│   ├── util/                    # Helper de feedback háptico (VibrationEffect)
│   │   └── BipHapticHelper.kt
│   └── viewmodel/               # ViewModels con StateFlow y corrutinas
│       ├── MainViewModel.kt
│       └── MovementsViewModel.kt
└── res/
    └── values/strings.xml       # Textos y recursos localizados
```

---

## 🧪 Pruebas y Compilación Local

Para compilar el proyecto y ejecutar la suite completa de 31 pruebas unitarias:

```bash
# Ejecutar pruebas unitarias deterministas
./gradlew :wear:testDebugUnitTest

# Compilar APK Debug
./gradlew :wear:assembleDebug
```

---

## ⚖️ Descargo de Responsabilidad Legal

* Este proyecto es una iniciativa de código abierto desarrollada con fines estrictamente **educativos y de interoperabilidad técnica**, amparada bajo el **Artículo 71 Ñ de la Ley N° 17.336 de Propiedad Intelectual de Chile**.
* No tiene afiliación, patrocinio ni relación oficial con el Ministerio de Transportes y Telecomunicaciones (MTT), Directorio de Transporte Público Metropolitano (DTPM), Metro de Santiago ni SONDA S.A.
* **La aplicación no comete fraude ni evasión:** utiliza las credenciales legítimas del usuario mediante ClaveÚnica y debita el importe oficial del pasaje de los fondos o saldo del usuario en el sistema de transporte.
* Todas las marcas registradas pertenecen a sus respectivos titulares.

---

## 📄 Licencia

Este proyecto está liberado bajo la Licencia **MIT**. Consulta el archivo [LICENSE](LICENSE) para conocer los términos completos.
