package common;

import java.io.*;

public class Properties {

    public String cpuId;
    public String deviceName;

    public final int httpPort = 8080;

    public String influxIp = "192.168.178.100";
    public int influxPort = 8087;

    public String monitorIp = "192.168.178.18";
    public int monitorPort = 8000;

    public String logstashIp = "192.168.178.101";
    public int logstashPort = 9000;

    public String boilerName;
    public String boilerSensor;
    public boolean hasAuxilaryTemperature = true;

    public final int elevation = 100;
    public final double latitude = 50.803;
    public final double longitude = 5.955;

    public final String usbSolar = "/dev/ttyACM0";
    public final String usbFurnace = "/dev/ttyUSB0";

    public String services;
    public String loggers;

    final String CPU_INFO = "/proc/cpuinfo";

    public Properties()  {
        try (BufferedReader br = new BufferedReader(new FileReader(CPU_INFO))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Serial")) {
                    cpuId = line.replaceAll("Serial\\s+:", "").trim();
                    setProperties(cpuId);
                    break;
                }
            }
        } catch (IOException e) {
            LogstashLogger.INSTANCE.error("Could not retrieve cpu serial number from " + CPU_INFO + ". " + e.getMessage());
        }
    }

    public void setProperties(String cpuId) {
        this.cpuId = cpuId;
        switch (cpuId) {
            case "000000006f0cfbce":
                deviceName = "koetshuis_kelder";
                services = "I2CMaster, http";
                break;
            case "00000000748d357c":
                deviceName = "kasteel_hal";
                services = "I2CMaster, http";
                break;
            case "0000000069cec62c":
                deviceName = "koetshuis_trap";
                services = "I2CMaster, http";
                break;
            case "000000009dbfb2a1":
                deviceName = "kasteel_zolder";
                services = "I2CMaster, http, FurnaceMonitor";
                loggers = "FurnaceStateToInflux";
                boilerName = "boiler120";
                boilerSensor = "Ttop";
                hasAuxilaryTemperature = true;
                break;
            case "00000000fee88d9a":
                deviceName = "sensor_room_2";
                services = "DallasTemperature";
                break;
            case "0000000056718ccc":
                deviceName = "sensor_apartment_I";
                services = "DallasTemperature";
                break;
            case "0000000018d4278e":
                deviceName = "koetshuis_kelder";
                boilerName = "boiler200";
                boilerSensor = "Ttop";
                services = "http, FurnaceMonitor, SolarControl, FurnaceSlave, SolarSlave";
                loggers = "FurnaceStateToInflux, SolarStateToInflux";
                break;
        }
    }
}
