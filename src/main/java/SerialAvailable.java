import com.fazecast.jSerialComm.SerialPort;
import redis.clients.jedis.Jedis;

import java.util.Arrays;

/**
 * Created by Jaap on 14-1-2018.
 */
public class SerialAvailable {

    private SerialPort port = null;
    private long timer;
    final long TIMEOUT_MS = 90000;

    private Jedis jedis;

    public SerialAvailable() {
        boolean foundNew = false;
        timer = System.currentTimeMillis();
        jedis = new Jedis("localhost");
        for (SerialPort port : SerialPort.getCommPorts()) {
            if (!port.getDescriptivePortName().contains("uart") && !jedis.exists(port.getSystemPortName())) {
                this.port = port;
                jedis.setex(port.getSystemPortName(), 90, "hello");
                attachPort();
                addShutdownHook();
                foundNew = true;
                break;
            }
        }

        jedis.close();

        if (!foundNew) {
            System.exit(0);
        }
    }

    private void attachPort() {
        System.out.println("Open " + port.getSystemPortName());
        port.openPort();
    }

    public void run() {
        LogstashLogger.INSTANCE.message("Starting ValveGroupSlave");
        Thread t = new Thread() {
            public void run() {
                try {
                    while (true) {
                        Thread.sleep(5000);
                        System.out.println("beep " + port.getSystemPortName() + " " + port.bytesAvailable());
                        if (port.bytesAvailable() > 0) {
                            timer = System.currentTimeMillis();
                            jedis = new Jedis("localhost");
                            jedis.setex(port.getSystemPortName(), 90, "hello");
                            jedis.close();

                            byte[] readBuffer = new byte[port.bytesAvailable()];
                            int numRead = port.readBytes(readBuffer, readBuffer.length);
                            System.out.println("Read " + numRead + " bytes " + Arrays.toString(readBuffer));
                        } else if (timer + TIMEOUT_MS < System.currentTimeMillis()) {
                            System.out.println("Timeout on " + port.getDescriptivePortName());
                            System.exit(0);
                        }
                    }
                } catch (InterruptedException ie) {
                }
            }
        };
        t.start();
    }

    /**
     * This should be called when you stop using the port.
     * This will prevent port locking on platforms like Linux.
     */
    public synchronized void close() {
        if (port != null) {
            System.out.println("Closing " + port.getSystemPortName());
            port.removeDataListener();
            port.closePort();
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
