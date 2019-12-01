package i2c;

import com.pi4j.io.i2c.I2CDevice;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.fluent.Request;
import redis.clients.jedis.Jedis;
import util.FluxLogger;
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
    private final String deviceName;

    public Double auxiliaryTemperature;
    public boolean boilerState = false;

    public FurnaceMaster(Properties prop) {
        this.monitorIp = prop.monitorIp;
        this.monitorPort = prop.monitorPort;
        this.deviceName = prop.deviceName;
    }

    protected Map<String, I2CDevice> devices = new HashMap<>();

    public boolean parse(String deviceId) {
        String slaveResponse, monitorResponse;
        String monitorRequest = "http://" + monitorIp + ":" + monitorPort + "/furnace/" + deviceId + "/";
        try {
            monitorResponse = Request.Get(monitorRequest).execute().returnContent().asString();
        } catch (IOException e) {
            //Ignore this error, without a directive from the monitor the furnace is controller
            //with other variables like date and outside temperature
            monitorResponse = e.getMessage();
            LogstashLogger.INSTANCE.error("Did not retrieve monitor response @" + monitorRequest);
        }
        String slaveRequest = furnaceState(monitorResponse) ? "T" : "F";
        slaveRequest += pumpState(monitorResponse) ? "T" : "F";
        try {
            devices.get(deviceId).write(slaveRequest.getBytes());
            slaveResponse = Master.response(devices.get(deviceId));

            if (StringUtils.countMatches(slaveResponse, ":") > 2) {
                state2Redis(slaveResponse);
                send2Flux(slaveResponse);
                send2Log(slaveResponse);
                LogstashLogger.INSTANCE.info("Requested furnace slave after monitor directive: " + monitorResponse
                        + ", slave request: " + slaveRequest + " and slave response: " + slaveResponse);
            } else {
                LogstashLogger.INSTANCE.error("Furnace slave response was not expected: " + monitorResponse
                        + ", slave request: " + slaveRequest + " and slave response: " + slaveResponse);
            }

        } catch (IOException e) {
            LogstashLogger.INSTANCE.error("Rescanning bus after communication error for " + deviceId);
            return false;
        }

        return true;
    }

    public boolean furnaceState(String furnaceResponse) {
        if (furnaceResponse.contains("furnace\"=\"ON")) {
            return true;
        }
        if (furnaceResponse.contains("furnace\"=\"OFF")) {
            return false;
        }
        LogstashLogger.INSTANCE.error("Unexpected response iot-monitor @/furnace " + furnaceResponse);
        Calendar now = Calendar.getInstance();
        if (now.get(Calendar.HOUR) < 23 && now.get(Calendar.HOUR) > 5) {
            return false;
        }

        if (auxiliaryTemperature != null) {
            LogstashLogger.INSTANCE.warn("No iot-monitor furnace state available, using outside temperature "
                    + auxiliaryTemperature);
            return auxiliaryTemperature < 16.0;
        }

        LogstashLogger.INSTANCE.warn("No iot-monitor furnace state available, using month based default");
        return now.get(Calendar.MONTH) < 4 || now.get(Calendar.MONTH) > 9;
    }

    public boolean pumpState(String furnaceResponse) {
        return !boilerState && furnaceResponse.contains("pump\"=\"ON");
    }

    void send2Flux(String slaveResponse) {
        try (FluxLogger flux = new FluxLogger()) {
            flux.send("boiler,name=" + TemperatureSensor.boiler + " state=" + slaveResponse.split(":")[0].trim());
            if (!TemperatureSensor.isOutlier(slaveResponse.split(":")[1].trim())) {
                flux.send("boiler,name=" + TemperatureSensor.boiler + ",position=" + TemperatureSensor.position + " temperature="
                        + slaveResponse.split(":")[1].trim());
            }
            if (StringUtils.countMatches(slaveResponse, ":") > 1
                    && !TemperatureSensor.isOutlier(slaveResponse.split(":")[2].trim())) {
                auxiliaryTemperature = Double.parseDouble(slaveResponse.split(":")[2].trim());
                flux.send("environment.temperature " + deviceName + "=" + slaveResponse.split(":")[2].trim());
            }
        } catch (IOException e) {
            LogstashLogger.INSTANCE.error("Failed to send to flux " + e.getMessage());
        }
    }

    void send2Log(String slaveResponse) {
        if (slaveResponse.split(":").length > 2) {
            int code = Integer.parseInt(slaveResponse.split(":")[3].trim());
            switch (code) {
                case 0:
                    // do nothing, no log to mention
                    break;
                case 1:
                    LogstashLogger.INSTANCE.info("Starting (furnace controller)");
                    break;
                case 2:
                    LogstashLogger.INSTANCE.info("Furnace switching off (furnace controller)");
                    break;
                case 3:
                    LogstashLogger.INSTANCE.info("Opening boiler valve (furnace controller)");
                    break;
                case 4:
                    LogstashLogger.INSTANCE.info("Turning off boiler (furnace controller)");
                    break;
                case 20:
                    LogstashLogger.INSTANCE.warn("Unconnected, using aux temp (furnace controller)");
                    break;
                case 21:
                    LogstashLogger.INSTANCE.warn("Unconnected, simply turned on (furnace controller)");
                    break;
                case 22:
                    LogstashLogger.INSTANCE.warn("Unexpected master command (furnace controller)");
                    break;
                case 23:
                    LogstashLogger.INSTANCE.warn("Temperature read failure (furnace controller)");
                    break;
                case 40:
                    LogstashLogger.INSTANCE.error("Sensor init: incomplete sensor count (furnace controller)");
                    break;
                default:
                    LogstashLogger.INSTANCE.error("Unknown logCode: " + code + " from furnace controller");
                    break;
            }
        }
    }
    
    void state2Redis(String slaveResponse) {
        try (Jedis jedis = new Jedis("localhost")) {
            boilerState = "1".equals(slaveResponse.split(":")[0].trim());
            jedis.setex(TemperatureSensor.boiler + ".state", Properties.redisExpireSeconds, slaveResponse.split(":")[0]);
            jedis.setex(TemperatureSensor.redisKey, Properties.redisExpireSeconds, slaveResponse.split(":")[1]);
        } catch (Exception e) {
            LogstashLogger.INSTANCE.error("Redis exception " + e.getMessage());
        }
    }
}
