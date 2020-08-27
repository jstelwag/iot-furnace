package furnace;

import gnu.io.CommPortIdentifier;
import gnu.io.SerialPortEvent;
import gnu.io.SerialPortEventListener;

import java.util.Enumeration;

public class ListPorts implements SerialPortEventListener {
    @Override
    public void serialEvent(SerialPortEvent serialPortEvent) {
        CommPortIdentifier portId = null;
        Enumeration portEnum = CommPortIdentifier.getPortIdentifiers();

        while (portEnum.hasMoreElements()) {
            CommPortIdentifier currPortId = (CommPortIdentifier) portEnum.nextElement();
            System.out.println("Found port: " + currPortId.getName() + ", is owned: " + currPortId.isCurrentlyOwned());
        }
    }
}
