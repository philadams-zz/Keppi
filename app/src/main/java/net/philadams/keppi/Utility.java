package net.philadams.keppi;

import java.text.SimpleDateFormat;
import java.util.Calendar;

/**
 * Various utility functions.
 */
public class Utility {

  public static float linearlyScale(float x, float oldMin, float oldMax, float newMin,
      float newMax) {
    float oldRange = oldMax - oldMin;
    float newRange = newMax - newMin;
    return (((x - oldMin) * newRange) / oldRange) + newMin;
  }

  public static int linearlyScale(int x, int oldMin, int oldMax, int newMin, int newMax) {
    float oldRange = oldMax - oldMin;
    float newRange = newMax - newMin;
    return Math.round((((x - oldMin) * newRange) / oldRange) + newMin);
  }

  public static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  public static String getDateTimeString() {
    Calendar cal = Calendar.getInstance();
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS");
    return sdf.format(cal.getTime());
  }
}
