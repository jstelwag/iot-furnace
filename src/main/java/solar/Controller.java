package solar;

import common.LogstashLogger;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;

/**
 * Solar boiler control
 *
 * The solar is controlled mainly by the inflow and outflow temperatures. This is more accurate than boiler temperature sensors. A
 * secondary control is the sun location indicator.
 *
 * Why two boilers?
 * One might wonder, why not a single boiler?
 * Well, for a larger solar collector a single 500 liter boiler does not suffice. And a larger boiler than 500 liter would not fit in my cellar.
 * Further you will need a boiler with auxiliary heating coil in case of a rainy day. Finally, in off season, the smaller
 * boiler is large enough to harvest solar energy.
 *
 * Setup
 * The large 500 liter boiler (boiler L) is fitted with one (solar) coil. The small 200 liter boiler (boiler S)
 * is fitted with a solar coil and a furnace coil in the top. The latter is controlled by another controller (FurnaceController).
 *
 * The solar system is connected with two three way valves. The first valve (valve I) controls the flow to boiler L (B) or the second valve (A).
 * By default the flow is to boiler L. The second valve controls the flow to boiler S (B) or the return flow (A). By default in this valve is
 * the small boiler.
 * When both valves are activated the solar flow is returned to the collectors bypassing both boilers. This allows for precise temperature switching.
 * (Without the return flow, the classical setup is a sensor in the solar collector to switch on the solar pump.)
 *
 * The small and large boilers have 1 and 3 thermometers respectively (Tsl and Tll, Tlm, Tlh). There are thermometers on the input
 * and output flow (Tin, Tout).
 *
 * Control
 * The main purpose of the small boiler is to ensure there is always warm water with the central heater coil. However, the lower solar coil can heat up
 * the entire boiler and save gas. Boiler S is not allowed to exceed 70C, when it does exceed this the recycle pump is switched on permanently.
 * The central heating / solar heating mode for boiler S work independantly. Solar will heat this boiler until the max temperature of 70C
 * is reached. The lower thermometer Tsl is used to control the solar coil.
 * Boiler L is used to accumulate excess heat from the collectors either because the sun is too strong and the small boiler over heats or
 * because the sun is not strong enough to reach the required temperature. The boiler is not allowed to exceed 95C, in this case the valves
 * are switched to recycle mode. The solar pump is switched off when Tin exceeds 120C.
 */
public class Controller implements Runnable {
    private final Jedis jedis;

    private double TflowIn, TflowOut, stateStartTflowOut;
    private Double Ttop200 = null;
    private Double Tbottem500 = null;

    private final static int STATE_CHANGE_GRACE_MILLISECONDS = 2*60*1000;

    private final static double MAX_FLOWOUT_TEMP = 95.0;
    private final static double LEGIONELLA_TEMP = 60.0;
    private final static double MAX_SMALL_BOILER_TEMP = 70.0;

    private final static long OVERHEAT_TIMEOUT_MS = 30*60*1000; //Set to 30 minutes

    private final static double RECYCLE_MAX_TEMP = 40.0;
    /** How many miliseconds should control stay in recycle? */
    private long recycleTimeMS() {
        if (isWinter()) {
            return 30*60*1000;
        }
        return 10*60*1000;
    }

    private final static double SWAP_BOILER_TEMP_RISE = 5.0;
    private final static double MIN_FLOW_DELTA = 0.5;
    private final static double LARGE_FLOW_DELTA_THRESHOLD = 2.0; //Meaning, sun is shining strong
    private final static double MIN_SOLAR_PIPE_TEMP = 20.0;
    private final static double BOILER_TEMP_CONTROL_OFFSET = 5.0; //use boiler temp for control if temp diff is larger then offset

    public final static double SLOPE_WINDOW_HR = 0.5;
    public final static int MIN_OBSERVATIONS = 20;

    private SolarState currentState;

