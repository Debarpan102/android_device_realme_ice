package org.lineageos.settings.refreshrate;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.lineageos.settings.R;

import java.util.Locale;

public class RefreshTileService extends TileService {
    private Context context;
    private Tile tile;
    private boolean observerRegistered;

    private final ContentObserver settingsObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateTileView(RefreshRateHelper.syncFromSettings(context));
        }
    };

    private void updateTileView(RefreshRateHelper.State state) {
        if (tile == null) {
            return;
        }
        String subtitle;
        if (state.smoothDisplayActive || state.minRate <= 0 || state.minRate == state.maxRate) {
            subtitle = String.format(Locale.US, "%d Hz", state.maxRate);
        } else {
            subtitle = String.format(Locale.US, "%d - %d Hz", state.minRate, state.maxRate);
        }

        tile.setLabel(getString(R.string.refresh_rate_tile_title));
        tile.setSubtitle(subtitle);
        tile.setContentDescription(subtitle);
        tile.setState(state.maxRate > 60 ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        tile = getQsTile();
        updateTileView(RefreshRateHelper.syncFromSettings(context));
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        tile = getQsTile();
        if (!observerRegistered) {
            context.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(RefreshRateHelper.KEY_MIN_REFRESH_RATE),
                    false, settingsObserver);
            context.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(RefreshRateHelper.KEY_PEAK_REFRESH_RATE),
                    false, settingsObserver);
            observerRegistered = true;
        }
        updateTileView(RefreshRateHelper.syncFromSettings(context));
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        if (observerRegistered) {
            context.getContentResolver().unregisterContentObserver(settingsObserver);
            observerRegistered = false;
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        updateTileView(RefreshRateHelper.cycleMaxRate(context));
    }
}