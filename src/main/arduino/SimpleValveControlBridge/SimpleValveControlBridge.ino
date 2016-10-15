/*
    Copyright 2016 Jaap Stelwagen
    
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
#include <EEPROM.h>

/**
* Valve controller for low temperature (LT) floor and wall heating system.
* Bridges the master controller to the valves. Uses Ethernet / UDP to communicate with
* the master what can be a RaspberryPi hanging in the network.
*/

#define ntest_relays // remove the NO to test the relays
#define nflash_props // Writes property setting into eeprom memory

const byte RELAY_START_PIN = 3;
const byte MAX_VALVES = 16;

struct ValveProperties {
  char deviceId[20];
  byte valveCount;
  boolean defaultControlStatus[MAX_VALVES];
};

boolean received = false;

unsigned long lastPostTime;
unsigned long lastConnectionTime;
const unsigned long POSTING_INTERVAL = 30000;
const unsigned long LOST_CONNECTION_TIMEOUT = 120000;
boolean receiveBuffer[MAX_VALVES + 5];
byte bufferPosition;

void setup(void) {
  Serial.begin(9600);

#ifdef flash_props
  //ValveProperties pIn = {"koetshuis_kelder", 9, {true, true, true, false, false, false, true, false, false, false, false, false}};
  //ValveProperties pIn = {"koetshuis_trap_15L", 8, {false, false, true, true, true, true, false, true, false, false, false, true}};
  //ValveProperties pIn = {"koetshuis_trap_15R", 7, {false, false, false, false, false, false, false, false, false, false, false, false}};
  //ValveProperties pIn = {"koetshuis_trap_6", 6, {true, true, true, true, true, false, false, false, false, false, false, false}};
  ValveProperties pIn = {"kasteel_zolder", 8, {true, true, true, true, false, false, false, true}};
  EEPROM.put(0, pIn);
#endif

  setupValveRelayPins();

#ifdef test_relays
  valveTest();
#endif

  lastConnectionTime = millis();
  Serial.println(F("started"));
}

void loop(void) {
  if (millis() > lastPostTime + POSTING_INTERVAL || millis() < lastPostTime) {
    request();
  }
  receive();

  if (received) {
    setValves();
    received = false;
    lastConnectionTime = millis();
  }

  if (millis() < lastConnectionTime) {
    lastConnectionTime = millis(); //clock reset, also reset lastConnectionTime
  } else if (millis() > lastConnectionTime + LOST_CONNECTION_TIMEOUT) {
    Serial.println(F("log: lost connection mode"));
    ValveProperties prop;
    EEPROM.get(0, prop);
    for (byte b = 0; b < prop.valveCount; b++) {
      receiveBuffer[b] = prop.defaultControlStatus[b];
    }
    setValves();
    delay(5000);
  }
}

void receive() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == 'E') {
      received = true;
    } else {
      receiveBuffer[bufferPosition] = (c == '1');
      bufferPosition++;
    }
  }
}

void request() {
  bufferPosition = 0;
  for (byte b = 0; b < sizeof(receiveBuffer); b++) {
    receiveBuffer[b] = false;
  }

  ValveProperties prop;
  EEPROM.get(0, prop);
  
  Serial.print(prop.deviceId);
  char comma[] = ":";
  int checksum = 0;
  for (byte i = 0; i < prop.valveCount; i++) {
    Serial.print(comma);
    int state = !digitalRead(findRelayPin(i));
    checksum += state;
    Serial.print(state);
  }
  Serial.print(comma);
  Serial.println(checksum);

  lastPostTime = millis();
}

void setValves() {
  ValveProperties prop;
  EEPROM.get(0, prop);
  byte c = 0;
  for (byte i = 0; i < prop.valveCount; i++) {
    if (digitalRead(findRelayPin(i)) == receiveBuffer[i]) {
      digitalWrite(findRelayPin(i), !receiveBuffer[i]);
      c++;
    }
  }
  if (c > 0) {
    Serial.print(F("log: changed "));
    Serial.print(c);
    Serial.println(F(" valves"));
  }
}

/**
* Maps relay pins with loop number.
* It turns out to be a pretty straight forward functions because the analog pins
* simply extend the digital pin numbering. In the beginning and the end a few pins
* are reserved for other functions.
*/
uint8_t findRelayPin(uint8_t sensorNumber) {
  return sensorNumber + RELAY_START_PIN;
}

// ######################################## SETUP
void setupValveRelayPins() {
  ValveProperties prop;
  EEPROM.get(0, prop);
  for (uint8_t i = 0; i < prop.valveCount; i++) {
    // There are   14 digital pins, where 0 and 1 are reserved and 2 is used for oneWire
    // The analog pins are available except A4 and A5 for I2C communications
    pinMode(findRelayPin(i), OUTPUT);
    digitalWrite(findRelayPin(i), !prop.defaultControlStatus[i]);
  }
}
// ######################################## /SETUP


#ifdef test_relays
void valveTest() {
  ValveProperties prop;
  EEPROM.get(0, prop);

  Serial.print(F("Testing valve on"));
  for (byte i = 0; i < prop.valveCount; i++) {
    digitalWrite(findRelayPin(i), false);
  }
  delay(10000);
  Serial.print(F("Testing valve off"));
  for (byte i = 0; i < prop.valveCount; i++) {
    digitalWrite(findRelayPin(i), true);
  }
  delay(10000);
  
  for (byte i = 0; i < prop.valveCount; i++) {
    Serial.print(F("Testing valve "));
    Serial.println(i);
    digitalWrite(findRelayPin(i), true);
    delay(2500);
    digitalWrite(findRelayPin(i), false);
  }
}
#endif
