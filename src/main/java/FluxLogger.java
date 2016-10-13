import org.apache.commons.lang3.StringUtils;
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

    public FluxLogger() throws SocketException, UnknownHostException {
        final Properties properties = new Properties();
        if (StringUtils.isEmpty(properties.prop.getProperty("influx.ip"))) {
            LogstashLogger.INSTANCE.message("ERROR: influx.ip setting missing from properties");
            throw new UnknownHostException("Please set up influx.ip and port in iot.conf");
        }
        try {
            host = InetAddress.getByName(properties.prop.getProperty("influx.ip"));
            port = Integer.parseInt(properties.prop.getProperty("influx.port"));
        } catch (UnknownHostException e) {
            LogstashLogger.INSTANCE.message("ERROR: trying to set up InluxDB client for unknown host " + e.toString());
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
            for (String sensorPosition : TemperatureSensor.sensors.get(sensorLocation)) {
                String key = sensorLocation + '.' + sensorPosition;
                if (jedis.exists(key)) {
                    line += (line.contains("=") ? "," : "") + sensorPosition + '=' + jedis.get(key);
                } else {
                    LogstashLogger.INSTANCE.message("WARN: no temperature for " + key);
                }
            }
            if (line.contains("=")) {
                send(line);
            }
        }
        if (jedis.exists("pipe.Tslope")) {
            send("pipe.velocity slope=" + jedis.get("pipe.Tslope") + ",deviation=" + jedis.get("pipe.TstandardDeviation"));
        }
    }

    private void logState() {
        if (jedis.exists("boiler120.state")) {
            String line = "boiler120.state value=" + jedis.get("boiler120.state");
            send(line);
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