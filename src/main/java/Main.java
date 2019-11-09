import handlers.RedisHandler;
import i2c.Master;
import monitor.FurnaceMonitor;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import util.FluxLogger;
import util.LogstashLogger;
import util.Properties;

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
                case "FurnaceMonitor":
                    if (hasService(args[0])) {
                        new FurnaceMonitor().run();
                    }
                    break;
                case "DallasTemperature":
                    if (hasService(args[0])) {
                        new DallasTemperature().run();
                    }
                    break;
                case "I2CMaster":
                    new Master().run();
                    break;
                case "http":
                    startHttp(8080);
                    break;
                case "prop":
                    System.out.println(prop(args[1]));
                    break;
                default:
                    LogstashLogger.INSTANCE.error("Unknown parameter for Main " + args[0]);
                    break;
            }
        } catch (Exception e) {
            LogstashLogger.INSTANCE.error(args[0] + " has finished with unhandled exception " + e.toString());
        }
    }

    private static String prop(String name) {
        Properties prop = new Properties();
        switch(name) {
            case "deviceName":
                return prop.deviceName;
            case "logstashIp":
                return prop.logstashIp;
            case "logstashPort":
                return Integer.toString(prop.logstashPort);
            case "cpuId":
                return prop.cpuId;
        }
        return "unknown";
    }

    private static void startHttp(int port) {
        LogstashLogger.INSTANCE.info("Starting http");


        ContextHandler redisContext = new ContextHandler("/redis");
        redisContext.setHandler(new RedisHandler());
        Server httpServer = new Server(port);
        httpServer.setHandler(redisContext);
        removeHeaders(httpServer);
        ErrorHandler errorHandler = new ErrorHandler();
        errorHandler.setShowStacks(true);
        httpServer.addBean(errorHandler);

        try {
            httpServer.start();
            httpServer.join();
        } catch (Exception e) {
            LogstashLogger.INSTANCE.error("Failed to start http listener " + e.toString());
            System.exit(0);
        }

        while (true) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
            }
            try {
                //hello
            } catch (RuntimeException e) {
                LogstashLogger.INSTANCE.error("Exception occurred at the regular speaker scheduling " + e.toString());
            }
        }
    }


    public static boolean hasService(String service) {
        final Properties prop = new Properties();
            return StringUtils.isEmpty(prop.services)
                    || Arrays.asList(prop.services.split(",")).contains(service);
    }
    public static boolean hasLogger(String logger) {
        final Properties prop = new Properties();
        return StringUtils.isEmpty(prop.loggers)
                || Arrays.asList(prop.services.split(",")).contains(logger);
    }

    private static void removeHeaders(Server server) {
        for (Connector y : server.getConnectors()) {
            for (ConnectionFactory x : y.getConnectionFactories()) {
                if (x instanceof HttpConnectionFactory) {
                    ((HttpConnectionFactory)x).getHttpConfiguration().setSendServerVersion(false);
                    ((HttpConnectionFactory)x).getHttpConfiguration().setSendDateHeader(false);
                }
            }
        }
    }

}
