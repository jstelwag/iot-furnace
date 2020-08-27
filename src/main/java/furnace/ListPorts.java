package furnace;

import gnu.io.CommPortIdentifier;

import java.util.Enumeration;

public class ListPorts {

    public static void print() {
        Enumeration portEnum = CommPortIdentifier.getPortIdentifiers();

        while (portEnum.hasMoreElements()) {
            CommPortIdentifier currPortId = (CommPortIdentifier) portEnum.nextElement();
            System.out.println("Found port: " + currPortId.getName() + ", is owned: " + currPortId.isCurrentlyOwned());
        }
    }
}
