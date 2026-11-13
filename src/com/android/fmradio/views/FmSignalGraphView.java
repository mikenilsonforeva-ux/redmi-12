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

package com.android.fmradio.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import com.android.fmradio.R;

/**
 * Minimal line graph of raw signal samples over time. The vertical axis is
 * auto-scaled to the visible samples and labelled with the raw min/max values:
 * no unit is implied, because the RSSI scale of the MT6631 driver is not
 * confirmed.
 */
public class FmSignalGraphView extends View {
    /** Smallest vertical span drawn, so a flat signal does not look like noise. */
    private static final int MIN_SPAN = 10;
    private static final int GRID_LINES = 3;

    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();

    private final float mDensity;
    private final float mPadding;

    // Samples copied out of the history, oldest first.
    private int[] mSamples = new int[0];
    private int mCount = 0;
    private int mCapacity = 1;
    private String mEmptyText = "";

    public FmSignalGraphView(Context context) {
        this(context, null);
    }

    public FmSignalGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        mPadding = 8 * mDensity;

        int lineColor = context.getColor(R.color.signal_graph_line);
        mLinePaint.setColor(lineColor);
        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeWidth(2 * mDensity);
        mLinePaint.setStrokeJoin(Paint.Join.ROUND);
        mLinePaint.setStrokeCap(Paint.Cap.ROUND);

        mDotPaint.setColor(lineColor);
        mDotPaint.setStyle(Paint.Style.FILL);

        mGridPaint.setColor(context.getColor(R.color.signal_graph_grid));
        mGridPaint.setStyle(Paint.Style.STROKE);
        mGridPaint.setStrokeWidth(1 * mDensity);

        mTextPaint.setColor(context.getColor(R.color.signal_graph_text));
        mTextPaint.setTextSize(12 * mDensity);
    }

    /**
     * Replace the displayed samples.
     *
     * @param samples samples, oldest first (only the first count are used)
     * @param count number of valid samples
     * @param capacity number of samples that fill the whole width
     * @param emptyText text shown while there are fewer than two samples
     */
    public void setSamples(int[] samples, int count, int capacity, String emptyText) {
        mSamples = samples;
        mCount = count;
        mCapacity = Math.max(capacity, 2);
        mEmptyText = emptyText != null ? emptyText : "";
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final float left = mPadding;
        final float right = getWidth() - mPadding;
        final float top = mPadding;
        final float bottom = getHeight() - mPadding;
        final float width = right - left;
        final float height = bottom - top;
        if (width <= 0 || height <= 0) {
            return;
        }

        for (int i = 0; i < GRID_LINES; i++) {
            float y = top + height * i / (GRID_LINES - 1);
            canvas.drawLine(left, y, right, y, mGridPaint);
        }

        if (mCount < 2) {
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(mEmptyText, (left + right) / 2,
                    (top + bottom) / 2 + mTextPaint.getTextSize() / 3, mTextPaint);
            return;
        }

        int min = mSamples[0];
        int max = mSamples[0];
        for (int i = 1; i < mCount; i++) {
            min = Math.min(min, mSamples[i]);
            max = Math.max(max, mSamples[i]);
        }
        if (max - min < MIN_SPAN) {
            int extra = (MIN_SPAN - (max - min) + 1) / 2;
            min -= extra;
            max += extra;
        }
        final float span = max - min;

        mPath.reset();
        float lastX = left;
        float lastY = bottom;
        for (int i = 0; i < mCount; i++) {
            // The newest sample is always at the right edge; the line grows
            // from the right as samples accumulate.
            float x = right - width * (mCount - 1 - i) / (mCapacity - 1);
            float y = bottom - height * (mSamples[i] - min) / span;
            if (i == 0) {
                mPath.moveTo(x, y);
            } else {
                mPath.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        canvas.drawPath(mPath, mLinePaint);
        canvas.drawCircle(lastX, lastY, 3 * mDensity, mDotPaint);

        mTextPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(String.valueOf(max), left + 2 * mDensity,
                top + mTextPaint.getTextSize(), mTextPaint);
        canvas.drawText(String.valueOf(min), left + 2 * mDensity,
                bottom - 2 * mDensity, mTextPaint);
    }
}
