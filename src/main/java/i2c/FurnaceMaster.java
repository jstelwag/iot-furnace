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
    private final String boilerName;
    private final String boilerSensor;
    private final String iotId;

    public Double auxiliaryTemperature;
    public boolean boilerState = false;

    private Jedis jedis;

    public FurnaceMaster(String monitorIp, int monitorPort, String boiler, String boilerSensor, String iotId) {
        this.monitorIp = monitorIp;
        this.monitorPort = monitorPort;
        this.boilerName = boiler;
        this.boilerSensor = boilerSensor;
        this.iotId = iotId;
    }

    protected Map<String, I2CDevice> devices = new HashMap<>();

    public boolean parse(String deviceId) {
        String slaveResponse;

        String monitorResponse;
        String monitorRequest = "http://" + monitorIp + ":" + monitorPort + "/furnace/" + deviceId + "/";
        try {
            monitorResponse = Request.Get(monitorRequest).execute().returnContent().asString();
        } catch (IOException e) {
            //Ignore this error, without a directive from the monitor the furnace is controller
            //with other variables like date and outside temperature
            monitorResponse = e.getMessage();
            System.out.println("ERROR: did not retrieve monitor response @" + monitorRequest);
            LogstashLogger.INSTANCE.message("ERROR: did not retrieve monitor response @" + monitorRequest);
        }
        String slaveRequest = furnaceState(monitorResponse) ? "T" : "F";
        slaveRequest += pumpState(monitorResponse) ? "T" : "F";
        try {
            devices.get(deviceId).write(slaveRequest.getBytes());
            slaveResponse = Master.response(devices.get(deviceId));
            if (StringUtils.countMatches(slaveResponse, ":") > 3) {
                state2Redis(slaveResponse);
                send2Flux(slaveResponse);
                send2Log(slaveResponse);
            }
            LogstashLogger.INSTANCE.message("Requested furnace slave after monitor directive: " + monitorResponse
                    + ", slave request: " + slaveRequest + " and slave response: " + slaveResponse);
        } catch (IOException e) {
            System.out.println("ERROR: Rescanning bus after communication error for " + deviceId);
            LogstashLogger.INSTANCE.message("ERROR: Rescanning bus after communication error for " + deviceId);
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
        LogstashLogger.INSTANCE.message("Unexpected response iot-monitor @/furnace " + furnaceResponse);
        Calendar now = Calendar.getInstance();
        if (now.get(Calendar.HOUR) < 23 && now.get(Calendar.HOUR) > 5) {
            return false;
        }

        if (auxiliaryTemperature != null) {
            LogstashLogger.INSTANCE.message("No iot-monitor furnace state available, using outside temperature "
                    + auxiliaryTemperature);
            return auxiliaryTemperature < 16.0;
        }
        jedis.close();

        LogstashLogger.INSTANCE.message("No iot-monitor furnace state available, using month based default");
        return now.get(Calendar.MONTH) < 4 || now.get(Calendar.MONTH) > 9;
    }

    public boolean pumpState(String furnaceResponse) {
        return !boilerState && furnaceResponse.contains("pump\"=\"ON");
    }

    void send2Flux(String slaveResponse) {
        try (FluxLogger flux = new FluxLogger()) {
            flux.send(boilerName + ".state value=" + slaveResponse.split(":")[2]);
            if (!TemperatureSensor.isOutlier(slaveResponse.split(":")[3].trim())) {
                flux.send(boilerName + ".temperature " + boilerSensor + "=" + slaveResponse.split(":")[3].trim());
            }
            if (StringUtils.countMatches(slaveResponse, ":") > 4
                    && !TemperatureSensor.isOutlier(slaveResponse.split(":")[4].trim())) {
                auxiliaryTemperature = Double.parseDouble(slaveResponse.split(":")[4].trim());
                flux.send("environment.temperature " + iotId + "=" + slaveResponse.split(":")[4].trim());
            }
        } catch (IOException e) {
            System.out.println("ERROR: failed to send to flux " + e.getMessage());
            LogstashLogger.INSTANCE.message("ERROR: failed to send to flux " + e.getMessage());
        }
    }

    void send2Log(String slaveResponse) {
        if (StringUtils.countMatches(slaveResponse, ":") > 4) {
            int code = Integer.parseInt(slaveResponse.split(":")[4]);
            switch (code) {
                case 1:
                    LogstashLogger.INSTANCE.message("INFO_STARTED");
                    break;
                case 2:
                    LogstashLogger.INSTANCE.message("INFO_FURNACE_OFF");
                    break;
                case 3:
                    LogstashLogger.INSTANCE.message("INFO_BOILER_VALVE_OPEN");
                    break;
                case 4:
                    LogstashLogger.INSTANCE.message("INFO_BOILER_OFF");
                    break;
                case 20:
                    LogstashLogger.INSTANCE.message("WARN_UNCONNECTED_AUX_TEMP");
                    break;
                case 21:
                    LogstashLogger.INSTANCE.message("WARN_UNCONNECTED_ON");
                    break;
                case 22:
                    LogstashLogger.INSTANCE.message("WARN_UNEXPECTED_MASTER_COMMAND");
                    break;
                case 23:
                    LogstashLogger.INSTANCE.message("WARN_TEMP_READ_FAILURE");
                    break;
                case 40:
                    LogstashLogger.INSTANCE.message("ERROR_SENSOR_INIT_COUNT");
                    break;
            }
        }
    }


    void state2Redis(String slaveResponse) {
        jedis = new Jedis("localhost");
        boilerState = "1".equals(slaveResponse.split(":")[2].trim());
        jedis.setex(TemperatureSensor.boiler + ".state", Properties.redisExpireSeconds, slaveResponse.split(":")[2]);
        jedis.close();
    }
}
