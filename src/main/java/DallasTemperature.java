import java.io.IOException;
import com.pi4j.io.w1.W1Device;
import com.pi4j.io.w1.W1Master;
import com.pi4j.component.temperature.impl.TmpDS18B20DeviceType;
import com.pi4j.component.temperature.TemperatureSensor;

public class DallasTemperature {

    public void run() {
        W1Master master = new W1Master();
        for (W1Device device : master.getDevices(TmpDS18B20DeviceType.FAMILY_CODE)) {
            System.out.println("Temperature: " + ((TemperatureSensor) device).getTemperature());
            //TODO confirm Celcius
            try {
                System.out.println("1-Wire ID: " + device.getId() + " value: " + device.getValue());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
