package furnace;

import common.LogstashLogger;
import common.Properties;
import org.apache.http.client.fluent.Request;

import java.io.IOException;

/**
 * Get the furnace state from iot-monitor. Pass it on to the furnace slave via a Redis state setting
 */
public class FurnaceMonitor {
    private final String monitorIp;
    private final int monitorPort;
    private final String deviceName;

    public FurnaceMonitor() {
        Properties properties = new Properties();
        monitorIp = properties.monitorIp;
        monitorPort = properties.monitorPort;
        deviceName = properties.deviceName;
    }

    public void run() {
        try {
            String furnaceRequest = "http://" + monitorIp +":" + monitorPort + "/furnace/" + deviceName + "/";
            String furnaceResponse = Request.Get(furnaceRequest)
                    .execute().returnContent().asString();
            LogstashLogger.INSTANCE.info("Furnace response from the monitor for " + deviceName + ": " + furnaceResponse);
            parseAndPersist(furnaceResponse);
        } catch (IOException e) {
            LogstashLogger.INSTANCE.error("Connection failure with iot-monitor @/furnace.", e);
            // Keep last state in Redis, when the TTL expires the furnace will go to the default mode
        }
    }

    private void parseAndPersist(String response) {
        try (FurnaceDAO dao = new FurnaceDAO()) {
            if (response.contains("furnace\"=\"ON")) {
                dao.setFurnaceState(true);
            } else if (response.contains("OFF")) {
                dao.setFurnaceState(false);
            } else {
                LogstashLogger.INSTANCE.warn("Unexpected response iot-monitor @/furnace " + response);
                // Keep last state in Redis, when the TTL expires the furnace will go to the default mode
            }
            if (response.contains("pump\"=\"ON")) {
                dao.setPumpState(true);
            } else if (response.contains("OFF")) {
                dao.setPumpState(false);
            } else {
                LogstashLogger.INSTANCE.warn("Unexpected response iot-monitor @/furnace " + response);
                // Keep last state in Redis, when the TTL expires the furnace will go to the default mode
            }
        }
    }
}
