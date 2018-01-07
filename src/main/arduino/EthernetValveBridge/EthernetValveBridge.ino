#include <SPI.h>
#include <Ethernet.h>

/**
* Control bridge for floor / wall heating system. The master controller (located in the iot-monitor java project) decides which
* output or heating loops must be active. Communication is via HTTP.
* If the connection with the controller is lost, the bridge switches to default settings.
*/

#define Ntest_relays // remove the NO to test the relays
#define Nkoetshuis_kelder  //koetshuis_kelder has one relay that is controlled with an inverted signal
#define koetshuis_trap_6  //negate all relays

const byte RELAY_START_PIN = 2;
const byte MAX_VALVES = 16;

struct ValveProperties {
  char deviceId[20];
  byte valveCount;
  byte pumpPin;
  uint8_t macAddress[6];
  IPAddress masterIP;
  uint16_t masterPort;
  byte defaultControlStatus[MAX_VALVES];
};

boolean received = false;
IPAddress masterIP(192, 168, 178, 18);
//ValveProperties prop = {"koetshuis_trap_15", 15, 22, {0xDE, 0xAD, 0xBE, 0xEF, 0xFE, 0xED}, masterIP, 9999, {false, false, true, true, true, true, false, true, false, false, false, true, false, false, false}};
//ValveProperties prop = {"koetshuis_kelder", 9, 0, {0xDE, 0xAD, 0xBE, 0xEF, 0xFA, 0xEB}, masterIP, 9999, {1, 1, 1, 0, 0, 0, 1, 0, 0}};
ValveProperties prop = {"koetshuis_trap_6", 6, 0, {0xDE, 0xAD, 0xBE, 0xEF, 0xFA, 0xEB}, masterIP, 9999, {1, 1, 1, 1, 1, 0}};

EthernetClient client;
long lastPostTime;
long lastSuccessTime;
const long POSTING_INTERVAL = 30000;
const long SUCCESS_THRESHOLD = 5*POSTING_INTERVAL;
byte receiveBuffer[MAX_VALVES+5];
boolean relay[MAX_VALVES];
byte bufferPosition;

void setup(void) {
  Serial.begin(9600);
  dhcp(prop.macAddress);
  setupValveRelayPins();

  #ifdef test_relays
    valveTest();
  #endif
  Serial.println(F("ready"));
  defaults();
  setValves();
}

void defaults() {
  for (byte s = 0; s <= prop.valveCount; s++) {
    if (relay[s] != prop.defaultControlStatus[s]) {
      Serial.print(F("Changing relay "));
      Serial.println(s+1);
      relay[s] = prop.defaultControlStatus[s];
    }
  }
}

void loop(void) {
  if (millis() > lastPostTime + POSTING_INTERVAL) {
    request();
  }
  if (millis() > lastSuccessTime + SUCCESS_THRESHOLD) {
    lastSuccessTime = millis();
    defaults();
    client.stop();
    Ethernet.maintain();
    Serial.println(F("No connection"));
  }
  if (millis() < lastPostTime) {
    lastPostTime = 0;
  }
  if (millis() < lastSuccessTime) {
    lastSuccessTime = 0;
  }

  receive();

  if (received) {
    if (checksum()) {
      for (byte s = 0; s <= prop.valveCount; s++) {
        if (relay[s] != receiveBuffer[s]) {
          Serial.print(F("Changing relay "));
          Serial.println(s+1);
          relay[s] = receiveBuffer[s];
        }
      }
      setValves();
      lastSuccessTime = millis();
    } else {
      Serial.println(F(" failed checksum"));
    }
    received = false;
  }
}

void receive() {
  if (client.available()) {
    Serial.print(F("receive"));
    while (client.available()) {
      char c = client.read();
      if (c == 'E') {
        receiveBuffer[prop.valveCount + 1] = receiveBuffer[bufferPosition - 1];
        client.flush();
        received = true;
        Serial.println(F("d"));
      } else {
        receiveBuffer[bufferPosition] = (c - 48);
        bufferPosition++;
      }
    }
  }
}

