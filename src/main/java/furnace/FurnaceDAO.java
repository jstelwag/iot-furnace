package furnace;

import common.LogstashLogger;
import redis.clients.jedis.Jedis;

import java.io.Closeable;
import java.util.Calendar;

import static furnace.BoilerDAO.*;

public class FurnaceDAO implements Closeable {

    private final Jedis jedis;
    private final int TTL5 = 5*60;

    public static final String FURNACE_KEY = "furnace.state";
    public static final String PUMP_KEY = "furnace.pumpState";
    public static final String AUXILIARY_TEMP_KEY = "auxiliary.temp";

    public FurnaceDAO() {
        jedis = new Jedis("localhost");
    }

    public void setFurnaceState(boolean state) {
        jedis.setex(FURNACE_KEY, TTL5, state ? "ON" : "OFF");
    }

    public void setPumpState(boolean state) {
        jedis.setex(PUMP_KEY, TTL5, state ? "ON" : "OFF");
    }

    public void setAuxiliaryTemperature(String temperature) {
        if (!isOutlier(temperature, -30.0, 50.0, 3.0, getAuxiliaryTemperature())) {
            jedis.setex(AUXILIARY_TEMP_KEY, TTL5, temperature);
        }
    }

    public Double getAuxiliaryTemperature() {
        if (jedis.exists(AUXILIARY_TEMP_KEY)) {
            return Double.parseDouble(jedis.get(AUXILIARY_TEMP_KEY));
        }
        return null;
    }

    public boolean getFurnaceState() {
        if (jedis.exists(FURNACE_KEY)) {
            return "ON".equalsIgnoreCase(jedis.get(FURNACE_KEY));
        } else {
            LogstashLogger.INSTANCE.warn("No desired furnace state available from monitor, creating one myself");
            Calendar now = Calendar.getInstance();
            if (now.get(Calendar.HOUR) < 23 && now.get(Calendar.HOUR) > 5) {
                return false;
            }

            if (getAuxiliaryTemperature() != null) {
                return getAuxiliaryTemperature() < 16.0;
            }
            return now.get(Calendar.MONTH) < 4 || now.get(Calendar.MONTH) > 9;
        }
    }

    public boolean getPumpState() {
        return !new BoilerDAO(jedis).getState() && jedis.exists(PUMP_KEY) && "ON".equalsIgnoreCase(jedis.get(PUMP_KEY));
    }

    @Override
    public void close() {
        jedis.close();
    }
}
