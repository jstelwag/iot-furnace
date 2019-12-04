package i2c;

import com.pi4j.io.i2c.I2CDevice;

import java.util.Map;

/**
 * As a i2c master, one or more slaves are connected to the raspberry pi.
 * This class will establish an i2c link, and scan the bus for slaves.
 * If valve type slaves are found, I2CFurnaceMaster is launched and/if a furnace slave is found I2CFurnaceMaster.
 *
 * The watchdog will regularly check the i2c bus link and if necessary reboot the raspberry pi.
 */
public interface I2CMaster {

    public boolean request(String deviceId);
    public Map<String, I2CDevice> devices();
}
