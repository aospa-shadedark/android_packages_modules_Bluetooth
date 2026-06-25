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

/*
 * Changes from Qualcomm Technologies, Inc. are provided under the following license:
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.bluetooth.a2dpsink;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAudioConfig;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.os.Looper;
import android.os.SystemProperties;
import android.sysprop.BluetoothProperties;
import android.util.Log;
import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Message;

import com.android.bluetooth.avrcpcontroller.AvrcpControllerService;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Provides Bluetooth A2DP Sink profile, as a service in the Bluetooth application. */
public class A2dpSinkService extends ProfileService {
    private static final String TAG = A2dpSinkService.class.getSimpleName();

    private static A2dpSinkService sService;

    // This is also used as a lock for shared data in {@link A2dpSinkService}
    @GuardedBy("mDeviceStateMap")
    private final Map<BluetoothDevice, A2dpSinkStateMachine> mDeviceStateMap =
            new ConcurrentHashMap<>(1);

    private final Object mActiveDeviceLock = new Object();
    private final Object mStreamHandlerLock = new Object();
    private static final Object sStateLock = new Object();

    @GuardedBy("sStateLock")
    protected static BluetoothDevice mStreamingDevice;
    private final AdapterService mAdapterService;
    private final DatabaseManager mDatabaseManager;
    private final A2dpSinkNativeInterface mNativeInterface;
    private final Looper mLooper;
    private final int mMaxConnectedAudioDevices;
    private final A2dpSinkVendorService mA2dpSinkVendor;
    private final AudioManager mAudioManager;
    private boolean sAudioIsEnabled = false;
    private boolean isPendingStart = false;

    @GuardedBy("sStateLock")
    protected static BluetoothDevice mHandOffPendingDevice = null;
    @GuardedBy("sStateLock")
    private static boolean sIsHandOffPending = false;
    private static final boolean mIsSplitSink;

    static {
        mIsSplitSink = SystemProperties.getBoolean(
                           "persist.vendor.qcom.bluetooth.a2dp_sink_offload.enabled", false);
    }

    @GuardedBy("mStreamHandlerLock")
    private final A2dpSinkStreamHandler mA2dpSinkStreamHandler;

    @GuardedBy("mActiveDeviceLock")
    private BluetoothDevice mActiveDevice = null;

    private BluetoothDevice mExposedActiveDevice;

    private final AudioManagerAudioDeviceCallback mAudioManagerAudioDeviceCallback =
    new AudioManagerAudioDeviceCallback();

    public A2dpSinkService(AdapterService adapterService) {
        this(adapterService, A2dpSinkNativeInterface.getInstance(), Looper.getMainLooper());
    }

    @VisibleForTesting
    A2dpSinkService(
            AdapterService adapterService, A2dpSinkNativeInterface nativeInterface, Looper looper) {
        super(requireNonNull(adapterService));
        mAdapterService = adapterService;
        mDatabaseManager = requireNonNull(mAdapterService.getDatabase());
        mNativeInterface = requireNonNull(nativeInterface);
        mLooper = looper;

        mMaxConnectedAudioDevices = mAdapterService.getMaxConnectedAudioDevices();
        mNativeInterface.init(mMaxConnectedAudioDevices);
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mA2dpSinkVendor = new A2dpSinkVendorService(this);
        if (mA2dpSinkVendor != null) {
            mA2dpSinkVendor.init();
        }
        synchronized (mStreamHandlerLock) {
            mA2dpSinkStreamHandler = new A2dpSinkStreamHandler(mAdapterService, mNativeInterface);
        }
        if (mAudioManager != null) {
          //For cleanup
          mAudioManager.setParameters("btsink_enable=false");
          mAudioManager.registerAudioDeviceCallback(mAudioManagerAudioDeviceCallback
                                                                     , mA2dpSinkStreamHandler);
        }

        setA2dpSinkService(this);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileA2dpSinkEnabled().orElse(false);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "Cleanup A2DP Sink Service");
        if(sAudioIsEnabled == true) {
            if (mAudioManager != null) {
              mAudioManager.setParameters("btsink_enable=false");
            }
            sAudioIsEnabled = false;
        }

        if (isPendingStart == true) {
          isPendingStart = false;
        }

