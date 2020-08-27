package furnace;

import com.fazecast.jSerialComm.SerialPort;

public class ListPorts {

    public static void print() {
        for (SerialPort port : SerialPort.getCommPorts()) {
            System.out.println("Port: " + port.getSystemPortName() + " (" + port.getDescriptivePortName() + ") - is open: " + port.isOpen());
        }
    }
}
