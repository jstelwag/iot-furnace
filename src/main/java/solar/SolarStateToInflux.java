package solar;

import common.FluxLogger;
import common.LogstashLogger;
import net.e175.klaus.solarpositioning.AzimuthZenithAngle;
import redis.clients.jedis.Jedis;

import java.net.UnknownHostException;

public class SolarStateToInflux extends FluxLogger implements Runnable {
    public SolarStateToInflux() throws UnknownHostException {
        super();
    }

    @Override
    public void run() {
        try (Jedis jedis = new Jedis("localhost")) {
            logTemperatures(jedis);
            logControl(jedis);
        }
        sunLogger();
    }

    private void logTemperatures(Jedis jedis) {
        for (String sensorLocation : TemperatureSensor.sensors.keySet()) {
            for (String sensorPosition : TemperatureSensor.sensors.get(sensorLocation)) {
                String key = sensorLocation + '.' + sensorPosition;
                if (jedis.exists(key)) {
                    String line;
                    if (sensorLocation.startsWith("boiler")) {
                        line = "boiler,name=" + sensorLocation + ",position=" + sensorPosition
                                + " temperature=" + jedis.get(key);
                    } else {
                        line = sensorLocation + ".temperature " + sensorPosition + "=" + jedis.get(key);
                    }
                    send(line);
                } else {
                    LogstashLogger.INSTANCE.warn("No temperature for " + key);
                }
            }
        }
        if (jedis.exists("pipe.Tslope")) {
            send("pipe.velocity slope=" + jedis.get("pipe.Tslope") + ",deviation=" + jedis.get("pipe.TstandardDeviation"));
        }
    }

    private void logControl(Jedis jedis) {
        String line = "solarstate,controlstate="
                + (jedis.exists("solarState") ? jedis.get("solarState") : "unavailable");
        line += ",realstate=" + (jedis.exists("solarStateReal") ? jedis.get("solarStateReal") : "unavailable");
        line += " startTflowOut=" + jedis.get("stateStartTflowOut");
        line += ",value=1";

        send(line);
    }

    private void sunLogger() {
        Sun sun = new Sun();
        AzimuthZenithAngle position = sun.position();
        String line = "sun azimuth=" + position.getAzimuth()
                + ",zenithAngle=" + position.getZenithAngle()
                + ",power=" + (sun.shining() ? "1" : "0");
        send(line);
    }
}
