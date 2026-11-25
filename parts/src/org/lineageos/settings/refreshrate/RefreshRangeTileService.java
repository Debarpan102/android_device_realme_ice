package org.lineageos.settings.refreshrate;

import android.app.AlertDialog;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.lineageos.settings.R;

import java.util.List;
import java.util.Locale;

public class RefreshRangeTileService extends TileService {
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

    private int dpToPx(int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()));
    }

    private void updateTileView(RefreshRateHelper.State state) {
        if (tile == null) {
            return;
        }
        String subtitle;
        if (state.smoothDisplayActive || state.minRate <= 0) {
            subtitle = String.format(Locale.US, "Auto - %d Hz", state.maxRate);
        } else if (state.minRate == state.maxRate) {
            subtitle = String.format(Locale.US, "%d Hz", state.maxRate);
        } else {
            subtitle = String.format(Locale.US, "%d - %d Hz", state.minRate, state.maxRate);
        }

        tile.setLabel(getString(R.string.refresh_range_tile_title));
        tile.setSubtitle(subtitle);
        tile.setContentDescription(subtitle);
        tile.setState(state.maxRate > 60 ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    private void showRangeDialog() {
        List<Integer> rates = RefreshRateHelper.getSupportedRates(context);
        if (rates.isEmpty()) {
            return;
        }

        RefreshRateHelper.State state = RefreshRateHelper.syncFromSettings(context);
        int currentMaxIdx = RefreshRateHelper.findClosestIndex(rates, state.maxRate);
        int effectiveMin = state.minRate > 0 ? state.minRate : state.userMinRate;
        int currentMinIdx = Math.min(
                RefreshRateHelper.findClosestIndex(rates, effectiveMin), currentMaxIdx);

        Context dialogContext = new ContextThemeWrapper(
                this, android.R.style.Theme_DeviceDefault_Dialog_Alert);

        int padH = dpToPx(24);
        int padV = dpToPx(14);
        LinearLayout container = new LinearLayout(dialogContext);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padH, padV, padH, padV / 2);

        TextView minLabel = new TextView(dialogContext);
        minLabel.setText(getString(R.string.refresh_rate_min_label, rates.get(currentMinIdx)));
        minLabel.setPadding(0, 0, 0, dpToPx(6));
        container.addView(minLabel);

        SeekBar minSeek = new SeekBar(dialogContext);
        minSeek.setMax(rates.size() - 1);
        minSeek.setProgress(currentMinIdx);
        container.addView(minSeek);

        TextView maxLabel = new TextView(dialogContext);
        maxLabel.setText(getString(R.string.refresh_rate_max_label, rates.get(currentMaxIdx)));
        maxLabel.setPadding(0, dpToPx(16), 0, dpToPx(6));
        container.addView(maxLabel);

        SeekBar maxSeek = new SeekBar(dialogContext);
        maxSeek.setMax(rates.size() - 1);
        maxSeek.setProgress(currentMaxIdx);
        container.addView(maxSeek);

        minSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                minLabel.setText(getString(R.string.refresh_rate_min_label, rates.get(progress)));
                if (progress > maxSeek.getProgress()) {
                    maxSeek.setProgress(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        maxSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int minProgress = minSeek.getProgress();
                if (progress < minProgress) {
                    seekBar.setProgress(minProgress);
                    return;
                }
                maxLabel.setText(getString(R.string.refresh_rate_max_label, rates.get(progress)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        AlertDialog dialog = new AlertDialog.Builder(dialogContext)
                .setTitle(R.string.refresh_range_tile_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    int selectedMin = rates.get(minSeek.getProgress());
                    int selectedMax = rates.get(maxSeek.getProgress());
                    updateTileView(RefreshRateHelper.applyUserRange(
                            context, selectedMin, selectedMax, true));
                })
                .setNeutralButton(R.string.refresh_rate_reset, (d, which) ->
                        updateTileView(RefreshRateHelper.resetToDefault(context)))
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        showDialog(dialog);
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
        showRangeDialog();
    }
}