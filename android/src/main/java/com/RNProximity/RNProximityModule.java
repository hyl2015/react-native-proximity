
package com.RNProximity;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class RNProximityModule extends ReactContextBaseJavaModule implements SensorEventListener {

    private static final String TAG = "RNProximityModule";
    private static final String KEY_PROXIMITY = "proximity";
    private static final String KEY_DISTANCE = "distance";
    private static final String KEY_EVENT_ON_SENSOR_CHANGE = "EVENT_ON_SENSOR_CHANGE";
    private static final String EVENT_ON_SENSOR_CHANGE = "onSensorChanged";
    private final ReactApplicationContext reactContext;
    private PowerManager mPowerManager;
    private SensorManager mSensorManager;
    private Sensor mProximity;
    private WakeLock mProximityLock = null;
    private boolean isProximitySupported = false;
    private Method mPowerManagerRelease;
    private WindowManager.LayoutParams lastLayoutParams;

    public RNProximityModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        mSensorManager = (SensorManager) reactContext.getSystemService(Context.SENSOR_SERVICE);
        mProximity = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        mPowerManager = (PowerManager) reactContext.getSystemService(Context.POWER_SERVICE);
        checkProximitySupport();
    }

    public void sendEvent(String eventName, @Nullable WritableMap params) {
        if (this.reactContext.hasActiveCatalystInstance()) {
            this.reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit(eventName, params);
        } else {
            Log.i(TAG, "Waiting for CatalystInstance");
        }
    }

    private void checkProximitySupport() {
        if (mProximity != null) {
            isProximitySupported = true;
        }

        // --- Check if PROXIMITY_SCREEN_OFF_WAKE_LOCK is implemented.
        try {
            boolean _isProximitySupported = false;
            Field field = PowerManager.class.getDeclaredField("PROXIMITY_SCREEN_OFF_WAKE_LOCK");
            int proximityScreenOffWakeLock = (Integer) field.get(null);
            if (android.os.Build.VERSION.SDK_INT < 17) {
                Method method = mPowerManager.getClass().getDeclaredMethod("getSupportedWakeLockFlags");
                int powerManagerSupportedFlags = (Integer) method.invoke(mPowerManager);
                _isProximitySupported = ((powerManagerSupportedFlags & proximityScreenOffWakeLock) != 0x0);
            } else {
                // --- android 4.2+
                Method method = mPowerManager.getClass().getDeclaredMethod("isWakeLockLevelSupported", int.class);
                _isProximitySupported = (Boolean) method.invoke(mPowerManager, proximityScreenOffWakeLock);
            }
            if (_isProximitySupported) {
                mProximityLock = mPowerManager.newWakeLock(proximityScreenOffWakeLock, TAG);
                mProximityLock.setReferenceCounted(false);
            }
        } catch (Exception e) {
            Log.d(TAG, "Failed to get proximity screen locker.");
        }
        if (mProximityLock != null) {
            Log.d(TAG, "Using native screen locker...");
            try {
                mPowerManagerRelease = mProximityLock.getClass().getDeclaredMethod("release", int.class);
            } catch (Exception e) {
                Log.d(TAG, "Failed to get proximity screen locker release().");
            }
        } else {
            Log.d(TAG, "fallback to old school screen locker...");
        }
    }

    private boolean isProximityWakeLockSupported() {
        return mProximityLock != null;
    }

    private void releaseProximityWakeLock(final boolean waitForNoProximity) {
        if (!isProximityWakeLockSupported()) {
            return;
        }
        synchronized (mProximityLock) {
            if (mProximityLock.isHeld()) {
                try {
                    int flags = waitForNoProximity ? PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY : 0;
                    mPowerManagerRelease.invoke(mProximityLock, flags);
                    Log.d(TAG, "releaseProximityWakeLock()");
                } catch (Exception e) {
                    Log.e(TAG, "failed to release proximity lock");
                }
            }
        }
    }

    private void manualTurnScreenOn() {
        Log.d(TAG, "manualTurnScreenOn()");
        UiThreadUtil.runOnUiThread(new Runnable() {
            public void run() {
                Activity mCurrentActivity = getCurrentActivity();
                if (mCurrentActivity == null) {
                    Log.d(TAG, "ReactContext doesn't hava any Activity attached.");
                    return;
                }
                Window window = mCurrentActivity.getWindow();
                WindowManager.LayoutParams params = window.getAttributes();
                params.screenBrightness = -1; // --- Dim to preferable one
                window.setAttributes(params);
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            }
        });
    }

    private void acquireProximityWakeLock() {
        if (!isProximityWakeLockSupported()) {
            return;
        }
        synchronized (mProximityLock) {
            if (!mProximityLock.isHeld()) {
                Log.d(TAG, "acquireProximityWakeLock()");
                mProximityLock.acquire();
            }
        }
    }

    private void manualTurnScreenOff() {
        Log.d(TAG, "manualTurnScreenOff()");
        UiThreadUtil.runOnUiThread(new Runnable() {
            public void run() {
                Activity mCurrentActivity = getCurrentActivity();
                if (mCurrentActivity == null) {
                    Log.d(TAG, "ReactContext doesn't hava any Activity attached.");
                    return;
                }
                Window window = mCurrentActivity.getWindow();
                WindowManager.LayoutParams params = window.getAttributes();
                lastLayoutParams = params; // --- store last param
                params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF; // --- Dim as dark as possible. see BRIGHTNESS_OVERRIDE_OFF
                window.setAttributes(params);
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    @ReactMethod
    public void addListener() {
        mSensorManager.registerListener(this, mProximity, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @ReactMethod
    public void removeListener() {
        mSensorManager.unregisterListener(this);
    }

    @ReactMethod
    public void turnScreenOn() {
        if (isProximityWakeLockSupported()) {
            Log.d(TAG, "turnScreenOn(): use proximity lock.");
            releaseProximityWakeLock(true);
        } else {
            Log.d(TAG, "turnScreenOn(): proximity lock is not supported. try manually.");
            manualTurnScreenOn();
        }
    }

    @ReactMethod
    public void turnScreenOff() {
        if (isProximityWakeLockSupported()) {
            Log.d(TAG, "turnScreenOff(): use proximity lock.");
            acquireProximityWakeLock();
        } else {
            Log.d(TAG, "turnScreenOff(): proximity lock is not supported. try manually.");
            manualTurnScreenOff();
        }
    }

    @Override
    public String getName() {
        return "RNProximity";
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        constants.put(KEY_EVENT_ON_SENSOR_CHANGE, EVENT_ON_SENSOR_CHANGE);
        return constants;
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        WritableMap params = Arguments.createMap();

        double distance = sensorEvent.values[0];
        double maximumRange = mProximity.getMaximumRange();
        boolean isNearDevice = distance < maximumRange;

        params.putBoolean(KEY_PROXIMITY, isNearDevice);
        params.putDouble(KEY_DISTANCE, distance);

        sendEvent(EVENT_ON_SENSOR_CHANGE, params);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }
}
