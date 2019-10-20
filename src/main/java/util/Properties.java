package util;

import org.apache.commons.io.IOUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;

public class Properties {

    public static final int redisExpireSeconds = 5*60;
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

    public String services;
    public String loggers;

    public Properties()  {
        InputStream inputStream = null;
        try {
            File confFile = new File("/etc/iot.conf");
            if (confFile.exists()) {
                inputStream = new FileInputStream(confFile);
                prop = new java.util.Properties();
                prop.load(inputStream);
            }
            String line;
            BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"));
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Serial")) {
                    cpuId = line.replaceAll("Serial\\s+:", "").trim();
                    setProperties(cpuId);
                    break;
                }
            }
        } catch (IOException e) {
            prop = null;
        } finally {
            IOUtils.closeQuietly(inputStream);
        }
    }

    public void setProperties(String cpuId) {
        this.cpuId = cpuId;
        switch (cpuId) {
            case "000000006f0cfbce":
                System.out.println("hi");
                deviceName = "kasteel_kelder";
                services = "I2CMaster";
                loggers = "FluxLogger";
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
    }
}
