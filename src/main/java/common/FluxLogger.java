package common;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;

/**
 * Created by Jaap on 25-7-2016.
 */
public class FluxLogger implements Closeable {
    private final InetAddress host;
    private final int port;
    private final DatagramSocket socket;
    private final String deviceName;

    public FluxLogger() throws SocketException, UnknownHostException {
        final Properties properties = new Properties();
        deviceName = properties.deviceName;
        try {
            host = InetAddress.getByName(properties.influxIp);
            port = properties.influxPort;
        } catch (UnknownHostException e) {
            LogstashLogger.INSTANCE.error("Trying to set up InfluxDB client for unknown host " + e.toString());
            throw e;
        }
        try {
            socket = new DatagramSocket();
        } catch (SocketException e) {
            LogstashLogger.INSTANCE.error("Unable to open socket to connect to InfluxDB @" + host + ":" + port
                    + " " + e.getMessage());
            throw e;
        }
    }

    public FluxLogger send(String line) {
        byte[] data = line.getBytes();
        try {
            DatagramPacket packet = new DatagramPacket(data, data.length, host, port);
            socket.send(packet);
        } catch (IOException e) {
            LogstashLogger.INSTANCE.error("IOException for UDP connection " + socket.isConnected() + ", @"
                    + host.getHostAddress() + ":" + port + ", socket " + socket.isBound() + ": "
                    + e.getMessage());
        }

        return this;
    }

    @Override
    public void close() {
        if (socket != null)
            socket.close();
    }
}
