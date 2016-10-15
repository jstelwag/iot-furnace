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
#include <OneWire.h>
#include <DallasTemperature.h>

/**
* Simple boiler controller. Turns the furnace on and off when the preset temperature is reached.
*
* The setup assumes you have a boiler, a (gas) furnace and a three-way valve. All is connected together with
* an Arduino, a temperature sensor and two relays (one for the furnace and one for the three-way valve).
*
* It is a slave. It needs to be leashed through Serial to the master which is probably a Raspberry Pi running
* iot-solar-boiler/FurnaceSlave
*/

// Pin configuration
// The ethernet shield uses pins 10, 11, 12, and 13 for SPI communication
// Pin 4 is used to communicate with the SD card (unused)
const byte ONE_WIRE_PIN = 2;
const byte FLOW_VALVE_RELAY_PIN = 5;   // a three way valve
const byte FURNACE_BOILER_RELAY_PIN = 6;  // relay to set the furnace in boiler mode
const byte FURNACE_HEATING_RELAY_PIN = 7;  // relay to set the furnace in heating mode
const byte PUMP_RELAY_PIN = 8;  // relay to set the pump

const float MAX_TEMP_CHANGE_THRESHOLD_85 = 0.2;

//Thermometer devices DALLAS DS18B20+ with the OneWire protocol
OneWire oneWire(ONE_WIRE_PIN);
DallasTemperature sensors(&oneWire);
boolean sensorsReady = false;

DeviceAddress boilerSensorAddress = {0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0};
double Tboiler;

boolean furnaceState = false;  // state == false is normal heating mode
boolean flowValveState = false; // valve == false is normal heating mode
boolean furnaceHeatingState = true;
boolean pumpState = false;

const long DISCONNECT_TIMOUT = 180000;
const long POSTING_INTERVAL = 30000;
long lastConnectTime, lastPostTime;

const float BOILER_START_TEMP = 50.0;
const float BOILER_STOP_TEMP = 56.0; // Depends on the furnace setting, Nefit is set to 60C, take a lower value for the boiler temperature.

void setup() {
  Serial.begin(9600);
  pinMode(FLOW_VALVE_RELAY_PIN, OUTPUT);
  pinMode(FURNACE_BOILER_RELAY_PIN, OUTPUT);
  pinMode(FURNACE_HEATING_RELAY_PIN, OUTPUT);
  pinMode(PUMP_RELAY_PIN, OUTPUT);
  digitalWrite(FURNACE_BOILER_RELAY_PIN, !furnaceState);
  digitalWrite(FLOW_VALVE_RELAY_PIN, !flowValveState);
  digitalWrite(FURNACE_HEATING_RELAY_PIN, !furnaceHeatingState);
  digitalWrite(PUMP_RELAY_PIN, !pumpState);
  lastConnectTime = millis(); //assume connection has been successful
  lastPostTime = POSTING_INTERVAL; //force immediate post
  Serial.println(F("log: furnace controller has started"));
}

void loop() {
  setupSensors();
  readSensors();
  furnaceControl();
  if (millis() > lastPostTime + POSTING_INTERVAL || millis() < lastPostTime) {
    lastPostTime = millis();
    logMaster();
  }
  receiveFromMaster();
  furnaceHeatingControl();
}

void furnaceControl() {
  if (Tboiler < BOILER_START_TEMP) {
    if (furnaceState) {
      //Furnace is already on
    } else {
      flowValveState = true;
      pumpState = false;
      digitalWrite(FLOW_VALVE_RELAY_PIN, !flowValveState);
      digitalWrite(PUMP_RELAY_PIN, !pumpState);
      delay(2000); // wait for the valve to switch
      furnaceState = true;
      digitalWrite(FURNACE_BOILER_RELAY_PIN, !furnaceState);
      Serial.println(F("log: switched furnace on"));
    }
  } else if (Tboiler < BOILER_STOP_TEMP && furnaceState) {
    //Keep the furnace buring
  } else {
    if (!furnaceState) {
      //Furnace us already off
    } else {
      furnaceState = false;
      digitalWrite(FURNACE_BOILER_RELAY_PIN, !furnaceState);
      delay(60000); //Let the last heat flow out
      flowValveState = false;
      digitalWrite(FLOW_VALVE_RELAY_PIN, !flowValveState);
      Serial.println(F("log: switched furnace off"));
    }
  }
}

void furnaceHeatingControl() {
  if (lastConnectTime + DISCONNECT_TIMOUT < millis()) {
    //Connection lost, go to native mode
    //TODO set to true
    furnaceHeatingState = true;
    pumpState = false;
    Serial.println(F("log:not receiving from master"));
  }
  digitalWrite(FURNACE_HEATING_RELAY_PIN, !furnaceHeatingState);
  digitalWrite(PUMP_RELAY_PIN, !pumpState);
}

/**
* Set the senso readings in their global variables.
* Remove the 85C error sensors sometimes return.
*/
void readSensors() {
  sensors.begin();
  sensors.requestTemperatures();
  
  Tboiler = filterSensorTemp(sensors.getTempC(boilerSensorAddress), Tboiler);
}

void logMaster() {
  Serial.print(furnaceState ? "1:" : "0:");
  Serial.println(Tboiler);
}

void receiveFromMaster() {
  //line format: [furnace: T|F][pump: T|F]
  char receivedFurnaceState, receivedPumpState;
  short i = 0;
  while (Serial.available()) {
    if (i == 0) {
      receivedFurnaceState = Serial.read();
    } else if (i == 1) {
      receivedPumpState = Serial.read();
    } else {
      Serial.read();
    }
    i++;
  }

  if (i == 2) {
    lastConnectTime = millis();
    furnaceHeatingState = (receivedFurnaceState == 'T');
    pumpState = (receivedPumpState == 'T');
  } else if (i > 0) {
    Serial.println(F("log: received unexpected master command"));
    Serial.end();
    Serial.begin(9600);
  }
}

/**
* Filters typical sensor failure at 85C and -127C
*/
float filterSensorTemp(float rawSensorTemp, float currentTemp) {
  if (rawSensorTemp == 85.0 && (abs(rawSensorTemp - 85) > MAX_TEMP_CHANGE_THRESHOLD_85)) {
    return currentTemp;
  } else if (rawSensorTemp == -127.0) {
    return currentTemp;
  } else {
    return rawSensorTemp;
  }
}

// ######################################## SETUP

void setupSensors() {
  if (!sensorsReady) {
    byte addr[8];
    byte sensorCount = 0;
    while (oneWire.search(addr)) {
      for (byte i = 0; i < 8; i++) {
        boilerSensorAddress[i] = addr[i];
      }
      sensorCount++;
    }
    if (sensorCount != 1) {
      Serial.println("log: ERROR: unexpected amount of sensors");
      delay(30000);
    }
  } else {
    sensorsReady = true;
  }
}

// ######################################## /SETUP
