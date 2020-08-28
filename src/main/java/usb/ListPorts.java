package usb;

import com.fazecast.jSerialComm.SerialPort;

public class ListPorts {

    public enum Device {
        furnace,
        solar
    }

    public static void print() {
        for (SerialPort port : SerialPort.getCommPorts()) {
            System.out.println("Port: " + port.getSystemPortName() + " (" + port.getDescriptivePortName() + ") - device: " + sniff(port));
        }
    }

    /**
    * Finds and returns a (closed) port for given Device.
    **/
    public static SerialPort findDevice(Device device) {
        for (SerialPort port : SerialPort.getCommPorts()) {
            if (sniff(port) == device) {
                return port;
            }
        }
        return null;
    }

    /**
    * Listens to given port and returns the Device that is connected to it, or null.
    **/
    public static Device sniff(SerialPort port) {
        port.openPort();
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING  | SerialPort.TIMEOUT_WRITE_BLOCKING, 500, 500);
        port.writeBytes("?".getBytes(), "?".getBytes().length);

        long start = System.currentTimeMillis();
        int TIMEOUT = 2000;
        try {
            while (System.currentTimeMillis() - start < TIMEOUT) {
                while (port.bytesAvailable() == 0) {
                    Thread.sleep(20);
                    if (System.currentTimeMillis() - start > TIMEOUT) {
                        break;
                    }
                }
                if (port.bytesAvailable() > 0) {
                    byte[] readBuffer = new byte[port.bytesAvailable()];
                    port.readBytes(readBuffer, readBuffer.length);
                    for (Device device : Device.values()) {
                        if (new String(readBuffer).contains(device.name())) {
                            port.closePort();
                            return device;
                        }
                    }
                    Thread.sleep(50);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            port.closePort();
        }
        return null;
    }
}
