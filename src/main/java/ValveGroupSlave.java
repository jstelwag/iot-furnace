import gnu.io.CommPortIdentifier;
import gnu.io.SerialPort;
import gnu.io.SerialPortEvent;
import gnu.io.SerialPortEventListener;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import redis.clients.jedis.Jedis;

import java.io.*;
import java.util.Date;
import java.util.Enumeration;


/**
 * Created by Jaap on 25-7-2016.
 */
public class ValveGroupSlave implements SerialPortEventListener {

    private final static int TTL = 60;
    private final String startTime;
    public final static String STARTTIME = "valvegroupslave.starttime";
    /**
     * A BufferedReader which will be fed by a InputStreamReader
     * converting the bytes into characters
     * making the displayed results codepage independent
     */
    private BufferedReader input;
    private SerialPort serialPort;

    private Jedis jedis;

    private final String iotId;
    private final String monitorIp;
    private final int monitorPort;

    /** Milliseconds to block while waiting for port open */
    private static final int TIME_OUT = 2000;
    /** Default bits per second for COM port. */
    private static final int DATA_RATE = 9600;

    public ValveGroupSlave() {
        startTime = String.valueOf(new Date().getTime());
        jedis = new Jedis("localhost");
        if (jedis.exists(STARTTIME)) {
            LogstashLogger.INSTANCE.message("Exiting redundant ValveGroupSlave");
            jedis.close();
            System.exit(0);
        }

        jedis.setex(STARTTIME, TTL, startTime);
        jedis.close();

        Properties prop = new Properties();
        // the next line is for Raspberry Pi and
        // gets us into the while loop and was suggested here was suggested http://www.raspberrypi.org/phpBB3/viewtopic.php?f=81&t=32186
        System.setProperty("gnu.io.rxtx.SerialPorts", prop.prop.getProperty("usb.valvegroup"));
        iotId = prop.prop.getProperty("iot.id");
        monitorIp = prop.prop.getProperty("monitor.ip");
        monitorPort = Integer.parseInt(prop.prop.getProperty("monitor.port"));

        CommPortIdentifier portId = null;
        Enumeration portEnum = CommPortIdentifier.getPortIdentifiers();

        while (portEnum.hasMoreElements()) {
            CommPortIdentifier currPortId = (CommPortIdentifier) portEnum.nextElement();
            if (currPortId.getName().equals(prop.prop.getProperty("usb.valvegroup"))) {
                portId = currPortId;
                break;
            }
        }
        if (portId == null) {
            LogstashLogger.INSTANCE.message("ERROR: could not find USB at " + prop.prop.getProperty("usb.valvegroup"));
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
            System.err.println(e.toString());
        }
        addShutdownHook();
    }

    /**
     * This should be called when you stop using the port.
     * This will prevent port locking on platforms like Linux.
     */
    public synchronized void close() {
        if (jedis == null || !jedis.isConnected()) {
            jedis = new Jedis("localhost");
        }
        jedis.del(STARTTIME);
        jedis.close();
        if (serialPort != null) {
            serialPort.removeEventListener();
            serialPort.close();
        }
    }

    /**
     * Handle an event on the serial port. Read the data and print it.
     */
    public synchronized void serialEvent(SerialPortEvent oEvent) {
        jedis = new Jedis("localhost");
        if (jedis.exists(STARTTIME) && !jedis.get(STARTTIME).equals(startTime)) {
            LogstashLogger.INSTANCE.message("Connection hijack, exiting SolarSlave");
            jedis.close();
            System.exit(0);
        }
        jedis.setex(STARTTIME, TTL, startTime);
        jedis.close();
        if (oEvent.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
            try {
                String inputLine = input.readLine();
System.out.println("hoi " + inputLine);
                if (inputLine.startsWith("log:")) {
                    LogstashLogger.INSTANCE.message("iot-furnace-controller-" + iotId, inputLine.substring(4).trim());
                } else if (StringUtils.countMatches(inputLine, ":") > 1) {
                    //Forward state message from controller
                    String response = Request.Post("http://" + monitorIp +":" + monitorPort + "/furnace/koetshuis_kelder/")
                            .bodyString(inputLine, ContentType.DEFAULT_TEXT).execute().returnContent().asString();
                    try {
                        PrintWriter out = new PrintWriter(serialPort.getOutputStream());
                        out.print(response);
                        out.flush();
                    } catch (IOException e) {
                        LogstashLogger.INSTANCE.message("ERROR: writing to ValveGroup controller");
                        close();
                        System.exit(0);
                    }
                } else {
                    LogstashLogger.INSTANCE.message("ERROR: received garbage from the ValveGroup micro controller: " + inputLine);
                }
            } catch (IOException e) {
                LogstashLogger.INSTANCE.message("ERROR: problem reading serial input from USB, exiting " + e.toString());
                close();
                System.exit(0);
            }
        }
    }

    public void run() {
        LogstashLogger.INSTANCE.message("Starting ValveGroupSlave");
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

    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                close();
            }
        });
    }
}
