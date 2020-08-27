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
        String retVal = null;
        port.openPort();
        port.writeBytes(new byte[]{'?'}, 1);
        boolean waiting = true;
        try {
            while (waiting) {
                while (port.bytesAvailable() == 0) {
                    Thread.sleep(20);
                }
                waiting = false;
                byte[] readBuffer = new byte[port.bytesAvailable()];
                int numRead = port.readBytes(readBuffer, readBuffer.length);
                System.out.print("Read " + numRead + " bytes ");
                System.out.println(readBuffer);
                retVal = new String(readBuffer);
                System.out.println(retVal);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        port.closePort();
        return retVal;
    }

    public static SerialPort findDevice(Device device) {
        for (SerialPort port : SerialPort.getCommPorts()) {
            String echo = echo(port);
            if (echo != null && Device.valueOf(echo) != null) {
                return port;
            }
        }
        return null;
    }
}
