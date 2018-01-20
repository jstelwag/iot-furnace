import com.pi4j.io.i2c.I2CBus;
import com.pi4j.io.i2c.I2CDevice;
import com.pi4j.io.i2c.I2CFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


/**
 * Created by Jaap on 20-1-2018.
 */
public class I2CMaster {
    Map<String,I2CDevice> devices = new HashMap<>();
    private final String iotId;
    private final String monitorIp;
    private final int monitorPort;

    private final I2CBus bus;

    private Jedis jedis;

    public I2CMaster() throws IOException, I2CFactory.UnsupportedBusNumberException {
        jedis = new Jedis("localhost");
        if (jedis.exists("i2cmaster")) {
            jedis.close();
            System.exit(0);
        }
        Properties prop = new Properties();
        iotId = prop.prop.getProperty("iot.id");
        monitorIp = prop.prop.getProperty("monitor.ip");
        monitorPort = Integer.parseInt(prop.prop.getProperty("monitor.port"));
        try {
            bus = I2CFactory.getInstance(I2CBus.BUS_1);
        } catch (I2CFactory.UnsupportedBusNumberException | IOException e) {
            System.out.println("FATAL: cannot connect i2c bus " + e.getMessage());
            LogstashLogger.INSTANCE.message("FATAL: cannot connect i2c bus " + e.getMessage());
            throw e;
        }
    }

    public void run()  {
        scanDevices();
        while (true) {
            if (devices.size() > 0) {
                jedis = new Jedis("localhost");
                jedis.setex("i2cmaster", 90, "i am the one");
                jedis.close();
            }
            for (String deviceId : devices.keySet()) {
                String slaveResponse;
                try {
                    String request = Request.Post("http://" + monitorIp +":" + monitorPort + "/valvegroup/")
                            .bodyString(deviceId + ":", ContentType.DEFAULT_TEXT).execute().returnContent().asString();
                    devices.get(deviceId).write(request.getBytes());
                    slaveResponse = response(devices.get(deviceId));
                } catch (IOException e) {
                    System.out.println("ERROR: Rescanning bus after communication error for " + deviceId);
                    LogstashLogger.INSTANCE.message("ERROR: Rescanning bus after communication error for " + deviceId);
                    scanDevices();
                    break;
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
            }
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                //ignore
            }
        }
    }

    public String response(I2CDevice device) throws IOException {
        String retval = "";
        byte res[] = new byte[30];
        device.read(res, 0, 30);
        for (byte b : res) {
            if (b > 0) {
                retval += (char)(b & 0xFF);
            }
        }

        return retval;
    }

    public void scanDevices() {
        devices.clear();
        for (int i = 0; i < 255; i++) {
            try {
                I2CDevice device = bus.getDevice(i);
                device.write((byte)0x00);
                String response = response(device);
                if (response.contains(":")) {
                    devices.put(response.split(":")[0], device);
                }
            } catch (IOException e) {
                //Device does not exist, ignore
            }
        }
        System.out.println("Scanned " + devices.size() + " devices");
        LogstashLogger.INSTANCE.message("Scanned " + devices.size() + " devices");
    }
}


