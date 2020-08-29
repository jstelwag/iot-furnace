package solar;

import com.fazecast.jSerialComm.SerialPort;
import common.LogstashLogger;
import common.Properties;
import furnace.BoilerDAO;

import redis.clients.jedis.Jedis;
import usb.ListPorts;

import java.io.*;
import java.util.Date;

/**
 * Created by Jaap on 25-7-2016.
 */
public class SolarSlave implements Runnable, Closeable {

    private static final String STARTTIME = "solarslave.starttime";
    private final static int TTL = 60;
    private final long startTime;
    private long lastConnection;

    private final Properties prop = new Properties();

    private final SerialPort serialPort;
    private boolean stayOpen = true;
    private String buffer = "";
    private String lineIn = "";

    /** Set length is number of measurements in window (times 2 to be certain you have enough) */
    public static final int T_SET_LENGTH = (int)Controller.SLOPE_WINDOW_HR*60*60*2*2;

    public SolarSlave() {
        startTime = System.currentTimeMillis();
        lastConnection = System.currentTimeMillis();

        checkAndExitIfNotSingle();
        serialPort = ListPorts.findDevice(ListPorts.Device.solar);

        if (serialPort == null) {
            LogstashLogger.INSTANCE.error("Could not find USB port, exiting");
            close();
            System.exit(0);
        }

        try (Jedis jedis = new Jedis("localhost")) {
            jedis.set("usb.solar", serialPort.getSystemPortName());
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
            String[] lineParts = lineIn.split(":");
            if (lineIn.startsWith("log:solar:")) {
                LogstashLogger.INSTANCE.message("iot-solar-controller-" + prop.deviceName, lineIn.substring(10).trim());
            } else if (lineParts.length == 8) {
                LogstashLogger.INSTANCE.info("Solar event " + lineIn);
                //Format: Ttop:Tmiddle:Tbottom:TflowIn:TflowOut:SvalveI:SvalveII:Spump
                //       20.06:17.87:16.31:14.00:15.69:T:T:T

                try (Jedis jedis = new Jedis("localhost")) {
                    if (!BoilerDAO.isOutlier(lineParts[0], 5.0
                            , 105.0, 5.0, null)) {
                        jedis.setex("boiler500.Ttop", 60, lineParts[0]);
                    }
                    if (!BoilerDAO.isOutlier(lineParts[1], 5.0
                            , 105.0, 5.0, null)) {
                        jedis.setex("boiler500.Tmiddle", 60, lineParts[1]);
                    }
                    if (!BoilerDAO.isOutlier(lineParts[2], 5.0
                            , 105.0, 5.0, null)) {
                        jedis.setex("boiler500.Tbottom", 60, lineParts[2]);
                    }
                    if (!BoilerDAO.isOutlier(lineParts[3], -20.0
                            , 125.0, 5.0, null)) {
                        jedis.setex("pipe.TflowIn", 60, lineParts[3]);
                    }
                    if (!BoilerDAO.isOutlier(lineParts[4], -20.0
                            , 125.0, 5.0, null)) {
                        jedis.setex("pipe.TflowOut", 60, lineParts[4]);
                    }
                    jedis.setex("solarStateReal", 60, SolarState.principalState(
                            "T".equals(lineParts[5]), "T".equals(lineParts[6]), "T".equals(lineParts[7])).name());

                    jedis.lpush("pipe.TflowSet", Double.toString(((double) new Date().getTime()) / (60 * 60 * 1000))
                            + ":" + lineParts[4]);
                    jedis.ltrim("pipe.TflowSet", 0, T_SET_LENGTH);

                    try {
                        //Response format: [ValveI][ValveII][SolarPump]
                        if (jedis.exists("solarState")) {
                            SolarState state = SolarState.valueOf(jedis.get("solarState"));
                            serialPort.getOutputStream().write(state.line());
                        } else {
                            serialPort.getOutputStream().write(SolarState.error.line());
                        }
                        serialPort.getOutputStream().flush();
                    } catch (IOException e) {
                        LogstashLogger.INSTANCE.error("Failed writing to solar controller");
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
        LogstashLogger.INSTANCE.info("Starting SolarSlave");
        while (stayOpen) {
            if (!serialPort.isOpen()) {
                serialPort.openPort();
            }

            listen();
            respond();

            if (System.currentTimeMillis() - lastConnection > TTL*1000) {
                LogstashLogger.INSTANCE.error("Closing solar controller usb connection after timeout of inactivity.");
                System.out.println("Perhaps lost the solar serial connection, closing");
                close();
                System.exit(0);
            }

            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
            }
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
