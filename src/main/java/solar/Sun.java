package solar;

import common.Properties;
import net.e175.klaus.solarpositioning.AzimuthZenithAngle;
import net.e175.klaus.solarpositioning.DeltaT;
import net.e175.klaus.solarpositioning.SPA;

import java.util.Calendar;
import java.util.GregorianCalendar;

/**
 * Created by Jaap on 25-7-2016.
 */
public class Sun {

    public final int elevation;
    public final double latitude, longitude;

    private final static double MIN_AZIMUTH = 95.0;
    private final static double MAX_AZIMUTH = 300.0;
    private final static double MORNING_ZENITH = 79.0;
    private final static double EVENING_ZENITH = 83.5;

    public Sun() {
        Properties prop = new Properties();
        elevation = prop.elevation;
        latitude = prop.latitude;
        longitude = prop.longitude;
    }

    public AzimuthZenithAngle position() {
        final GregorianCalendar dateTime = new GregorianCalendar();
        return SPA.calculateSolarPosition(
                dateTime,
                latitude, longitude, elevation,
                DeltaT.estimate(dateTime),
                1010, // avg. air pressure (hPa)
                11); // avg. air temperature (°C)
    }

    public boolean shining() {
        boolean retVal;
        AzimuthZenithAngle position = position();
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 12) {
            retVal = position.getAzimuth() < MAX_AZIMUTH
                    && position.getAzimuth() > MIN_AZIMUTH
                    && position.getZenithAngle() < MORNING_ZENITH;
        } else {
            retVal = position.getAzimuth() < MAX_AZIMUTH
                    && position.getAzimuth() > MIN_AZIMUTH
                    && position.getZenithAngle() < EVENING_ZENITH;
        }

        return retVal;
    }

    @Override
    public String toString() {
        AzimuthZenithAngle position = position();
        return "Sun position azimuth: " + position.getAzimuth() +
                ", zenith angle: " + position.getZenithAngle() + ", shining: " + shining();
    }
}
