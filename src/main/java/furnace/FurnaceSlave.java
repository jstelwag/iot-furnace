package furnace;

import com.fazecast.jSerialComm.SerialPort;
import common.LogstashLogger;
import common.Properties;
import redis.clients.jedis.Jedis;
import usb.ListPorts;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Created by Jaap on 25-7-2016.
 */
public class FurnaceSlave implements Runnable, Closeable {

    private final static int TTL = 60;
    private final long startTime;
    public final static String STARTTIME = "furnaceslave.runtime.seconds";

    private final SerialPort serialPort;
    private boolean stayOpen = true;

    /** Milliseconds to block while waiting for port open */
    private static final int TIME_OUT = 2000;

    public FurnaceSlave() {
        startTime = System.currentTimeMillis();

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
    }

    public synchronized void listenAndRespond() {
        if (serialPort.bytesAvailable() > 0) {
            byte[] readBuffer = new byte[serialPort.bytesAvailable()];
            serialPort.readBytes(readBuffer, readBuffer.length);
            String[] inputLines = new String(readBuffer).split("\r\n");
            for (String line : inputLines) {
                System.out.println(line);
                String[] lineParts = line.split(":");
                if (line.startsWith("log:furnace:")) {
                    Properties prop = new Properties();
                    System.out.println(line.substring(12).trim());
                    LogstashLogger.INSTANCE.message("iot-furnace-controller-" + prop.deviceName, line.substring(12).trim());
                } else if (lineParts.length >= 2) {
                    LogstashLogger.INSTANCE.info("Furnace event " + line);
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
                    LogstashLogger.INSTANCE.error("Received garbage from the Furnace micro controller: " + line);
                }
            }
            try (Jedis jedis = new Jedis("localhost")) {
                jedis.setex(STARTTIME, TTL, String.valueOf((int)((System.currentTimeMillis() - startTime)/1000)));
            }
            //Thread.sleep(50);
        }
    }

    public void run() {
        LogstashLogger.INSTANCE.info("Starting FurnaceSlave");
        while (stayOpen) {
            listenAndRespond();
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
