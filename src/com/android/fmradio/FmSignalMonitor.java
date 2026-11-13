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

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

/**
 * Polls read-only signal quality values from the FM driver once per second on
 * a dedicated background thread and delivers immutable snapshots to a listener
 * on the main thread.
 *
 * Safety rules implemented here:
 * - only read-only native calls are made (readRssi, stereoMono, readRdsBler,
 *   readCapArray); nothing that writes registers or changes the radio state;
 * - no native call is made while the radio is off, the device is closed, or a
 *   scan/seek is in progress, so the poller never competes with them;
 * - a native method missing from the installed libfmjni.so (reported by the
 *   VM as UnsatisfiedLinkError) is marked "unsupported" for the lifetime of
 *   this monitor and never called again;
 * - a failed read is reported as a failure; an old value is never presented
 *   as a fresh one.
 *
 * All values are raw driver values. Units are not confirmed for MT6631, so
 * they are never presented as dBm or percent.
 */
final class FmSignalMonitor {
    private static final String TAG = "FmSignalMonitor";

    /** Poll period: roughly one second, to keep the load on /dev/fm low. */
    static final long POLL_INTERVAL_MS = 1000;

    // Snapshot state
    static final int STATE_NO_SERVICE = 0;
    static final int STATE_RADIO_OFF = 1;
    /** Scan or seek in progress: polling is paused on purpose. */
    static final int STATE_BUSY = 2;
    static final int STATE_ACTIVE = 3;

    // Reading status
    static final int STATUS_OK = 0;
    /** The call threw or the radio went away while reading. */
    static final int STATUS_ERROR = 1;
    /** The installed native library has no such method. */
    static final int STATUS_UNSUPPORTED = 2;
    /** Not read in this snapshot (radio off or busy). */
    static final int STATUS_NOT_READ = 3;

    // Parameter indexes
    private static final int P_RSSI = 0;
    private static final int P_STEREO = 1;
    private static final int P_BLER = 2;
    private static final int P_CAP_ARRAY = 3;
    private static final int P_COUNT = 4;

    /** Result of reading one parameter. */
    static final class Reading {
        final int status;
        /** Raw driver value, meaningful only when status == STATUS_OK. */
        final int value;
        /** Wall-clock time of the last successful read of this parameter, 0 if never. */
        final long lastOkTimeMs;

        Reading(int status, int value, long lastOkTimeMs) {
            this.status = status;
            this.value = value;
            this.lastOkTimeMs = lastOkTimeMs;
        }

        boolean isOk() {
            return status == STATUS_OK;
        }
    }

    /** Immutable result of one poll cycle. */
    static final class Snapshot {
        final int state;
        /** Current station in 100 kHz units (e.g. 1067 = 106.7 MHz), -1 if unknown. */
        final int station;
        final long timeMs;
        final Reading rssi;
        final Reading stereo;
        final Reading bler;
        final Reading capArray;

        Snapshot(int state, int station, long timeMs, Reading rssi, Reading stereo,
                Reading bler, Reading capArray) {
            this.state = state;
            this.station = station;
            this.timeMs = timeMs;
            this.rssi = rssi;
            this.stereo = stereo;
            this.bler = bler;
            this.capArray = capArray;
        }
    }

    /** Receives snapshots on the main thread. */
    interface Listener {
        void onSnapshot(Snapshot snapshot);
    }

    private interface IntSource {
        int read();
    }

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Listener mListener;

    // Touched only on the worker thread after start().
    private final boolean[] mUnsupported = new boolean[P_COUNT];
    private final long[] mLastOkMs = new long[P_COUNT];

    private volatile FmService mService;
    private volatile boolean mRunning = false;
    private HandlerThread mThread;
    private volatile Handler mWorker;

    FmSignalMonitor(Listener listener) {
        mListener = listener;
    }

    /** Set (or clear with null) the service used to query radio state. */
    void setService(FmService service) {
        mService = service;
    }

