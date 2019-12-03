package furnace;

import common.LogstashLogger;
import common.Properties;
import gnu.io.CommPortIdentifier;
import gnu.io.SerialPort;
import gnu.io.SerialPortEvent;
import gnu.io.SerialPortEventListener;
import org.apache.commons.lang3.StringUtils;
import redis.clients.jedis.Jedis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.Enumeration;

/**
 * Created by Jaap on 25-7-2016.
 */
@Deprecated
public class FurnaceSlave implements SerialPortEventListener {

    private final static int TTL = 60;
    private final String startTime;
    public final static String STARTTIME = "furnaceslave.starttime";
    /**
     * A BufferedReader which will be fed by a InputStreamReader
     * converting the bytes into characters
     * making the displayed results codepage independent
     */
    private BufferedReader input;
    private SerialPort serialPort;

    /** Milliseconds to block while waiting for port open */
    private static final int TIME_OUT = 2000;
    /** Default bits per second for COM port. */
    private static final int DATA_RATE = 9600;

    public FurnaceSlave() {
        startTime = String.valueOf(new Date().getTime());

        checkAndExitIfNotSingle();

        Properties prop = new Properties();
        // the next line is for Raspberry Pi and
        // gets us into the while loop and was suggested here was suggested http://www.raspberrypi.org/phpBB3/viewtopic.php?f=81&t=32186
        System.setProperty("gnu.io.rxtx.SerialPorts", prop.usbFurnace);

        CommPortIdentifier portId = null;
        Enumeration portEnum = CommPortIdentifier.getPortIdentifiers();

        while (portEnum.hasMoreElements()) {
            CommPortIdentifier currPortId = (CommPortIdentifier) portEnum.nextElement();
            if (currPortId.getName().equals(prop.usbFurnace)) {
                portId = currPortId;
                break;
            }
        }
        if (portId == null) {
            LogstashLogger.INSTANCE.error("Could not find USB at " + prop.usbFurnace);
            close();
            System.exit(0);
        }

        try {
            // open serial port, and use class name for the appName.
            serialPort = (SerialPort) portId.open(this.getClass().getName(), TIME_OUT);

            // set port parameters
            serialPort.setSerialPortParams(DATA_RATE,
                    SerialPort.DATABITS_8,
                    SerialPort.STOPBITS_1,
                    SerialPort.PARITY_NONE);

            // open the streams
            input = new BufferedReader(new InputStreamReader(serialPort.getInputStream()));

            // add event listeners
            serialPort.addEventListener(this);
            serialPort.notifyOnDataAvailable(true);
        } catch (Exception e) {
            LogstashLogger.INSTANCE.error("Faild to open usb connection at initializing Furnace Slave." + e.getMessage());
        }
        addShutdownHook();
    }

    /**
     * This should be called when you stop using the port.
     * This will prevent port locking on platforms like Linux.
     */
    public synchronized void close() {
        if (serialPort != null) {
            serialPort.removeEventListener();
            serialPort.close();
        }
    }

    /**
     * Handle an event on the serial port. Read the data and print it.
     */
    public synchronized void serialEvent(SerialPortEvent oEvent) {
        try (Jedis jedis = new Jedis("localhost")) {
            if (jedis.exists(STARTTIME) && !jedis.get(STARTTIME).equals(startTime)) {
                LogstashLogger.INSTANCE.info("Connection hijack, exiting SolarSlave");
                System.exit(0);
            }
            jedis.setex(STARTTIME, TTL, startTime);
        }
        if (oEvent.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
            try {
                String inputLine = input.readLine();
                if (inputLine.startsWith("log:")) {
                    Properties prop = new Properties();
                    LogstashLogger.INSTANCE.message("iot-furnace-controller-" + prop.deviceName, inputLine.substring(4).trim());
                } else if (StringUtils.countMatches(inputLine, ":") >= 1) {
                    LogstashLogger.INSTANCE.info("Furnace event " + inputLine);
                    try (FurnaceDAO furnaceDAO = new FurnaceDAO(); BoilerDAO boilerDAO = new BoilerDAO()) {
                        boilerDAO.setState("ON".equalsIgnoreCase(inputLine.split(":")[0]));
                        boilerDAO.setTemperature(inputLine.split(":")[1]);
                        if (StringUtils.countMatches(inputLine, ":") > 1) {
                            furnaceDAO.setAuxiliaryTemperature(inputLine.split(":")[2]);
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
                    LogstashLogger.INSTANCE.error("Received garbage from the Furnace micro controller: " + inputLine);
                }
            } catch (IOException e) {
                LogstashLogger.INSTANCE.error("Problem reading serial input from USB, exiting " + e.toString());
                close();
                System.exit(0);
            }
        }
    }

    public void run() {
        LogstashLogger.INSTANCE.info("Starting FurnaceSlave");
        Thread t = new Thread() {
            public void run() {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ie) {
                }
            }
        };
        t.start();
    }

    private void checkAndExitIfNotSingle() {
        try (Jedis jedis = new Jedis("localhost")) {
            if (jedis.exists(STARTTIME)) {
                System.exit(0);
            }
            jedis.setex(STARTTIME, TTL, startTime);
        }
    }

    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                close();
            }
        });
    }
}
