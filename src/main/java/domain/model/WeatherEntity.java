package domain.model;

import java.util.Objects;

/**
 * Entity representing one weather record.
 * Matches the JSON structure from the assignment specification.
 */
public class WeatherEntity {
    private final String id;
    private final String name;
    private final String state;
    private final String timeZone;
    private final double lat;
    private final double lon;
    private final String localDateTime;
    private final String localDateTimeFull;
    private final double airTemp;
    private final double apparentTemp;
    private final String cloud;
    private final double dewpt;
    private final double press;
    private final int relHum;
    private final String windDir;
    private final int windSpdKmh;
    private final int windSpdKt;

    public WeatherEntity(
            String id,
            String name,
            String state,
            String timeZone,
            double lat,
            double lon,
            String localDateTime,
            String localDateTimeFull,
            double airTemp,
            double apparentTemp,
            String cloud,
            double dewpt,
            double press,
            int relHum,
            String windDir,
            int windSpdKmh,
            int windSpdKt
    ) {
        this.id = id;
        this.name = name;
        this.state = state;
        this.timeZone = timeZone;
        this.lat = lat;
        this.lon = lon;
        this.localDateTime = localDateTime;
        this.localDateTimeFull = localDateTimeFull;
        this.airTemp = airTemp;
        this.apparentTemp = apparentTemp;
        this.cloud = cloud;
        this.dewpt = dewpt;
        this.press = press;
        this.relHum = relHum;
        this.windDir = windDir;
        this.windSpdKmh = windSpdKmh;
        this.windSpdKt = windSpdKt;
    }

    // Getters (immutable entity) I did not put setters because of the concurrency
    public String getId() { return id; }
    public String getName() { return name; }
    public String getState() { return state; }
    public String getTimeZone() { return timeZone; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public String getLocalDateTime() { return localDateTime; }
    public String getLocalDateTimeFull() { return localDateTimeFull; }
    public double getAirTemp() { return airTemp; }
    public double getApparentTemp() { return apparentTemp; }
    public String getCloud() { return cloud; }
    public double getDewpt() { return dewpt; }
    public double getPress() { return press; }
    public int getRelHum() { return relHum; }
    public String getWindDir() { return windDir; }
    public int getWindSpdKmh() { return windSpdKmh; }
    public int getWindSpdKt() { return windSpdKt; }

    // I added this part for uniqueness in the future
    @Override
    public boolean equals(Object o) {
        if (this == o){
            return true;
        }
        if (!(o instanceof WeatherEntity)) {
            return  false;
        }
        WeatherEntity that = (WeatherEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "WeatherEntity{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", state='" + state + '\'' +
                ", timeZone='" + timeZone + '\'' +
                ", lat=" + lat +
                ", lon=" + lon +
                ", localDateTime='" + localDateTime + '\'' +
                ", localDateTimeFull='" + localDateTimeFull + '\'' +
                ", airTemp=" + airTemp +
                ", apparentTemp=" + apparentTemp +
                ", cloud='" + cloud + '\'' +
                ", dewpt=" + dewpt +
                ", press=" + press +
                ", relHum=" + relHum +
                ", windDir='" + windDir + '\'' +
                ", windSpdKmh=" + windSpdKmh +
                ", windSpdKt=" + windSpdKt +
                '}';
    }
}