        if (mA2dpSinkVendor != null) {
            mA2dpSinkVendor.cleanup();
        }
        setA2dpSinkService(null);

        //Unregister Audio Device Callback
        mAudioManager.unregisterAudioDeviceCallback(mAudioManagerAudioDeviceCallback);
        mNativeInterface.cleanup();
        synchronized (mDeviceStateMap) {
            for (A2dpSinkStateMachine stateMachine : mDeviceStateMap.values()) {
                stateMachine.quitNow();
            }
            mDeviceStateMap.clear();
        }
        synchronized (mStreamHandlerLock) {
            mA2dpSinkStreamHandler.cleanup();
        }
    }

    public static synchronized A2dpSinkService getA2dpSinkService() {
        return sService;
    }

    /** Testing API to inject a mockA2dpSinkService. */
    @VisibleForTesting
    public static synchronized void setA2dpSinkService(A2dpSinkService service) {
        sService = service;
    }

    /** Set the device that should be allowed to actively stream */
    public boolean setActiveDevice(BluetoothDevice device) {
        Log.i(TAG, "setActiveDevice(device=" + device + ")");
        synchronized (mActiveDeviceLock) {
            if (mNativeInterface.setActiveDevice(device)) {
                mActiveDevice = device;
                return true;
            }
            return false;
        }
    }

    /** Get the device that is allowed to be actively streaming */
    public BluetoothDevice getActiveDevice() {
        synchronized (mActiveDeviceLock) {
            return mActiveDevice;
        }
    }

    /** Request audio focus such that the designated device can stream audio */
    public void requestAudioFocus(BluetoothDevice device, boolean request) {
        synchronized (mStreamHandlerLock) {
            mA2dpSinkStreamHandler.requestAudioFocus(request);
        }
    }

    /**
     * Get the current Bluetooth Audio focus state
     *
     * @return AudioManger.AUDIOFOCUS_* states on success, or AudioManager.ERROR on error
     */
    public int getFocusState() {
        synchronized (mStreamHandlerLock) {
            return mA2dpSinkStreamHandler.getFocusState();
        }
    }

    boolean isA2dpPlaying(BluetoothDevice device) {
        A2dpSinkStateMachine mStateMachine;
        synchronized (mDeviceStateMap) {
            mStateMachine = mDeviceStateMap.get(device);
        }
        if (mStateMachine == null) {
            return false;
        }
        return mStateMachine.isPlaying(device);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new A2dpSinkServiceBinder(this);
    }

    public void initiateHandoffOperations(BluetoothDevice device) {
        synchronized (sStateLock) {
            if (mStreamingDevice != null && !mStreamingDevice.equals(device)) {
                Log.d(TAG, "Soft-Handoff. Prev Device:" + mStreamingDevice + ", New: " + device);
                A2dpSinkStateMachine otherSm = getStateMachineForDevice(mStreamingDevice);
                if (otherSm != null) {
                    Log.d(TAG, "Release Audio Focus for " + mStreamingDevice);
                    otherSm.sendMessage(A2dpSinkStateMachine.EVENT_RELEASE_FOCUS);
                    // Send Passthrough Command for PAUSE
                    AvrcpControllerService avrcpService =
                            AvrcpControllerService.getAvrcpControllerService();
                    Log.d(TAG, "Sending pause to device : " + mStreamingDevice);
                    if (avrcpService != null) {
                        avrcpService.sendPassThroughCmd(mStreamingDevice,
                                AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE,
                                AvrcpControllerService.KEY_STATE_PRESSED);

                        avrcpService.sendPassThroughCmd(mStreamingDevice,
                                AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE,
                                AvrcpControllerService.KEY_STATE_RELEASED);
                        Log.d(TAG, "Set Active Avrcp device : "+device);
                        avrcpService.setActiveDevice(device);
                    }
                    setConnectionPolicy(mStreamingDevice,
                            BluetoothProfile.CONNECTION_POLICY_ALLOWED);
                    setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
                }
            } else if (mStreamingDevice == null && device != null) {
                Log.d(TAG, "Prev Device: Null. New Streaming Device: " + device);
                // No Action Required
            }
        }
    }

    public void informTGStatePlaying(BluetoothDevice device, boolean isPlaying) {
        synchronized (sStateLock) {
            Log.d(TAG, "informTGStatePlaying: device: " + device
                    + ", mStreamingDevice:" + mStreamingDevice);
            A2dpSinkStateMachine mStateMachine = getStateMachineForDevice(device);
            if (mStateMachine == null) {
                return;
            }
            if (!isPlaying) {
                mStateMachine.sendMessage(A2dpSinkStateMachine.EVENT_AVRCP_TG_PAUSE);
            } else {
                initiateHandoffOperations(device);
                if (mStreamingDevice != null && !mStreamingDevice.equals(device)) {
                    Log.d(TAG, "updating streaming device after avrcp status command");
                    if (!mIsSplitSink) {
                        mStreamingDevice = device;
                    }
                }
                mStateMachine.sendMessage(A2dpSinkStateMachine.EVENT_AVRCP_TG_PLAY);
            }
        }
    }

    /* Generic Profile Code */

    /**
     * Connect the given Bluetooth device.
     *
     * @return true if connection is successful, false otherwise.
     */
    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "connect device=" + device);
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        if (getConnectionPolicy(device) == CONNECTION_POLICY_FORBIDDEN) {
            Log.w(TAG, "Connection not allowed: <" + device + "> is CONNECTION_POLICY_FORBIDDEN");
            return false;
        }

        A2dpSinkStateMachine stateMachine = getOrCreateStateMachine(device);
        if (stateMachine != null) {
            stateMachine.connect();
            return true;
        } else {
            // a state machine instance doesn't exist yet, and the max has been reached.
            Log.e(
                    TAG,
                    "Maxed out on the number of allowed A2DP Sink connections. "
                            + "Connect request rejected on "
                            + device);
            return false;
        }
    }

    /**
     * Disconnect the given Bluetooth device.
     *
     * @return true if disconnect is successful, false otherwise.
     */
    public boolean disconnect(BluetoothDevice device) {
        Log.d(TAG, "disconnect device=" + device);
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }

        A2dpSinkStateMachine stateMachine;
        synchronized (mDeviceStateMap) {
            stateMachine = mDeviceStateMap.get(device);
        }
        // a state machine instance doesn't exist. maybe it is already gone?
        if (stateMachine == null) {
            return false;
        }
        int connectionState = stateMachine.getState();
        if (connectionState == STATE_DISCONNECTED || connectionState == STATE_DISCONNECTING) {
            return false;
        }
        // upon completion of disconnect, the state machine will remove itself from the available
        // devices map
        stateMachine.disconnect();
        return true;
    }

    /**
     * Remove a device's state machine.
     *
     * <p>Called by the state machines when they disconnect.
     *
     * <p>Visible for testing so it can be mocked and verified on.
     */
    void removeStateMachine(A2dpSinkStateMachine stateMachine) {
        if (stateMachine == null) {
            return;
        }
        synchronized (mDeviceStateMap) {
            mDeviceStateMap.remove(stateMachine.getDevice());
        }
        stateMachine.quitNow();
    }

    public List<BluetoothDevice> getConnectedDevices() {
        return getDevicesMatchingConnectionStates(new int[] {BluetoothAdapter.STATE_CONNECTED});
    }

    protected A2dpSinkStateMachine getOrCreateStateMachine(BluetoothDevice device) {
        synchronized (mDeviceStateMap) {
            A2dpSinkStateMachine sm = mDeviceStateMap.get(device);
            if (sm != null) {
                return sm;
            }
            sm = new A2dpSinkStateMachine(this, device, mLooper, mNativeInterface);
            mDeviceStateMap.put(device, sm);
            return sm;
        }
    }

    @VisibleForTesting
    protected A2dpSinkStateMachine getStateMachineForDevice(BluetoothDevice device) {
        synchronized (mDeviceStateMap) {
            return mDeviceStateMap.get(device);
        }
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        Log.d(TAG, "getDevicesMatchingConnectionStates(states=" + Arrays.toString(states) + ")");
        List<BluetoothDevice> deviceList = new ArrayList<>();
        BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        int connectionState;
        for (BluetoothDevice device : bondedDevices) {
            connectionState = getConnectionState(device);
            Log.d(TAG, "Device: " + device + "State: " + connectionState);
            for (int i = 0; i < states.length; i++) {
                if (connectionState == states[i]) {
                    deviceList.add(device);
                }
            }
        }
        Log.d(
                TAG,
                "getDevicesMatchingConnectionStates("
                        + Arrays.toString(states)
                        + "): Found "
                        + deviceList.toString());
        return deviceList;
    }

    /**
     * Get the current connection state of the profile
     *
     * @param device is the remote bluetooth device
     * @return {@link BluetoothProfile#STATE_DISCONNECTED} if this profile is disconnected, {@link
     *     BluetoothProfile#STATE_CONNECTING} if this profile is being connected, {@link
     *     BluetoothProfile#STATE_CONNECTED} if this profile is connected, or {@link
     *     BluetoothProfile#STATE_DISCONNECTING} if this profile is being disconnected
     */
    public int getConnectionState(BluetoothDevice device) {
        if (device == null) return STATE_DISCONNECTED;
        A2dpSinkStateMachine stateMachine;
        synchronized (mDeviceStateMap) {
            stateMachine = mDeviceStateMap.get(device);
        }
        return (stateMachine == null) ? STATE_DISCONNECTED : stateMachine.getState();
    }

    /**
     * Set connection policy of the profile and connects it if connectionPolicy is {@link
     * BluetoothProfile#CONNECTION_POLICY_ALLOWED} or disconnects if connectionPolicy is {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}
     *
     * <p>The device should already be paired. Connection policy can be one of: {@link
     * BluetoothProfile#CONNECTION_POLICY_ALLOWED}, {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     */
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);

        if (!mDatabaseManager.setProfileConnectionPolicy(
                device, BluetoothProfile.A2DP_SINK, connectionPolicy)) {
            return false;
        }
        if (connectionPolicy == CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
    }

    /**
     * Get the connection policy of the profile.
     *
     * @param device the remote device
     * @return connection policy of the specified device
     */
    public int getConnectionPolicy(BluetoothDevice device) {
        return mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.A2DP_SINK);
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        ProfileService.println(sb, "Active Device = " + getActiveDevice());
        ProfileService.println(sb, "Max Connected Devices = " + mMaxConnectedAudioDevices);
        synchronized (mDeviceStateMap) {
            ProfileService.println(sb, "Devices Tracked = " + mDeviceStateMap.size());
            for (A2dpSinkStateMachine stateMachine : mDeviceStateMap.values()) {
                ProfileService.println(
                        sb, "==== StateMachine for " + stateMachine.getDevice() + " ====");
                stateMachine.dump(sb);
            }
        }
    }

    BluetoothAudioConfig getAudioConfig(BluetoothDevice device) {
        if (device == null) return null;
        A2dpSinkStateMachine stateMachine;
        synchronized (mDeviceStateMap) {
            stateMachine = mDeviceStateMap.get(device);
        }
        // a state machine instance doesn't exist. maybe it is already gone?
        if (stateMachine == null) {
            return null;
        }
        return stateMachine.getAudioConfig();
    }

    /** Receive and route a stack event from the JNI */
    protected void messageFromNative(StackEvent event) {
        switch (event.mType) {
            case StackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED -> onConnectionStateChanged(event);
            case StackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED -> onAudioStateChanged(event);
            case StackEvent.EVENT_TYPE_AUDIO_CONFIG_CHANGED -> onAudioConfigChanged(event);
            default -> Log.e(TAG, "Received unknown stack event of type " + event.mType);
        }
    }

    private void onConnectionStateChanged(StackEvent event) {
        BluetoothDevice device = event.mDevice;
        if (device == null) {
            return;
        }
        A2dpSinkStateMachine stateMachine = getOrCreateStateMachine(device);
        synchronized (sStateLock) {
            Log.d(TAG, "Device : " + device + "mStreamingDevice : "+mStreamingDevice);
            if (event.mState == BluetoothProfile.STATE_DISCONNECTED
                    && device.equals(mStreamingDevice)) {
                synchronized (mStreamHandlerLock) {
                    if (sAudioIsEnabled == true) {
                        mA2dpSinkStreamHandler
                                .obtainMessage(A2dpSinkStreamHandler.STOP_SINK)
                                .sendToTarget();
                        sAudioIsEnabled = false;
                    }
                    if (mAudioManager != null) {
                        Message msg =
                                mA2dpSinkStreamHandler.obtainMessage(
                                        A2dpSinkStreamHandler.REMOVE_ACTIVE);
                        msg.obj = device;
                        mA2dpSinkStreamHandler.sendMessage(msg);
                    }
                }
            }
        }
        if (event.mState == BluetoothProfile.STATE_CONNECTED) {
            if (mAudioManager != null) {
                synchronized (mStreamHandlerLock) {
                    Message msg =
                            mA2dpSinkStreamHandler.obtainMessage(A2dpSinkStreamHandler.SET_ACTIVE);
                    msg.obj = device;
                    /* If mExposedActiveDevice is not null,
                       wait for the previous disconnect event to finish,
                       then inform connection to Audio Manager. */
                    if (mExposedActiveDevice != null) {
                        Log.d(TAG, "A2DP device still in AudioManager list"
                                                      + ", waiting for disconnect to process");
                        mA2dpSinkStreamHandler.sendMessageDelayed(msg, 1000);
                    } else {
                        mA2dpSinkStreamHandler.sendMessage(msg);
                    }

                    AvrcpControllerService avrcpService =
                            AvrcpControllerService.getAvrcpControllerService();
                    if(getConnectionState(device) != BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "Device was not connected previously so do set active");
                        avrcpService.setActiveDevice(device);
                    }
                }
            }
        }
        stateMachine.onStackEvent(event);
    }

    private void onAudioStateChanged(StackEvent event) {
        int state = event.mState;
        BluetoothDevice device = event.mDevice;
        synchronized (sStateLock) {
            Log.d(TAG, "onAudioStateChanged. Audio State = " + state + ", device:" + device +
                        "mStreamingDevice ="+ mStreamingDevice);
        }
        synchronized (mStreamHandlerLock) {
            if (state == StackEvent.AUDIO_STATE_STARTED) {
                synchronized (sStateLock) {
                    if (sIsHandOffPending == true) {
                        mStreamingDevice = mHandOffPendingDevice;
                        sIsHandOffPending = false;
                        mHandOffPendingDevice = null;
                    }
                    initiateHandoffOperations(device);
                    mStreamingDevice = device;
                }
                mA2dpSinkStreamHandler.sendEmptyMessage(A2dpSinkStreamHandler.SRC_STR_START);
            } else if (state == StackEvent.AUDIO_STATE_STOPPED
                    || state == StackEvent.AUDIO_STATE_REMOTE_SUSPEND) {
                mA2dpSinkStreamHandler.sendEmptyMessage(A2dpSinkStreamHandler.SRC_STR_STOP);
                synchronized (sStateLock) {
                    if (sIsHandOffPending == true && mStreamingDevice != null
                            && mHandOffPendingDevice != null) {
                        Log.d(TAG, "current stopped device: " + device);
                        mA2dpSinkStreamHandler.obtainMessage(
                                A2dpSinkStreamHandler.START_SINK).sendToTarget();
                        sAudioIsEnabled = true;
                        return;
                    }

                    if (mIsSplitSink) {
                        sAudioIsEnabled = false;
                        mStreamingDevice = null;
                    }
                }
            } else {
                Log.w(TAG, "Unhandled audio state change, state=" + state);
            }
        }
    }

    private void onAudioConfigChanged(StackEvent event) {
        BluetoothDevice device = event.mDevice;
        if (device == null) {
            return;
        }
        A2dpSinkStateMachine stateMachine = getStateMachineForDevice(device);
        if (stateMachine == null) {
            Log.w(
                    TAG,
                    "Received audio config changed event for an unconnected device, device="
                            + device);
            return;
        }
        stateMachine.onStackEvent(event);
    }

    void connectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        mAdapterService.notifyProfileConnectionStateChangeToGatt(
                BluetoothProfile.A2DP_SINK, fromState, toState);
        mAdapterService.updateProfileConnectionAdapterProperties(
                device, BluetoothProfile.A2DP_SINK, toState, fromState);
    }

    public void onStartIndCallback(byte[] address) {
        BluetoothDevice device = mAdapterService.getDeviceFromByte(address);
        Log.d(TAG, "onStartIndCallback dev " + device);
        synchronized (mStreamHandlerLock) {
            synchronized (sStateLock) {
                if (mStreamingDevice != null && !mStreamingDevice.equals(device)) {
                    Log.d(TAG, "current streaming device: " + mStreamingDevice);
                    if (sIsHandOffPending == true && mHandOffPendingDevice.equals(device)) {
                        Log.d(TAG, "handoff already triggered: " + mStreamingDevice);
                        return;
                    }
                    sIsHandOffPending = true;
                    mHandOffPendingDevice = device;
                    initiateHandoffOperations(device);
                    mA2dpSinkStreamHandler.obtainMessage(
                            A2dpSinkStreamHandler.STOP_SINK).sendToTarget();
                    sAudioIsEnabled = false;
                    return;
                }
            }

            if (sAudioIsEnabled == false) {
                if(mExposedActiveDevice == null ||
                                     mA2dpSinkStreamHandler.hasMessages(
                                                      A2dpSinkStreamHandler.SET_ACTIVE)) {
                    Log.d(TAG, " onStartIndCallback: queing start request"
                                         + " as connected device not informed to MM yet");
                    isPendingStart = true;
                    return;
                }

                mA2dpSinkStreamHandler.sendEmptyMessage(A2dpSinkStreamHandler.START_SINK);
                sAudioIsEnabled = true;
            }
        }
    }

    public void onSuspendIndCallback(byte[] address) {
        BluetoothDevice device = mAdapterService.getDeviceFromByte(address);
        Log.d(TAG, "onSuspendIndCallback" + device);
        synchronized (mStreamHandlerLock) {
            if(sAudioIsEnabled == true) {
              mA2dpSinkStreamHandler.sendEmptyMessage(A2dpSinkStreamHandler.STOP_SINK);
              sAudioIsEnabled = false;
            }
        }
    }

    /* Notifications of audio device connection/disconnection events. */
    private class AudioManagerAudioDeviceCallback extends AudioDeviceCallback {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            if (mAudioManager == null || mAdapterService == null) {
                Log.e(TAG, "Callback called when A2dpSinkService is stopped");
                return;
            }

            synchronized (mActiveDeviceLock) {
                for (AudioDeviceInfo deviceInfo : addedDevices) {
                    if (deviceInfo.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                                                   !deviceInfo.isSource()) {
                        Log.d(TAG, " onAudioDevicesAdded: skipped device="
                                           + deviceInfo.getAddress()
                                           + " as not relavent");
                        continue;
                    }

                    String address = deviceInfo.getAddress();
                    if (address.equals("00:00:00:00:00:00")) {
                        continue;
                    }

                    byte[] addressBytes = Utils.getBytesFromAddress(address);
                    BluetoothDevice device = mAdapterService.getDeviceFromByte(addressBytes);

                    Log.d(
                            TAG,
                            " onAudioDevicesAdded: "
                                    + device
                                    + ", device type: "
                                    + deviceInfo.getType());

                    /* Don't expose already exposed active device */
                    if (device.equals(mExposedActiveDevice)) {
                        Log.d(TAG, " onAudioDevicesAdded: " + device + " is already exposed");
                        return;
                    }

                    mExposedActiveDevice = device;
                    break;
                }
            }

            if (mExposedActiveDevice != null) {
                synchronized (mStreamHandlerLock) {
                    if (isPendingStart) {
                        Log.d(TAG, " onAudioDevicesAdded: sending setParameters for pending start");
                        mA2dpSinkStreamHandler.sendEmptyMessage(A2dpSinkStreamHandler.START_SINK);
                        sAudioIsEnabled = true;
                        isPendingStart = false;
                    }
                }
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            if (mAudioManager == null || mAdapterService == null) {
                Log.e(TAG, "Callback called when A2dpSinkService is stopped");
                return;
            }
            synchronized (mActiveDeviceLock) {
                for (AudioDeviceInfo deviceInfo : removedDevices) {
                    if (deviceInfo.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                        continue;
                    }

                    String address = deviceInfo.getAddress();
                    if (address.equals("00:00:00:00:00:00")) {
                        continue;
                    }

                    mExposedActiveDevice = null;
                    Log.d(
                            TAG,
                            " onAudioDevicesRemoved: "
                                    + address
                                    + ", device type: "
                                    + deviceInfo.getType()
                                    + ", mActiveDevice: "
                                    + mActiveDevice);
                }
            }
        }
    }
}
