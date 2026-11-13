/*
 * Copyright (C) 2014,2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.fmradio;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.format.DateFormat;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.android.fmradio.views.FmSignalGraphView;

import java.util.Date;
import java.util.Locale;

/**
 * Read-only FM signal diagnostics screen.
 *
 * Shows raw values obtained through {@link FmSignalMonitor}. The screen only
 * polls while it is visible (onStart..onStop) and never changes any radio
 * setting.
 */
public class FmSignalActivity extends Activity implements FmSignalMonitor.Listener {
    /** Number of RSSI samples kept: about two minutes at one sample per second. */
    private static final int HISTORY_SIZE = 120;

    private final FmSignalHistory mHistory = new FmSignalHistory(HISTORY_SIZE);
    private final int[] mSamples = new int[HISTORY_SIZE];
    /** Station the history belongs to, -1 before the first snapshot. */
    private int mHistoryStation = -1;

    private FmSignalMonitor mMonitor;
    private boolean mIsServiceBound = false;

    private TextView mFrequency;
    private TextView mStatus;
    private TextView mLastOk;
    private FmSignalGraphView mGraph;
    private TextView mGraphStats;
    private Row mRssiRow;
    private Row mStereoRow;
    private Row mBlerRow;
    private Row mCapArrayRow;

    /** Views of one parameter row (layout fm_signal_row). */
    private final class Row {
        private final TextView mValue;
        private final TextView mNote;

        Row(View root, int titleRes, int descRes) {
            ((TextView) root.findViewById(R.id.signal_row_title)).setText(titleRes);
            ((TextView) root.findViewById(R.id.signal_row_desc)).setText(descRes);
            mValue = (TextView) root.findViewById(R.id.signal_row_value);
            mNote = (TextView) root.findViewById(R.id.signal_row_note);
        }

        void update(FmSignalMonitor.Reading reading, String okValue, int okNoteRes) {
            switch (reading.status) {
                case FmSignalMonitor.STATUS_OK:
                    mValue.setText(okValue);
                    mNote.setText(okNoteRes);
                    break;
                case FmSignalMonitor.STATUS_UNSUPPORTED:
                    mValue.setText(R.string.signal_value_unavailable);
                    mNote.setText(R.string.signal_note_unsupported);
                    break;
                case FmSignalMonitor.STATUS_ERROR:
                    mValue.setText(R.string.signal_value_unavailable);
                    mNote.setText(getString(R.string.signal_note_error,
                            formatTime(reading.lastOkTimeMs)));
                    break;
                default:
                    mValue.setText(R.string.signal_value_unavailable);
                    mNote.setText(getString(R.string.signal_note_not_read,
                            formatTime(reading.lastOkTimeMs)));
                    break;
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fm_signal_activity);
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        mFrequency = (TextView) findViewById(R.id.signal_frequency);
        mStatus = (TextView) findViewById(R.id.signal_status);
        mLastOk = (TextView) findViewById(R.id.signal_last_ok);
        mGraph = (FmSignalGraphView) findViewById(R.id.signal_graph);
        mGraphStats = (TextView) findViewById(R.id.signal_graph_stats);

        mRssiRow = new Row(findViewById(R.id.row_rssi),
                R.string.signal_rssi_title, R.string.signal_rssi_desc);
        mStereoRow = new Row(findViewById(R.id.row_stereo),
                R.string.signal_stereo_title, R.string.signal_stereo_desc);
        mBlerRow = new Row(findViewById(R.id.row_bler),
                R.string.signal_bler_title, R.string.signal_bler_desc);
        mCapArrayRow = new Row(findViewById(R.id.row_cap_array),
                R.string.signal_cap_title, R.string.signal_cap_desc);

        mMonitor = new FmSignalMonitor(this);

        // No BIND_AUTO_CREATE: this screen must never start the radio service
        // itself, it only observes a service that the main screen started.
        mIsServiceBound = bindService(new Intent(this, FmService.class),
                mServiceConnection, 0);
        showNoData();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mMonitor.start();
    }

    @Override
    protected void onStop() {
        mMonitor.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mMonitor.stop();
        mMonitor.setService(null);
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mMonitor.setService(((FmService.ServiceBinder) service).getService());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mMonitor.setService(null);
        }
    };

    @Override
    public void onSnapshot(FmSignalMonitor.Snapshot snapshot) {
        // A new station makes the old samples meaningless for the graph.
        if (snapshot.station >= 0 && snapshot.station != mHistoryStation) {
            mHistory.clear();
            mHistoryStation = snapshot.station;
        }
        if (snapshot.rssi.isOk()) {
            mHistory.add(snapshot.rssi.value);
        }

        mFrequency.setText(formatFrequency(snapshot.station));
        mStatus.setText(stateText(snapshot.state));
        long lastOk = latestOk(snapshot);
        mLastOk.setText(lastOk > 0
                ? getString(R.string.signal_last_ok, formatTime(lastOk))
                : getString(R.string.signal_last_ok_never));

        mRssiRow.update(snapshot.rssi, String.valueOf(snapshot.rssi.value),
                R.string.signal_rssi_note);
        mStereoRow.update(snapshot.stereo, String.valueOf(snapshot.stereo.value),
                R.string.signal_stereo_note);
        mBlerRow.update(snapshot.bler, String.valueOf(snapshot.bler.value),
                R.string.signal_bler_note);
        mCapArrayRow.update(snapshot.capArray, String.valueOf(snapshot.capArray.value),
                R.string.signal_cap_note);

        updateGraph();
    }

    /** Initial state before the first snapshot arrives. */
    private void showNoData() {
        mFrequency.setText(R.string.signal_frequency_unknown);
        mStatus.setText(R.string.signal_state_no_service);
        mLastOk.setText(R.string.signal_last_ok_never);
        updateGraph();
    }

    private void updateGraph() {
        final int count = mHistory.size();
        for (int i = 0; i < count; i++) {
            mSamples[i] = mHistory.get(i);
        }
        mGraph.setSamples(mSamples, count, HISTORY_SIZE,
                getString(R.string.signal_graph_empty));
        if (count > 0) {
            mGraphStats.setText(getString(R.string.signal_graph_stats,
                    mHistory.min(),
                    String.format(Locale.getDefault(), "%.1f", mHistory.average()),
                    mHistory.max(), count));
        } else {
            mGraphStats.setText("");
        }
    }

    private String formatFrequency(int station) {
        if (!FmUtils.isValidStation(station)) {
            return getString(R.string.signal_frequency_unknown);
        }
        return getString(R.string.signal_frequency_format, FmUtils.formatStation(station));
    }

    private int stateText(int state) {
        switch (state) {
            case FmSignalMonitor.STATE_ACTIVE:
                return R.string.signal_state_active;
            case FmSignalMonitor.STATE_RADIO_OFF:
                return R.string.signal_state_radio_off;
            case FmSignalMonitor.STATE_BUSY:
                return R.string.signal_state_busy;
            default:
                return R.string.signal_state_no_service;
        }
    }

    private static long latestOk(FmSignalMonitor.Snapshot s) {
        long latest = s.rssi.lastOkTimeMs;
        latest = Math.max(latest, s.stereo.lastOkTimeMs);
        latest = Math.max(latest, s.bler.lastOkTimeMs);
        latest = Math.max(latest, s.capArray.lastOkTimeMs);
        return latest;
    }

    private String formatTime(long timeMs) {
        if (timeMs <= 0) {
            return getString(R.string.signal_time_never);
        }
        return DateFormat.getTimeFormat(this).format(new Date(timeMs));
    }
}
