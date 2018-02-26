package i2c;

import com.pi4j.io.i2c.I2CBus;
import com.pi4j.io.i2c.I2CDevice;
import com.pi4j.io.i2c.I2CFactory;
import org.apache.commons.lang3.StringUtils;
import redis.clients.jedis.Jedis;
import util.LogstashLogger;
import util.Properties;

import java.io.IOException;


/**
 * Created by Jaap on 20-1-2018.
 */
public class Master {

    private ValveMaster valve;
    private FurnaceMaster furnace;

    private final I2CBus bus;

    private Jedis jedis;

    public Master() throws IOException, I2CFactory.UnsupportedBusNumberException {
        jedis = new Jedis("localhost");
        if (jedis.exists("i2cmaster")) {
            jedis.close();
            System.exit(0);
        }

        Properties prop = new Properties();
        String ip = prop.prop.getProperty("monitor.ip");
        int port = Integer.parseInt(prop.prop.getProperty("monitor.port"));
        String boilerName = prop.prop.getProperty("boiler.name");
        String boilerSensor = prop.prop.getProperty("boiler.sensor");
        String iotId = prop.prop.getProperty("iot.id") == null ? "valvegroup" : prop.prop.getProperty("iot.id");

        valve = new ValveMaster(ip, port);
        furnace = new FurnaceMaster(ip, port, boilerName, boilerSensor, iotId);

        try {
            bus = I2CFactory.getInstance(I2CBus.BUS_1);
            System.out.println("Started i2c master for " + iotId);
        } catch (I2CFactory.UnsupportedBusNumberException | IOException e) {
            System.out.println("FATAL: cannot connect i2c bus " + e.getMessage());
            LogstashLogger.INSTANCE.message("FATAL: cannot connect i2c bus " + e.getMessage());
            throw e;
        }
    }

    public void run()  {
        scanDevices();
        while (true) {
            if (valve.devices.size() + furnace.devices.size() > 0) {
                //Touch a value to show other processes we are alive
                jedis = new Jedis("localhost");
                jedis.setex("i2cmaster", 90, "i am the one");
                jedis.close();
            } else {
                System.out.println("There are no devices connected to this master");
                LogstashLogger.INSTANCE.message("There are no devices connected to this master");
                System.exit(0);
            }
            for (String deviceId : valve.devices.keySet()) {
                if (!valve.parse(deviceId)) {
                    scanDevices();
                    break;
                }
            }
            for (String deviceId : furnace.devices.keySet()) {
                if (!furnace.parse(deviceId)) {
                    scanDevices();
                    break;
                }
            }
            try {
                Thread.sleep(30000);
                if (deviceCount() != (valve.devices.size() + furnace.devices.size())) {
                    scanDevices();
                }
            } catch (InterruptedException e) {
                //ignore
            }
        }
    }

    public static String response(I2CDevice device) throws IOException {
        String retval = "";

        byte res[] = new byte[32];
        device.read(res, 0, 32);
        for (byte b : res) {
            if (b > 0) {
                retval += (char)(b & 0xFF);
            }
        }

        return retval;
    }

    public int deviceCount() {
        int  retval = 0;
        for (int i = 0; i < 255; i++) {
            try {
                I2CDevice device = bus.getDevice(i);
                device.write("H".getBytes());
                if (StringUtils.isNotEmpty(response(device))) {
                    retval++;
                }
            } catch (IOException e) {
                //Device does not exist, ignore
            }
        }
        return retval;
    }

    public void scanDevices() {
        valve.devices.clear();
        furnace.devices.clear();
        for (int i = 0; i < 255; i++) {
            try {
                I2CDevice device = bus.getDevice(i);
                device.write("H".getBytes());
                String response = response(device);
                if (response.startsWith("F:") && StringUtils.countMatches(response, ":") > 1) {
                    furnace.devices.put(response.split(":")[1], device);
                } else if (response.startsWith("V")) {
                    valve.devices.put(response.substring(1), device);
                } else {
                    System.out.println("Unrecognized device " + response);
                }
            } catch (IOException e) {
                //Device does not exist, ignore
            }
        }
        System.out.println("Scanned " + valve.devices.size() + furnace.devices.size() + " devices");
        LogstashLogger.INSTANCE.message("Scanned " + valve.devices.size() + furnace.devices.size() + " devices");
    }
}


