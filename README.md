# bip!QR Wear OS Standalone (Red Movilidad)

Aplicación **100% Autónoma y Standalone** para generar pasajes dinámicos **bip!QR** de la red de transporte público de Santiago, Chile, directamente en relojes inteligentes con **Wear OS** (Samsung Galaxy Watch 4/5/6/7, Google Pixel Watch, etc.) **sin requerir ninguna app instalada en el teléfono**.

---

## 🏗️ Arquitectura 100% Reloj (`libc2d` + Red API v13)

A diferencia de enfoques basados en captura de pantalla o apps puente obligatorias en el teléfono, este proyecto implementa de forma nativa el motor criptográfico **Sonda `libc2d`** (`ECDSA secp224r1` con `SHA224withECDSA`) y gestiona todo el ciclo de vida desde el propio smartwatch:

1. **Vinculación Autónoma vía Remote Intent**:
   * Al abrir la app en el reloj por primera vez, presionas **"Vincular ClaveÚnica"**.
   * El reloj solicita la sesión a Red Movilidad (`/claveunica/start`) y utiliza **`RemoteActivityHelper`** para abrir automáticamente la página oficial de ClaveÚnica en el navegador predeterminado de tu teléfono (Chrome, Samsung Internet, etc.).
   * **¡Cero apps en el teléfono!** El teléfono únicamente abre su navegador web para que digites tu RUT y clave con el teclado grande.
   * El reloj detecta automáticamente en segundo plano la finalización del login, genera su par de llaves **EC 224-bit (`secp224r1`)**, registra la llave pública en tu cuenta bip!QR (`/payment/qr/account/enrollment` / `/update`) y descarga la semilla de 2 horas.
2. **Generación 100% Local y Offline en el Reloj**:
   * Al tocar **"Generar Pasaje"** en el reloj, **`BipC2dEngine`** construye el bloque telemétrico en little-endian (latitud, longitud, timestamp UTC sincronizado y precisión), firma los 53 bytes con `SHA224withECDSA` y renderiza el código **`BarcodeFormat.AZTEC`** en **menos de 50 ms, 100% offline**.
   * Mientras el pasaje está en pantalla, el reloj rota automáticamente la firma dinámica cada **5 segundos** (`tiempoRefrescoC2D`) con brillo al 100%.
   * Cuando la semilla de 2 horas expira, el reloj la renueva directamente vía HTTPS (`RedApiClient.requestC2dSeed`).

---

## 📦 Estructura del Proyecto

* **`:wear`**: Aplicación principal standalone para el smartwatch Wear OS (100% autónoma).
  * `MainActivity.kt`: UI adaptada a pantallas redondas con inicio de sesión remoto en navegador de teléfono, generación instantánea, rotación cada 5s y visualización de saldo.
  * `BipC2dEngine.kt`: Motor criptográfico `secp224r1` + `SHA224withECDSA` y generador offline de códigos Aztec C2D.
  * `RedApiClient.kt`: Cliente HTTPS directo para ClaveÚnica y renovación de la semilla `tramaSistemaCentral`.
* **`:mobile`**: (Opcional) App complementaria para teléfonos Android para quienes prefieran gestionar su cuenta desde el teléfono.