    public Controller() {
        jedis = new Jedis("localhost");
        if (jedis.exists("solar.state")) {
            currentState = SolarState.valueOf(jedis.get("solar.state"));
        }
    }
    @Override
    public void run() {
        try {
            readTemperatures();
        } catch (IOException e) {
            e.printStackTrace();
        } //todo do the clean up (probably stop controlling
//        pipeTSlope();
        overheatControl();
        smallBoilerHeatControl();
        if (defrostCheck()) {
            stateDefrost();
        }

        if (currentState == SolarState.defrost) {
            checkDefrost();
        } else if (!new Sun().shining()) {
            stateSunset();
        } else if (currentState == SolarState.overheat) {
            resetOverheat();
        } else {
            control();
        }
        jedis.close();
    }

    private void control() {
        long lastStateChange = 0;
        if (jedis.exists("solar.lastStateChange")) {
            lastStateChange = new Date().getTime() - Long.parseLong(jedis.get("solar.lastStateChange"));
        }
        if (lastStateChange == 0) {
            stateStartup();
        } else if (lastStateChange > STATE_CHANGE_GRACE_MILLISECONDS) {
            if (currentState == SolarState.startup) {
                if (TflowIn > MIN_SOLAR_PIPE_TEMP) {
                    stateLargeBoiler();
                } else {
                    stateRecycleTimeout();
                }
            } else if (currentState == SolarState.recycle) {
                if (TflowOut > (stateStartTflowOut + 4.0) && TflowIn > MIN_SOLAR_PIPE_TEMP) {
                    // Recycle is heating up, try again
                    stateLargeBoiler();
                } else if (lastStateChange > recycleTimeMS() && TflowOut < RECYCLE_MAX_TEMP) {
                   if (Tbottem500 != null && TflowIn > (Tbottem500 + BOILER_TEMP_CONTROL_OFFSET)) {
                       stateLargeBoiler();
                   } else {
                       stateRecycleTimeout();
                   }
                }
            } else if (currentState == SolarState.recycleTimeout) {
                if (lastStateChange > recycleTimeMS()) {
                    stateRecycle();
                }
            } else if (TflowIn > TflowOut + MIN_FLOW_DELTA) {
                // Heat is being exchanged now, what to do?
                // Heat up 'legionella smart'
                if (currentState == SolarState.boiler200 && TflowOut < LEGIONELLA_TEMP
                        && TflowIn - TflowOut > LARGE_FLOW_DELTA_THRESHOLD) {
                    // Prefer small boiler to avoid growth of Legionella
                    // So, do nothing now
                } else if (currentState == SolarState.boiler500 && Ttop200 != null && Ttop200 > LEGIONELLA_TEMP
                        && TflowOut < LEGIONELLA_TEMP && TflowIn - TflowOut > LARGE_FLOW_DELTA_THRESHOLD) {
                    // Prefer to stick to the large boiler to reach Legionella entirely bcs the small boiler is already
                    // at a clean temperature
                } else if (stateStartTflowOut + SWAP_BOILER_TEMP_RISE < TflowOut) {
                    //Time to switch to another boiler
                    if (currentState == SolarState.boiler200) {
                        stateLargeBoiler();
                    } else {
                        stateSmallBoiler();
                    }
                }
                // Do nothing, let the current boiler heat up
            } else {
                // Do something, heat is extracted from the boiler now
                if (currentState == SolarState.boiler200) {
                    // Small boiler is not heating up, try the large boiler
                    stateLargeBoiler();
                } else if (currentState == SolarState.boiler500) {
                    stateRecycle();
                } else {
                    LogstashLogger.INSTANCE.error("Unexpected solar state " + currentState
                            + " I will go into recycle mode");
                    stateRecycle();
                }
            }
        }
    }

    private void overheatControl() {
        if (TflowOut > MAX_FLOWOUT_TEMP) {
            stateOverheat();
        }
    }

    /** From the small boiler water exits to the user. The Tout temperature must be limited */
    private void smallBoilerHeatControl() {
        if (currentState == SolarState.boiler200 && Ttop200 != null && Ttop200 > MAX_SMALL_BOILER_TEMP) {
            LogstashLogger.INSTANCE.info("Switching off small boiler to prevent overheated user water");
            stateLargeBoiler();
        }
    }

