import com.pi4j.io.i2c.I2CBus;
import com.pi4j.io.i2c.I2CDevice;
import com.pi4j.io.i2c.I2CFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;

import java.io.IOException;
import java.io.PrintWriter;
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

    public I2CMaster() {
        Properties prop = new Properties();
        iotId = prop.prop.getProperty("iot.id");
        monitorIp = prop.prop.getProperty("monitor.ip");
        monitorPort = Integer.parseInt(prop.prop.getProperty("monitor.port"));
    }

    public void run() throws IOException, I2CFactory.UnsupportedBusNumberException, InterruptedException {
        I2CBus i2c = I2CFactory.getInstance(I2CBus.BUS_1);

        for (int i = 0; i < 255; i++) {
            try {
                I2CDevice device = i2c.getDevice(i);
                device.write((byte)0x00);
                String response = response(device);
                if (response.contains(":")) {
                    devices.put(response.split(":")[0], device);
                }
            } catch (IOException e) {
                //Device does not exist, ignore
            }
        }

        while (true) {
            for (String deviceId : devices.keySet()) {
                String request = Request.Post("http://" + monitorIp +":" + monitorPort + "/valvegroup/")
                        .bodyString(deviceId + ":", ContentType.DEFAULT_TEXT).execute().returnContent().asString();
                devices.get(deviceId).write(request.getBytes());
                String slaveResponse = response(devices.get(deviceId));

                if (StringUtils.countMatches(slaveResponse, ":") > 1) {
                    //Send response from valvegroup back to monitor for logging
                    Request.Post("http://" + monitorIp +":" + monitorPort + "/valvegroup/")
                            .bodyString(slaveResponse, ContentType.DEFAULT_TEXT).execute().returnContent().asString();
                } else {
                    System.out.println("ERROR: received garbage from the ValveGroup micro controller: " + slaveResponse);
                    LogstashLogger.INSTANCE.message("ERROR: received garbage from the ValveGroup micro controller: " + slaveResponse);
                }
            }
            Thread.sleep(30000);
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
}


