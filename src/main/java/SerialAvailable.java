import com.fazecast.jSerialComm.SerialPort;

/**
 * Created by Jaap on 14-1-2018.
 */
public class SerialAvailable {

    public SerialAvailable() {
        for (SerialPort port : SerialPort.getCommPorts()) {
            System.out.println("Port: " + port.getSystemPortName());
        }
    }
}