    private void resetOverheat() {
        if (new Date().getTime() - Long.parseLong(jedis.get("solar.lastStateChange")) > OVERHEAT_TIMEOUT_MS) {
            LogstashLogger.INSTANCE.info("Ending overheat status, switching to boiler500");
            stateLargeBoiler();
        }
    }

    /**
     * Weigh both the outside temperature and the pipe temperature.
     * If no outside temperature is available only check in the winter months
     * @return true if defrosting is deemed necessary
     */
    private boolean defrostCheck() {
        double pipeTemperature = TflowIn;
        if (pipeTemperature > TflowOut) {
            pipeTemperature = TflowOut;
        }
        if (jedis.exists("auxiliary.temperature")) {
            double auxTemperature = Double.parseDouble(jedis.get("auxiliary.temperature"));
            return pipeTemperature + auxTemperature < 5.0;
        }

        return isWinterNight() && pipeTemperature < 10.0;
    }

    private boolean isWinterNight() {
        Calendar now = Calendar.getInstance();
        return isWinter()
                && now.get(Calendar.HOUR_OF_DAY) < 7
                && now.get(Calendar.HOUR_OF_DAY) > 21;
    }

    private boolean isWinter() {
        Calendar now = Calendar.getInstance();
        return (now.get(Calendar.MONTH) == Calendar.NOVEMBER
                || now.get(Calendar.MONTH) == Calendar.DECEMBER
                || now.get(Calendar.MONTH) == Calendar.JANUARY);
    }

    private void checkDefrost() {
        if (!defrostCheck()) {
            LogstashLogger.INSTANCE.info("Ending defrost status, switching to startup");
            stateStartup();
        }
    }

    private void readTemperatures() throws IOException {
        if (jedis.exists("pipe.TflowIn") && jedis.exists("pipe.TflowOut")) {
            TflowIn = Double.parseDouble(jedis.get("pipe.TflowIn"));
            TflowOut = Double.parseDouble(jedis.get("pipe.TflowOut"));
        } else {
            stateError(); //avoid overheating the pump, shut everything down
            LogstashLogger.INSTANCE.error("No temperature readings available, going into error state");
            throw new IOException("No control temperature available");
        }

        if (jedis.exists("boiler500.Tbottom")) {
            Tbottem500 = Double.parseDouble(jedis.get("boiler500.Tbottom"));
        } else {
            Tbottem500 = null;
            LogstashLogger.INSTANCE.warn("Boiler temperature boiler500.Tbottom not available");
        }
        if (jedis.exists("solar.stateStartTflowOut")) {
            stateStartTflowOut = Double.parseDouble(jedis.get("solar.stateStartTflowOut"));
        }
        if (jedis.exists("boiler200.Ttop")) {
            Ttop200 = Double.parseDouble(jedis.get("boiler200.Ttop"));
        } else {
            Ttop200 = null;
            LogstashLogger.INSTANCE.warn("Boiler temperature boiler200.Ttop not available");
        }
    }

    private void stateStartup() {
        jedis.set("solar.state", SolarState.startup.name());
        //Take some extra time to smooth out early morning temperature swings.
        jedis.set("solar.lastStateChange", String.valueOf(new Date().getTime() + 10*60*1000));
        LogstashLogger.INSTANCE.info("Going into startup state");
        resetTSlope();
    }

    private void stateRecycle() {
        jedis.set("solar.state", SolarState.recycle.name());
        jedis.set("solar.lastStateChange", String.valueOf(new Date().getTime()));
        jedis.set("solar.stateStartTflowOut", String.valueOf(TflowOut));
        LogstashLogger.INSTANCE.info("Going into recycle state");
        resetTSlope();
    }

    private void stateRecycleTimeout() {
        jedis.set("solar.state", SolarState.recycleTimeout.name());
        jedis.set("solar.lastStateChange", String.valueOf(new Date().getTime()));
        jedis.set("solar.stateStartTflowOut", String.valueOf(TflowOut));
        LogstashLogger.INSTANCE.info("Going into recycle timeout state");
        resetTSlope();
    }

