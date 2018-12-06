package i2c;

import com.pi4j.io.i2c.I2CDevice;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import util.LogstashLogger;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles requests and responses from a connected Arduino valvegroup (I2CValveBridge)
 */
public class ValveMaster {

    private final String monitorIp;
    private final int monitorPort;

    public ValveMaster(String monitorIp, int monitorPort) {
        this.monitorIp = monitorIp;
        this.monitorPort = monitorPort;
    }

    protected Map<String, I2CDevice> devices = new HashMap<>();

    public boolean parse(String deviceId) {
        String slaveResponse;
        try {
            String monitorRequest = "http://" + monitorIp + ":" + monitorPort + "/valvegroup/";
            String monitorResponse = Request.Post(monitorRequest)
                    .bodyString(deviceId + ":", ContentType.DEFAULT_TEXT).execute().returnContent().asString();
            devices.get(deviceId).write(monitorResponse.trim().getBytes());
            slaveResponse = Master.response(devices.get(deviceId));
            LogstashLogger.INSTANCE.info("Requested valve slave from monitor directive " + monitorResponse +
                    " which after passing on to the slave resulted in the following response: " + slaveResponse);
        } catch (IOException e) {
            LogstashLogger.INSTANCE.warn("Rescanning bus after communication error for " + deviceId);
            return false;
        }
        if (slaveResponse.contains("]")) {
            //Send response from valvegroup back to monitor for logging
            try {
                Request.Post("http://" + monitorIp + ":" + monitorPort + "/valvegroup/")
                        .bodyString(deviceId + ":" + slaveResponse.substring(0, slaveResponse.indexOf("]") + 1)
                                , ContentType.DEFAULT_TEXT).execute().returnContent().asString();
            } catch (IOException e) {
                LogstashLogger.INSTANCE.error("Failed to post valvegroup status for " + deviceId);
            }
        } else {
            LogstashLogger.INSTANCE.error("Received garbage from the ValveGroup micro controller: " + slaveResponse);
        }
        return true;
    }
}
