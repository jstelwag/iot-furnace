package util;

import org.apache.commons.lang3.math.NumberUtils;

/**
 * Created by Jaap on 25-7-2016.
 */
public class TemperatureSensor {

    public final static String position;
    public final static String boiler;
    public final static String tempKey;
    public final static String stateKey;

    static {
        Properties prop = new Properties();
        boiler = prop.boilerName;
        position = prop.boilerSensor;
        tempKey = boiler + "." + position;
        stateKey = boiler + ".state";
    }

    public static boolean isOutlier(String temperature) {
        return !NumberUtils.isParsable(temperature)
                || Double.parseDouble(temperature) < -5.0 || Double.parseDouble(temperature) > 120.0;
    }
}
