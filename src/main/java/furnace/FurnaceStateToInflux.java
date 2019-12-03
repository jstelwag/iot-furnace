package furnace;

import common.FluxLogger;
import common.LogstashLogger;
import common.Properties;

import java.net.UnknownHostException;

public class FurnaceStateToInflux extends FluxLogger implements Runnable {
    public FurnaceStateToInflux() throws UnknownHostException {
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
                send("boiler,name=" + BoilerDAO.boiler + ",position=" + BoilerDAO.position
                        + " temperature=" + boilerDAO.getTemperature());
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
                send("boiler,name=" + BoilerDAO.boiler + " state=" + (boilerDAO.getState() ? "1i" : "0i"));
            } else {
                LogstashLogger.INSTANCE.error("There is no state in Redis to log boiler state");
            }
        }
    }
}
