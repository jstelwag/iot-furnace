package util;

import redis.clients.jedis.Jedis;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;

/**
 * Created by Jaap on 25-7-2016.
 */
public class FluxLogger implements Closeable {

    private final InetAddress host;
    private final int port;
    private Jedis jedis;
    private final DatagramSocket socket;
    private final String iotId;

    public FluxLogger() throws SocketException, UnknownHostException {
        final Properties properties = new Properties();
        iotId = properties.deviceName;
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

    public FluxLogger log() {
        jedis = new Jedis("localhost");
        logState();
        logTemperatures();
        jedis.close();
        return this;
    }

    @Deprecated
    private void logTemperatures() {
        if (jedis.exists(TemperatureSensor.redisKey)) {
            String line = "boiler,name=" + TemperatureSensor.boiler + ",position=" + TemperatureSensor.position
                        + " temperature=" + jedis.get(TemperatureSensor.redisKey);
            send(line);
        } else {
            LogstashLogger.INSTANCE.warn("No temperature for " + TemperatureSensor.redisKey);
        }

        if (jedis.exists("auxiliary.temperature")) {
            send("environment.temperature " + iotId + "=" + jedis.get("auxiliary.temperature"));
        }
    }

    @Deprecated
    private void logState() {
        if (jedis.exists("boiler200.state")) {
            send("boiler,name=boiler200 state=" + jedis.get("boiler200.state"));
        } else if (jedis.exists("boiler120.state")) {
            send("boiler,name=boiler120 state=" + jedis.get("boiler120.state"));
        } else {
            LogstashLogger.INSTANCE.error("There is no state in Redis to log boiler state");
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
