# JAL-SENSE — Water Quality & Treatment Monitoring Platform

**JAL-SENSE** is an IoT-enabled water quality monitoring and treatment platform developed for Smart India Hackathon (SIH). The system provides end-to-end monitoring across multiple water tanks (input, output, and level monitoring) using LoRa-based telemetry, an ESP32 edge gateway, and a modern native Android application built with Jetpack Compose.

---

## 🌟 Key Features

### 📱 Android Application
- **Modern Jetpack Compose UI**: Designed with Material 3, custom typography, clean telemetry cards, and responsive state navigation.
- **Dual-Tank & Level Monitoring**:
  - **Input Tank**: Real-time pH, TDS (ppm), Turbidity (NTU), Temperature (°C), and pump/relay status.
  - **Output Tank**: Processed water parameters including pH, TDS, Turbidity, and post-treatment relay status.
  - **Output Level**: Ultrasonic tank level percentage, distance (cm), and data freshness monitoring (stale alert if >90s without updates).
- **Zero-Falsification Data Integrity**: Explicit `SensorValue` semantics (`Valid`, `Fault`, `Missing`). Faulty or disconnected sensors will never falsely read as `0.00`.
- **Live Alert Management**: Automatically surfaces transmitter/sensor fault alerts (e.g. `ADC_RANGE`, `TIMEOUT`) into an operator triage view with acknowledge and resolve workflows.
- **Configurable Gateway**: Dynamic Wi-Fi IP and endpoint configuration via the in-app Settings screen without recompiling.
- **LoRa Link Diagnostics**: Visual indicators for gateway connectivity, LoRa link status, and packet RSSI in dBm.

### 📡 LoRa Transmitter Firmware (`JAL_SENSE_SX1278_TX.ino`)
- Developed for **ESP32-S3** paired with an **SX1278** 433 MHz LoRa transceiver.
- Register-level diagnostics for operation mode (`REG_OP_MODE`), IRQ flags (`REG_IRQ_FLAGS`), and modem configuration.
- Robust SPI transaction handling and automatic IRQ flag clearing preventing false-positive transmission states.
- Push-button (GPIO 0 BOOT) and serial triggers (`S`/`s`) for diagnostic telemetry transmissions.

---

## 🏗️ System Architecture

```
┌───────────────────────────────┐
│     Sensors & Field Nodes     │
│ (pH, TDS, Turbidity, Temp, US)│
└───────────────┬───────────────┘
                │
                ▼
┌───────────────────────────────┐
│  ESP32-S3 + SX1278 LoRa TX    │  ─── LoRa (433 MHz, SF7, BW 125kHz) ───►
└───────────────────────────────┘
                                                                           │
                                                                           ▼
                                                            ┌──────────────────────────────┐
                                                            │     ESP32 Gateway / Server   │
                                                            │  HTTP REST (/api/v1/measure) │
                                                            └──────────────┬───────────────┘
                                                                           │
                                                                     Wi-Fi / LAN
                                                                           │
                                                                           ▼
                                                            ┌──────────────────────────────┐
                                                            │    JAL-SENSE Android App     │
                                                            │ (Jetpack Compose, Live View) │
                                                            └──────────────────────────────┘
```

---

## 📋 Pinout & Hardware Configuration

### ESP32-S3 to SX1278 (SPI Wiring)

| SX1278 Pin | ESP32-S3 GPIO | Description |
| :--- | :--- | :--- |
| **SCK** | `GPIO 12` | SPI Clock |
| **MISO** | `GPIO 13` | SPI Master In / Slave Out |
| **MOSI** | `GPIO 11` | SPI Master Out / Slave In |
| **NSS / SS** | `GPIO 10` | Chip Select (Slave Select) |
| **RST** | `GPIO 9` | Hardware Reset |
| **DIO0** | `GPIO 8` | Packet Rx/Tx Interrupt |
| **VCC** | `3.3V` | Supply Voltage |
| **GND** | `GND` | Ground |
| **BOOT** | `GPIO 0` | Manual Trigger Button |

---

## 🚀 Getting Started

### 1. Android Application Setup

#### Prerequisites
- Android Studio Ladybug / Iguana or newer
- JDK 17 or higher
- Android SDK (API 24 minimum, target API 34+)

#### Build & Run
1. Open the project folder in Android Studio:
   ```bash
   git clone https://github.com/aryamangale1710/JalSense.git
   ```
2. Allow Gradle sync to complete.
3. To compile via command line with Gradle:
   ```bash
   ./gradlew assembleDebug
   ```
4. Install on your connected Android device or emulator:
   ```bash
   ./gradlew installDebug
   ```
5. Ensure your phone is connected to the same Wi-Fi network as the ESP32 gateway. Go to **Settings** in the app and configure the Gateway URL (default: `http://10.222.230.15/api/v1/measurements`).

### 2. Flashing the Transmitter Firmware

1. Install the [Arduino IDE](https://www.arduino.cc/en/software) or use the ESP-IDF / PlatformIO extension.
2. Install the **ESP32** board definitions by Espressif and the **LoRa** library by Sandeep Mistry.
3. Open `JAL_SENSE_SX1278_TX.ino`.
4. Select board **ESP32S3 Dev Module**.
5. Connect your device and click **Upload**.
6. Open the Serial Monitor at **115200 baud** to view real-time transmission diagnostics.

---

## 📦 API Payload Specification

The Android application consumes JSON payloads from the gateway endpoint structured as follows:

```json
{
  "input_tank": {
    "packet": 16,
    "ph": 7.14,
    "tds": 240.0,
    "turbidity": 2.58,
    "temperature": 28.94,
    "relay": "ON",
    "ph_status": "VALID",
    "tds_status": "VALID"
  },
  "output_tank": {
    "packet": 5,
    "ph": 6.85,
    "tds": 145.0,
    "turbidity": 0.42,
    "relay": "ON",
    "ph_status": "VALID"
  },
  "output_level": {
    "packet": 10,
    "level": 78.5,
    "distance": 8.0,
    "relay": "ON",
    "fresh": true
  },
  "faults": [
    "OUTPUT_PH"
  ],
  "lora": {
    "rssi": -72,
    "connected": true
  },
  "status": "ONLINE"
}
```

---

## 🛠️ Technology Stack

- **Mobile App**: Kotlin, Jetpack Compose, Kotlin Coroutines, Material 3, AndroidX
- **Firmware**: C++, Arduino, LoRa.h (Sandeep Mistry), SPI
- **Networking**: REST over HTTP/Wi-Fi, LoRa RF PHY (433 MHz)

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
