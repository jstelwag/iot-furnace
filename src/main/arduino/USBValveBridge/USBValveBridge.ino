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

/**
* Valve bridge for low temperature (LT) floor and wall heating system.
* Bridges the master controller to the valves. Uses USB serial to communicate with
* a proxy (raspberry pi) who connects with the master controller (iot-monitor) via http.
*/

#define test_relays // remove the NO to test the relays

const byte RELAY_START_PIN = 3;
const byte MAX_VALVES = 16;

struct ValveProperties {
  char deviceId[20];
  byte valveCount;
  byte defaultControlStatus[MAX_VALVES];
};

boolean received = false;

unsigned long lastPostTime;
long lastSuccessTime;
const long POSTING_INTERVAL = 30000;
const long SUCCESS_THRESHOLD = 5*POSTING_INTERVAL;
boolean receiveBuffer[MAX_VALVES + 5];
boolean relay[MAX_VALVES];
byte bufferPosition;

ValveProperties prop = {"kasteel_zolder", 8, {1, 1, 1, 1, 0, 0, 0, 1}};

void setup(void) {
  Serial.begin(9600);
  setupValveRelayPins();

#ifdef test_relays
  valveTest();
#endif
  defaults();
  setValves();
  Serial.println(F("log:started"));
}

void loop(void) {
  if (millis() > lastPostTime + POSTING_INTERVAL) {
    request();
  }
  if (millis() > lastSuccessTime + SUCCESS_THRESHOLD) {
    lastSuccessTime = millis();
    defaults();
    Serial.println(F("log: ERROR connection lost"));
  }
  if (millis() < lastPostTime) {
    lastPostTime = 0;
  }
  if (millis() < lastSuccessTime) {
    lastSuccessTime = 0;
  }
  
  receive();

  if (received) {
    for (byte s = 0; s <= prop.valveCount; s++) {
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

void defaults() {
  for (byte s = 0; s <= prop.valveCount; s++) {
    if (relay[s] != prop.defaultControlStatus[s]) {
      Serial.print(F("log:Changing relay "));
      Serial.println(s+1);
      relay[s] = prop.defaultControlStatus[s];
    }
  }
}

void setValves() {
  byte c = 0;
  for (byte i = 0; i < prop.valveCount; i++) {
    if (digitalRead(findRelayPin(i)) == relay[i]) {
      digitalWrite(findRelayPin(i), !relay[i]);
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
  for (uint8_t i = 0; i < prop.valveCount; i++) {
    // There are   14 digital pins, where 0 and 1 are reserved and 2 is used for oneWire
    // The analog pins are available except A4 and A5 for I2C communications
    pinMode(findRelayPin(i), OUTPUT);
  }
}
// ######################################## /SETUP


#ifdef test_relays
void valveTest() {
  Serial.print(F("Testing valve on"));
  for (byte i = 0; i < prop.valveCount; i++) {
    digitalWrite(findRelayPin(i), false);
  }
  delay(4000);
  Serial.print(F("Testing valve off"));
  for (byte i = 0; i < prop.valveCount; i++) {
    digitalWrite(findRelayPin(i), true);
  }
  delay(4000);
  
  for (byte i = 0; i < prop.valveCount; i++) {
    Serial.print(F("Testing valve "));
    Serial.println(i);
    digitalWrite(findRelayPin(i), false);
    delay(1500);
    digitalWrite(findRelayPin(i), true);
  }
}
#endif
