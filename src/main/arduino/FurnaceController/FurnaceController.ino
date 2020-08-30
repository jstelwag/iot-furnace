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
const byte BOILER_VALVE_RELAY_PIN = 5;   // a three way valve
const byte FURNACE_BOILER_RELAY_PIN = 6;  // relay to set the furnace in boiler mode
const byte FURNACE_HEATING_RELAY_PIN = 7;  // relay to set the furnace in heating mode
const byte PUMP_RELAY_PIN = 8;  // relay to set the pump

const float MAX_TEMP_CHANGE_THRESHOLD_85 = 0.2;

//Thermometer devices DALLAS DS18B20+ with the OneWire protocol
OneWire oneWire(ONE_WIRE_PIN);
DallasTemperature sensors(&oneWire);
byte sensorCount = 0;

DeviceAddress boilerSensorAddress = {0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0};
DeviceAddress auxillarySensorAddress = {0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0};
double Tboiler, Tauxillary;

boolean furnaceBoilerState = false;  // state == false is normal heating mode
boolean boilerValveState = false; // valve == false is normal heating mode
boolean furnaceHeatingState = false;
boolean pumpState = false;

const long DISCONNECT_TIMOUT = 180000;
const long POSTING_INTERVAL = 30000;
long lastConnectTime, lastPostTime;

const float BOILER_START_TEMP = 50.0;
const float BOILER_STOP_TEMP = 56.5;

void setup() {
  Serial.begin(9600);
  pinMode(BOILER_VALVE_RELAY_PIN, OUTPUT);
  pinMode(FURNACE_BOILER_RELAY_PIN, OUTPUT);
  pinMode(FURNACE_HEATING_RELAY_PIN, OUTPUT);
  pinMode(PUMP_RELAY_PIN, OUTPUT);
  digitalWrite(FURNACE_BOILER_RELAY_PIN, !furnaceBoilerState);
  digitalWrite(BOILER_VALVE_RELAY_PIN, !boilerValveState);
  digitalWrite(FURNACE_HEATING_RELAY_PIN, !furnaceHeatingState);
  digitalWrite(PUMP_RELAY_PIN, !pumpState);
  lastConnectTime = millis(); //assume connection has been successful
  lastPostTime = POSTING_INTERVAL; //force immediate post
  Serial.println(F("log:furnace:INFO:controller has started"));
}

void loop() {
  setupSensors();
  readSensors();
  boilerControl();
  if (millis() > lastPostTime + POSTING_INTERVAL || millis() < lastPostTime) {
    lastPostTime = millis();
    logMaster();
  }
  receiveFromMaster();
  noMasterHeatingControl();
}

void boilerControl() {
  // Check if the sensor are connected
  // Check if the boiler temp is below setpoint
  // Maintain heating up until stop setpoint
  // Turn off boiler if is running after reaching setpoint
  
  if (sensorCount == 0) {
    // No temperature sensors are connected. Do not operate the boiler.
  } else if (Tboiler < BOILER_START_TEMP) {
    if (furnaceBoilerState) {
      //Furnace is already on
    } else {
      setPump(false);
      if (furnaceHeatingState) {
        setFurnaceHeating(false);
        Serial.println(F("log:INFO:furnace:waiting 4 min for pump to stop"));
        delay(240000); // wait 4 minutes
      }
      setBoilerValve(true);
      delay(5000); // wait for the valve to switch
      setFurnaceBoiler(true);
      Serial.println(F("log:INFO:furnace:switched furnace boiler on"));
    }
  } else if (Tboiler < BOILER_STOP_TEMP && furnaceBoilerState) {
    //Keep the boiler on
  } else if (furnaceBoilerState) {
    setFurnaceBoiler(false);
    Serial.println(F("log:furnace:INFO:waiting 2 min for pump to stop"));
    delay(120000);
    setBoilerValve(false);
    Serial.println(F("log:furnace:INFO:switched furnace boiler off"));
  }
}

void noMasterHeatingControl() {
  if (lastConnectTime + DISCONNECT_TIMOUT < millis()) {
    //Connection lost, go to native mode
    setPump(false);
    if (sensorCount > 1) {
      //There is an auxillary sensor, use it
      if (Tauxillary < 15.0) {
        setFurnaceHeating(true);
      } else {
        setFurnaceHeating(false);
      }
    } else {
      setFurnaceHeating(true);
    }
    Serial.println(F("log:furnace:ERROR:not receiving from master"));
    delay(30000);
  }
}

