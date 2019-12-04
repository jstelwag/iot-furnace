package i2c;

import com.pi4j.io.i2c.I2CBus;
import com.pi4j.io.i2c.I2CDevice;
import com.pi4j.io.i2c.I2CFactory;
import com.pi4j.io.i2c.I2CFactory.UnsupportedBusNumberException;
import furnace.I2CFurnaceMaster;

import org.apache.commons.lang3.StringUtils;
import redis.clients.jedis.Jedis;
import common.Properties;
import common.LogstashLogger;
import valve.I2CValveMaster;

import java.io.IOException;
import java.util.Date;

/**
 * Created by Jaap on 20-1-2018.
 */
public class Master {

    private final I2CValveMaster valve;
    private final I2CFurnaceMaster furnace;
    private final I2CBus bus;
    private long lastSuccessTime = 0;

    public Master() throws IOException, UnsupportedBusNumberException {
        Properties prop = new Properties();
        lastSuccessTime = new Date().getTime();
        valve = new I2CValveMaster(prop.monitorIp, prop.monitorPort);
        furnace = new I2CFurnaceMaster(prop.hasAuxilaryTemperature);
        try {
            bus = I2CFactory.getInstance(I2CBus.BUS_1);
            LogstashLogger.INSTANCE.info("Started i2c master");
        } catch (UnsupportedBusNumberException | IOException e) {
            LogstashLogger.INSTANCE.fatal("Cannot connect to the i2c bus.", e);
            throw e;
        }
    }

    public void run()  {
        scanDevices();
        while (true) {
            if (valve.devices().size() + furnace.devices().size() == 0) {
                if (new Date().getTime() - lastSuccessTime > 120000) {
                    LogstashLogger.INSTANCE.error("There are no devices connected to this master, rebooting");
                    try {
                        Runtime.getRuntime().exec("sudo reboot");
                    } catch (IOException e) {
                        LogstashLogger.INSTANCE.fatal("Failed to reboot.", e);
                    }
                }
            } else {
                lastSuccessTime = new Date().getTime();
                for (String deviceId : valve.devices().keySet()) {
                    if (!valve.request(deviceId)) {
                        scanDevices();
                        break;
                    }
                }
                for (String deviceId : furnace.devices().keySet()) {
                    if (!furnace.request(deviceId)) {
                        scanDevices();
                        break;
                    }
                }
            }
            try {
                Thread.sleep(30000);
                if (deviceCount() != (valve.devices().size() + furnace.devices().size())) {
                    scanDevices();
                }
            } catch (InterruptedException e) {
                //ignore
            }
        }
    }

    public int deviceCount() {
        int retval = 0;
        for (int i = 0; i < 255; i++) {
            try {
                I2CDevice device = bus.getDevice(i);
                device.write("H".getBytes()); // "H" is a hello message
                if (StringUtils.isNotEmpty(I2CUtil.byteToString(device))) {
                    retval++;
                }
            } catch (IOException ignored) {
                //Device does not exist, ignore
            }
        }
        return retval;
    }

    public void scanDevices() {
        valve.devices().clear();
        furnace.devices().clear();
        for (int i = 0; i < 255; i++) {
            try {
                I2CDevice device = bus.getDevice(i); //throws an exception when the device does not exist
                device.write("H".getBytes());
                String response = I2CUtil.byteToString(device);
                LogstashLogger.INSTANCE.info("Device " + i + " response " + response);
                String splittedResponse[] = response.split(":");
                if (response.startsWith("F:") && splittedResponse.length > 2) {
                    //deprecate
                    furnace.devices().put(splittedResponse[1], device);
                } else if (response.startsWith("H:")) {
                    furnace.devices().put(splittedResponse[1], device);
                } else if (response.startsWith("V") && response.contains("]")) {
                    valve.devices().put(response.substring(1, response.indexOf("]")), device);
                } else {
                    LogstashLogger.INSTANCE.error("Unrecognized device " + response);
                }
            } catch (IOException ignored) {
            }
        }
        try (Jedis jedis = new Jedis("localhost")) {
            jedis.set("valveGroupCount", Integer.toString(valve.devices().size()));
            jedis.set("furnaceCount", Integer.toString(furnace.devices().size()));
        } catch (Exception e) {
            //Ignore, as this is just for information
        }
        LogstashLogger.INSTANCE.info("Scanned " + (valve.devices().size() + furnace.devices().size()) + " devices");
    }
}
