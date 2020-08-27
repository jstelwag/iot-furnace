package usb;

import com.fazecast.jSerialComm.SerialPort;

public class ListPorts {

    public enum Device {
        furnace,
        solar
    }

    public static void print() {
        for (SerialPort port : SerialPort.getCommPorts()) {
            System.out.println("Port: " + port.getSystemPortName() + " (" + port.getDescriptivePortName() + ") - echo: " + echo(port));
        }
    }

    public static String echo(SerialPort port) {
        String retVal = "";
        int sleep = 0;
        port.openPort();
        port.writeBytes(new byte[]{'?'}, 1);
        boolean waiting = true;
        try {
            while (waiting) {
                while (port.bytesAvailable() == 0) {
                    Thread.sleep(20);
                    sleep += 20;
                    if (sleep > 2000) {
                        waiting = false;
                        System.out.println("Waiting too long");
                        break;
                    }
                }
                if (port.bytesAvailable() > 0) {
                    byte[] readBuffer = new byte[port.bytesAvailable()];
                    port.readBytes(readBuffer, readBuffer.length);
                    retVal += new String(readBuffer);
                    Thread.sleep(10);
                    sleep += 10;
                    if (port.bytesAvailable() <= 0) {
                        waiting = false;
                    }
                }
            }
            System.out.println(retVal);
        } catch (Exception e) {
            e.printStackTrace();
        }
        port.closePort();
        return retVal;
    }

    public static SerialPort findDevice(Device device) {
        for (SerialPort port : SerialPort.getCommPorts()) {
            String echo = echo(port);
            if (echo != null && echo.contains(device.name())) {
                return port;
            }
        }
        return null;
    }
}
