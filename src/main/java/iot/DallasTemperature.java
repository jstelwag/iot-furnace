package iot;

import java.io.IOException;
import com.pi4j.io.w1.W1Device;
import com.pi4j.io.w1.W1Master;
import com.pi4j.component.temperature.impl.TmpDS18B20DeviceType;
import com.pi4j.component.temperature.TemperatureSensor;
import common.LogstashLogger;
import org.apache.http.client.fluent.Request;
import common.Properties;

public class DallasTemperature {

    public void run() {
        W1Master master = new W1Master();
        if (master.getDevices(TmpDS18B20DeviceType.FAMILY_CODE).size() == 1) {
            W1Device device = master.getDevices(TmpDS18B20DeviceType.FAMILY_CODE).get(0);
            send(((TemperatureSensor) device).getTemperature());
        } else {
            LogstashLogger.INSTANCE.error("Unexpected sensor count " + master.getDevices(TmpDS18B20DeviceType.FAMILY_CODE).size());
        }
    }

    public void send(Double value) {
        Properties properties = new Properties();
        String furnaceRequest = "http://" + properties.monitorIp + ":"
                + properties.monitorPort + "/rest/heating/temperature/"
                + properties.deviceName.replace("sensor_", "") + "/"
                + value + "/";
        try {
            String furnaceResponse = Request.Get(furnaceRequest)
                    .execute().returnContent().asString();
            //todo check response
        } catch (IOException e) {
            LogstashLogger.INSTANCE.warn("Could not send temperature to monitor.", e);
        }
    }
}
