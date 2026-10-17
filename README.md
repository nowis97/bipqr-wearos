# bip!QR Wear OS Bridge (Red Movilidad)

Solución multi-módulo (`:mobile` + `:wear`) para desplegar boletos dinámicos **bip!QR** de la red de transporte metropolitano de Santiago, Chile, en relojes inteligentes con **Wear OS** (Samsung Galaxy Watch 4/5/6/7, Google Pixel Watch, TicWatch, etc.).

---

## 🏗️ Arquitectura de la Solución (App Puente)

Google Play Services Wearable Data Layer (`MessageClient` / `DataClient`) exige estrictamente que la aplicación en el teléfono y en el reloj inteligente compartan tanto el mismo **`applicationId`** como la **misma firma criptográfica (keystore)**.

Dado que la app oficial de Red Movilidad (`com.sonda.wiu`) está firmada con la llave privada de producción de SONDA y no permite instalar versiones debug paralelas en el teléfono, este proyecto implementa una **App Puente**:

```
[ Reloj Wear OS ] (:wear)
  │  (applicationId: com.nowis.bipwear)
  │
  │  1. Presionar "Generar Pasaje"
  ▼  (Envía mensaje Bluetooth /bip/request_ticket)
[ Teléfono - App Puente ] (:mobile)
  │  (applicationId: com.nowis.bipwear - misma firma debug)
  │
  │  2. Servicio de Accesibilidad detecta solicitud y abre Red Movilidad (com.sonda.wiu)
  │  3. Captura el boleto Aztec mediante takeScreenshot() (API 30+)
  │  4. Decodifica el Aztec con ZXing y genera imagen HD nítida para el reloj
  ▼  (Envía imagen comprimida vía /bip/ticket_data y /qrDataSync)
[ Reloj Wear OS ] (:wear)
  │  5. Muestra el código con brillo al 100% y temporizador de 120s
  ▼
[ Validador Metro / Bus ] (Lectura exitosa)
```

---

## 📦 Estructura del Proyecto

* **`:mobile`**: Aplicación complementaria para el teléfono Android. Contiene:
  * `MainActivity`: Panel de diagnóstico (estado de conexión del reloj, botón para probar envío de ticket demo).
  * `BipAccessibilityService`: Servicio de accesibilidad para automatizar la captura de pantalla del boleto en Red Movilidad sin tocar el teléfono.
  * `PhoneWearListenerService`: Servicio receptor en segundo plano que escucha peticiones del reloj por Bluetooth.
  * `AztecTicketProcessor`: Motor ZXing para decodificar y generar códigos Aztec nítidos de alto contraste.
* **`:wear`**: Aplicación para el smartwatch con Wear OS. Contiene:
  * `MainActivity`: Interfaz de usuario adaptada a pantallas redondas con botón de generación, visualización de código Aztec a brillo máximo y cuenta regresiva.
  * `BipWearListenerService`: Receptor en segundo plano para sincronizar boletos de forma automática.
  * `TicketManager`: Manejador de estado y decodificador de datos binarios y Assets.

---

## 🚀 Guía de Instalación y Uso

### 1. Requisitos
* Android Studio (Koala, Jellyfish, Iguana o superior) con SDK 34.
* Teléfono Android con la app oficial **Red Movilidad** instalada y con sesión iniciada en bip!QR.
* Reloj con **Wear OS** emparejado con tu teléfono por Bluetooth.

### 2. Compilar e Instalar en el Teléfono
1. Conecta tu teléfono por USB o Wi-Fi con depuración activada.
2. En Android Studio, selecciona la configuración de ejecución **`mobile`** y presiona **Run** (o ejecuta en terminal):
   ```powershell
   ./gradlew :mobile:installDebug
   ```
3. Abre la app **bip!QR Puente** en tu teléfono.
4. Presiona **"Activar Servicio de Accesibilidad"** y habilita el servicio **Puente bip!QR Wear OS**.
5. Verifica que en la tarjeta del reloj indique **"Conectado a: [Nombre de tu reloj]"**.
6. (Opcional) Presiona **"Enviar Código de Prueba al Reloj"** para verificar la comunicación Bluetooth.

### 3. Compilar e Instalar en el Reloj
1. En tu reloj Wear OS, ve a **Ajustes > Opciones de desarrollador** y activa **Depuración inalámbrica (Wi-Fi)**.
2. Conecta tu PC al reloj vía ADB:
   ```powershell
   adb connect <IP_DEL_RELOJ>:<PUERTO>
   ```
3. En Android Studio, selecciona la configuración de ejecución **`wear`** y presiona **Run** (o ejecuta en terminal):
   ```powershell
   ./gradlew :wear:installDebug
   ```

### 4. Uso Diario
1. En tu reloj, abre **bip!QR**.
2. Presiona **"Generar Pasaje"**.
3. El reloj se comunicará por Bluetooth con la app puente en el teléfono, la cual capturará el boleto oficial y se lo enviará al reloj en menos de 2 segundos.
4. Acerca la pantalla de tu reloj al torniquete del Metro o al validador del bus.
