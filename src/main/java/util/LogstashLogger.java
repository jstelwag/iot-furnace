package util;

import java.io.IOException;
import java.net.*;

/**
 * Created by Jaap on 26-5-2016.
 */
public class LogstashLogger {

    public final static LogstashLogger INSTANCE = new LogstashLogger();

    public final String iotId;

    InetAddress host;
    final int port;

    private LogstashLogger() {
        final Properties properties = new Properties();
        port = Integer.parseInt(properties.prop.getProperty("logstash.port"));
        iotId = properties.prop.getProperty("iot.id");

        try {
            host = InetAddress.getByName(properties.prop.getProperty("logstash.ip"));
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    public void message(String line) {
        message("iot-furnace-" + iotId, line);
    }

    public void message(String who, String line) {
        send(who + ": " + line);
    }
    private void send(String message) {
        try (DatagramSocket socket = new DatagramSocket()){
            byte[] data = message.getBytes();
            try {
                DatagramPacket packet = new DatagramPacket(data, data.length, host, port);
                socket.send(packet);
            } catch (IOException e) {
                System.out.println("ERROR for UDP connection " + socket.isConnected() + ", @"
                        + host.getHostAddress() + ":" + port + ", socket " + socket.isBound() + ". For " + message);
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }
}
