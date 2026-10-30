/*
 * Copyright (C) 2024 The Android Open Source Project
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
 *
 * ​​​​​Changes from Qualcomm Technologies, Inc. are provided under the following license:
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries. 
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.bluetooth.channelsoundingtestapp;

import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.android.bluetooth.channelsoundingtestapp.DistanceMeasurementInitiator.BtDistanceMeasurementCallback;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import android.util.Log;

/** ViewModel for the Initiator. */
public class InitiatorViewModel extends AndroidViewModel {
    private static final String TAG = "AndroidViewModel";

    // A map to hold the state for each device session.
    private final Map<String, DeviceSession> mDeviceSessions = new ConcurrentHashMap<>();

    // Global LiveData to report all distance results for the main screen.
    private final MutableLiveData<Map<String, Double>> mAllDistances = new MutableLiveData<>();
    // Cross-activity static singleton LiveData for live data sharing, needed by SeeMoreActivity.
    private static final MutableLiveData<Double> liveDistanceSingleton = new MutableLiveData<>(-1.0);
    public static MutableLiveData<Double> getLiveDistanceSingleton() { return liveDistanceSingleton; }

    // Used to start/stop heartbeat liveness monitoring of the Reflector app. Attached once from the Fragment.
    private BleConnectionViewModel mBleConnectionViewModel;

    public InitiatorViewModel(@NonNull Application application) {
        super(application);
    }

    /** Attaches the shared BleConnectionViewModel so sessions can drive heartbeat monitoring. */
    public void attachConnectionViewModel(BleConnectionViewModel bleConnectionViewModel) {
        mBleConnectionViewModel = bleConnectionViewModel;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        // Stop all ongoing measurements when the ViewModel is cleared.
        for (DeviceSession session : mDeviceSessions.values()) {
            session.initiator.stopDistanceMeasurement();
        }
        mDeviceSessions.clear();
    }

    /**
     * Gets or creates a session for a given device.
     * This is the key to isolating device states.
     */
    private DeviceSession getOrCreateSession(BluetoothDevice device) {
        String address = device.getAddress();
        return mDeviceSessions.computeIfAbsent(address, k -> new DeviceSession(getApplication(), device));
    }

    // Public methods now operate on a specific device session.

    public LiveData<String> getLogText(BluetoothDevice device) {
        return getOrCreateSession(device).logText;
    }

    public LiveData<Boolean> getCsStarted(BluetoothDevice device) {
        return getOrCreateSession(device).csStarted;
    }

    public LiveData<Double> getDistanceResult(BluetoothDevice device) {
        return getOrCreateSession(device).distanceResult;
    }

    /**
     * Provides a LiveData stream of all device distances for the InitiatorFragment.
     */
    public LiveData<Map<String, Double>> getAllDistances() {
        return mAllDistances;
    }


    // The following methods are wrappers that delegate to a specific session's initiator.
    // Note: It's assumed these are called after a session is established.
    public List<String> getSupportedDmMethods(BluetoothDevice device) {
        return getOrCreateSession(device).initiator.getDistanceMeasurementMethods();
    }

    public List<String> getMeasurementFreqs(BluetoothDevice device) {
        return getOrCreateSession(device).initiator.getMeasurementFreqs();
    }

    public List<String> getMeasurementDurations(BluetoothDevice device) {
        return getOrCreateSession(device).initiator.getMeasureDurationsInSeconds();
    }

    public void logMarker(BluetoothDevice device) {
        getOrCreateSession(device).logMarker();
    }

    public void actualDistance(BluetoothDevice device, String distance) {
        getOrCreateSession(device).actualDistance(distance);
    }

    public void toggleCsStartStop(BluetoothDevice device, String methodName, String freq, String secMode, String freq2, int duration) {
        getOrCreateSession(device).toggleCsStartStop(methodName, freq, secMode, freq2, duration);
    }

    /**
     * Represents the state and measurement initiator for a single Bluetooth device.
     */
    private class DeviceSession {
        final BluetoothDevice device;
        final DistanceMeasurementInitiator initiator;
        final MutableLiveData<String> logText = new MutableLiveData<>();
        final MutableLiveData<Boolean> csStarted = new MutableLiveData<>(false);
        final MutableLiveData<Double> distanceResult = new MutableLiveData<>();
        int distance_count = 0;

        DeviceSession(Context context, BluetoothDevice device) {
            this.device = device;
            this.initiator = new DistanceMeasurementInitiator(context,
                    new BtDistanceMeasurementCallback() {
                        @Override
                        public void onStartSuccess() {
                            csStarted.postValue(true);
                            logText.postValue("CS started for " + device.getAddress());
                            // Start heartbeat monitoring of the Reflector so we can
                            // auto-stop ranging if its app process is killed.
                            if (mBleConnectionViewModel != null) {
                                mBleConnectionViewModel.startHeartbeatMonitoring(
                                        device.getAddress(),
                                        () -> {
                                            Log.w(TAG, "Reflector heartbeat failed for "
                                                    + device.getAddress()
                                                    + " - auto-stopping ranging");
                                            logText.postValue(
                                                    "Reflector app appears to have died - "
                                                    + "auto-stopping ranging for "
                                                    + device.getAddress());
                                            distance_count = 0;
                                            initiator.stopDistanceMeasurement();
                                        });
                            }
                        }

                        @Override
                        public void onStartFail() {
                            logText.postValue("CS start failed for " + device.getAddress());
                        }

                        @Override
                        public void onStop() {
                            csStarted.postValue(false);
                            logText.postValue("CS stopped for " + device.getAddress());
                            // Always stop heartbeat monitoring once ranging stops, whether
                            // triggered manually or via the heartbeat failure above.
                            if (mBleConnectionViewModel != null) {
                                mBleConnectionViewModel.stopHeartbeatMonitoring(
                                        device.getAddress());
                            }
                        }

                        @Override
                        public void onDistanceResult(double distanceMeters) {
                            distanceResult.postValue(distanceMeters);
                            // Also update the legacy singleton for SeeMoreActivity
                            liveDistanceSingleton.postValue(distanceMeters);

                            // Update the global map of distances
                            Map<String, Double> currentDistances = mAllDistances.getValue();
                            if (currentDistances == null) {
                                currentDistances = new ConcurrentHashMap<>();
                            }
                            currentDistances.put(device.getAddress(), distanceMeters);
                            mAllDistances.postValue(currentDistances);
                        }
                    },
                    log -> logText.postValue("BT LOG: " + log)
            );
            // Set the target device on the newly created initiator instance.
            this.initiator.setTargetDevice(device);
        }

        void logMarker() {
            Log.d(TAG, "BCS LOG MARKER for " + device.getAddress() + " Count : " + distance_count);
            distance_count++;
        }

        void actualDistance(String distance) {
            Log.d(TAG, "BCS Actual distance for " + device.getAddress() + " : " + distance);
        }

        void toggleCsStartStop(String methodName, String freq, String secMode, String freq2, int duration) {
            Boolean started = csStarted.getValue();
            if (started == null || !started) {
                initiator.startDistanceMeasurement(methodName, freq, secMode, freq2, duration);
            } else {
                distance_count = 0;
                initiator.stopDistanceMeasurement();
            }
        }
    }
    public static class FileAppender {
      public static void appendToFile(Context context, String filename, String data) {
        try (FileOutputStream fos = context.openFileOutput(filename, Context.MODE_APPEND)) {
            fos.write(data.getBytes());
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
}
