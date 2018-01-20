import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;

/**
 * Created by Jaap on 25-7-2016.
 */
public class Main {
    public static void main(String[] args) {
        try {
            switch (args[0]) {
                case "FluxLogger":
                    if (hasLogger(args[0])) {
                        new FluxLogger().log().close();
                    }
                    break;
                case "FurnaceSlave":
                    if (hasService(args[0])) {
                        new FurnaceSlave().run();
                    }
                    break;
                case "ValveGroupSlave":
                    if (hasService(args[0])) {
                        new ValveGroupSlave().run();
                    }
                    break;
                case "FurnaceMonitor":
                    if (hasService(args[0])) {
                        new FurnaceMonitor().run();
                    }
                    break;
                case "I2CMaster":
                    if (hasService(args[0])) {
                        new I2CMaster().run();
                    }
                    break;
                default:
                    LogstashLogger.INSTANCE.message("ERROR: unknown parameter for Main " + args[0]);
                    break;
            }
        } catch (Exception e) {
            LogstashLogger.INSTANCE.message("ERROR: " + args[0] + " has finished with unhandled exception " + e.toString());
        }
    }

    public static boolean hasService(String service) {
        final Properties properties = new Properties();
            return StringUtils.isEmpty(properties.prop.getProperty("services"))
                    || Arrays.asList(properties.prop.getProperty("services").split(",")).contains(service);
    }
    public static boolean hasLogger(String logger) {
        final Properties properties = new Properties();
        return StringUtils.isEmpty(properties.prop.getProperty("loggers"))
                || Arrays.asList(properties.prop.getProperty("services").split(",")).contains(logger);
    }

}