    private void stateLargeBoiler() {
        jedis.set("solar.state", SolarState.boiler500.name());
        jedis.set("solar.lastStateChange", String.valueOf(new Date().getTime()));
        jedis.set("solar.stateStartTflowOut", String.valueOf(TflowOut));
        LogstashLogger.INSTANCE.info("Switching to boiler500");
        resetTSlope();
    }

    private void stateSmallBoiler() {
        jedis.set("solar.state", SolarState.boiler200.name());
        jedis.set("solar.lastStateChange", String.valueOf(new Date().getTime()));
        jedis.set("solar.stateStartTflowOut", String.valueOf(TflowOut));
        LogstashLogger.INSTANCE.info("Switching to boiler200");
        resetTSlope();
    }

    private void stateError() {
        if (currentState != SolarState.error) {
            jedis.set("solar.state", SolarState.error.name());
            if (jedis.exists("solar.lastStateChange")) {
                jedis.del("solar.lastStateChange"); //this will force system to startup at new state change
            }
            if (jedis.exists("solar.stateStartTflowOut")) {
                jedis.del("solar.stateStartTflowOut");
            }
            LogstashLogger.INSTANCE.info("Going into error state");
            resetTSlope();
        }
    }

    private void stateOverheat() {
        if (currentState != SolarState.overheat) {
            jedis.set("solar.state", SolarState.overheat.name());
            jedis.set("solar.lastStateChange", String.valueOf(new Date().getTime()));
            jedis.set("solar.stateStartTflowOut", String.valueOf(TflowOut));
            LogstashLogger.INSTANCE.info("Going into overheat state");
            resetTSlope();
        }
    }

    private void stateDefrost() {
        if (currentState != SolarState.defrost) {
            jedis.set("solar.state", SolarState.defrost.name());
            jedis.set("solar.lastStateChange", String.valueOf(new Date().getTime()));
            jedis.set("solar.stateStartTflowOut", String.valueOf(TflowOut));
            LogstashLogger.INSTANCE.info("Going into defrost state");
            resetTSlope();
        }
    }

    private void stateSunset() {
        if (currentState != SolarState.sunset) {
            jedis.set("solar.state", SolarState.sunset.name());
            LogstashLogger.INSTANCE.info("Going into sunset state, " + new Sun());
            if (jedis.exists("solar.lastStateChange")) {
                jedis.del("solar.lastStateChange"); //this will force system to startup at new state change
            }
            if (jedis.exists("solar.stateStartTflowOut")) {
                jedis.del("solar.stateStartTflowOut");
            }
        }
        resetTSlope();
    }
/** todo make this work if needed
    private void pipeTSlope() {
        if (jedis.llen("pipe.TflowSet") >= MIN_OBSERVATIONS) {
            SimpleRegression regression = new SimpleRegression();
            List<String> pipeTemperatures = jedis.lrange("pipe.TflowSet", 0, SolarSlave.T_SET_LENGTH);
            for (String pipeTemperature : pipeTemperatures) {
                double time = Double.parseDouble(pipeTemperature.split(":")[0]);
                if (time > ((double)new Date().getTime())/(60*60*1000) - SLOPE_WINDOW_HR) {
                    regression.addData(time, Double.parseDouble(pipeTemperature.split(":")[1]));
                }
            }
            if (regression.getN() >= MIN_OBSERVATIONS) {
                jedis.setex("pipe.Tslope", Properties.redisExpireSeconds, String.valueOf(regression.getSlope()));
                jedis.setex("pipe.TstandardDeviation", Properties.redisExpireSeconds
                        , String.valueOf(regression.getSlopeStdErr()));
            } else {
                LogstashLogger.INSTANCE.info("Not enough recent observations (" + regression.getN()
                        + ") for slope calculation");
            }
        } else {
            LogstashLogger.INSTANCE.info("Not yet enough observations for slope calculation");
        }
    }
*/
    private void resetTSlope() {
        jedis.del("pipe.TflowSet");
    }
}
