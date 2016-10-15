/**
 * Created by Jaap on 25-7-2016.
 */
public class Main {
    public static void main(String[] args) {
        try {
            switch (args[0]) {
                case "FluxLogger":
                    new FluxLogger().log().close();
                    break;
                case "FurnaceSlave":
                    new FurnaceSlave().run();
                    break;
                case "ValveGroupSlave":
                    new ValveGroupSlave().run();
                    break;
                case "FurnaceMonitor":
                    new FurnaceMonitor().run();
                    break;
                default:
                    LogstashLogger.INSTANCE.message("ERROR: unknown parameter for Main " + args[0]);
                    break;
            }
        } catch (Exception e) {
            LogstashLogger.INSTANCE.message("ERROR: " + args[0] + " has finished with unhandled exception " + e.toString());
        }
    }
}
