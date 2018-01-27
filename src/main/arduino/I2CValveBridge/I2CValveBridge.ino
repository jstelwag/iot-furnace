/*
    Copyright 2018 Jaap Stelwagen
    
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

/**
* Valve bridge for low temperature (LT) floor and wall heating system.
* Bridges the master controller to the valves. Uses I2C to communicate with
* a bridge (raspberry pi) who connects with the master controller (iot-monitor) via http.
*/
#include <Wire.h>
 
#define ntest_relays // remove the NO to test the relays

#define koetshuis_kelder  //koetshuis_kelder has one relay that is controlled with an inverted signal
#define nkoetshuis_trap_6  //negate all relays
#define nkoetshuis_trap_15  //negate all relays and different pin settings
#define nkasteel_zolder

#ifdef koetshuis_trap_6
  const byte VALVE_COUNT = 6;
  const char DEVICE_ID[] = "koetshuis_trap_6";
  const byte SLAVE_ADDRESS = 0x04;
  byte defaultControlStatus[] = {1, 1, 1, 1, 1, 0};
  const byte RELAY_START_PIN = 3;
#elif defined(koetshuis_trap_15)
  const byte VALVE_COUNT = 15;
  const char DEVICE_ID[] = "koetshuis_trap_15";
  const byte SLAVE_ADDRESS = 0x05;
  byte defaultControlStatus[] = {0, 0, 1, 1, 1, 1, 0, 1, 0, 0, 0, 1, 0, 0, 0};
  const byte RELAY_START_PIN = 3;
#elif defined(koetshuis_kelder)
  const byte VALVE_COUNT = 9;
  const char DEVICE_ID[] = "koetshuis_kelder";
  const byte SLAVE_ADDRESS = 0x06;
  byte defaultControlStatus[] = {1, 1, 1, 0, 0, 0, 1, 0, 0};
  const byte RELAY_START_PIN = 2;
#elif defined(kasteel_zolder)
  const byte VALVE_COUNT = 8;
  const char DEVICE_ID[] = "kasteel_zolder";
  const byte SLAVE_ADDRESS = 0x07;
  byte defaultControlStatus[] = {1, 1, 1, 1, 0, 0, 0, 1};
  const byte RELAY_START_PIN = 3;
#endif

long lastSuccessTime;
const long SUCCESS_THRESHOLD = 120000;
boolean receiveBuffer[VALVE_COUNT + 5];
boolean relay[VALVE_COUNT];
byte bufferPosition;
boolean received  = false;

void setup(void) {
  Serial.begin(9600);
#ifdef koetshuis_trap_15
  Wire1.begin(SLAVE_ADDRESS);
  Wire1.onReceive(receiveData);
  Wire1.onRequest(sendData);
#else
  Wire.begin(SLAVE_ADDRESS);
  Wire.onReceive(receiveData);
  Wire.onRequest(sendData);
#endif

  setupValveRelayPins();

#ifdef test_relays
  valveTest();
#endif

  defaults();
  setValves();
  Serial.println(F("log:started"));
}

void loop(void) {
  if (millis() > lastSuccessTime + SUCCESS_THRESHOLD) {
    lastSuccessTime = millis();
    defaults();
    Serial.println(F("log: ERROR connection lost"));
  }
  if (millis() < lastSuccessTime) {
    lastSuccessTime = 0;
  }


  if (received) {
    for (byte s = 0; s <= VALVE_COUNT; s++) {
      if (relay[s] != receiveBuffer[s]) {
        Serial.print(F("log:Changing relay "));
        Serial.println(s+1);
        relay[s] = receiveBuffer[s];
      }
    }

    setValves();
    received = false;
    lastSuccessTime = millis();
  }
}

void receiveData(int byteCount) {
#ifdef koetshuis_trap_15
  while (Wire1.available()) {
    char c = Wire1.read();
#else
  while (Wire.available()) {
    char c = Wire.read();
#endif
    if (c == 'E') {
      received = true;
    } else {
      receiveBuffer[bufferPosition] = (c == '1');
      bufferPosition++;
    }
  }
}

void sendData() {
  bufferPosition = 0;
  for (byte b = 0; b < sizeof(receiveBuffer); b++) {
    receiveBuffer[b] = false;
  }

#ifdef koetshuis_trap_15
  Wire1.write(DEVICE_ID);
  char comma[] = ":";
  for (byte i = 0; i < VALVE_COUNT; i++) {
    Wire1.write(comma);
    if (relayValue(i, digitalRead(findRelayPin(i)))) {
      Wire1.write('1');
    } else {
      Wire1.write('0');
    }
  }
#else
  Wire.write(DEVICE_ID);
  char comma[] = ":";
  for (byte i = 0; i < VALVE_COUNT; i++) {
    Wire.write(comma);
    if (relayValue(i, digitalRead(findRelayPin(i)))) {
      Wire.write('1');
    } else {
      Wire.write('0');
    }
  }
  Serial.print(F("pushed "));
  Serial.println(VALVE_COUNT);
#endif
}

void defaults() {
  for (byte s = 0; s <= VALVE_COUNT; s++) {
    if (relay[s] != defaultControlStatus[s]) {
      Serial.print(F("log:Changing relay "));
      Serial.println(s+1);
      relay[s] = defaultControlStatus[s];
    }
  }
}

void setValves() {
  byte c = 0;
  for (byte i = 0; i < VALVE_COUNT; i++) {
    if (relayValue(i, digitalRead(findRelayPin(i))) != relay[i]) {
      digitalWrite(findRelayPin(i), relayValue(i, relay[i]));
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

boolean relayValue(uint8_t valveNumber, byte value) {
  boolean reverse = true;
#ifdef koetshuis_kelder
  reverse = false;
  if (valveNumber == 0) {
    reverse = true;
  }
#endif
  return (reverse == value);
}

// ######################################## SETUP
void setupValveRelayPins() {
  for (uint8_t i = 0; i < VALVE_COUNT; i++) {
    // There are   14 digital pins, where 0 and 1 are reserved and 2 is used for oneWire
    // The analog pins are available except A4 and A5 for I2C communications
    pinMode(findRelayPin(i), OUTPUT);
  }
}
// ######################################## /SETUP


#ifdef test_relays
void valveTest() {
  Serial.println(F("Testing valve on"));
  for (byte i = 0; i < VALVE_COUNT; i++) {
    digitalWrite(findRelayPin(i), relayValue(i,false));
  }
  delay(3000);
  Serial.println(F("Testing valve off"));
  for (byte i = 0; i < VALVE_COUNT; i++) {
    digitalWrite(findRelayPin(i), relayValue(i, true));
  }
  delay(3000);
  
  for (byte i = 0; i < VALVE_COUNT; i++) {
    Serial.print(F("Testing valve "));
    Serial.println(i);
    digitalWrite(findRelayPin(i), relayValue(i, false));
    delay(1000);
    digitalWrite(findRelayPin(i), relayValue(i, true));
  }
}
#endif