void request() {
  Serial.print(F("post"));
  client.stop();
  bufferPosition = 0;
  for (byte b = 0; b < sizeof(receiveBuffer); b++) {
    receiveBuffer[b] = false;
  }
  
  if (client.connect(prop.masterIP, prop.masterPort)) {
    char comma = ':';
    client.print(prop.deviceId);
    client.print(comma);
    int checksum = 0;
    for (byte s = 0; s < prop.valveCount; s++) {
      checksum += relay[s];
      client.print(relay[s]);
      client.print(comma);
      Serial.print(relay[s]);
    }
    if (prop.pumpPin > 0) {
      checksum += relay[prop.valveCount];
      client.print(relay[prop.valveCount]);
      client.print(comma);
      Serial.print(relay[prop.valveCount]);
    }
    client.println(checksum);
    Serial.println(F("ed"));
  } else {
    Serial.println(F(" failed"));
  }
  lastPostTime = millis();
  delay(1000);
}

boolean checksum() {
/*
  byte checksum = 0;
  for (byte i = 0; i <= prop.valveCount; i++) {
    checksum += receiveBuffer[i];
    Serial.print(receiveBuffer[i]);
  }

  if (checksum > 9) {
    byte digicheck = checksum/10;
    checksum = checksum - 10*digicheck;
  }
  //assuming you never let more than 99 valves open
  if ((byte)receiveBuffer[prop.valveCount+1] == checksum) {
    return true;
  }
  Serial.print(F(" -checksum mismatch: "));
  Serial.print(receiveBuffer[prop.valveCount+1]);
  Serial.print(F("/"));
  Serial.print(checksum);
  return false;
  */
  return true;
}

void setValves() {
  for (byte i = 0; i < prop.valveCount; i++) {
    digitalWrite(findRelayPin(i), relayValue(i, relay[i]));
  }
  if (prop.pumpPin > 0) {
    digitalWrite(prop.pumpPin, relayValue(prop.pumpPin, relay[prop.valveCount]));
  }
}

/**
* Maps relay pins with loop number.
* It turns out to be a pretty straight forward functions because the analog pins
* simply extend the digital pin numbering. In the beginning and the end a few pins
* are reserved for other functions.
*/
uint8_t findRelayPin(uint8_t valveNumber) { 
  if (valveNumber + RELAY_START_PIN <= 9) {
    return valveNumber + RELAY_START_PIN;
  }
  return valveNumber + RELAY_START_PIN + 4;
}

byte relayValue(uint8_t valveNumber, byte value) {
  boolean negate = false;
#ifdef koetshuis_trap_6
  negate = true;
#endif
#ifdef koetshuis_kelder
  if (valveNumber == 0) {
    negate = true;
  }
#endif
  return (!negate == value);
}



// ######################################## SETUP
void dhcp(uint8_t *macAddress) {
  Serial.print(F("Connecting to network... "));
  delay(1000); // give the ethernet module time to boot up
  if (Ethernet.begin(macAddress) == 0) {
    Serial.println(F(" failed to configure Ethernet using DHCP"));
  } else {
    Serial.print(F(" success! My IP is now "));
    Serial.println(Ethernet.localIP());
  }
}

void setupValveRelayPins() {
  for (byte i = 0; i < prop.valveCount; i++) {
    // There are 14 digital pins, where 0 and 1 are reserved and 2 is used for oneWire
    // The analog pins are available except A4 and A5 for I2C communications
    pinMode(findRelayPin(i), OUTPUT);
  }
  if (prop.pumpPin > 0) {
    pinMode(prop.pumpPin, OUTPUT);
    digitalWrite(prop.pumpPin, relayValue(prop.pumpPin, false));
  }
}
// ######################################## /SETUP


#ifdef test_relays
  void valveTest() {
    Serial.println(F("Testing valves ON"));
    for (byte i = 0; i < prop.valveCount; i++) {
      digitalWrite(findRelayPin(i), relayValue(i, true));
    }
    delay(2000);
    Serial.println(F("Testing valves OFF"));
    for (byte i = 0; i < prop.valveCount; i++) {
      digitalWrite(findRelayPin(i), relayValue(i, false));
    }
    delay(1500);
    for (byte i = 0; i < prop.valveCount; i++) {
      Serial.print(F("Testing valve "));
      Serial.println(i + 1);
      digitalWrite(findRelayPin(i), relayValue(i, true));
      delay(1000);
      digitalWrite(findRelayPin(i), relayValue(i, false));
    }
    if (prop.pumpPin > 0) {
      delay(500);
      Serial.println(F("Testing pump valve"));
      digitalWrite(prop.pumpPin, relayValue(prop.pumpPin, true));
      delay(500);
      digitalWrite(prop.pumpPin, relayValue(prop.pumpPin, false));
    }
  }
#endif
