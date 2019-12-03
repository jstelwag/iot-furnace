package common;

import java.io.IOException;
import java.net.*;

/**
 * Created by Jaap on 25-7-2016.
 */
public class FluxLogger {
    private final InetAddress host;
    private final int port;

    public FluxLogger() throws UnknownHostException {
        final Properties properties = new Properties();
        port = properties.influxPort;
        try {
            host = InetAddress.getByName(properties.influxIp);
        } catch (UnknownHostException e) {
            LogstashLogger.INSTANCE.error("Trying to set up InfluxDB client for unknown host " + e.toString());
            throw e;
        }
    }

    public FluxLogger send(String line) {
        byte[] data = line.getBytes();
        try (DatagramSocket socket = new DatagramSocket()) {
            DatagramPacket packet = new DatagramPacket(data, data.length, host, port);
            socket.send(packet);
        } catch (IOException e) {
            LogstashLogger.INSTANCE.error("IOException for message '" + line + "', UDP connection @"
                    + host.getHostAddress() + ":" + port + ": "
                    + e.getMessage());
        }

        return this;
    }
}
