package util;

import org.apache.commons.lang3.math.NumberUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by Jaap on 25-7-2016.
 */
public class TemperatureSensor {

    public final static String position;
    public final static String boiler;

    static {
        Properties prop = new Properties();
        boiler = prop.prop.getProperty("boiler.name");
        position = prop.prop.getProperty("boiler.sensor");
    }

    public static boolean isOutlier(String temperature) {
        return !NumberUtils.isParsable(temperature)
                || Double.parseDouble(temperature) < -5.0 || Double.parseDouble(temperature) > 120.0;
    }
}
