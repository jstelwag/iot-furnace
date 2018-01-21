package util;

import org.apache.commons.lang3.math.NumberUtils;
import util.LogstashLogger;
import util.Properties;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by Jaap on 25-7-2016.
 */
public class TemperatureSensor {

    public final static Map<String, String> sensors = new HashMap<>();
    public final static String boiler;

    static {
        Properties prop = new Properties();
        String iotId = prop.prop.getProperty("iot.id");
        switch (iotId) {
            case "koetshuis_kelder":
                boiler = "boiler200";
                sensors.put(boiler, "Ttop");
                break;
            case "kasteel_zolder":
                boiler = "boiler120";
                sensors.put(boiler, "Tbottom");
                break;
            default:
                boiler = "error";
                LogstashLogger.INSTANCE.message("ERROR, undefined iot.id (" + iotId + "( for Temperature Sensor");
                System.exit(0);
        }
    }

    public static boolean isOutlier(String temperature) {
        return !NumberUtils.isParsable(temperature)
                || Double.parseDouble(temperature) < -5.0 || Double.parseDouble(temperature) > 120.0;
    }
}
