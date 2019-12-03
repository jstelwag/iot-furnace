package furnace;

import common.LogstashLogger;
import common.Properties;
import redis.clients.jedis.Jedis;

import java.io.Closeable;

public class BoilerDAO implements Closeable {

    private final Jedis jedis;
    private final int TTL1 = 60;

    public final static String position;
    public final static String boiler;
    public final static String tempKey;

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
        jedis.setex(boiler + ".state", TTL1, state ? "ON" : "OFF");
    }

    public boolean getState() {
        if (jedis.exists(boiler + ".state")) {
            return "ON".equalsIgnoreCase(jedis.get(boiler + ".state"));
        }
        return false;
    }
    public Boolean getStateRaw() {
        if (jedis.exists(boiler + ".state")) {
            return "ON".equalsIgnoreCase(jedis.get(boiler + ".state"));
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
        double t;
        try {
            t = Double.parseDouble(temperature);
        } catch (NumberFormatException e) {
            LogstashLogger.INSTANCE.warn("Not a parsable temperature '" + temperature + "'");
            return true;
        }
        if (minTemp > t || t > maxTemp) {
            LogstashLogger.INSTANCE.warn("Temperature outside range " + temperature);
            return true;
        }
        if (previousTemperature != null && Math.abs(previousTemperature - t) > maxDelta) {
            LogstashLogger.INSTANCE.warn("Too large temperature difference " + temperature);
            return true;
        }
        return false;
    }

    @Override
    public void close() {
        jedis.close();
    }
}
