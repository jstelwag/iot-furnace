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
        try (BoilerDAO boilerDAO = new BoilerDAO(); FurnaceDAO furnaceDAO = new FurnaceDAO()) {
            if (boilerDAO.getTemperature() != null) {
                send("boiler,name=" + BoilerDAO.boiler + ",position=" + BoilerDAO.position
                        + " temperature=" + boilerDAO.getTemperature());
            } else {
                LogstashLogger.INSTANCE.warn("No temperature for " + BoilerDAO.tempKey);
            }
            if (boilerDAO.getStateRaw() != null) {
                send("boiler,name=" + BoilerDAO.boiler + " state=" + (boilerDAO.getState() ? "1" : "0"));
            } else {
                LogstashLogger.INSTANCE.warn("There is no state in Redis to log boiler state");
            }
            if (furnaceDAO.getAuxiliaryTemperature() != null) {
                send("environment,device=" + new Properties().deviceName
                        + " temperature=" + furnaceDAO.getAuxiliaryTemperature());
            }
        }
    }
}
