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
#include <OneWire.h>
#include <DallasTemperature.h>
#include <Wire.h>

/**
* Simple boiler controller. Turns the furnace on and off when the preset temperature is reached.
*
* The setup assumes you have a boiler, a (gas) furnace and a three-way valve. All is connected together with
* an Arduino, a temperature sensor and two relays (one for the furnace and one for the three-way valve).
*
* It is a slave. It needs to be leashed through Wire (i2c) to the master which is probably a Raspberry Pi running
* iot-solar-boiler/I2CMaster
*/

#define nnkoetshuis_kelder
#define nnkasteel_torenkelder
#define kasteel_zolder

#define ntest_relays
#define Nmock_onewire

#ifdef koetshuis_kelder
  const char DEVICE_ID[] = "F:koetshuis_kelder";
  const byte SLAVE_ADDRESS = 0x21;
#elif defined(kasteel_torenkelder)
  const char DEVICE_ID[] = "F:kasteel_torenkelder";
  const byte SLAVE_ADDRESS = 0x22;
#elif defined(kasteel_zolder)
  const char DEVICE_ID[] = "kasteel_zolder";
  const byte SLAVE_ADDRESS = 0x23;
#endif

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
// use values that lead to non-action from the start
double Tboiler = 65.0;
double Tauxillary = 18.0;

boolean furnaceBoilerState = false;  // state == false is normal heating mode
boolean boilerValveState = false; // valve == false is normal heating mode
boolean furnaceHeatingState = false;
boolean pumpState = false;

const long DISCONNECT_TIMOUT = 180000;
unsigned long lastConnectTime;
unsigned long runoutTime = 0; // Switch of gracetime
long timeOut = 0; //time out of grace

//Request from master to return ID
boolean scanRequest = false;

byte logCode = 0;
// logCode values. Higher number is more severe 
const byte INFO_STARTED = 1;
const byte INFO_FURNACE_OFF = 2;
const byte INFO_BOILER_VALVE_OPEN = 3;
const byte INFO_BOILER_OFF = 4;
const byte WARN_UNCONNECTED_AUX_TEMP = 20;
const byte WARN_UNCONNECTED_ON = 21;
const byte WARN_UNEXPECTED_MASTER_COMMAND = 22;
const byte WARN_TEMP_READ_FAILURE = 23;
const byte ERROR_SENSOR_INIT_COUNT = 40;

const float BOILER_START_TEMP = 50.0;
const float BOILER_STOP_TEMP = 56.5;

void setup() {
  pinMode(BOILER_VALVE_RELAY_PIN, OUTPUT);
  pinMode(FURNACE_BOILER_RELAY_PIN, OUTPUT);
  pinMode(FURNACE_HEATING_RELAY_PIN, OUTPUT);
  pinMode(PUMP_RELAY_PIN, OUTPUT);
  digitalWrite(FURNACE_BOILER_RELAY_PIN, !furnaceBoilerState);
  digitalWrite(BOILER_VALVE_RELAY_PIN, !boilerValveState);
  digitalWrite(FURNACE_HEATING_RELAY_PIN, !furnaceHeatingState);
  digitalWrite(PUMP_RELAY_PIN, !pumpState);

#ifdef test_relays
  Serial.begin(9600);
  testRelays();
#endif

  lastConnectTime = millis(); //assume connection has been successful

  Wire.begin(SLAVE_ADDRESS);
  Wire.onReceive(receiveData);
  Wire.onRequest(sendData);

  log(INFO_STARTED);
}

void loop() {
  #ifndef mock_onewire
    setupSensors();
    readSensors();
  #else
    sensorCount = 2;
    Tboiler = 48.0;
    Tauxillary = 12.0;
  #endif
  
  if (millis() < lastConnectTime) {
    lastConnectTime = millis();
  }

  
  if (runoutTime == 0) {
    furnaceControl();
    unconnectedHeatingControl();
  } else {  
    if (millis() < runoutTime || millis() > runoutTime + timeOut) {
      runoutTime = 0;
    }
  }
}

void furnaceControl() {
  if (Tboiler < BOILER_START_TEMP) {
    setPump(false);
    if (furnaceHeatingState) {
      setFurnaceHeating(false);
      log(INFO_FURNACE_OFF);
      grace(150000);
    } else if (!boilerValveState) {
      setBoilerValve(true);
      log(INFO_BOILER_VALVE_OPEN);
      grace(5000); // wait for the valve to switch
    } else {
      setBoilerValve(true); //repeat this to make sure it is set
      setFurnaceBoiler(true);
    }
  } else if (Tboiler < BOILER_STOP_TEMP && furnaceBoilerState) {
    //Keep the furnace buring
  } else {
    if (furnaceBoilerState) {
      setFurnaceBoiler(false);
      log(INFO_BOILER_OFF);
      grace(120000); //wait for the pump to stop
    } else {
      setBoilerValve(false);
    }
  }
}

void unconnectedHeatingControl() {
  if (!furnaceBoilerState && lastConnectTime + DISCONNECT_TIMOUT < millis()) {
    //Connection lost, go to native mode
    setPump(false);
    if (sensorCount > 1) {
      log(WARN_UNCONNECTED_AUX_TEMP);
      //There is an auxillary sensor, use it
      if (Tauxillary < 15.0) {
        setFurnaceHeating(true);
      } else {
        setFurnaceHeating(false);
      }
    } else {
      //Assume heating must be on...
      log(WARN_UNCONNECTED_ON);
      setFurnaceHeating(true);
    }
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
  }
}

