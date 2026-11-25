package org.lineageos.settings.refreshrate;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.view.Display;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class RefreshRateHelper {
    static final String KEY_MIN_REFRESH_RATE = "min_refresh_rate";
    static final String KEY_PEAK_REFRESH_RATE = "peak_refresh_rate";

    private static final int DEFAULT_MIN_RATE = 60;
    private static final String PREFS_NAME = "refresh_rate_tile_prefs";
    private static final String PREF_INITIALIZED = "initialized";
    private static final String PREF_USER_MIN = "user_min_rate";
    private static final String PREF_LAST_MIN = "last_min_rate";
    private static final String PREF_LAST_PEAK = "last_peak_rate";
    private static final String PREF_LAST_PEAK_INFINITE = "last_peak_infinite";
    private static final String PREF_SMOOTH_OVERRIDE = "smooth_display_override";
    private static final String PREF_PREV_MIN = "prev_min_rate";
    private static final String PREF_PREV_MAX = "prev_max_rate";

    private static final Set<Integer> TARGET_RATES = new HashSet<>(
            Arrays.asList(48, 50, 60, 90, 120, 144));

    static final class State {
        final int minRate;
        final int maxRate;
        final int userMinRate;
        final boolean smoothDisplayActive;

        State(int minRate, int maxRate, int userMinRate, boolean smoothDisplayActive) {
            this.minRate = minRate;
            this.maxRate = maxRate;
            this.userMinRate = userMinRate;
            this.smoothDisplayActive = smoothDisplayActive;
        }
    }

    private RefreshRateHelper() {}

    static List<Integer> getSupportedRates(Context context) {
        List<Integer> rates = new ArrayList<>();
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        Display display = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
        if (display == null) {
            return rates;
        }

        Display.Mode activeMode = display.getMode();
        for (Display.Mode m : display.getSupportedModes()) {
            int rate = Math.round(m.getRefreshRate());
            if (m.getPhysicalWidth() == activeMode.getPhysicalWidth()
                    && m.getPhysicalHeight() == activeMode.getPhysicalHeight()
                    && TARGET_RATES.contains(rate)
                    && !rates.contains(rate)) {
                rates.add(rate);
            }
        }
        Collections.sort(rates);
        return rates;
    }

    static int findClosestIndex(List<Integer> rates, int target) {
        int exactIdx = rates.indexOf(target);
        if (exactIdx >= 0) {
            return exactIdx;
        }
        int closestIdx = 0;
        int minDiff = Integer.MAX_VALUE;
        for (int i = 0; i < rates.size(); i++) {
            int diff = Math.abs(rates.get(i) - target);
            if (diff < minDiff) {
                minDiff = diff;
                closestIdx = i;
            }
        }
        return closestIdx;
    }

    static int getUserMinRate(Context context, List<Integer> rates) {
        if (rates.isEmpty()) {
            return DEFAULT_MIN_RATE;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int defaultMin = rates.contains(DEFAULT_MIN_RATE) ? DEFAULT_MIN_RATE : rates.get(0);
        int savedMin = prefs.getInt(PREF_USER_MIN, defaultMin);
        return rates.get(findClosestIndex(rates, savedMin));
    }

    static synchronized State syncFromSettings(Context context) {
        List<Integer> rates = getSupportedRates(context);
        if (rates.isEmpty()) {
            return new State(DEFAULT_MIN_RATE, DEFAULT_MIN_RATE, DEFAULT_MIN_RATE, false);
        }

        int highestRate = rates.get(rates.size() - 1);
        int userMin = getUserMinRate(context, rates);

        float peak = Settings.System.getFloat(
                context.getContentResolver(), KEY_PEAK_REFRESH_RATE, Float.POSITIVE_INFINITY);
        float min = Settings.System.getFloat(
                context.getContentResolver(), KEY_MIN_REFRESH_RATE, 0f);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_INITIALIZED, false)) {
            boolean isInf = Float.isInfinite(peak);
            prefs.edit()
                    .putBoolean(PREF_INITIALIZED, true)
                    .putFloat(PREF_LAST_MIN, min)
                    .putFloat(PREF_LAST_PEAK, isInf ? -1f : peak)
                    .putBoolean(PREF_LAST_PEAK_INFINITE, isInf)
                    .putBoolean(PREF_SMOOTH_OVERRIDE, false)
                    .commit();
        } else {
            float lastMin = prefs.getFloat(PREF_LAST_MIN, 0f);
            float lastPeak = prefs.getFloat(PREF_LAST_PEAK, -1f);
            boolean lastPeakInf = prefs.getBoolean(PREF_LAST_PEAK_INFINITE, false);
            boolean smoothOverride = prefs.getBoolean(PREF_SMOOTH_OVERRIDE, false);

            boolean isInf = Float.isInfinite(peak);
            boolean peakChangedExternally = isInf
                    ? !lastPeakInf
                    : (lastPeakInf || (lastPeak > 0f && Math.round(peak) != Math.round(lastPeak)));

            if (peakChangedExternally) {
                boolean smoothTurnedOn = isInf || Math.round(peak) >= highestRate;
                boolean smoothTurnedOff = smoothOverride && !isInf && Math.round(peak) <= 60;

                if (smoothTurnedOn) {
                    SharedPreferences.Editor editor = prefs.edit();
                    if (!smoothOverride && lastPeak > 0f && !lastPeakInf) {
                        int prevMax = rates.get(findClosestIndex(rates, Math.round(lastPeak)));
                        int prevMin = lastMin > 0f
                                ? rates.get(findClosestIndex(rates, Math.round(lastMin)))
                                : userMin;
                        prevMin = Math.min(prevMin, prevMax);
                        editor.putInt(PREF_PREV_MIN, prevMin)
                                .putInt(PREF_PREV_MAX, prevMax)
                                .putBoolean(PREF_SMOOTH_OVERRIDE, true);
                    }
                    editor.putFloat(PREF_LAST_MIN, 0f)
                            .putFloat(PREF_LAST_PEAK, isInf ? -1f : peak)
                            .putBoolean(PREF_LAST_PEAK_INFINITE, isInf)
                            .commit();

                    if (min > 0f) {
                        Settings.System.putFloat(
                                context.getContentResolver(), KEY_MIN_REFRESH_RATE, 0f);
                        min = 0f;
                    }
                } else if (smoothTurnedOff) {
                    int prevMin = prefs.getInt(PREF_PREV_MIN, -1);
                    int prevMax = prefs.getInt(PREF_PREV_MAX, -1);

                    if (prevMax > 0) {
                        int restoreMax = rates.get(findClosestIndex(rates, prevMax));
                        int restoreMin = prevMin > 0
                                ? Math.min(rates.get(findClosestIndex(rates, prevMin)), restoreMax)
                                : Math.min(userMin, restoreMax);

                        prefs.edit()
                                .putFloat(PREF_LAST_MIN, restoreMin)
                                .putFloat(PREF_LAST_PEAK, restoreMax)
                                .putBoolean(PREF_LAST_PEAK_INFINITE, false)
                                .putBoolean(PREF_SMOOTH_OVERRIDE, false)
                                .commit();

                        Settings.System.putFloat(
                                context.getContentResolver(), KEY_MIN_REFRESH_RATE, restoreMin);
                        Settings.System.putFloat(
                                context.getContentResolver(), KEY_PEAK_REFRESH_RATE, restoreMax);
                        min = restoreMin;
                        peak = restoreMax;
                    } else {
                        prefs.edit()
                                .putFloat(PREF_LAST_MIN, 0f)
                                .putFloat(PREF_LAST_PEAK, peak)
                                .putBoolean(PREF_LAST_PEAK_INFINITE, false)
                                .putBoolean(PREF_SMOOTH_OVERRIDE, false)
                                .commit();
                    }
                } else {
                    prefs.edit()
                            .putFloat(PREF_LAST_MIN, 0f)
                            .putFloat(PREF_LAST_PEAK, peak)
                            .putBoolean(PREF_LAST_PEAK_INFINITE, false)
                            .putBoolean(PREF_SMOOTH_OVERRIDE, false)
                            .commit();
                    if (min > 0f) {
                        Settings.System.putFloat(
                                context.getContentResolver(), KEY_MIN_REFRESH_RATE, 0f);
                        min = 0f;
                    }
                }
            }
        }

        int activeMax = (Float.isInfinite(peak) || peak <= 0f)
                ? highestRate
                : rates.get(findClosestIndex(rates, Math.round(peak)));

        boolean smoothActive = prefs.getBoolean(PREF_SMOOTH_OVERRIDE, false) || min <= 0f;
        int activeMin = smoothActive
                ? 0
                : Math.min(rates.get(findClosestIndex(rates, Math.round(min))), activeMax);

        return new State(activeMin, activeMax, userMin, smoothActive);
    }

    static synchronized State applyUserRange(
            Context context, int minRate, int maxRate, boolean updateUserMin) {
        List<Integer> rates = getSupportedRates(context);
        if (rates.isEmpty()) {
            return new State(DEFAULT_MIN_RATE, DEFAULT_MIN_RATE, DEFAULT_MIN_RATE, false);
        }

        // Min is the floor; max is strictly clamped to at least min
        int clampedMin = rates.get(findClosestIndex(rates, minRate));
        int clampedMax = rates.get(findClosestIndex(rates, Math.max(clampedMin, maxRate)));

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(PREF_INITIALIZED, true)
                .putFloat(PREF_LAST_MIN, clampedMin)
                .putFloat(PREF_LAST_PEAK, clampedMax)
                .putBoolean(PREF_LAST_PEAK_INFINITE, false)
                .putBoolean(PREF_SMOOTH_OVERRIDE, false)
                .putInt(PREF_PREV_MIN, clampedMin)
                .putInt(PREF_PREV_MAX, clampedMax);

        if (updateUserMin) {
            editor.putInt(PREF_USER_MIN, clampedMin);
        }
        editor.commit();

        Settings.System.putFloat(context.getContentResolver(), KEY_MIN_REFRESH_RATE, clampedMin);
        Settings.System.putFloat(context.getContentResolver(), KEY_PEAK_REFRESH_RATE, clampedMax);

        requestAllTilesUpdate(context);
        int userMin = updateUserMin ? clampedMin : getUserMinRate(context, rates);
        return new State(clampedMin, clampedMax, userMin, false);
    }

    static synchronized State cycleMaxRate(Context context) {
        List<Integer> rates = getSupportedRates(context);
        if (rates.isEmpty()) {
            return new State(DEFAULT_MIN_RATE, DEFAULT_MIN_RATE, DEFAULT_MIN_RATE, false);
        }

        State current = syncFromSettings(context);
        int minRate = current.userMinRate;

        List<Integer> eligibleMaxRates = new ArrayList<>();
        for (int r : rates) {
            if (r >= minRate) {
                eligibleMaxRates.add(r);
            }
        }
        if (eligibleMaxRates.isEmpty()) {
            eligibleMaxRates.add(rates.get(rates.size() - 1));
        }

        int nextMax;
        if (current.maxRate < minRate) {
            // Snap to min first if an external toggle lowered peak below min
            nextMax = eligibleMaxRates.get(0);
        } else {
            int currentIdx = findClosestIndex(eligibleMaxRates, current.maxRate);
            nextMax = eligibleMaxRates.get((currentIdx + 1) % eligibleMaxRates.size());
        }

        // Keeps user's min Hz (or 60 Hz default) intact while cycling max Hz
        return applyUserRange(context, minRate, nextMax, false);
    }

    static synchronized State resetToDefault(Context context) {
        List<Integer> rates = getSupportedRates(context);
        if (rates.isEmpty()) {
            return new State(DEFAULT_MIN_RATE, DEFAULT_MIN_RATE, DEFAULT_MIN_RATE, false);
        }
        int defaultMin = rates.contains(DEFAULT_MIN_RATE) ? DEFAULT_MIN_RATE : rates.get(0);
        int highestRate = rates.get(rates.size() - 1);
        return applyUserRange(context, defaultMin, highestRate, true);
    }

    private static void requestAllTilesUpdate(Context context) {
        TileService.requestListeningState(
                context, new ComponentName(context, RefreshTileService.class));
        TileService.requestListeningState(
                context, new ComponentName(context, RefreshRangeTileService.class));
    }
}