void setFurnaceBoiler(boolean state) {
  furnaceBoilerState = state;
  if (furnaceBoilerState) {
    // Force pump shutdown and boilerValve set
    setPump(false);
    setBoilerValve(true);
  }
  if (digitalRead(FURNACE_BOILER_RELAY_PIN) == furnaceBoilerState) {
    digitalWrite(FURNACE_BOILER_RELAY_PIN, !furnaceBoilerState);
    Serial.println(F("log:furnace:INFO:changed furnace boiler state"));
  }  
}

void setBoilerValve(boolean state) {
  if (state && (furnaceHeatingState || furnaceBoilerState)) {
    Serial.println(F("log:furnace:WARN:ignoring valve change when furnace is on"));
  } else {
    boilerValveState = state;
    if (digitalRead(BOILER_VALVE_RELAY_PIN) == boilerValveState) {
      digitalWrite(BOILER_VALVE_RELAY_PIN, !boilerValveState);
      Serial.println(F("log:furnace:INFO:changed boiler valve state"));
    }
  }
}
void setFurnaceHeating(boolean state) {
  furnaceHeatingState = state;
  // Force pump shutdown
  if (!furnaceHeatingState) {
    setPump(false);
  }
  if (digitalRead(FURNACE_HEATING_RELAY_PIN) == furnaceHeatingState) {
    digitalWrite(FURNACE_HEATING_RELAY_PIN, !furnaceHeatingState);
    Serial.println(F("log:furnace:INFO:changed furnace heating state"));
  }
}

void setPump(boolean state) {
  if (!furnaceHeatingState && state) {
    Serial.println(F("log:furnace:WARN:ignoring request to start pump while furnace is off"));
  } else if (boilerValveState && state) {
    Serial.println(F("log:furnace:WARN:ignoring request to start pump while boiler valve is on"));
  } else {
    pumpState = state;
    if (digitalRead(PUMP_RELAY_PIN) == pumpState) {
      digitalWrite(PUMP_RELAY_PIN, !pumpState);
      Serial.println(F("log:furnace:INFO:changed pump state"));
    }    
  }
}

/**
* Set the senso readings in their global variables.
* Remove the 85C error sensors sometimes return.
*/
void readSensors() {
  sensors.begin();
  sensors.requestTemperatures();
  
  Tboiler = filterSensorTemp(sensors.getTempC(boilerSensorAddress), Tboiler);
  if (sensorCount > 1) {
    Tauxillary = filterSensorTemp(sensors.getTempC(auxillarySensorAddress), Tauxillary);
  }
}

void logMaster() {
  Serial.print("furnace:");
  Serial.print(furnaceBoilerState ? "1:" : "0:");
  Serial.print(Tboiler);
  if (sensorCount > 1) {
    Serial.print(':');
    Serial.print(Tauxillary);
  }
  Serial.println();
}

void receiveFromMaster() {
  //line format: [furnace: T|F][pump: T|F]
  //or [?] 
  boolean receivedFurnaceState, receivedPumpState;
  short i = 0;
  while (Serial.available()) {
    char masterByte = Serial.read();
    if (i == 0) {
      if (masterByte == '?') {
        Serial.println(F("furnace"));
      }
      receivedFurnaceState = (masterByte == 'T');
    } else if (i == 1) {
      receivedPumpState = (masterByte == 'T');
    } else {
      Serial.read();
    }
    i++;
  }

  if (i == 2) {
    lastConnectTime = millis();
    setFurnaceHeating(receivedFurnaceState);
    setPump(receivedPumpState);
  } else if (i != 0 && 1 != 1) {
    Serial.println(F("log:furnace:ERROR:received unexpected master command"));
//    Serial.end();
//    Serial.begin(9600);
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
  if (sensorCount == 0) {
    byte addr[8];
    while (oneWire.search(addr)) {
      for (byte i = 0; i < 8; i++) {
        if (sensorCount == 0) {
          boilerSensorAddress[i] = addr[i];
        } else {
          auxillarySensorAddress[i] = addr[i];
        }
        Serial.print(addr[i]);
      }
      Serial.print(':');
      if (sensorCount == 0) {
        Serial.println(sensors.getTempC(boilerSensorAddress));
      } else {
        Serial.println(sensors.getTempC(auxillarySensorAddress));
      }
      sensorCount++;
    }
    if (sensorCount != 1 && sensorCount != 2) {
      Serial.println("log:furnace:ERROR:unexpected amount of sensors");
      delay(10000);
      sensorCount = 0;
    }
  }
}

// ######################################## /SETUP
