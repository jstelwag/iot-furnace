package furnace;

import com.fazecast.jSerialComm.SerialPort;
import common.LogstashLogger;
import common.Properties;
import redis.clients.jedis.Jedis;
import usb.ListPorts;

import java.io.Closeable;
import java.io.IOException;

/**
 * Created by Jaap on 25-7-2016.
 */
public class FurnaceSlave implements Runnable, Closeable {

    private final static int TTL = 60;
    private final long startTime;
    private long lastConnection;

    private final Properties prop = new Properties();

    public final static String STARTTIME = "furnaceslave.runtime.seconds";

    private final SerialPort serialPort;
    private boolean stayOpen = true;

    private String buffer = "";
    private String lineIn = "";

    public FurnaceSlave() {
        startTime = System.currentTimeMillis();
        lastConnection = System.currentTimeMillis();
        checkAndExitIfNotSingle();
        serialPort = ListPorts.findDevice(ListPorts.Device.furnace);

        if (serialPort == null) {
            LogstashLogger.INSTANCE.error("Could not find USB port, exiting");
            close();
            System.exit(0);
        }

        try (Jedis jedis = new Jedis("localhost")) {
            jedis.set("usb.furnace", serialPort.getSystemPortName());
        }
    }

    /**
     * This should be called when you stop using the port.
     * This will prevent port locking on platforms like Linux.
     */
    public void close() {
        stayOpen = false;
        if (serialPort != null && serialPort.isOpen()) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) { }
            serialPort.closePort();
        }
        try (Jedis jedis = new Jedis("localhost")) {
            jedis.del(STARTTIME);
        }
    }

    public void listen() {
        if (serialPort.bytesAvailable() > 0) {
            byte[] readBuffer = new byte[serialPort.bytesAvailable()];
            serialPort.readBytes(readBuffer, readBuffer.length);
            String input = new String(readBuffer);
            if (input.endsWith("\r\n")) {
                lineIn = buffer + input.split("\r\n")[0];
                buffer = "";
            } else if (input.contains("\r\n")) {
                lineIn = buffer + input.split("\r\n")[0];
                buffer = input.split("\r\n")[1];
            } else {
                buffer += input;
            }
        }
    }

    public void respond() {
        if (!lineIn.equals("")) {
            LogstashLogger.INSTANCE.info("Serial input from furnace: " + lineIn);
            String[] lineParts = lineIn.split(":");
            if (lineIn.startsWith("log:furnace:")) {
                LogstashLogger.INSTANCE.message("iot-furnace-controller-" + prop.deviceName, lineIn.substring(12).trim());
            } else if (lineParts.length >= 2) {
                LogstashLogger.INSTANCE.info("Furnace event " + lineIn);
                try (FurnaceDAO furnaceDAO = new FurnaceDAO(); BoilerDAO boilerDAO = new BoilerDAO()) {
                    boilerDAO.setState("1".equalsIgnoreCase(lineParts[0]));
                    boilerDAO.setTemperature(lineParts[1]);
                    if (lineParts.length > 2) {
                        furnaceDAO.setAuxiliaryTemperature(lineParts[2]);
                    }
                    try {
                        serialPort.getOutputStream().write(furnaceDAO.getFurnaceState() ? 'T' : 'F');
                        serialPort.getOutputStream().write(furnaceDAO.getPumpState() ? 'T' : 'F');
                        serialPort.getOutputStream().flush();
                    } catch (IOException e) {
                        LogstashLogger.INSTANCE.error("Writing to furnace controller");
                        close();
                        System.exit(0);
                    }
                }
            } else {
                LogstashLogger.INSTANCE.error("Received garbage from the Furnace micro controller: " + lineIn);
            }
            lineIn = "";
            try (Jedis jedis = new Jedis("localhost")) {
                jedis.setex(STARTTIME, TTL, String.valueOf((int)((System.currentTimeMillis() - startTime)/1000)));
            }
            lastConnection = System.currentTimeMillis();
        }
    }

    public void run() {
        LogstashLogger.INSTANCE.info("Starting FurnaceSlave");
        while (stayOpen) {
            if (!serialPort.isOpen()) {
                serialPort.openPort();
            }

            listen();
            respond();

            if (System.currentTimeMillis() - lastConnection > TTL*1000) {
                LogstashLogger.INSTANCE.error("Closing furnace controller usb connection after timeout of inactivity.");
                System.out.println("Perhaps lost the furnace serial connection, closing");
                close();
                System.exit(0);
            }

            try {
                Thread.sleep(150);
            } catch (InterruptedException e) { }
        }
    }

    private void checkAndExitIfNotSingle() {
        try (Jedis jedis = new Jedis("localhost")) {
            if (jedis.exists(STARTTIME)) {
                System.exit(0);
            }
            jedis.setex(STARTTIME, TTL, String.valueOf((int)((System.currentTimeMillis() - startTime)/1000)));
        }
    }
}
