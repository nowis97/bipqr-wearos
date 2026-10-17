# bip!QR Wear OS Companion (Red Movilidad)

Aplicación complementaria para relojes inteligentes con **Wear OS** (Samsung Galaxy Watch 4/5/6/7, Google Pixel Watch, TicWatch, etc.) para sincronizar y desplegar boletos dinámicos **bip!QR** de la red de transporte metropolitano de Santiago, Chile.

---

## 🔍 Contexto y Arquitectura Descubierta

Mediante ingeniería inversa sobre el APK oficial de **Red Movilidad** (`com.sonda.wiu` v3.26.1):
1. Se descubrió que la aplicación oficial **ya contiene el módulo de integración para Wear OS** utilizando el canal `flutter_wear_os_connectivity` y las APIs de Google Play Services Wearable (`NodeClient`, `MessageClient`).
2. La aplicación utiliza el protocolo:
   - **`/qrGenerationRequest`**: Solicitud enviada por el reloj al teléfono vía Bluetooth.
   - **`/qrDataSync`**: Trama enviada por el teléfono al reloj con la imagen comprimida del **Aztec Code** y la vigencia temporal.
3. Para que Google Play Services permita la comunicación entre el teléfono y el reloj, **ambas aplicaciones deben compartir el mismo `applicationId`** (`com.sonda.wiu`).

---

## 📋 Requisitos Previos

1. **Android Studio** (Hedgehog, Iguana, Jellyfish o superior) con SDK de Android 34 instalado.
2. Teléfono Android con la app oficial **Red Movilidad** instalada y sesión iniciada con ClaveÚnica.
3. **Importante (PIN):** En la app de Red Movilidad en el teléfono, ve a *Ajustes > Pagar con QR > Desactivar PIN* (o déjalo sin PIN configurado). La app oficial requiere esto para que el servicio en segundo plano pueda firmar el pasaje con la llave privada ECDSA automáticamente cuando el reloj lo solicite.
4. Reloj con **Wear OS** emparejado con tu teléfono.

---

## 🚀 Cómo Compilar e Instalar

### Paso 1: Abrir en Android Studio
1. Abre Android Studio.
2. Selecciona **Open** y navega a la carpeta:
   `c:\Users\NOWIS\Documents\bipqr-wearos`
3. Deja que Gradle sincronice las dependencias (`play-services-wearable`, AndroidX Wear, etc.).

### Paso 2: Activar Depuración en el Reloj
1. En tu reloj Wear OS, ve a:
   **Ajustes > Información del reloj > Software** y presiona repetidamente (7 veces) en **Número de compilación** hasta activar el modo desarrollador.
2. Ve a **Ajustes > Opciones de desarrollador**:
   - Activa **Depuración ADB**.
   - Activa **Depuración inalámbrica (Wi-Fi)**.
3. Anota la dirección IP y el puerto que muestra la pantalla (ejemplo: `192.168.1.50:5555`).

### Paso 3: Conectar por ADB e Instalar
En tu terminal de PowerShell:
```powershell
adb connect 192.168.1.50:5555
```
*(Acepta la confirmación de huella digital que aparecerá en la pantalla del reloj).*

Compila e instala directamente desde Android Studio seleccionando tu reloj como dispositivo destino, o vía comando:
```powershell
./gradlew installDebug
```

---

## 🎫 Cómo Usar

1. Abre la app **bip!QR** en el reloj.
2. Presiona el botón rojo **"Generar Pasaje"**.
3. El reloj enviará la señal `/qrGenerationRequest` a tu teléfono por Bluetooth.
4. Tu teléfono contactará al servidor de Sonda, inyectará la telemetría GPS, firmará con ECDSA y enviará la imagen del código Azteca de regreso al reloj vía `/qrDataSync`.
5. La pantalla del reloj se pondrá con brillo al 100% mostrando el código de barras y un contador regresivo de vigencia.
6. ¡Acerca la pantalla del reloj al validador del bus o torniquete de Metro para validar tu pasaje!