    /** Start polling. Calling it twice is harmless. */
    synchronized void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        mThread = new HandlerThread("FmSignalMonitor");
        mThread.start();
        mWorker = new Handler(mThread.getLooper());
        mWorker.post(new PollTask(mWorker));
    }

    /** Stop polling. No snapshot is delivered after this returns. */
    synchronized void stop() {
        if (!mRunning) {
            return;
        }
        mRunning = false;
        if (mWorker != null) {
            mWorker.removeCallbacksAndMessages(null);
            mWorker = null;
        }
        if (mThread != null) {
            // Lets a poll that is already running finish, then ends the thread.
            mThread.quitSafely();
            mThread = null;
        }
        mMainHandler.removeCallbacksAndMessages(null);
    }

    /**
     * One polling chain, bound to the worker of one start()/stop() session. A
     * chain from an earlier session that was still inside a native call when
     * stop() ran ends itself instead of attaching to the next session's
     * worker, so two chains can never poll at the same time.
     */
    private final class PollTask implements Runnable {
        private final Handler mOwner;

        PollTask(Handler owner) {
            mOwner = owner;
        }

        @Override
        public void run() {
            if (!mRunning || mWorker != mOwner) {
                return;
            }
            final Snapshot snapshot = collect();
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mRunning) {
                        mListener.onSnapshot(snapshot);
                    }
                }
            });
            synchronized (FmSignalMonitor.this) {
                if (mRunning && mWorker == mOwner) {
                    mOwner.postDelayed(this, POLL_INTERVAL_MS);
                }
            }
        }
    }

    private Snapshot collect() {
        final long now = System.currentTimeMillis();
        final FmService service = mService;

        if (service == null) {
            return notRead(STATE_NO_SERVICE, -1, now);
        }
        final int station = service.getFrequency();
        if (!service.isDeviceOpen() || service.getPowerStatus() != FmService.POWER_UP) {
            return notRead(STATE_RADIO_OFF, station, now);
        }
        if (service.isScanning() || service.isSeeking()) {
            return notRead(STATE_BUSY, station, now);
        }

        Reading rssi = read(P_RSSI, new IntSource() {
            @Override
            public int read() {
                return FmNative.readRssi();
            }
        });
        Reading stereo = read(P_STEREO, new IntSource() {
            @Override
            public int read() {
                return FmNative.stereoMono() ? 1 : 0;
            }
        });
        Reading bler = read(P_BLER, new IntSource() {
            @Override
            public int read() {
                return FmNative.readRdsBler();
            }
        });
        Reading capArray = read(P_CAP_ARRAY, new IntSource() {
            @Override
            public int read() {
                return FmNative.readCapArray();
            }
        });
        return new Snapshot(STATE_ACTIVE, station, now, rssi, stereo, bler, capArray);
    }

    private Reading read(int index, IntSource source) {
        if (mUnsupported[index]) {
            return new Reading(STATUS_UNSUPPORTED, 0, mLastOkMs[index]);
        }
        try {
            int value = source.read();
            mLastOkMs[index] = System.currentTimeMillis();
            return new Reading(STATUS_OK, value, mLastOkMs[index]);
        } catch (LinkageError e) {
            // UnsatisfiedLinkError (a LinkageError): the method is declared in
            // FmNative.java but the installed libfmjni.so does not register
            // it, so no native code ran.
            Log.w(TAG, "native method #" + index + " is not available: " + e.getMessage());
            mUnsupported[index] = true;
            return new Reading(STATUS_UNSUPPORTED, 0, mLastOkMs[index]);
        } catch (RuntimeException e) {
            Log.w(TAG, "native read #" + index + " failed", e);
            return new Reading(STATUS_ERROR, 0, mLastOkMs[index]);
        }
    }

    private Snapshot notRead(int state, int station, long now) {
        Reading r0 = new Reading(STATUS_NOT_READ, 0, mLastOkMs[P_RSSI]);
        Reading r1 = new Reading(STATUS_NOT_READ, 0, mLastOkMs[P_STEREO]);
        Reading r2 = new Reading(STATUS_NOT_READ, 0, mLastOkMs[P_BLER]);
        Reading r3 = new Reading(STATUS_NOT_READ, 0, mLastOkMs[P_CAP_ARRAY]);
        return new Snapshot(state, station, now, r0, r1, r2, r3);
    }
}
