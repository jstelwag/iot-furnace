#include <SPI.h>
#include <Ethernet.h>

/**
* Slave controller for low temperature (LT) floor and wall heating system.
* The slave's purpose is to measure and control the heating loop temperature.
* A room has one or more of these loops in the floor and walls, each of them is set
* to a setpoint given by the master. Typically the master wants the floor to have a 
* temperature of 25 C that in the end will result in a room temparature of say 22 C.
*
* Slave controllers are connected through I2C to their master controller. The communications
* between master and slave are setpoints given by the master and loop and relay status by the slave.
*
* The loop flow is controlled by valves in the heating system, these are thermo actuated valves
* ie. a medium heats up and melts/expands and thereby pressing the valve to open it.
* These valves take a some time to open.
* Temperature is measured by sensors connected with the onwire bus. Each return loop pipe has a sensor. Ie, a
* feedback loop control system is applied with an on/off control.
*
* The advantage / purpose of the slave controller is to control each loop seperately. Each tube is different
* in length and will show a different behaviour. By using a slave controller, a large concrete floor will
* get the same behaviour as a small wall. Also less loops will be open on average and therefor open loops will
* respond faster to heat demand given the limited pump and furnace capacity.
*
* On startup or after a reset the test cycle is performed where every valve is opened.
*/

#define Ntest_relays // remove the NO to test the relays

const byte RELAY_START_PIN = 2;
const byte MAX_VALVES = 16;

struct ValveProperties {
  char deviceId[20];
  byte valveCount;
  byte pumpPin;
  uint8_t macAddress[6];
  IPAddress masterIP;
  uint16_t masterPort;
  boolean defaultControlStatus[MAX_VALVES];
};

boolean received = false;
IPAddress masterIP(192, 168, 178, 18);
ValveProperties prop = {"koetshuis_trap_15", 15, 22, {0xDE, 0xAD, 0xBE, 0xEF, 0xFE, 0xED}, masterIP, 9999, {false, false, true, true, true, true, false, true, false, false, false, true, false, false, false, false}};

EthernetClient client;
long lastPostTime;
const long POSTING_INTERVAL = 30000;
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
}

void loop(void) {
  if (millis() > lastPostTime + POSTING_INTERVAL || millis() < lastPostTime) {
    request();
  }
  receive();

  if (received) {
    if (checksum()) {
      for (byte s = 0; s <= prop.valveCount; s++) {
        if (relay[s] != receiveBuffer[s]) {
          Serial.print(F("Changing valve "));
          Serial.println(s+1);
          relay[s] = receiveBuffer[s];
        }
      }
      setValves();
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
    for (byte s = 0; s <= prop.valveCount; s++) {
      checksum += relay[s];
      client.print(relay[s]);
      client.print(comma);
      Serial.print(relay[s]);
    }
    client.println(checksum);
    Serial.println(F("ed"));
  } else {
    Serial.println(F(" failed"));
    client.stop();
    Ethernet.maintain();
  }
  lastPostTime = millis();
  delay(1000);
}

boolean checksum() {
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
}

void setValves() {
  for (byte i = 0; i < prop.valveCount; i++) {
    digitalWrite(findRelayPin(i), !relay[i]);
  }
  digitalWrite(prop.pumpPin, !relay[prop.valveCount]);
}

/**
* Maps relay pins with loop number.
* It turns out to be a pretty straight forward functions because the analog pins
* simply extend the digital pin numbering. In the beginning and the end a few pins
* are reserved for other functions.
*/
uint8_t findRelayPin(uint8_t valveNumber) { 
  if (valveNumber + RELAY_START_PIN < 9) {
    return valveNumber + RELAY_START_PIN;
  }
  return valveNumber + RELAY_START_PIN + 5;
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
    relay[i] = prop.defaultControlStatus[i];
    digitalWrite(findRelayPin(i), !relay[i]);
  }
  pinMode(prop.pumpPin, OUTPUT);
  digitalWrite(prop.pumpPin, !false);
}
// ######################################## /SETUP


#ifdef test_relays
  void valveTest() {
    Serial.println(F("Testing valves ON"));
    for (byte i = 0; i < prop.valveCount; i++) {
      digitalWrite(findRelayPin(i), !true);
    }
    delay(10000);
    Serial.println(F("Testing valves OFF"));
    for (byte i = 0; i < prop.valveCount; i++) {
      digitalWrite(findRelayPin(i), !false);
    }
    delay(5000);
    for (byte i = 0; i < prop.valveCount; i++) {
      Serial.print(F("Testing valve "));
      Serial.println(i + 1);
      digitalWrite(findRelayPin(i), !true);
      delay(1500);
      digitalWrite(findRelayPin(i), !false);
    }
    delay(500);
    Serial.println(F("Testing pump valve"));
    digitalWrite(prop.pumpPin, !true);
    delay(500);
    digitalWrite(prop.pumpPin, !false);
  }
#endif
