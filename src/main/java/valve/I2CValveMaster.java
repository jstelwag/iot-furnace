package valve;

import com.pi4j.io.i2c.I2CDevice;
import i2c.I2CMaster;
import i2c.I2CUtil;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import common.LogstashLogger;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import redis.clients.jedis.Jedis;

/**
 * Handles requests and responses from a connected Arduino valvegroup (I2CValveBridge)
 */
public class I2CValveMaster implements I2CMaster {

    private final String monitorIp;
    private final int monitorPort;

    private final int TTL = 60;

    public I2CValveMaster(String monitorIp, int monitorPort) {
        this.monitorIp = monitorIp;
        this.monitorPort = monitorPort;
    }

    final Map<String, I2CDevice> devices = new HashMap<>();

    public Map<String, I2CDevice> devices() {return devices;}

    public boolean request(String deviceId) {
        String slaveResponse;
        try {
            String monitorRequest = "http://" + monitorIp + ":" + monitorPort + "/valvegroup/";
            String monitorResponse = Request.Post(monitorRequest)
                    .bodyString(deviceId + ":", ContentType.DEFAULT_TEXT).execute().returnContent().asString();
            devices.get(deviceId).write(monitorResponse.trim().getBytes());
            slaveResponse = I2CUtil.byteToString(devices.get(deviceId));
            LogstashLogger.INSTANCE.info("Requested valve slave from monitor directive " + monitorResponse +
                    " which after passing on to the slave resulted in the following response: " + slaveResponse);
        } catch (IOException e) {
            LogstashLogger.INSTANCE.warn("Rescanning bus after communication error for " + deviceId);
            return false;
        }
        if (slaveResponse.contains("]")) {
            //Send response from valvegroup back to monitor for logging
            String response = deviceId + ":" + slaveResponse.substring(0, slaveResponse.indexOf("]") + 1);
            try (Jedis jedis = new Jedis("localhost")) {
                jedis.setex("lastValveResponse", TTL, response);
            } catch (Exception e) {
            }
            try {
                Request.Post("http://" + monitorIp + ":" + monitorPort + "/valvegroup/")
                        .bodyString(response, ContentType.DEFAULT_TEXT).execute().returnContent().asString();
            } catch (IOException e) {
                LogstashLogger.INSTANCE.error("Failed to post valvegroup status for " + deviceId);
            }
        } else {
            LogstashLogger.INSTANCE.error("Received garbage from the ValveGroup micro controller: " + slaveResponse);
        }
        return true;
    }
}
