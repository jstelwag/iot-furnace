package furnace;

import com.pi4j.io.i2c.I2CDevice;
import common.LogstashLogger;
import i2c.I2CUtil;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles requests and responses from a connected Arduino valvegroup (I2CValveBridge)
 */
public class I2CFurnaceMaster {
    private int minimumSlaveResponse = 2;
    private boolean hasAuxiliryTemperature = false;

    public I2CFurnaceMaster(boolean hasAuxilaryTemperature) {
        if (hasAuxilaryTemperature) {
            minimumSlaveResponse = 3;
            hasAuxiliryTemperature = true;
        }
    }

    public Map<String, I2CDevice> devices = new HashMap<>();

    public boolean parse(String deviceName) {
        try {
            devices.get(deviceName).write(slaveRequest().getBytes());
            String slaveResponse = I2CUtil.byteToString(devices.get(deviceName));

            int matchCount = StringUtils.countMatches(slaveResponse,":");
            if (matchCount >= minimumSlaveResponse + 1) {
                state2Redis(slaveResponse);
                if (matchCount == minimumSlaveResponse + 2) {
                    send2Log(slaveResponse);
                }
                LogstashLogger.INSTANCE.info("Requested furnace slave, request: " + slaveRequest()
                        + " and slave response: " + slaveResponse);
            } else {
                LogstashLogger.INSTANCE.error("Furnace slave response was not expected: " + slaveResponse
                        + ", after slave request: " + slaveRequest());
            }
        } catch (IOException e) {
            LogstashLogger.INSTANCE.error("Rescanning bus after communication error for " + deviceName);
            return false;
        }

        return true;
    }

    private String slaveRequest() {
        String slaveRequest;
        try (FurnaceDAO furnaceDAO = new FurnaceDAO()) {
            slaveRequest = furnaceDAO.getFurnaceState() ? "T" : "F";
            slaveRequest += furnaceDAO.getPumpState() ? "T" : "F";
        }
        return slaveRequest;
    }

    void send2Log(String slaveResponse) {
        try {
            int code = Integer.parseInt(slaveResponse.split(":")[minimumSlaveResponse + 2].trim());
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
        } catch (Exception e) {
            LogstashLogger.INSTANCE.error("Could not log the code message from this response " + slaveResponse
                    + ", " + e.getMessage());
        }
    }
    
    void state2Redis(String slaveResponse) {
        try (BoilerDAO boilerDAO = new BoilerDAO()) {
            boilerDAO.setState("1".equals(slaveResponse.split(":")[0].trim()));
            boilerDAO.setTemperature(slaveResponse.split(":")[1]);
            if (hasAuxiliryTemperature) {
                try (FurnaceDAO furnaceDAO = new FurnaceDAO()) {
                    furnaceDAO.setAuxiliaryTemperature(slaveResponse.split(":")[2]);
                }
            }
        }
    }
}
