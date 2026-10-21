# bip!QR Wear OS (Red Movilidad) ⌚🚇

[![Build and Release Watch APK](https://github.com/nowis97/bipqr-wearos/actions/workflows/build-apk.yml/badge.svg)](https://github.com/nowis97/bipqr-wearos/actions/workflows/build-apk.yml)
![Wear OS](https://img.shields.io/badge/Platform-Wear%20OS%20(API%2026%2B)-blue?logo=wearos)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20Wear-green?logo=jetpackcompose)
![Architecture](https://img.shields.io/badge/Architecture-100%25%20Standalone-success)
![Cryptography](https://img.shields.io/badge/Crypto-ECDSA%20secp224r1%20%7C%20SHA224-orange)

Aplicación **100% Autónoma y Standalone** para generar pasajes dinámicos **bip!QR** de la red de transporte público de Santiago de Chile (Metro, micros Red y Tren Nos) directamente en tu reloj inteligente con **Wear OS**.

> [!IMPORTANT]
> **Sin aplicaciones complementarias en el teléfono.** El reloj gestiona la autenticación, la criptografía y la generación de pasajes de manera totalmente independiente.

---

## ✨ Características Principales

* **Generación 100% Offline (<50 ms):** Una vez sincronizada la semilla de 2 horas, el reloj genera y firma el código Aztec localmente en milisegundos, sin requerir conexión a internet ni tener el teléfono cerca.
* **Criptografía Nativa Sonda `libc2d`:** Implementación exacta en curva elíptica **`secp224r1` (`P-224`)** con firmas **`SHA224withECDSA`**, compatible con los validadores y torniquetes físicos de la red.
* **Rotación Dinámica cada 5 segundos:** Rota la firma telemétrica en tiempo real mientras el pasaje está visible en pantalla, con brillo al 100% para una lectura óptica inmediata.
* **Vinculación con ClaveÚnica vía Remote Intent:** Al iniciar sesión por primera vez, el reloj envía una orden nativa por Bluetooth (`RemoteActivityHelper`) para abrir la página oficial de ClaveÚnica en el navegador de tu teléfono (Chrome, Samsung Internet, etc.). Escribes tu clave con comodidad en la pantalla grande y el reloj detecta el éxito automáticamente.
* **Visualización de Saldo y Dale QR:** Muestra tu saldo bip!QR actualizado en pesos chilenos (`$`) y el avance mensual del beneficio **Dale QR** (Monto Máximo Mensual de `$42.000` con barra de progreso e insignia de viajes gratis).
* **Compilación Automatizada:** GitHub Actions compila automáticamente la APK lista para instalar con cada cambio.

---

## 📱 Compatibilidad

Compatible con cualquier smartwatch con **Wear OS 2.0 / 3.0 / 4.0 / 5.0 (Android 8.0+ / API 26+)**:
* **Samsung:** Galaxy Watch 4, Watch 5, Watch 6, Watch 7 y Watch Ultra.
* **Google:** Pixel Watch 1, Pixel Watch 2 y Pixel Watch 3.
* **Xiaomi:** Xiaomi Watch 2, Xiaomi Watch 2 Pro.
* **Mobvoi:** TicWatch Pro 3, Pro 5, E3.
* **OnePlus:** OnePlus Watch 2 / 2R.

---

## 📥 Descarga de la APK

Puedes descargar la última versión compilada de dos formas:

1. **Desde GitHub Releases (Recomendado):**  
   Ve a la pestaña [**Releases**](https://github.com/nowis97/bipqr-wearos/releases) y descarga el archivo `bipqr-watch.apk`.
2. **Desde GitHub Actions (Último commit):**  
   Entra a [**Actions**](https://github.com/nowis97/bipqr-wearos/actions), haz clic en la última ejecución exitosa de *Build and Release Watch APK* y descarga el artefacto **`bipqr-watch-apk`**.

---

## 🛠️ Instalación en tu Reloj

Para instalar el archivo `bipqr-watch.apk` en tu smartwatch:

### Opción A: Desde tu teléfono con "GeminiMan Wear OS Manager" o "Wear Installer 2" (Fácil)
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
  3. Tu teléfono abrirá automáticamente Chrome/navegador con ClaveÚnica.
  4. Ingresa tu RUT y clave en el teléfono.
  5. ¡Listo! El reloj se activa solo y descarga tu saldo.

[ Uso Diario en Metro / Micro ]
  1. Abre bip!QR en el reloj.
  2. Presiona "Generar Pasaje" (o se genera automáticamente si la semilla está vigente).
  3. Acerca la pantalla del reloj al lector del torniquete o validador.
```

> [!TIP]
> **Cambiar o re-vincular cuenta:** Mantén presionado el botón principal en la pantalla de inicio del reloj para volver a lanzar la vinculación con ClaveÚnica en cualquier momento.

---

## 🔬 Arquitectura Técnica y Protocolo C2D

1. **Flujo de Autenticación:**
   - `POST /phone/v13/claveunica/start`: Inicia la sesión OAuth enviando el identificador UUID estándar (`phoneId`).
   - `GET /phone/v13/claveunica/isLogged`: Monitoreo en segundo plano hasta que el usuario complete el inicio de sesión.
   - `POST /phone/v13/user/signIn`: Registro de sesión y obtención del `Session-Token`.
2. **Enrolamiento Criptográfico (`libc2d`):**
   - Generación de un par de llaves en curva elíptica **`secp224r1`** mediante BouncyCastle (`com.sonda.libc2d`).
   - `POST /phone/v13/payment/qr/account/enrollment` o `/update`: Firma de los datos de enrolamiento con `SHA224withECDSA` y registro de la llave pública.
3. **Semilla de 2 Horas y Generación Offline:**
   - `POST /phone/v13/payment/qr/account/generatec2d`: Descarga de la `tramaSistemaCentral` (62 caracteres hex) y sincronización del desfase horario del servidor (`deltaEpochMs`).
   - Construcción del bloque telemétrico de 22 bytes (latitud, longitud, epoch ajustado y precisión en IEEE-754 little-endian).
   - Firma de los 53 bytes con la llave privada `secp224r1` y renderizado directo en formato **`BarcodeFormat.AZTEC`** vía ZXing.

---

## 📂 Estructura del Código

```
wear/src/main/
├── AndroidManifest.xml          # Configuración Wear OS (standalone=true)
├── java/com/nowis/bipwear/
│   ├── MainActivity.kt          # UI reactiva con Jetpack Compose for Wear OS
│   ├── BipC2dEngine.kt          # Motor criptográfico ECDSA y generador Aztec
│   ├── RedApiClient.kt          # Cliente HTTP Red Movilidad y ClaveÚnica
│   ├── TicketManager.kt         # Gestión de estados del pasaje y caché
│   └── BipWearListenerService.kt# Receptor en segundo plano Wearable
└── res/
    └── values/strings.xml       # Textos y estados de la interfaz
```

---

## ⚖️ Descargo de Responsabilidad

Este proyecto es una iniciativa independiente desarrollada con fines educativos y de interoperabilidad tecnológica. No tiene afiliación oficial con el Ministerio de Transportes y Telecomunicaciones (DTPM), SONDA S.A., Metro de Santiago ni Red Movilidad. Todas las marcas registradas pertenecen a sus respectivos propietarios.