void setBoilerValve(boolean state) {
  if (state && (furnaceHeatingState || furnaceBoilerState)) {
    //ignore valve change when furnace is on
  } else {
    boilerValveState = state;
    if (digitalRead(BOILER_VALVE_RELAY_PIN) == boilerValveState) {
      digitalWrite(BOILER_VALVE_RELAY_PIN, !boilerValveState);
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
  }
}

void setPump(boolean state) {
  if (!furnaceHeatingState && state) {
    //ignore request to start pump while furnace is off
  } else if (boilerValveState && state) {
    //ignore request to start pump while boiler valve is on
  } else {
    pumpState = state;
    if (digitalRead(PUMP_RELAY_PIN) == pumpState) {
      digitalWrite(PUMP_RELAY_PIN, !pumpState);
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

void sendData() {
  if (scanRequest) {
    scanRequest = false;
    Wire.write('H');
    Wire.write(':');
    Wire.write(DEVICE_ID);
  } else {
    Wire.write(furnaceBoilerState ? '1' : '0');
    Wire.write(':');
    char result[5] = "";
    Wire.write(dtostrf(Tboiler,5, 1, result));
    if (sensorCount > 1) {
      Wire.write(':');
      Wire.write(dtostrf(Tauxillary,5, 1, result));
    }
    if (logCode > 0) {
      Wire.write(':');
      char ibuf[15] = "";
      Wire.write(itoa(logCode, ibuf, 10));
      logCode = 0;
    }
  }
}

void receiveData(int howMany) {
  //line format: [furnace: T|F][pump: T|F] or [scan request: H]
  boolean receivedFurnaceState, receivedPumpState;
  int i = 0;
  while (i < howMany && Wire.available()) {
    char request = Wire.read();
    if (i == 0) {
      if (request == 'H') {
        scanRequest = true;
      } else {
        receivedFurnaceState = (request == 'T');
      }
    } else if (i == 1) {
      receivedPumpState = (request == 'T');
    }
    i++;
  }

  if (scanRequest) {
    // do nothing 
  } else if (i == 2) {
    lastConnectTime = millis();
    setFurnaceHeating(receivedFurnaceState);
    setPump(receivedPumpState);
  } else if (i > 0) {
    log(WARN_UNEXPECTED_MASTER_COMMAND);
  }
}

void grace(long graceTime) {
  timeOut = graceTime;
  runoutTime = millis();
}

void log(byte code) {
  if (code > logCode) {
    logCode = code;
  }
}

/**
* Filters typical sensor failure at 85C and -127C
*/
float filterSensorTemp(float rawSensorTemp, float currentTemp) {
  if (rawSensorTemp == 85.0 && (abs(rawSensorTemp - 85) > MAX_TEMP_CHANGE_THRESHOLD_85) || rawSensorTemp == -127.0) {
    log(WARN_TEMP_READ_FAILURE);
    return currentTemp;
  } else {
    return rawSensorTemp;
  }
}

#ifdef test_relays
void testRelays() {
  Serial.println(F("FURNACE_BOILER_RELAY_PIN on 5"));
  digitalWrite(FURNACE_BOILER_RELAY_PIN, false);
  delay(500);
  Serial.println(F("FURNACE_BOILER_RELAY_PIN off 5"));
  digitalWrite(FURNACE_BOILER_RELAY_PIN, true);
  delay(1000);
  
  Serial.println(F("FURNACE_BOILER_RELAY_PIN on 6"));
  digitalWrite(FURNACE_BOILER_RELAY_PIN, false);
  delay(500);
  Serial.println(F("FURNACE_BOILER_RELAY_PIN off 6"));
  digitalWrite(FURNACE_BOILER_RELAY_PIN, true);
  delay(1000);
  
  Serial.println(F("FURNACE_HEATING_RELAY_PIN on 7"));
  digitalWrite(FURNACE_HEATING_RELAY_PIN, false);
  delay(500);
  Serial.println(F("FURNACE_HEATING_RELAY_PIN off 7"));
  digitalWrite(FURNACE_HEATING_RELAY_PIN, true);
  delay(1000);
  
  Serial.println(F("PUMP_RELAY_PIN on 8"));
  digitalWrite(PUMP_RELAY_PIN, false);
  delay(500);
  Serial.println(F("PUMP_RELAY_PIN off 8"));
  digitalWrite(PUMP_RELAY_PIN, true);
  delay(1000);
  
  digitalWrite(FURNACE_BOILER_RELAY_PIN, !furnaceBoilerState);
  digitalWrite(BOILER_VALVE_RELAY_PIN, !boilerValveState);
  digitalWrite(FURNACE_HEATING_RELAY_PIN, !furnaceHeatingState);
  digitalWrite(PUMP_RELAY_PIN, !pumpState);
}
#endif

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
      }
      sensorCount++;
    }
    if (sensorCount != 1 && sensorCount != 2) {
      log(ERROR_SENSOR_INIT_COUNT);
      delay(30000);
      sensorCount = 0;
    }
    oneWire.reset_search();
  }
}

// ######################################## /SETUP
