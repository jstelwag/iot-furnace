package i2c;

import com.pi4j.io.i2c.I2CDevice;
import org.apache.commons.lang3.StringUtils;
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

    protected Map<String,I2CDevice> devices = new HashMap<>();

    public boolean parse(String deviceId) {
        String slaveResponse;
        try {
            String request = Request.Post("http://" + monitorIp + ":" + monitorPort + "/valvegroup/")
                    .bodyString(deviceId + ":", ContentType.DEFAULT_TEXT).execute().returnContent().asString();
            devices.get(deviceId).write(request.trim().getBytes());
            slaveResponse = Master.response(devices.get(deviceId));
            System.out.println(request + "/" + slaveResponse);
        } catch (IOException e) {
            System.out.println("ERROR: Rescanning bus after communication error for " + deviceId);
            LogstashLogger.INSTANCE.message("ERROR: Rescanning bus after communication error for " + deviceId);
            return false;
        }
        if (StringUtils.countMatches(slaveResponse, ":") > 1) {
            //Send response from valvegroup back to monitor for logging
            try {
                Request.Post("http://" + monitorIp + ":" + monitorPort + "/valvegroup/")
                        .bodyString(slaveResponse, ContentType.DEFAULT_TEXT).execute().returnContent().asString();
            } catch (IOException e) {
                System.out.println("ERROR: failed to post valvegroup status for " + deviceId);
                LogstashLogger.INSTANCE.message("ERROR: failed to post valvegroup status for " + deviceId);
            }
        } else {
            System.out.println("ERROR: received garbage from the ValveGroup micro controller: " + slaveResponse);
            LogstashLogger.INSTANCE.message("ERROR: received garbage from the ValveGroup micro controller: " + slaveResponse);
        }
        return true;
    }
}
