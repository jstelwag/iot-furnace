import org.apache.commons.lang3.StringUtils;
import redis.clients.jedis.Jedis;
import util.LogstashLogger;
import util.Properties;
import util.TemperatureSensor;

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
        if (StringUtils.isEmpty(properties.prop.getProperty("influx.ip"))) {
            LogstashLogger.INSTANCE.message("ERROR: influx.ip setting missing from properties");
            throw new UnknownHostException("Please set up influx.ip and port in iot.conf");
        }
        iotId = properties.prop.getProperty("iot.id");
        try {
            host = InetAddress.getByName(properties.prop.getProperty("influx.ip"));
            port = Integer.parseInt(properties.prop.getProperty("influx.port"));
        } catch (UnknownHostException e) {
            LogstashLogger.INSTANCE.message("ERROR: trying to set up InfluxDB client for unknown host " + e.toString());
            throw e;
        }
        try {
            socket = new DatagramSocket();
        } catch (SocketException e) {
            System.out.println("Socket error " + e.toString());
            LogstashLogger.INSTANCE.message("ERROR: unable to open socket to connect to InfluxDB @" + host + ":" + port
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

    private void logTemperatures() {
        for (String sensorLocation : TemperatureSensor.sensors.keySet()) {
            String line = sensorLocation + '.' + "temperature ";
            String jedisKey = sensorLocation + ".temperature";
            if (jedis.exists(jedisKey)) {
                line += (line.contains("=") ? "," : "") + TemperatureSensor.sensors.get(sensorLocation)
                        + '=' + jedis.get(jedisKey);
            } else {
                LogstashLogger.INSTANCE.message("WARN: no temperature for " + sensorLocation);
            }
            if (line.contains("=")) {
                send(line);
            }
        }
        if (jedis.exists("auxiliary.temperature")) {
            send("environment.temperature " + iotId + "=" + jedis.get("auxiliary.temperature"));
        }
    }

    private void logState() {
        if (jedis.exists("boiler200.state")) {
            send("boiler200.state value=" + jedis.get("boiler200.state"));
        } else if (jedis.exists("boiler120.state")) {
            send("boiler120.state value=" + jedis.get("boiler120.state"));
        } else {
            LogstashLogger.INSTANCE.message("ERROR: there is no state in Redis");
        }
    }

    public FluxLogger send(String line) {
        byte[] data = line.getBytes();
        try {
            DatagramPacket packet = new DatagramPacket(data, data.length, host, port);
            socket.send(packet);
        } catch (IOException e) {
            LogstashLogger.INSTANCE.message("ERROR: for UDP connection " + socket.isConnected() + ", @"
                    + host.getHostAddress() + ":" + port + ", socket " + socket.isBound());
        }

        return this;
    }

    @Override
    public void close() throws IOException {
        if (socket != null)
            socket.close();
    }
}