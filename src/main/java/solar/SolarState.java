package solar;

/**
 * Created by Jaap on 29-7-2016.
 */
public enum SolarState {
    sunset(false, false, false),
    startup(true, true, true), // recycle
    recycle(true, true, true),
    recycleTimeout(true, true, false), //recycle with pump switched off
    boiler500(false, false, true),
    boiler200(true, false, true),
    overheat(false, false, false),
    defrost(false, false, true), //flow through large boiler to avoid freezing
    error(false, false, false);

    public final boolean valveOne, valveTwo, solarPump;

    SolarState(boolean valveOne, boolean valveTwo, boolean solarPump) {
        this.valveOne = valveOne;
        this.valveTwo = valveTwo;
        this.solarPump = solarPump;
    }

    public static SolarState principalState(boolean valveI, boolean valveII, boolean pump) {
        if (!valveI && !valveII && !pump) {
            return sunset;
        } else if (!valveI && !valveII){
            return boiler500;
        } else if (!valveII){
            return boiler200;
        } else {
            return recycle;
        }
    }

    public byte[] line() {
        return new byte[]{(byte) (valveOne ? 'T' : 'F'), (byte) (valveTwo ? 'T' : 'F'), (byte) (solarPump ? 'T' : 'F')};
    }
}
