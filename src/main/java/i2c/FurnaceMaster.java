package i2c;

import com.pi4j.io.i2c.I2CDevice;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.fluent.Request;
import redis.clients.jedis.Jedis;
import util.LogstashLogger;
import util.Properties;
import util.TemperatureSensor;

import java.io.IOException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles requests and responses from a connected Arduino valvegroup (I2CValveBridge)
 */
public class FurnaceMaster {

    private final String monitorIp;
    private final int monitorPort;

    private Jedis jedis;

    public FurnaceMaster(String monitorIp, int monitorPort) {
        this.monitorIp = monitorIp;
        this.monitorPort = monitorPort;
    }

    protected Map<String, I2CDevice> devices = new HashMap<>();

    public boolean parse(String deviceId) {
        String slaveResponse;

        String request = "";
        String url = "http://" + monitorIp + ":" + monitorPort + "/furnace/" + deviceId + "/";
        try {
            request = Request.Get(url).execute().returnContent().asString();
        } catch (IOException e) {
            System.out.println("ERROR: did not retrieve monitor response @" + url);
            LogstashLogger.INSTANCE.message("ERROR: did not retrieve monitor response @" + url);
        }
        String slaveRequest = furnaceState(request) ? "T" : "F";
        slaveRequest += pumpState(request) ? "T" : "F";
        try {
            devices.get(deviceId).write(slaveRequest.getBytes());
            slaveResponse = Master.response(devices.get(deviceId));
            if (StringUtils.countMatches(":", slaveResponse) > 3) {
                state2Redis(slaveResponse);
            }
            System.out.println(request + "/" + slaveRequest + "/" + slaveResponse);
        } catch (IOException e) {
            System.out.println("ERROR: Rescanning bus after communication error for " + deviceId);
            LogstashLogger.INSTANCE.message("ERROR: Rescanning bus after communication error for " + deviceId);
            return false;
        }
        //todo send furnace state back to monito
        return true;
    }

    public boolean furnaceState(String furnaceResponse) {
        if (furnaceResponse.contains("furnace\"=\"ON")) {
            return true;
        }
        if (furnaceResponse.contains("furnace\"=\"OFF")) {
            return false;
        }
        LogstashLogger.INSTANCE.message("Unexpected response iot-monitor @/furnace " + furnaceResponse);
        Calendar now = Calendar.getInstance();
        if (now.get(Calendar.HOUR) < 23 && now.get(Calendar.HOUR) > 5) {
            return false;
        }

        jedis = new Jedis("localhost");
        if (jedis.exists("auxiliary.temperature")) {
            double temp = Double.parseDouble(jedis.get("auxiliary.temperature"));
            LogstashLogger.INSTANCE.message("No iot-monitor furnace state available, using outside temperature " + temp);
            jedis.close();
            return temp < 16.0;
        }
        jedis.close();

        LogstashLogger.INSTANCE.message("No iot-monitor furnace state available, using month based default");
        return now.get(Calendar.MONTH) < 4 || now.get(Calendar.MONTH) > 9;
    }

    public boolean pumpState(String furnaceResponse) {
        return furnaceResponse.contains("pump\"=\"ON");
    }

    void state2Redis(String slaveResponse) {
        jedis = new Jedis("localhost");
        jedis.setex(TemperatureSensor.boiler + ".state", Properties.redisExpireSeconds, slaveResponse.split(":")[2]);
        if (!TemperatureSensor.isOutlier(slaveResponse.split(":")[3])) {
            jedis.setex(TemperatureSensor.boiler + ".temperature", Properties.redisExpireSeconds, slaveResponse.split(":")[3]);
        }

        if (StringUtils.countMatches(slaveResponse, ":") > 4) {
            if (!TemperatureSensor.isOutlier(slaveResponse.split(":")[4])) {
                jedis.setex("auxiliary.temperature", Properties.redisExpireSeconds, slaveResponse.split(":")[4]);
            }
        }
        jedis.close();
    }
}
