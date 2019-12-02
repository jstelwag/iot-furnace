package i2c;

import com.pi4j.io.i2c.I2CDevice;

import java.io.IOException;

public class I2CUtil {
    public static String byteToString(I2CDevice device) throws IOException {
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
}
