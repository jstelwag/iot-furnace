import com.pi4j.io.i2c.I2CBus;
import com.pi4j.io.i2c.I2CDevice;
import com.pi4j.io.i2c.I2CFactory;

import java.io.IOException;


/**
 * Created by Jaap on 20-1-2018.
 */
public class i2c {
    public static final int SLAVE_ADDRESS = 0x04;

    public void run() throws InterruptedException, I2CFactory.UnsupportedBusNumberException, IOException {
        System.out.println("MCP23017 Example");

        I2CBus i2c = I2CFactory.getInstance(I2CBus.BUS_1);
        I2CDevice device = i2c.getDevice(SLAVE_ADDRESS);

        device.write((byte) 0x00);

        while (true) {
            System.out.println(device.read());
            Thread.sleep(2000);
            device.write((byte) 0x10);
            Thread.sleep(2000);
            device.write((byte) 0xFF);
        }
    }
}


