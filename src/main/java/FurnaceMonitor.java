import org.apache.http.client.fluent.Request;
import redis.clients.jedis.Jedis;

import java.io.IOException;

/**
 * Get the furnace state from iot-monitor. Pass it on to the furnace slave via a Redis state setting
 */
public class FurnaceMonitor {
    private final String monitorIp;
    private final int monitorPort;

    public static final String FURNACE_KEY = "furnace.state";
    public static final String PUMP_KEY = "furnace.pumpState";
    private final int TTL = 60*10;

    public FurnaceMonitor() {
        Properties properties = new Properties();
        monitorIp = properties.prop.getProperty("monitor.ip");
        monitorPort = Integer.parseInt(properties.prop.getProperty("monitor.port"));
    }


    public void run() {
        Jedis jedis = new Jedis("localhost");
        try {
            String furnaceResponse = Request.Get("http://" + monitorIp +":" + monitorPort + "/furnace/koetshuis_kelder/")
                    .execute().returnContent().asString();
            if (furnaceResponse.contains("furnace\"=\"ON")) {
                jedis.setex(FURNACE_KEY, TTL, "ON");
            } else if (furnaceResponse.contains("OFF")) {
                jedis.setex(FURNACE_KEY, TTL, "OFF");
            } else {
                LogstashLogger.INSTANCE.message("Unexpected response iot-monitor @/furnace " + furnaceResponse);
                // Keep last state in Redis, when the TTL expires the furnace will go to the default mode
            }
            if (furnaceResponse.contains("pump\"=\"ON")) {
                jedis.setex(PUMP_KEY, TTL, "ON");
            } else if (furnaceResponse.contains("OFF")) {
                jedis.setex(PUMP_KEY, TTL, "OFF");
            } else {
                LogstashLogger.INSTANCE.message("Unexpected response iot-monitor @/furnace " + furnaceResponse);
                // Keep last state in Redis, when the TTL expires the furnace will go to the default mode
            }

        } catch (IOException e) {
            LogstashLogger.INSTANCE.message("Connection failure with iot-monitor @/furnace " + e.toString());
            // Keep last state in Redis, when the TTL expires the furnace will go to the default mode
            e.printStackTrace();
        }
        jedis.close();
    }

}
