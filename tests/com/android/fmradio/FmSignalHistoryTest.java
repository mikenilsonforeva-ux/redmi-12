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
 * Plain-JVM test for FmSignalHistory (no Android classes needed).
 * Run with tests/run_tests.sh. Exits non-zero on the first failure.
 */
public final class FmSignalHistoryTest {
    private static int sChecks = 0;

    private static void check(boolean condition, String message) {
        sChecks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectThrows(Class<? extends Throwable> type, Runnable r, String message) {
        sChecks++;
        try {
            r.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return;
            }
            throw new AssertionError(message + ": wrong exception " + t);
        }
        throw new AssertionError(message + ": nothing thrown");
    }

    public static void main(String[] args) {
        // Empty buffer
        final FmSignalHistory h = new FmSignalHistory(4);
        check(h.size() == 0, "starts empty");
        check(h.capacity() == 4, "capacity");
        expectThrows(IllegalStateException.class, new Runnable() {
            public void run() { h.min(); }
        }, "min on empty");
        expectThrows(IllegalStateException.class, new Runnable() {
            public void run() { h.max(); }
        }, "max on empty");
        expectThrows(IllegalStateException.class, new Runnable() {
            public void run() { h.average(); }
        }, "average on empty");
        expectThrows(IndexOutOfBoundsException.class, new Runnable() {
            public void run() { h.get(0); }
        }, "get on empty");

        // Partial fill keeps insertion order
        h.add(10);
        h.add(30);
        h.add(20);
        check(h.size() == 3, "size after 3 adds");
        check(h.get(0) == 10 && h.get(1) == 30 && h.get(2) == 20, "order before wrap");
        check(h.min() == 10, "min before wrap");
        check(h.max() == 30, "max before wrap");
        check(Math.abs(h.average() - 20f) < 1e-6, "average before wrap");

        // Exactly full
        h.add(40);
        check(h.size() == 4, "size when full");
        check(h.get(0) == 10 && h.get(3) == 40, "order when full");

        // Wrap-around drops the oldest sample, oldest-first order is preserved
        h.add(50);
        h.add(60);
        check(h.size() == 4, "size stays at capacity");
        check(h.get(0) == 20 && h.get(1) == 40 && h.get(2) == 50 && h.get(3) == 60,
                "order after wrap");
        check(h.min() == 20, "min after wrap");
        check(h.max() == 60, "max after wrap");
        check(Math.abs(h.average() - 42.5f) < 1e-6, "average after wrap");
        expectThrows(IndexOutOfBoundsException.class, new Runnable() {
            public void run() { h.get(4); }
        }, "get past size");
        expectThrows(IndexOutOfBoundsException.class, new Runnable() {
            public void run() { h.get(-1); }
        }, "get negative");

        // Many wraps
        for (int i = 0; i < 1000; i++) {
            h.add(i);
        }
        check(h.get(0) == 996 && h.get(3) == 999, "order after many wraps");

        // Negative values (raw RSSI may be signed)
        h.clear();
        check(h.size() == 0, "clear empties");
        h.add(-90);
        h.add(-60);
        h.add(-75);
        check(h.min() == -90 && h.max() == -60, "negative min/max");
        check(Math.abs(h.average() - (-75f)) < 1e-6, "negative average");

        // Reuse after clear starts from the beginning
        h.clear();
        h.add(7);
        check(h.size() == 1 && h.get(0) == 7, "reuse after clear");

        // No int overflow in average
        FmSignalHistory big = new FmSignalHistory(2);
        big.add(Integer.MAX_VALUE);
        big.add(Integer.MAX_VALUE);
        check(Math.abs(big.average() - (float) Integer.MAX_VALUE) < 1e3, "average no overflow");

        // Invalid capacity
        expectThrows(IllegalArgumentException.class, new Runnable() {
            public void run() { new FmSignalHistory(0); }
        }, "zero capacity");

        System.out.println("FmSignalHistoryTest: " + sChecks + " checks passed");
    }
}
