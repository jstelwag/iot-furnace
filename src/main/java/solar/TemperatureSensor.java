package solar;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by Jaap on 25-7-2016.
 */
public class TemperatureSensor {

    public final static Map<String, String[]> sensors = new HashMap<>();

    static {
        sensors.put("boiler500", new String[]{"Ttop", "Tmiddle", "Tbottom"});
        sensors.put("pipe", new String[]{"TflowIn", "TflowOut"});
    }
}
