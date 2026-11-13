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

/**
 * Fixed-size ring buffer of integer samples (raw RSSI values) with simple
 * statistics. Contains no Android dependencies so it can be unit-tested on a
 * plain JVM.
 *
 * Not thread-safe: use from a single thread (the UI thread).
 */
final class FmSignalHistory {
    private final int[] mValues;
    private int mCount = 0;
    private int mNextIndex = 0;

    FmSignalHistory(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        mValues = new int[capacity];
    }

    /** Append a sample, dropping the oldest one when the buffer is full. */
    void add(int value) {
        mValues[mNextIndex] = value;
        mNextIndex = (mNextIndex + 1) % mValues.length;
        if (mCount < mValues.length) {
            mCount++;
        }
    }

    /** Remove all samples. */
    void clear() {
        mCount = 0;
        mNextIndex = 0;
    }

    int size() {
        return mCount;
    }

    int capacity() {
        return mValues.length;
    }

    /**
     * Get a sample by age order.
     *
     * @param i 0 is the oldest stored sample, size() - 1 is the newest
     */
    int get(int i) {
        if (i < 0 || i >= mCount) {
            throw new IndexOutOfBoundsException("index " + i + ", size " + mCount);
        }
        int start = (mCount < mValues.length) ? 0 : mNextIndex;
        return mValues[(start + i) % mValues.length];
    }

    /** Smallest stored sample. Must not be called when size() == 0. */
    int min() {
        requireNotEmpty();
        int min = get(0);
        for (int i = 1; i < mCount; i++) {
            min = Math.min(min, get(i));
        }
        return min;
    }

    /** Largest stored sample. Must not be called when size() == 0. */
    int max() {
        requireNotEmpty();
        int max = get(0);
        for (int i = 1; i < mCount; i++) {
            max = Math.max(max, get(i));
        }
        return max;
    }

    /** Arithmetic mean of stored samples. Must not be called when size() == 0. */
    float average() {
        requireNotEmpty();
        long sum = 0;
        for (int i = 0; i < mCount; i++) {
            sum += get(i);
        }
        return (float) sum / mCount;
    }

    private void requireNotEmpty() {
        if (mCount == 0) {
            throw new IllegalStateException("history is empty");
        }
    }
}
