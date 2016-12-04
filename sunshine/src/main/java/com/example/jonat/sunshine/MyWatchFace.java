/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.jonat.sunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.v7.graphics.Palette;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't
 * shown. On devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient
 * mode. The watch face is drawn with less contrast in mute mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> weakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            weakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = weakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        private static final String WEATHER_PATH = "/weather";
        private static final String HIGH_TEMPERATURE = "high_temperature";
        private static final String LOW_TEMPERATURE = "low_temperature";
        private static final String WEATHER_CONDITION = "weather_condition";

        private static final int TEMP_DIVIDER = 10;

        final Handler updateTimeHandler = new EngineHandler(this);
        boolean registeredTimeZoneReceiver = false;
        Paint backgroundPaint;
        Paint timeTextPaint;
        Paint linePaint;
        Paint dateTextPaint;
        Paint highTempTextPaint;
        Paint lowTempTextPaint;
        boolean isAmbientMode;
        Calendar calendar;

        float timeYOffset;
        float dateYOffset;
        float weatherYOffset;

        Bitmap WeatherIcon;
        String highTemperature;
        String lowTemperature;

        final BroadcastReceiver timeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                calendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean lowBitAmbient;

        int digitalTextColor = -1;
        int digitalTextLightColor = -1;

        private GoogleApiClient mGoogleApiClient;
        private Resources resources;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            resources = MyWatchFace.this.getResources();
            timeYOffset = resources.getDimension(R.dimen.digital_time_y_offset);
            dateYOffset = resources.getDimension(R.dimen.digital_date_y_offset);
            weatherYOffset = resources.getDimension(R.dimen.weather_y_offset);

            backgroundPaint = new Paint();
            backgroundPaint.setColor(ContextCompat.getColor(MyWatchFace.this, R.color.background));

            digitalTextColor = ContextCompat.getColor(MyWatchFace.this, R.color.digital_text);
            digitalTextLightColor = ContextCompat.getColor(MyWatchFace.this, R.color.white);

            linePaint = new Paint();
            linePaint.setColor(digitalTextLightColor);

            timeTextPaint = createTextPaint(digitalTextColor);
            dateTextPaint = createTextPaint(digitalTextLightColor);
            highTempTextPaint = createTextPaint(digitalTextColor);
            lowTempTextPaint = createTextPaint(digitalTextLightColor);

            // allocate a Calendar to calculate local time using the UTC time and time zone
            calendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                calendar.setTimeZone(TimeZone.getDefault());
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }

                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (registeredTimeZoneReceiver) {
                return;
            }
            registeredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(timeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!registeredTimeZoneReceiver) {
                return;
            }
            registeredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(timeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            float timeTextSize = resources.getDimension(R.dimen.digital_time_text_size);
            timeTextPaint.setTextSize(timeTextSize);

            float dateTextSize = resources.getDimension(R.dimen.digital_date_text_size);
            dateTextPaint.setTextSize(dateTextSize);

            float temperatureTextSize = resources.getDimension(R.dimen.temperature_text_size);
            highTempTextPaint.setTextSize(temperatureTextSize);
            lowTempTextPaint.setTextSize(temperatureTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            lowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (isAmbientMode != inAmbientMode) {
                linePaint.setColor(inAmbientMode ? digitalTextColor : digitalTextLightColor);
                dateTextPaint.setColor(inAmbientMode ? digitalTextColor : digitalTextLightColor);
                lowTempTextPaint.setColor(inAmbientMode ? digitalTextColor : digitalTextLightColor);
                isAmbientMode = inAmbientMode;
                if (lowBitAmbient) {
                    timeTextPaint.setAntiAlias(!inAmbientMode);
                    dateTextPaint.setAntiAlias(!inAmbientMode);
                    highTempTextPaint.setAntiAlias(!inAmbientMode);
                    lowTempTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), backgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            calendar.setTimeInMillis(System.currentTimeMillis());
            int seconds = calendar.get(Calendar.SECOND);
            int minutes = calendar.get(Calendar.MINUTE);
            int hours = calendar.get(Calendar.HOUR);
            String time = isAmbientMode
                    ? String.format("%d:%02d", hours, minutes)
                    : String.format("%d:%02d:%02d", hours, minutes, seconds);

            // Draw time text in x-center of screen
            float timeTextWidth = timeTextPaint.measureText(time);
            float halfTimeTextWidth = timeTextWidth / 2;
            float xOffsetTime = bounds.centerX() - halfTimeTextWidth;
            canvas.drawText(time, xOffsetTime, timeYOffset, timeTextPaint);


            // Draw date text in x-center of screen
            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.US);
            String date = dateFormat.format(calendar.getTime()).toUpperCase(Locale.US);

            float dateTextWidth = dateTextPaint.measureText(date);
            float halfDateTextWidth = dateTextWidth / 2;
            float xOffsetDate = bounds.centerX() - halfDateTextWidth;
            canvas.drawText(date, xOffsetDate, dateYOffset, dateTextPaint);

            // Draw high and low temperature, icon for weather condition
            if (WeatherIcon != null && highTemperature != null && lowTemperature != null) {
                float highTemperatureTextWidth = highTempTextPaint.measureText(highTemperature);
                float lowTemperatureTextWidth = lowTempTextPaint.measureText(lowTemperature);

                Rect temperatureBounds = new Rect();
                highTempTextPaint.getTextBounds(highTemperature, 0, highTemperature.length(), temperatureBounds);

                float lineYOffset = (dateYOffset + weatherYOffset) / 2 - (temperatureBounds.height() / 2);
                canvas.drawLine(bounds.centerX() - 4 * TEMP_DIVIDER, lineYOffset,
                        bounds.centerX() + 4 * TEMP_DIVIDER, lineYOffset, linePaint);

                float xOffsetHighTemperature;
                if (isAmbientMode) {
                    xOffsetHighTemperature = bounds.centerX() - ((highTemperatureTextWidth + lowTemperatureTextWidth + TEMP_DIVIDER) / 2);
                } else {
                    xOffsetHighTemperature = bounds.centerX() - (highTemperatureTextWidth / 2);

                    canvas.drawBitmap(WeatherIcon, xOffsetHighTemperature - WeatherIcon.getWidth() - 2 * TEMP_DIVIDER,
                            weatherYOffset - (temperatureBounds.height() / 2) - (WeatherIcon.getHeight() / 2), null);
                }

                float xOffsetLowTemperature = xOffsetHighTemperature + highTemperatureTextWidth + TEMP_DIVIDER;

                canvas.drawText(highTemperature, xOffsetHighTemperature, weatherYOffset, highTempTextPaint);
                canvas.drawText(lowTemperature, xOffsetLowTemperature, weatherYOffset, lowTempTextPaint);
            }
        }

        /**
         * Starts the {@link #updateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                updateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #updateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                updateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();
                    if (dataItem.getUri().getPath().compareTo(WEATHER_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                        insertWeatherInfo(dataMap.getString(HIGH_TEMPERATURE),
                                dataMap.getString(LOW_TEMPERATURE), dataMap.getInt(WEATHER_CONDITION));
                        invalidate();
                    }
                }
            }
        }

        private void insertWeatherInfo(String highTemperature, String lowTemperature, int weatherCondition) {
            this.highTemperature = highTemperature;
            this.lowTemperature = lowTemperature;
            this.WeatherIcon = BitmapFactory.decodeResource(resources, InsertWeatherConditionIcon(weatherCondition));
        }




        public int InsertWeatherConditionIcon(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.ic_rain;
            } else if (weatherId == 511) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.ic_storm;
            } else if (weatherId == 800) {
                return R.drawable.ic_clear;
            } else if (weatherId == 801) {
                return R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.ic_cloudy;
            }
            return -1;
        }
    }
}