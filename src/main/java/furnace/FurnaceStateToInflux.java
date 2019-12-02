package furnace;

import common.FluxLogger;
import common.LogstashLogger;
import common.Properties;

import java.net.SocketException;
import java.net.UnknownHostException;

public class FurnaceStateToInflux extends FluxLogger implements Runnable {
    public FurnaceStateToInflux() throws SocketException, UnknownHostException {
        super();
    }

    @Override
    public void run() {
        logState();
        logTemperatures();
    }

    private void logTemperatures() {
        try (BoilerDAO boilerDAO = new BoilerDAO(); FurnaceDAO furnaceDAO = new FurnaceDAO()) {
            if (boilerDAO.getTemperature() != null) {
                String line = "boiler,name=" + BoilerDAO.boiler + ",position=" + BoilerDAO.position
                        + " temperature=" + boilerDAO.getTemperature();
                send(line);
            } else {
                LogstashLogger.INSTANCE.warn("No temperature for " + BoilerDAO.tempKey);
            }

            if (furnaceDAO.getAuxiliaryTemperature() != null) {
                send("environment.temperature " + new Properties().deviceName + "=" + furnaceDAO.getAuxiliaryTemperature());
            }
        }
    }

    private void logState() {
        try (BoilerDAO boilerDAO = new BoilerDAO()) {
            if (boilerDAO.getStateRaw() != null) {
                send("boiler,name=" + BoilerTemperatureSensor.boiler + " state=" + boilerDAO.getStateRaw());
            } else {
                LogstashLogger.INSTANCE.error("There is no state in Redis to log boiler state");
            }
        }
    }
}
