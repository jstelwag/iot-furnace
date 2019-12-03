package common;

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
        final Properties prop = new Properties();
        port = prop.logstashPort;
        iotId = prop.deviceName;

        try {
            host = InetAddress.getByName(prop.logstashIp);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    public void fatal(String message) {
        message("FATAL:" + message);
    }

    public void error(String message) {
        message("ERROR:" + message);
    }

    public void warn(String message) {
        message("WARN:" + message);
    }

    public void info(String message) {
        message("INFO:" + message);
    }

    private void message(String line) {
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