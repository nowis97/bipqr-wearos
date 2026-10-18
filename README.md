# bip!QR Wear OS Standalone (Red Movilidad)

Aplicación **100% Standalone** (`:wear` + `:mobile`) para generar pasajes dinámicos **bip!QR** de la red de transporte público de Santiago, Chile, directamente en relojes inteligentes con **Wear OS** (Samsung Galaxy Watch 4/5/6/7, Google Pixel Watch, etc.) sin necesidad de abrir ni despertar el teléfono.

---

## 🏗️ Arquitectura Standalone (`libc2d` + Red API v13)

A diferencia de enfoques basados en captura de pantalla, este proyecto implementa de forma nativa el motor criptográfico **Sonda `libc2d`** (`ECDSA secp224r1` con `SHA224withECDSA`) y el protocolo de semillas de 2 horas (`tramaSistemaCentral`):

1. **Vinculación Única (`:mobile`)**:
   * Inicia sesión por única vez mediante **ClaveÚnica** (`/phone/v13/claveunica/start` → `/claveunica/isLogged` → `/user/signIn`).
   * Genera un par de llaves **EC 224-bit (`secp224r1`)** y lo registra en la cuenta bip!QR (`/payment/qr/account/enrollment` / `/payment/qr/account/update`).
   * Obtiene la semilla inicial de 2 horas (`/payment/qr/account/generatec2d`) y sincroniza la configuración criptográfica (`StandaloneConfig`) al reloj vía `DataClient` / `MessageClient` (`/bip/standalone_config`).
2. **Generación 100% Local en el Reloj (`:wear`)**:
   * Al tocar **"Generar Pasaje"** en el reloj, **`BipC2dEngine`** construye el bloque telemétrico en little-endian (latitud, longitud, timestamp UTC sincronizado y precisión), firma los 53 bytes con `SHA224withECDSA` y renderiza el código **`BarcodeFormat.AZTEC`** en **menos de 50 ms, 100% offline**.
   * Mientras el pasaje está en pantalla, el reloj rota automáticamente la firma dinámica cada **5 segundos** (`tiempoRefrescoC2D`) con brillo al 100%.
   * Cuando la semilla de 2 horas expira, el reloj la renueva directamente vía HTTPS (`RedApiClient.requestC2dSeed`).

---

## 📦 Estructura del Proyecto

* **`:wear`**: App standalone para el smartwatch Wear OS:
  * `BipC2dEngine.kt`: Motor criptográfico `secp224r1` + `SHA224withECDSA` y generador offline de códigos Aztec C2D.
  * `RedApiClient.kt`: Cliente HTTPS directo para renovar la semilla `tramaSistemaCentral` cada 2 horas.
  * `MainActivity.kt`: UI adaptada a pantallas redondas con generación instantánea, rotación cada 5s, visualización de saldo y brillo máximo.
* **`:mobile`**: App de vinculación inicial para el teléfono Android:
  * `MainActivity.kt`: Flujo de autenticación con ClaveÚnica en `WebView`, enrolamiento de llave C2D y sincronización con el reloj.
  * `PhoneWearListenerService.kt`: Respaldo silencioso en segundo plano para renovar la semilla de 2 horas si el reloj no dispone de Wi-Fi/LTE directo.
