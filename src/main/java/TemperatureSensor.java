import org.apache.commons.lang3.math.NumberUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by Jaap on 25-7-2016.
 */
public class TemperatureSensor {

    public final static Map<String, String[]> sensors = new HashMap<>();

    public static final String SOLAR_STATE = "solar.state";

    static {
        //sensors.put("boiler500", new String[]{"Ttop", "Tmiddle", "Tbottom"});
        //sensors.put("boiler200", new String[]{"Ttop"});
        //sensors.put("pipe", new String[]{"TflowIn", "TflowOut"});
        sensors.put("boiler120", new String[]{"Tbottom"});
    }

    public static boolean isOutlier(String temperature) {
        return !NumberUtils.isParsable(temperature)
                || Double.parseDouble(temperature) < -5.0 || Double.parseDouble(temperature) > 120.0;
    }
}
