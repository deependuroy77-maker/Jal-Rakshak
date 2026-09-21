#include <SPI.h>
#include <LoRa.h>

// ESP32-S3 -> SX1278 wiring
#define LORA_SCK     12
#define LORA_MISO    13
#define LORA_MOSI    11
#define LORA_SS      10
#define LORA_RST      9
#define LORA_DIO0     8
#define BOOT_BUTTON   0

// SX1278 registers used for diagnostics
#define REG_OP_MODE        0x01
#define REG_IRQ_FLAGS      0x12
#define REG_MODEM_CONFIG1  0x1D
#define REG_MODEM_CONFIG2  0x1E
#define REG_MODEM_CONFIG3  0x26
#define REG_SYNC_WORD      0x39
#define REG_VERSION        0x42

uint8_t readRegister(uint8_t address) {
  SPI.beginTransaction(SPISettings(500000, MSBFIRST, SPI_MODE0));
  digitalWrite(LORA_SS, LOW);
  SPI.transfer(address & 0x7F);       // Bit 7 = 0: register read
  uint8_t value = SPI.transfer(0x00);
  digitalWrite(LORA_SS, HIGH);
  SPI.endTransaction();
  return value;
}

void writeRegister(uint8_t address, uint8_t value) {
  SPI.beginTransaction(SPISettings(500000, MSBFIRST, SPI_MODE0));
  digitalWrite(LORA_SS, LOW);
  SPI.transfer(address | 0x80);       // Bit 7 = 1: register write
  SPI.transfer(value);
  digitalWrite(LORA_SS, HIGH);
  SPI.endTransaction();
}

void printRegisters() {
  Serial.println();
  Serial.println("----- SX1278 REGISTERS -----");
  Serial.printf("Version : 0x%02X\n", readRegister(REG_VERSION));
  Serial.printf("OpMode  : 0x%02X\n", readRegister(REG_OP_MODE));
  Serial.printf("IRQ     : 0x%02X\n", readRegister(REG_IRQ_FLAGS));
  Serial.printf("Config1 : 0x%02X\n", readRegister(REG_MODEM_CONFIG1));
  Serial.printf("Config2 : 0x%02X\n", readRegister(REG_MODEM_CONFIG2));
  Serial.printf("Config3 : 0x%02X\n", readRegister(REG_MODEM_CONFIG3));
  Serial.printf("Sync    : 0x%02X\n", readRegister(REG_SYNC_WORD));
  Serial.println("-----------------------------");
}

void sendPacket() {
  Serial.println();
  Serial.println("========== TRANSMIT ==========");
  Serial.printf("Before TX OpMode: 0x%02X\n", readRegister(REG_OP_MODE));

  writeRegister(REG_IRQ_FLAGS, 0xFF); // Clear previous IRQ flags
  LoRa.beginPacket();
  LoRa.print("HELLO_FROM_TX");
  Serial.println("Packet loaded.");

  int result = LoRa.endPacket(true);  // asynchronous transmission
  Serial.printf("endPacket result: %d\n", result);
  if (result == 0) {
    Serial.println("ERROR: Could not start transmission.");
    return;
  }

  unsigned long startTime = millis();
  bool txDone = false;
  while (millis() - startTime < 3000) {
    uint8_t opMode = readRegister(REG_OP_MODE);
    uint8_t irq = readRegister(REG_IRQ_FLAGS);
    Serial.printf("OpMode: 0x%02X | IRQ: 0x%02X\n", opMode, irq);

    // TX_DONE is bit 3 (0x08) in REG_IRQ_FLAGS.
    if (irq & 0x08) {
      txDone = true;
      break;
    }
    delay(100);
  }

  if (txDone) {
    Serial.println("TX_DONE detected.");
    Serial.println("Packet transmitted successfully.");
  } else {
    Serial.println("ERROR: TX_DONE timeout.");
  }

  writeRegister(REG_IRQ_FLAGS, 0xFF); // Clear IRQ flags after handling
  LoRa.idle();
  delay(50);
  Serial.printf("Final OpMode: 0x%02X\n", readRegister(REG_OP_MODE));
  printRegisters();
  Serial.println("==============================");
  Serial.println("Press BOOT or type S to send.");
}

void setup() {
  Serial.begin(115200);
  delay(1500);

  pinMode(BOOT_BUTTON, INPUT_PULLUP);
  pinMode(LORA_SS, OUTPUT);
  digitalWrite(LORA_SS, HIGH);
  pinMode(LORA_RST, OUTPUT);

  // Hardware reset of the SX1278.
  digitalWrite(LORA_RST, LOW);
  delay(20);
  digitalWrite(LORA_RST, HIGH);
  delay(100);

  SPI.begin(LORA_SCK, LORA_MISO, LORA_MOSI, LORA_SS);
  LoRa.setSPI(SPI);
  LoRa.setSPIFrequency(500000);
  LoRa.setPins(LORA_SS, LORA_RST, LORA_DIO0);

  Serial.println();
  Serial.println("================================");
  Serial.println("     SX1278 TRANSMITTER");
  Serial.println("================================");
  Serial.println("Initializing LoRa...");

  if (!LoRa.begin(433E6)) {
    Serial.println("LoRa initialization FAILED!");
    while (true) {
      delay(1000);
    }
  }

  LoRa.setSpreadingFactor(7);
  LoRa.setSignalBandwidth(125E3);
  LoRa.setCodingRate4(5);
  LoRa.setSyncWord(0x12);
  LoRa.enableCrc();
  LoRa.setTxPower(17);
  LoRa.idle();
  delay(100);

  Serial.println("LoRa initialized successfully.");
  printRegisters();

  uint8_t version = readRegister(REG_VERSION);
  if (version == 0x12) {
    Serial.println("SX1278 version OK: 0x12");
  } else {
    Serial.printf("WARNING: Unexpected version: 0x%02X\n", version);
  }

  Serial.println("Press BOOT or type S to send.");
}

void loop() {
  static bool previousButtonState = HIGH;
  bool buttonState = digitalRead(BOOT_BUTTON);

  // Trigger once when the BOOT button is pressed.
  if (previousButtonState == HIGH && buttonState == LOW) {
    delay(30);  // debounce
    if (digitalRead(BOOT_BUTTON) == LOW) {
      sendPacket();
      while (digitalRead(BOOT_BUTTON) == LOW) {
        delay(10);
      }
    }
  }
  previousButtonState = buttonState;

  // The Serial Monitor can also send S or s.
  if (Serial.available() > 0) {
    char command = Serial.read();
    if (command == 'S' || command == 's') {
      sendPacket();
    }
  }
}
