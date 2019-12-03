package common;

import redis.clients.jedis.Jedis;

import java.io.*;

public class Properties {

    public java.util.Properties prop = null;
    public String cpuId;
    public String deviceName;

    public String influxIp = "192.168.78.14";
    public int influxPort = 8087;

    public String monitorIp = "192.168.178.18";
    public int monitorPort = 8000;

    public String logstashIp = "192.168.178.101";
    public int logstashPort = 9000;

    public String boilerName;
    public String boilerSensor;
    public boolean hasAuxilaryTemperature = true;

    public String services;
    public String loggers;

    private final String PROP_FILE = "/etc/iot.conf";
    private final String CPU_INFO = "/proc/cpuinfo";

    public Properties()  {
        File confFile = new File(PROP_FILE);
        if (confFile.exists()) {
            try (InputStream inputStream = new FileInputStream(confFile)) {
                prop = new java.util.Properties();
                prop.load(inputStream);
            } catch (IOException e) {
                prop = null;
                //todo logstash
                System.out.println("Could not open properties file " + PROP_FILE);
            }
        }
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
            //todo logstash
            System.out.println("Could not retrieve cpu serial number from " + CPU_INFO);
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
        }

        // Override from properties file
        if (prop != null) {
            if (prop.getProperty("iot.id") != null) {
                deviceName = prop.getProperty("iot.id");
            }
            if (prop.getProperty("influx.ip") != null) {
                influxIp = prop.getProperty("influx.ip");
            }
            if (prop.getProperty("influx.port") != null) {
                influxPort = Integer.parseInt(prop.getProperty("influx.port"));
            }
            if (prop.getProperty("monitor.ip") != null) {
                monitorIp = prop.getProperty("monitor.ip");
            }
            if (prop.getProperty("monitor.port") != null) {
                monitorPort = Integer.parseInt(prop.getProperty("monitor.port"));
            }
            if (prop.getProperty("logstash.ip") != null) {
                logstashIp = prop.getProperty("logstash.ip");
            }
            if (prop.getProperty("logstash.port") != null) {
                logstashPort = Integer.parseInt(prop.getProperty("logstash.port"));
            }
            if (prop.getProperty("boiler.name") != null) {
                boilerName = prop.getProperty("boiler.name");
            }
            if (prop.getProperty("boiler.sensor") != null) {
                boilerSensor = prop.getProperty("boiler.sensor");
            }
            if (prop.getProperty("services") != null) {
                services = prop.getProperty("services");
            }
            if (prop.getProperty("loggers") != null) {
                loggers = prop.getProperty("loggers");
            }
        }

        try (Jedis jedis = new Jedis("localhost")) {
            jedis.setex("deviceName", 24*60*60, deviceName);
            jedis.setex("services", 24*60*60, services);
            jedis.setex("loggers", 24*60*60, loggers);
        } catch (Exception e) {
            //todo logstash
        }
    }
}
