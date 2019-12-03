package furnace;

import common.LogstashLogger;
import common.Properties;
import org.apache.commons.lang3.math.NumberUtils;
import redis.clients.jedis.Jedis;

import java.io.Closeable;

public class BoilerDAO implements Closeable {

    private final Jedis jedis;
    private final int TTL1 = 60;

    public final static String position;
    public final static String boiler;
    public final static String tempKey;
    public static final String BOILER_KEY = "boiler.state";

    static {
        Properties prop = new Properties();
        boiler = prop.boilerName;
        position = prop.boilerSensor;
        tempKey = boiler + "." + position;
    }

    public BoilerDAO() {
        jedis = new Jedis("localhost");
    }

    public BoilerDAO(Jedis jedis) {
        this.jedis = jedis;
    }

    public void setState(boolean state) {
        jedis.setex(BOILER_KEY, TTL1, state ? "ON" : "OFF");
    }

    public boolean getState() {
        if (jedis.exists(BOILER_KEY)) {
            return "ON".equalsIgnoreCase(jedis.get(BOILER_KEY));
        }
        return false;
    }
    public Boolean getStateRaw() {
        if (jedis.exists(BOILER_KEY)) {
            return "ON".equalsIgnoreCase(jedis.get(BOILER_KEY));
        }
        return null;
    }

    public void setTemperature(String temperature) {
        if (!isOutlier(temperature.trim(), -10.0, 105.0, 20.0, getTemperature())) {
            jedis.setex(tempKey, TTL1, temperature.trim());
        }
    }

    public Double getTemperature() {
        if (jedis.exists(tempKey)) {
            return Double.parseDouble(jedis.get(tempKey));
        }
        return null;
    }

    public static boolean isOutlier(String temperature, double minTemp, double maxTemp, double maxDelta, Double previousTemperature) {
        try {
            Double.parseDouble(temperature);
        } catch (NumberFormatException e) {
            LogstashLogger.INSTANCE.warn("Not a parsable temperature '" + temperature + "'");
            return true;
        }
        double t = Double.parseDouble(temperature);
        if (t < minTemp || t > maxTemp) {
            LogstashLogger.INSTANCE.warn("Unrealistic temperature " + t);
            return true;
        }
        if (previousTemperature != null && Math.abs(previousTemperature - t) > maxDelta) {
            LogstashLogger.INSTANCE.warn("Ignoring too large temperature difference");
            return true;
        }
        return false;
    }

    @Override
    public void close() {
        jedis.close();
    }
}
