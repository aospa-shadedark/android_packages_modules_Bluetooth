/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.bluetooth.btservice;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

import static java.util.Objects.requireNonNull;

import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.bluetooth.BluetoothUuid;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.bluetooth.State;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.util.ArraySet;
import android.util.Log;

import com.android.bluetooth.BluetoothEventLogger;
import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.Util;
import com.android.bluetooth.Utils;
import com.android.bluetooth.R;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_audio.CallAudio;
import com.android.bluetooth.storage.BluetoothStorageManager;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * The active device manager is responsible for keeping track of the connected
 * A2DP/HFP/AVRCP/HearingAid/LE audio devices and select which device is active (for each profile).
 * The active device manager selects a fallback device when the currently active device is
 * disconnected, and it selects BT devices that are lastly activated one.
 *
 * <p>Current policy (subject to change):
 *
 * <p>1) If the maximum number of connected devices is one, the manager doesn't do anything. Each
 * profile is responsible for automatically selecting the connected device as active. Only if the
 * maximum number of connected devices is more than one, the rules below will apply.
 *
 * <p>2) The selected A2DP active device is the one used for AVRCP as well.
 *
 * <p>3) The HFP active device might be different from the A2DP active device.
 *
 * <p>4) The Active Device Manager always listens for the change of active devices. When it changed
 * (e.g., triggered indirectly by user action on the UI), the new active device is marked as the
 * current active device for that profile.
 *
 * <p>5) If there is a HearingAid active device, then A2DP, HFP and LE audio active devices must be
 * set to null (i.e., A2DP, HFP and LE audio cannot have active devices). The reason is that A2DP,
 * HFP or LE audio cannot be used together with HearingAid.
 *
 * <p>6) If there are no connected devices (e.g., during startup, or after all devices have been
 * disconnected, the active device per profile (A2DP/HFP/HearingAid/LE audio) is selected as
 * follows:
 *
 * <p>6.1) The last connected HearingAid device is selected as active. If there is an active A2DP,
 * HFP or LE audio device, those must be set to null.
 *
 * <p>6.2) The last connected A2DP, HFP or LE audio device is selected as active. However, if there
 * is an active HearingAid device, then the A2DP, HFP, or LE audio active device is not set (must
 * remain null).
 *
 * <p>7) If the currently active device (per profile) is disconnected, the Active Device Manager
 * just marks that the profile has no active device, and the lastly activated BT device that is
 * still connected would be selected.
 *
 * <p>8) If there is already an active device, however, if active device change notified with a null
 * device, the corresponding profile is marked as having no active device.
 */
public class ActiveDeviceManager implements AdapterService.BluetoothStateCallback {
    private static final String TAG = Util.BT_PREFIX + ActiveDeviceManager.class.getSimpleName();

    @VisibleForTesting
    static final int A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS = 5_000;
    private static final String DUAL_MODE_BREDR_LE_MODES_ENABLED_PROPERTY =
        "bluetooth.dual_mode_bredr_le_modes_enabled";
    Bundle contextBundleUpdate = new Bundle();

    private final AdapterService mAdapterService;
    private final BluetoothStorageManager mStorage;
    private HandlerThread mHandlerThread = null;
    private Handler mHandler = null;
    private final AudioManager mAudioManager;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final List<BluetoothDevice> mA2dpConnectedDevices = new ArrayList<>();

    @GuardedBy("mLock")
    private final List<BluetoothDevice> mHfpConnectedDevices = new ArrayList<>();

    @GuardedBy("mLock")
    private final List<BluetoothDevice> mHearingAidConnectedDevices = new ArrayList<>();

    @GuardedBy("mLock")
    private final List<BluetoothDevice> mLeAudioConnectedDevices = new ArrayList<>();

    @GuardedBy("mLock")
    private final List<BluetoothDevice> mLeHearingAidConnectedDevices = new ArrayList<>();

    private final AudioManagerAudioDeviceCallback mAudioManagerAudioDeviceCallback =
            new AudioManagerAudioDeviceCallback();

    private final List<BluetoothDevice> mPendingLeHearingAidActiveDevice = new ArrayList<>();

    @GuardedBy("mLock")
    private BluetoothDevice mA2dpActiveDevice = null;

    @GuardedBy("mLock")
    private BluetoothDevice mHfpActiveDevice = null;

    @GuardedBy("mLock")
    private final Set<BluetoothDevice> mHearingAidActiveDevices = new ArraySet<>();

    @GuardedBy("mLock")
    private BluetoothDevice mLeAudioActiveDevice = null;

    @GuardedBy("mLock")
    private int mLeAudioActiveGroupId = BluetoothLeAudio.GROUP_ID_INVALID;

    @GuardedBy("mLock")
    private BluetoothDevice mLeHearingAidActiveDevice = null;

    @GuardedBy("mLock")
    private BluetoothDevice mPendingClassicActiveDevice = null;

    @GuardedBy("mLock")
    private BluetoothDevice mPendingActiveDevice = null;

    private BluetoothDevice mClassicDeviceToBeActivated = null;
    private BluetoothDevice mClassicDeviceNotToBeActivated = null;

    private static final int METADATA_UNSPECIFIED    = 0x0001;
    private static final int METADATA_CONVERSATIONAL = 0x0002;
    private static final int METADATA_MEDIA          = 0x0004;
    private static final int METADATA_GAME           = 0x0008;
    private static final int METADATA_LIVE           = 0x0040;
    private int mAudioMode = AudioManager.MODE_INVALID;

    // Dual mode map for context_type : <Audio_Mode_Output_Only, Audio_Mode_Duplex>
    private final static HashMap<Integer, Integer[]> contextToModeBundle =
        new HashMap<Integer, Integer[]>();

    // Timeout for state machine thread join, to prevent potential ANR.
    private static final int SM_THREAD_JOIN_TIMEOUT_MS = 1000;

    private static final int LOG_NB_EVENTS = 50;
    private final BluetoothEventLogger mEventLogger =
            new BluetoothEventLogger(LOG_NB_EVENTS, TAG + " event log");

    @Override
    public void onBluetoothStateChange(int prevState, int newState) {
        if (newState != State.ON) {
            return;
        }
        mHandler.post(() -> handleAdapterStateChanged(newState));
    }

    public void contextBundle(BluetoothDevice device, int context) {
        Log.d(TAG, "contextBundle: context:" + context + ", device: " + device);
        Integer[] profile_values = new Integer[2];
        if (contextToModeBundle.containsKey(context)) {
            profile_values = contextToModeBundle.get(context);
            contextBundleUpdate.putInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY, profile_values[0]);
            contextBundleUpdate.putInt(BluetoothAdapter.AUDIO_MODE_DUPLEX, profile_values[1]);
        } else {
            profile_values = contextToModeBundle.get(METADATA_UNSPECIFIED);
            contextBundleUpdate.putInt(BluetoothAdapter.AUDIO_MODE_OUTPUT_ONLY, profile_values[0]);
            contextBundleUpdate.putInt(BluetoothAdapter.AUDIO_MODE_DUPLEX, profile_values[1]);
        }
        Log.d(TAG, "contextBundle: Setting up values for context: " + context +
            ", OUTPUT_ONLY mode: " + profile_values[0] + ", DUPLEX mode: " + profile_values[1]);
        mAdapterService.setPreferredAudioProfiles(device, contextBundleUpdate);
    }

    private static String getProfilesString(@BluetoothAdapter.ActiveDeviceUse int profiles) {
        return switch (profiles) {
            case BluetoothAdapter.ACTIVE_DEVICE_PHONE_CALL -> "phone call";
            case BluetoothAdapter.ACTIVE_DEVICE_AUDIO -> "audio";
            case BluetoothAdapter.ACTIVE_DEVICE_ALL -> "all";
            default -> "unknownProfile [" + profiles + "]";
        };
    }

    /**
     * Set device as the active devices for the given profiles.
     *
     * @param device is the remote bluetooth device
     * @param profiles is a constant that references for which profiles we'll be setting the remote
     *     device as our active device. One of the following: {@link
     *     BluetoothAdapter#ACTIVE_DEVICE_AUDIO}, {@link BluetoothAdapter#ACTIVE_DEVICE_PHONE_CALL}
     *     {@link BluetoothAdapter#ACTIVE_DEVICE_ALL}
     */
    public boolean setActiveDevice(BluetoothDevice device, int profiles) {
        mEventLogger.logi(
                TAG,
                ("[API call] setActiveDevice: " + device + ", profiles=")
                        + getProfilesString(profiles));
        boolean setHeadset = false;
        boolean setA2dp = false;

        // Determine for which profiles we want to set device as our active device
        switch (profiles) {
            case BluetoothAdapter.ACTIVE_DEVICE_PHONE_CALL -> setHeadset = true;
            case BluetoothAdapter.ACTIVE_DEVICE_AUDIO -> setA2dp = true;
            case BluetoothAdapter.ACTIVE_DEVICE_ALL -> {
                setHeadset = true;
                setA2dp = true;
            }
            default -> {
                return false;
            }
        }

        Log.i(
                TAG,
                "setActiveDevice: "
                        + device
                        + ", setHeadset="
                        + setHeadset
                        + ", setA2dp="
                        + setA2dp);

        final var headset = mAdapterService.getHeadsetService();
        boolean hfpSupported =
                headset.isPresent()
                        && (device == null
                                || headset.get().getConnectionPolicy(device)
                                        == CONNECTION_POLICY_ALLOWED);
        final var a2dp = mAdapterService.getA2dpService();
        boolean a2dpSupported =
                a2dp.isPresent()
                        && (device == null
                                || a2dp.get().getConnectionPolicy(device)
                                        == CONNECTION_POLICY_ALLOWED);
        final var leAudio = mAdapterService.getLeAudioService();
        boolean leAudioSupported =
                leAudio.isPresent()
                        && (device == null
                                || leAudio.get().getConnectionPolicy(device)
                                        == CONNECTION_POLICY_ALLOWED);
        final var hearingAid = mAdapterService.getHearingAidService();
        synchronized (mLock) {
            mPendingActiveDevice = device;
        }

        if (leAudioSupported) {
            if (true) {
                Log.i(TAG, "setActiveDevice: Setting active Le Audio device " + device);
                if (device == null) {
                    /* If called by BluetoothAdapter it means Audio should not be stopped.
                     * For this reason let's say that fallback device exists
                     */
                    if (Flags.admUseSetActiveDeviceHelpers()) {
                        setLeAudioActiveDevice(null, /* stopAudio= */ false);
                    } else {
                        leAudio.get().removeActiveDevice(true /* hasFallbackDevice */);
                    }
                } else {
                    /* We want to deactivate other profiles, to avoid potential races in
                     * the controller. However, HFP cannot be cleared here yet, because
                     * when HFP device inactivates, and it's saved as active, we will look
                     * for fallback, potentially activating it again, if it was the newest
                     * device connected.
                     */
                    if (Flags.admUseSetActiveDeviceHelpers()) {
                        setA2dpActiveDevice(null, /* stopAudio= */ false);
                        setHearingAidActiveDevice(null, /* stopAudio= */ false);
                        setLeAudioActiveDevice(device, /* stopAudio= */ false);
                    } else {
                        /*if (a2dp.isPresent() && a2dp.get().getActiveDevice() != null) {
                            // TODO:  b/312396770
                            if(!Utils.isDualModeAudioEnabled()){
                                a2dp.get().removeActiveDevice(false);
                            }
                        }
                        if (hearingAid.isPresent()
                                && (hearingAid.get().getActiveDevices().get(0) != null
                                        || hearingAid.get().getActiveDevices().get(1) != null)) {
                            hearingAid.get().removeActiveDevice(false);
                        }*/
                        leAudio.get().setActiveDevice(device);
                    }
                }
            } else {
                Log.i(TAG, "setActiveDevice: Setting active device " + device + " for Le Audio");
                if (device == null) {
                    /* If called by BluetoothAdapter it means Audio should not be stopped.
                     * For this reason let's say that fallback device exists
                     */
                    if (Flags.admUseSetActiveDeviceHelpers()) {
                        setLeAudioActiveDevice(null, /* stopAudio= */ false);
                    } else {
                        leAudio.get().removeActiveDevice(true /* hasFallbackDevice */);
                    }
                } else {
                    if (Flags.admUseSetActiveDeviceHelpers()) {
                        setA2dpActiveDevice(null, /* stopAudio= */ false);
                        setLeAudioActiveDevice(device, /* stopAudio= */ false);
                    } else {
                        /*if (a2dp.isPresent() && a2dp.get().getActiveDevice() != null) {
                            // TODO:  b/312396770
                            if(!Utils.isDualModeAudioEnabled()){
                                a2dp.get().removeActiveDevice(false);
                            }
                        }*/
                        leAudio.get().setActiveDevice(device);
                    }
                }
            }
        }

        // Order matters, some devices do not accept A2DP connection before HFP connection
        if (setHeadset && hfpSupported) {
            Log.i(TAG, "setActiveDevice: Setting active Headset " + device);
            if (Flags.admUseSetActiveDeviceHelpers()) {
                setHfpActiveDevice(device);
            } else {
                headset.get().setActiveDevice(device);
            }
        }

        if (setA2dp && a2dpSupported) {
            Log.i(TAG, "setActiveDevice: Setting active A2dp device " + device);
            if (device == null) {
                if (Flags.admUseSetActiveDeviceHelpers()) {
                    setA2dpActiveDevice(null, /* stopAudio= */ false);
                } else {
                    a2dp.get().removeActiveDevice(false);
                }
            } else {
                /* Workaround for the controller issue which is not able to handle correctly
                 * A2DP offloader vendor specific command while ISO Data path is set.
                 * Proper solutions should be delivered in b/312396770
                 */
                /*if (leAudio.isPresent()) {
                    List<BluetoothDevice> activeLeAudioDevices = leAudio.get().getActiveDevices();
                    //In dumo mode both a2dp and leaudio devices should be active.
                    if (activeLeAudioDevices.get(0) != null
                            && !Utils.isDualModeAudioEnabled()) {
                        if (Flags.admUseSetActiveDeviceHelpers()) {
                            setLeAudioActiveDevice(null, false);
                        } else {
                            leAudio.get().removeActiveDevice(true);
                        }
                    }
                }*/
                if (Flags.admUseSetActiveDeviceHelpers()) {
                    setA2dpActiveDevice(device, /* stopAudio= */ false);
                } else {
                    a2dp.get().setActiveDevice(device);
                }
            }
        }

        if (hearingAid.isPresent()) {
            if (device == null
                    || hearingAid.get().getConnectionPolicy(device) == CONNECTION_POLICY_ALLOWED) {
                Log.i(TAG, "setActiveDevice: Setting active Hearing Aid " + device);
                if (Flags.admUseSetActiveDeviceHelpers()) {
                    setHearingAidActiveDevice(device, /* stopAudio= */ false);
                } else {
                    if (device == null) {
                        hearingAid.get().removeActiveDevice(false);
                    } else {
                        hearingAid.get().setActiveDevice(device);
                    }
                }
            }
        }

        return true;
    }

    /**
     * Called when audio profile connection state changed
     *
     * @param profile The Bluetooth profile of which connection state changed
     * @param device The device of which connection state was changed
     * @param fromState The previous connection state of the device
     * @param toState The new connection state of the device
     */
    public void profileConnectionStateChanged(
            int profile, BluetoothDevice device, int fromState, int toState) {
        mEventLogger.logi(
                TAG,
                ("[From Service] "
                                + BluetoothProfile.getProfileName(profile)
                                + " connection state changed: "
                                + device)
                        + (BluetoothProfile.getConnectionStateName(fromState) + " -> ")
                        + (BluetoothProfile.getConnectionStateName(toState)));
        if (toState == STATE_CONNECTED) {
            switch (profile) {
                case BluetoothProfile.A2DP -> mHandler.post(() -> handleA2dpConnected(device));
                case BluetoothProfile.HEADSET -> mHandler.post(() -> handleHfpConnected(device));
                case BluetoothProfile.LE_AUDIO ->
                        mHandler.post(() -> handleLeAudioConnected(device));
                case BluetoothProfile.HEARING_AID ->
                        mHandler.post(() -> handleHearingAidConnected(device));
                case BluetoothProfile.HAP_CLIENT -> mHandler.post(() -> handleHapConnected(device));
                default -> {} // Nothing to do
            }
        } else if (fromState == STATE_CONNECTED) {
            switch (profile) {
                case BluetoothProfile.A2DP -> mHandler.post(() -> handleA2dpDisconnected(device));
                case BluetoothProfile.HEADSET -> mHandler.post(() -> handleHfpDisconnected(device));
                case BluetoothProfile.LE_AUDIO ->
                        mHandler.post(() -> handleLeAudioDisconnected(device));
                case BluetoothProfile.HEARING_AID ->
                        mHandler.post(() -> handleHearingAidDisconnected(device));
                case BluetoothProfile.HAP_CLIENT ->
                        mHandler.post(() -> handleHapDisconnected(device));
                default -> {} // Nothing to do
            }
        }
    }

    /**
     * Called when active state of audio profiles changed
     *
     * @param profile The Bluetooth profile of which active state changed
     * @param device The device currently activated. {@code null} if no device is active
     */
    public void profileActiveDeviceChanged(int profile, BluetoothDevice device) {
        mEventLogger.logi(
                TAG,
                ("Active Device Changed: "
                        + device
                        + " for profile: "
                        + BluetoothProfile.getProfileName(profile)));

        switch (profile) {
            case BluetoothProfile.A2DP ->
                    mHandler.post(() -> handleA2dpActiveDeviceChanged(device));
            case BluetoothProfile.HEADSET ->
                    mHandler.post(() -> handleHfpActiveDeviceChanged(device));
            case BluetoothProfile.LE_AUDIO ->
                    mHandler.post(() -> handleLeAudioActiveDeviceChanged(device));
            case BluetoothProfile.HEARING_AID ->
                    mHandler.post(() -> handleHearingAidActiveDeviceChanged(device));
            default -> {} // Nothing to do
        }
    }

    private void handleAdapterStateChanged(int currentState) {
        Log.d(TAG, "handleAdapterStateChanged: currentState=" + currentState);
        resetState();
    }

    private boolean isLeAudioHearingAidDevice(BluetoothDevice dev) {
        if (dev != null) {
            return mAdapterService.isProfileSupported(dev, BluetoothProfile.HAP_CLIENT);
        }
        return false;
    }

    @GuardedBy("mLock")
    private boolean isAnyHearingAidDeviceActive() {
        if (Flags.admRemoveHapVariables()) {
            return !mHearingAidActiveDevices.isEmpty()
                    || isLeAudioHearingAidDevice(mLeAudioActiveDevice);
        }

        return !mHearingAidActiveDevices.isEmpty() || mLeHearingAidActiveDevice != null;
    }

    /**
     * Handles the active device logic for when A2DP is connected. Does the following: 1. Check if a
     * hearing aid device is active. We will always prefer hearing aid devices, so if one is active,
     * we will not make this A2DP device active. 2. If there is no hearing aid device active, we
     * will make this A2DP device active. 3. We will make this device active for HFP if it's already
     * connected to HFP 4. If dual mode is disabled, we clear the LE Audio active device to ensure
     * mutual exclusion between classic and LE audio.
     *
     * @param device is the device that was connected to A2DP
     */
    private void handleA2dpConnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.i(TAG, "handleA2dpConnected: " + device);
            if (mA2dpConnectedDevices.contains(device)) {
                Log.w(TAG, "This device is already connected: " + device);
                return;
            }
            mA2dpConnectedDevices.add(device);
            if (isBroadcastingAudio()) {
                Log.i(
                        TAG,
                        "LE Audio Broadcast is streaming, skip setting A2dp device as active: "
                                + device);
                if (mPendingClassicActiveDevice != null) {
                    mHandler.removeCallbacksAndMessages(mPendingClassicActiveDevice);
                }
                return;
            }

            if (!isAnyHearingAidDeviceActive()) {
                // New connected device: select it as active
                // Activate HFP and A2DP at the same time if both profile already connected.
                if (mHfpConnectedDevices.contains(device)) {
                    boolean a2dpMadeActive = setA2dpActiveDevice(device, /* stopAudio= */ true);
                    boolean hfpMadeActive = setHfpActiveDevice(device);
                    if ((a2dpMadeActive || hfpMadeActive) && !Utils.isDualModeAudioEnabled()) {
                        setLeAudioActiveDevice(null, /* stopAudio= */ false);
                    }
                    return;
                }
                // Activate A2DP if audio mode is normal or HFP is not supported or enabled.
                if (mAdapterService.getProfileConnectionPolicy(device, BluetoothProfile.HEADSET)
                                != CONNECTION_POLICY_ALLOWED
                        || mAudioMode == AudioManager.MODE_NORMAL) {
                    boolean a2dpMadeActive = setA2dpActiveDevice(device, /* stopAudio= */ true);
                    if (a2dpMadeActive && !Utils.isDualModeAudioEnabled()) {
                        setLeAudioActiveDevice(null, /* stopAudio= */ false);
                    }
                } else {
                    Log.i(TAG, "A2DP activation is suspended until HFP connected: " + device);
                    if (mPendingClassicActiveDevice != null) {
                        mHandler.removeCallbacksAndMessages(mPendingClassicActiveDevice);
                    }
                    mPendingClassicActiveDevice = device;
                    // Activate A2DP if HFP is failed to connect.
                    mHandler.postDelayed(
                            () -> {
                                Log.w(TAG, "HFP connection timeout. Activate A2DP for " + device);
                                setA2dpActiveDevice(device, /* stopAudio= */ true);
                            },
                            mPendingClassicActiveDevice,
                            A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
                }
            }
        }
    }

    /**
     * Handles the active device logic for when HFP is connected. Does the following: 1. Check if a
     * hearing aid device is active. We will always prefer hearing aid devices, so if one is active,
     * we will not make this HFP device active. 2. If there is no hearing aid device active, we will
     * make this HFP device active. 3. We will make this device active for A2DP if it's already
     * connected to A2DP 4. If dual mode is disabled, we clear the LE Audio active device to ensure
     * mutual exclusion between classic and LE audio.
     *
     * @param device is the device that was connected to A2DP
     */
    private void handleHfpConnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.i(TAG, "handleHfpConnected: " + device);
            if (mHfpConnectedDevices.contains(device)) {
                Log.w(TAG, "This device is already connected: " + device);
                return;
            }
            mHfpConnectedDevices.add(device);
            if (isBroadcastingAudio()) {
                Log.i(
                        TAG,
                        "LE Audio Broadcast is streaming, skip setting Hfp device as active: "
                                + device);
                if (mPendingClassicActiveDevice != null) {
                    mHandler.removeCallbacksAndMessages(mPendingClassicActiveDevice);
                }
                return;
            }

            if (!isAnyHearingAidDeviceActive()) {
                // New connected device: select it as active
                // Activate HFP and A2DP at the same time once both profile connected.
                if (mA2dpConnectedDevices.contains(device)) {
                    boolean a2dpMadeActive = setA2dpActiveDevice(device, /* stopAudio= */ true);
                    boolean hfpMadeActive = setHfpActiveDevice(device);

                    /* Make LEA inactive if device is made active for any classic audio profile
                    and dual mode is disabled */
                    if ((a2dpMadeActive || hfpMadeActive) && !Utils.isDualModeAudioEnabled()) {
                        setLeAudioActiveDevice(null, /* stopAudio= */ false);
                    }
                    return;
                }
                // Activate HFP if audio mode is not normal or A2DP is not supported or enabled.
                if (mAdapterService.getProfileConnectionPolicy(device, BluetoothProfile.A2DP)
                                != CONNECTION_POLICY_ALLOWED
                        || mAudioMode != AudioManager.MODE_NORMAL) {
                    // Tries to make the device active for HFP
                    boolean hfpMadeActive = setHfpActiveDevice(device);

                    // Makes LEA inactive if device is made active for HFP & dual mode is disabled
                    if (hfpMadeActive && !Utils.isDualModeAudioEnabled()) {
                        setLeAudioActiveDevice(null, /* stopAudio= */ true);
                    }
                } else {
                    Log.i(TAG, "HFP activation is suspended until A2DP connected: " + device);
                    if (mPendingClassicActiveDevice != null) {
                        mHandler.removeCallbacksAndMessages(mPendingClassicActiveDevice);
                    }
                    mPendingClassicActiveDevice = device;
                    // Activate HFP if A2DP is failed to connect.
                    mHandler.postDelayed(
                            () -> {
                                Log.w(TAG, "A2DP connection timeout. Activate HFP for " + device);
                                setHfpActiveDevice(device);
                            },
                            mPendingClassicActiveDevice,
                            A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
                }
            }
        }
    }

    private void handleHearingAidConnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.i(TAG, "handleHearingAidConnected: " + device);
            if (mHearingAidConnectedDevices.contains(device)) {
                Log.w(TAG, "This device is already connected: " + device);
                return;
            }
            mHearingAidConnectedDevices.add(device);
            if (isBroadcastingAudio()) {
                Log.i(
                        TAG,
                        "LE Audio Broadcast is streaming, skip setting HearingAid device as "
                                + "active:  "
                                + device);
                return;
            }
            // New connected device: select it as active
            if (setHearingAidActiveDevice(device, /* stopAudio= */ true)) {
                setA2dpActiveDevice(null, /* stopAudio= */ false);
                setHfpActiveDevice(null);
                mAdapterService
                        .getLeAudioService()
                        .ifPresentOrElse(
                                leAudio ->
                                        setLeAudioActiveDevice(
                                                null,
                                                /* stopAudio= */ leAudio.getActiveDevices()
                                                        .contains(device)),
                                () -> setLeAudioActiveDevice(null, /* stopAudio= */ false));
            }
        }
    }

    private void handleLeAudioConnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.i(TAG, "handleLeAudioConnected: " + device);

            final var leAudio = mAdapterService.getLeAudioService();
            if (leAudio.isEmpty() || device == null) {
                Log.e(TAG, "Le Audio available: " + !leAudio.isEmpty());
                return;
            }
            Log.i(TAG, "Lead device for group:" + leAudio.get().getLeadDevice(device));
            leAudio.get().deviceConnected(device);

            if (mLeAudioConnectedDevices.contains(device)) {
                Log.w(TAG, "This device is already connected: " + device);
                return;
            }

            mLeAudioConnectedDevices.add(device);

            if (!leAudio.get().isGroupAvailableForStream(leAudio.get().getGroupId(device))) {
                Log.i(TAG, "LE Audio device is not available for streaming now." + device);
                return;
            }
            Integer[] profile_values = contextToModeBundle.get(METADATA_UNSPECIFIED);

            Log.d(TAG, "Sending native profile Values for Default context type: OUTPUT_ONLY mode: "
                + profile_values[0] + ", DUPLEX mode: " + profile_values[1] + " for device " + device);

            int groupId = leAudio.get().getGroupId(device);
            leAudio.get().sendAudioProfilePreferencesToNative(groupId,
                    profile_values[0] == BluetoothProfile.LE_AUDIO,
                    profile_values[1] == BluetoothProfile.LE_AUDIO);

            if (Flags.admRemoveHapVariables()) {
                if (!isAnyHearingAidDeviceActive()
                        && Objects.equals(device, leAudio.get().getLeadDevice(device))) {
                    // New connected device: select it as active
                    boolean leAudioMadeActive =
                            setLeAudioActiveDevice(device, /* stopAudio= */ true);
                    if (leAudioMadeActive && !Utils.isDualModeAudioEnabled()) {
                    //  setA2dpActiveDevice(null, /* stopAudio= */ false);
                        setHfpActiveDevice(null);
                    }
                } else if (isLeAudioHearingAidDevice(device)) {
                    if (setLeAudioActiveDevice(device, /* stopAudio= */ true)) {
                        setHearingAidActiveDevice(null, /* stopAudio= */ false);
                        setA2dpActiveDevice(null, /* stopAudio= */ false);
                        setHfpActiveDevice(null);
                    }
                }
                return;
            }
            if (mHearingAidActiveDevices.isEmpty()
                    && mLeHearingAidActiveDevice == null
                    && mPendingLeHearingAidActiveDevice.isEmpty()
                    && Objects.equals(device, leAudio.get().getLeadDevice(device))) {
                // New connected device: select it as active
                boolean leAudioMadeActive = setLeAudioActiveDevice(device, /* stopAudio= */ true);
                if (leAudioMadeActive && !Utils.isDualModeAudioEnabled()) {
                    setHfpActiveDevice(null);
                }
            } else if (mPendingLeHearingAidActiveDevice.contains(device)) {
                if (setLeHearingAidActiveDevice(device)) {
                    setHearingAidActiveDevice(null, /* stopAudio= */ false);
                    setA2dpActiveDevice(null, /* stopAudio= */ false);
                    setHfpActiveDevice(null);
                }
            }
        }
    }

    private void handleHapConnected(BluetoothDevice device) {
        if (Flags.admRemoveHapVariables()) {
            return;
        }
        synchronized (mLock) {
            Log.d(TAG, "handleHapConnected: " + device);
            if (mLeHearingAidConnectedDevices.contains(device)) {
                Log.w(TAG, "This device is already connected: " + device);
                return;
            }
            mLeHearingAidConnectedDevices.add(device);
            if (isBroadcastingAudio()) {
                Log.i(
                        TAG,
                        "LE Audio Broadcast is streaming, skip setting Hap device as active: "
                                + device);
                return;
            }

            if (!mLeAudioConnectedDevices.contains(device)) {
                mPendingLeHearingAidActiveDevice.add(device);
            } else if (Objects.equals(mLeAudioActiveDevice, device)) {
                mLeHearingAidActiveDevice = device;
            } else {
                // New connected device: select it as active
                if (setLeHearingAidActiveDevice(device)) {
                    setHearingAidActiveDevice(null, /* stopAudio= */ false);
                    setA2dpActiveDevice(null, /* stopAudio= */ false);
                    setHfpActiveDevice(null);
                }
            }
        }
    }

    private boolean isA2dpProfileConnected(BluetoothDevice device) {
        var a2dpService = mAdapterService.getA2dpService();
        if (a2dpService != null && !a2dpService.isEmpty()) {
            int connectionState = a2dpService.get().getConnectionState(device);
            if (connectionState == BluetoothProfile.STATE_CONNECTED) {
                return true;
            }
        }
        return false;
    }

    private boolean isHfpProfileConnected(BluetoothDevice device) {
        final var headsetService = mAdapterService.getHeadsetService();
        if (headsetService != null && !headsetService.isEmpty()) {
            int connectionState = headsetService.get().getConnectionState(device);
            if (connectionState == BluetoothProfile.STATE_CONNECTED) {
                return true;
            }
        }
        return false;
    }

    private void handleA2dpDisconnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.i(
                    TAG,
                    "handleA2dpDisconnected: "
                            + device
                            + ", mA2dpActiveDevice="
                            + mA2dpActiveDevice);
            mA2dpConnectedDevices.remove(device);

            if (Flags.admSuspendFallbackDuringChange()) {
                if (Objects.equals(mPendingActiveDevice, device)) {
                    Log.d(TAG, "Pending active device " + device + " disconnected before active");
                    mPendingActiveDevice = null;
                }
            }
            if (Objects.equals(mA2dpActiveDevice, device)) {
                setA2dpActiveDevice(null, /* stopAudio= */ true);
                if (!isHfpProfileConnected(device)) {
                    setFallbackDeviceActiveLocked(device);
                }
            }
        }
    }

    private void handleHfpDisconnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.i(
                    TAG,
                    "handleHfpDisconnected: " + device + ", mHfpActiveDevice=" + mHfpActiveDevice);
            mHfpConnectedDevices.remove(device);

            if (Flags.admSuspendFallbackDuringChange()) {
                if (Objects.equals(mPendingActiveDevice, device)) {
                    Log.d(TAG, "Pending active device " + device + " disconnected before active");
                    mPendingActiveDevice = null;
                }
            }
            if (Objects.equals(mHfpActiveDevice, device)) {
                if (mHfpConnectedDevices.isEmpty()) {
                    setHfpActiveDevice(null);
                }
                if (!isA2dpProfileConnected(device)) {
                    setFallbackDeviceActiveLocked(device);
                }
            }
        }
    }

    private void handleHearingAidDisconnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.i(
                    TAG,
                    "handleHearingAidDisconnected: "
                            + device
                            + ", mHearingAidActiveDevices="
                            + mHearingAidActiveDevices);
            mHearingAidConnectedDevices.remove(device);
            if (Flags.admSuspendFallbackDuringChange()) {
                if (Objects.equals(mPendingActiveDevice, device)) {
                    Log.d(TAG, "Pending active device" + device + "disconnected before active");
                    mPendingActiveDevice = null;
                }
            }
            if (mHearingAidActiveDevices.remove(device) && mHearingAidActiveDevices.isEmpty()) {
                if (!setFallbackDeviceActiveLocked(device)) {
                    setHearingAidActiveDevice(null, /* stopAudio= */ true);
                }
            }
        }
    }


    private void handleLeAudioDisconnected(BluetoothDevice device) {
        synchronized (mLock) {
            Log.i(
                    TAG,
                    "handleLeAudioDisconnected: "
                            + device
                            + ", mLeAudioActiveDevice="
                            + mLeAudioActiveDevice);

            if (Flags.admSuspendFallbackDuringChange()) {
                if (Objects.equals(mPendingActiveDevice, device)) {
                    Log.d(TAG, "Pending active device" + device + "disconnected before active");
                    mPendingActiveDevice = null;
                }
            }
            final var leAudio = mAdapterService.getLeAudioService();
            if (leAudio.isEmpty() || device == null) {
                Log.e(TAG, "Le Audio available: " + !leAudio.isEmpty());
                return;
            }

            mLeAudioConnectedDevices.remove(device);
            mLeHearingAidConnectedDevices.remove(device);

            boolean hasFallbackDevice = false;
            boolean isA2dpActive = false;
            if (Utils.isDualModeAudioEnabled()) {
                Log.d(TAG, "mA2dpActiveDevice: " + mA2dpActiveDevice
                          + ", mLeAudioActiveDevice:" + mLeAudioActiveDevice);
                if (Objects.equals(mA2dpActiveDevice, mLeAudioActiveDevice)) {
                    isA2dpActive = true;
                }
            }

            if (true) {
                /* Look for fallback if all devices from the active group disconnected.*/
                int groupId = leAudio.get().getGroupId(device);
                int activeGroupId = leAudio.get().getGroupId(mLeAudioActiveDevice);

                leAudio.get().deviceDisconnected(device, false);

                List<BluetoothDevice> connectedDevices = mLeAudioConnectedDevices;
                Log.d(TAG, "handleLeAudioDisconnected: groupId=" + groupId
                        + " activeGroupId=" + activeGroupId
                        + " mLeAudioActiveGroupId=" + mLeAudioActiveGroupId
                        + " mLeAudioActiveDevice=" + mLeAudioActiveDevice);

                /* Both lookups may return -1 if the device descriptor was already removed by
                 * before this handler ran. Fall back to the
                 * group id we cached when the device became active. Also covers the case where
                 * device == mLeAudioActiveDevice and the descriptor is already gone. */
                if (groupId == BluetoothLeAudio.GROUP_ID_INVALID
                        && Objects.equals(device, mLeAudioActiveDevice)) {
                    groupId = mLeAudioActiveGroupId;
                }
                if (activeGroupId == BluetoothLeAudio.GROUP_ID_INVALID) {
                    activeGroupId = mLeAudioActiveGroupId;
                }

                if (activeGroupId != BluetoothLeAudio.GROUP_ID_INVALID
                        && activeGroupId == groupId
                        && leAudio.get().getGroupDevices(groupId).stream()
                                .noneMatch(connectedDevices::contains)) {
                        hasFallbackDevice = setFallbackDeviceActiveLocked(device);
                        /* If hasFallbackDevice is true, it means fallback was found, and active
                         * device is being changed, or there is another LE Audio device active,
                         * from the same group as the disconnected device.
                         * In case fallback was not found, we should clear LE Audio active device.
                         */
                        if (!hasFallbackDevice) {
                            mLeAudioActiveDevice = null;
                            mLeAudioActiveGroupId = BluetoothLeAudio.GROUP_ID_INVALID;
                        }
                }
            } else {
                if (Objects.equals(mLeAudioActiveDevice, device)) {
                    hasFallbackDevice = setFallbackDeviceActiveLocked(device);
                    if (!hasFallbackDevice) {
                        setLeAudioActiveDevice(null, false);
                        var a2dpService = mAdapterService.getA2dpService();
                        if (Utils.isDualModeAudioEnabled() && isA2dpActive &&
                                                                a2dpService != null &&
                                                                !a2dpService.isEmpty()) {
                            int connectionState = a2dpService.get().
                                                    getConnectionState(mA2dpActiveDevice);
                            if (connectionState == BluetoothProfile.STATE_DISCONNECTED ||
                                connectionState == BluetoothProfile.STATE_DISCONNECTING) {
                                Log.d(TAG, "Setting mA2dpActiveDevice as NULL");
                                setA2dpActiveDevice(null, false);
                            }
                        }
                    }
                }
            }

        }
    }

    private void handleHapDisconnected(BluetoothDevice device) {
        if (Flags.admRemoveHapVariables()) {
            return;
        }
        synchronized (mLock) {
            Log.d(
                    TAG,
                    "handleHapDisconnected: "
                            + device
                            + ", mLeHearingAidActiveDevice="
                            + mLeHearingAidActiveDevice);
            mLeHearingAidConnectedDevices.remove(device);
            mPendingLeHearingAidActiveDevice.remove(device);
            if (Objects.equals(mLeHearingAidActiveDevice, device)) {
                mLeHearingAidActiveDevice = null;
            }
        }
    }

    /**
     * Update the LE Audio active device following a change of (dual mode compatible) active device
     * in a classic audio profile such as A2DP or HFP.
     *
     * @param previousActiveDevice previous active device of the classic profile
     * @param nextActiveDevice current active device of the classic profile
     */
    private void updateLeAudioActiveDeviceIfDualMode(
            @Nullable BluetoothDevice previousActiveDevice,
            @Nullable BluetoothDevice nextActiveDevice) {
        if (!Utils.isDualModeAudioEnabled()) {
            if (nextActiveDevice != null) {
               Log.i(TAG, "In non dual mode case, when A2dp/HFP becomes active, LE is made null" + nextActiveDevice);
               setLeAudioActiveDevice(null, true);
            }
            return;
        }

        if (nextActiveDevice != null) {
            boolean isDualModeDevice =
                    mAdapterService.isAllSupportedClassicAudioProfilesActive(nextActiveDevice);
            if (isDualModeDevice) {
                // If the active device for a classic audio profile is changed
                // to a dual mode compatible device, then also update the
                // active device for LE Audio.
                setLeAudioActiveDevice(nextActiveDevice, /* stopAudio= */ true);
            }
        } else {
            boolean wasDualModeDevice =
                    mAdapterService.isAllSupportedClassicAudioProfilesActive(previousActiveDevice);
            if (wasDualModeDevice) {
                // If the active device for a classic audio profile was a
                // dual mode compatible device, then also update the
                // active device for LE Audio.
                setLeAudioActiveDevice(null, /* stopAudio= */ false);
            }
        }
    }

    /**
     * Handles the active device logic for when the A2DP active device changes. Does the following:
     * 1. Clear the active hearing aid. 2. If dual mode is enabled and all supported classic audio
     * profiles are enabled, makes this device active for LE Audio. If not, clear the LE Audio
     * active device. 3. Make HFP active for this device if it is already connected to HFP. 4.
     * Stores the new A2DP active device.
     *
     * @param device is the device that was connected to A2DP
     */
    private void handleA2dpActiveDeviceChanged(BluetoothDevice device) {
        synchronized (mLock) {
            Log.i(
                    TAG,
                    "handleA2dpActiveDeviceChanged: "
                            + device
                            + ", mA2dpActiveDevice="
                            + mA2dpActiveDevice);

            if (Flags.admSuspendFallbackDuringChange()) {
                if (Objects.equals(mPendingActiveDevice, device)) {
                    mPendingActiveDevice = null;
                }
            }

            if (!Objects.equals(mA2dpActiveDevice, device)) {
                if (device != null) {
                    setHearingAidActiveDevice(null, /* stopAudio= */ false);
                }
                updateLeAudioActiveDeviceIfDualMode(mA2dpActiveDevice, device);
            } else {
                if (device != null && Utils.isDualModeAudioEnabled()
                        && !mAdapterService.isProfileSupported(device, BluetoothProfile.LE_AUDIO)) {
                    Log.i(TAG, "Set LE Audio in-active as new classic device become active ");
                    setLeAudioActiveDevice(null, /* stopAudio= */ false);
                }
            }

            // Just assign locally the new value
            mA2dpActiveDevice = device;

            // Activate HFP if needed.
            if (device != null) {
                if (Objects.equals(mClassicDeviceNotToBeActivated, device)) {
                    mHandler.removeCallbacksAndMessages(mClassicDeviceNotToBeActivated);
                    mClassicDeviceNotToBeActivated = null;
                    return;
                }
                if (Objects.equals(mClassicDeviceToBeActivated, device)) {
                    mHandler.removeCallbacksAndMessages(mClassicDeviceToBeActivated);
                    mClassicDeviceToBeActivated = null;
                }

                if (mClassicDeviceToBeActivated != null) {
                    mClassicDeviceNotToBeActivated = mClassicDeviceToBeActivated;
                    mHandler.removeCallbacksAndMessages(mClassicDeviceToBeActivated);
                    mHandler.postDelayed(
                            () -> mClassicDeviceNotToBeActivated = null,
                            mClassicDeviceNotToBeActivated,
                            A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
                    mClassicDeviceToBeActivated = null;
                }
                if (!Objects.equals(mHfpActiveDevice, device)
                        && mHfpConnectedDevices.contains(device)
                        && mAdapterService.getProfileConnectionPolicy(
                                        device, BluetoothProfile.HEADSET)
                                == CONNECTION_POLICY_ALLOWED) {
                    mClassicDeviceToBeActivated = device;
                    setHfpActiveDevice(device);
                    mHandler.postDelayed(
                            () -> mClassicDeviceToBeActivated = null,
                            mClassicDeviceToBeActivated,
                            A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
                }
            }
        }
    }

    /**
     * Handles the active device logic for when the HFP active device changes. Does the following:
     * 1. Clear the active hearing aid. 2. If dual mode is enabled and all supported classic audio
     * profiles are enabled, makes this device active for LE Audio. If not, clear the LE Audio
     * active device. 3. Make A2DP active for this device if it is already connected to A2DP. 4.
     * Stores the new HFP active device.
     *
     * @param device is the device that was connected to A2DP
     */
    private void handleHfpActiveDeviceChanged(BluetoothDevice device) {
        synchronized (mLock) {
            Log.i(
                    TAG,
                    "handleHfpActiveDeviceChanged: "
                            + device
                            + ", mHfpActiveDevice="
                            + mHfpActiveDevice);

            if (Flags.admSuspendFallbackDuringChange()) {
                if (Objects.equals(mPendingActiveDevice, device)) {
                    mPendingActiveDevice = null;
                }
            }

            CallAudio mCallAudio = CallAudio.get();
            if (mCallAudio != null && isVoipLeaWarEnabled()) {
                mCallAudio.updateActiveDevice(device, mCallAudio.HFP);
            }

            if (!Objects.equals(mHfpActiveDevice, device)) {
                if (device != null) {
                    setHearingAidActiveDevice(null, /* stopAudio= */ false);
                }

                updateLeAudioActiveDeviceIfDualMode(mHfpActiveDevice, device);
            } else {
                /* mLeAudioActiveDevice may be updated due to next or previous device being
                 * a dual mode one.
                 */
                if (mLeAudioActiveDevice != null
                        && device != null
                        && !Utils.isDualModeAudioEnabled()
                        && !mLeAudioActiveDevice.equals(device)) {
                    /* HFP device becoming active is not dual mode and was not set as
                     * active LE Audio device. Inactivate LE Audio device.
                     */
                    setLeAudioActiveDevice(null, /* stopAudio= */ true);
                }
                if (device != null && Utils.isDualModeAudioEnabled()
                        && !mAdapterService.isProfileSupported(device, BluetoothProfile.LE_AUDIO)) {
                    Log.i(TAG, "Set LE Audio in-active as new classic device become active ");
                    setLeAudioActiveDevice(null, /* stopAudio= */ false);
                }
            }

            // Just assign locally the new value
            mHfpActiveDevice = device;

            // Activate A2DP if needed.
            if (device != null) {
                if (Objects.equals(mClassicDeviceNotToBeActivated, device)) {
                    mHandler.removeCallbacksAndMessages(mClassicDeviceNotToBeActivated);
                    mClassicDeviceNotToBeActivated = null;
                    return;
                }
                if (Objects.equals(mClassicDeviceToBeActivated, device)) {
                    mHandler.removeCallbacksAndMessages(mClassicDeviceToBeActivated);
                    mClassicDeviceToBeActivated = null;
                }

                if (mClassicDeviceToBeActivated != null) {
                    mClassicDeviceNotToBeActivated = mClassicDeviceToBeActivated;
                    mHandler.removeCallbacksAndMessages(mClassicDeviceToBeActivated);
                    mHandler.postDelayed(
                            () -> mClassicDeviceNotToBeActivated = null,
                            mClassicDeviceNotToBeActivated,
                            A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
                    mClassicDeviceToBeActivated = null;
                }
                if (!Objects.equals(mA2dpActiveDevice, device)
                        && mA2dpConnectedDevices.contains(device)
                        && mAdapterService.getProfileConnectionPolicy(device, BluetoothProfile.A2DP)
                                == CONNECTION_POLICY_ALLOWED) {
                    mClassicDeviceToBeActivated = device;
                    setA2dpActiveDevice(device, /* stopAudio= */ true);
                    mHandler.postDelayed(
                            () -> mClassicDeviceToBeActivated = null,
                            mClassicDeviceToBeActivated,
                            A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
                }
            }
        }
    }

    private void handleHearingAidActiveDeviceChanged(BluetoothDevice device) {
        synchronized (mLock) {
            Log.i(
                    TAG,
                    "handleHearingAidActiveDeviceChanged: "
                            + device
                            + ", mHearingAidActiveDevices="
                            + mHearingAidActiveDevices);

            if (Flags.admSuspendFallbackDuringChange()) {
                if (Objects.equals(mPendingActiveDevice, device)) {
                    mPendingActiveDevice = null;
                }
            }

            mAdapterService
                    .getHearingAidService()
                    .ifPresent(
                            hearingAid -> {
                                // Just assign locally the new value
                                final var hiSyncId = hearingAid.getHiSyncId(device);
                                if (device != null
                                        && getHearingAidActiveHiSyncIdLocked() == hiSyncId) {
                                    mHearingAidActiveDevices.add(device);
                                } else {
                                    mHearingAidActiveDevices.clear();
                                    mHearingAidActiveDevices.addAll(
                                            hearingAid.getConnectedPeerDevices(hiSyncId));
                                }
                            });
            if (device != null) {
                setA2dpActiveDevice(null, /* stopAudio= */ false);
                setHfpActiveDevice(null);
                setLeAudioActiveDevice(null, /* stopAudio= */ false);
            }
        }
    }

    private void handleLeAudioActiveDeviceChanged(BluetoothDevice device) {
        synchronized (mLock) {
            Log.i(
                    TAG,
                    "handleLeAudioActiveDeviceChanged: "
                            + device
                            + ", mLeAudioActiveDevice="
                            + mLeAudioActiveDevice);

            if (Flags.admSuspendFallbackDuringChange()) {
                if (Objects.equals(mPendingActiveDevice, device)) {
                    mPendingActiveDevice = null;
                }
            }

            if (device != null && !mLeAudioConnectedDevices.contains(device)) {
                Log.w(
                        TAG,
                        "Failed to activate device "
                                + device
                                + ". Reason: Device is not connected.");
                return;
            }

            CallAudio mCallAudio = CallAudio.get();
            if (mCallAudio != null && isVoipLeaWarEnabled()) {
                mCallAudio.updateActiveDevice(device, mCallAudio.LE_AUDIO_VOICE);
            }

            // Just assign locally the new value
            if (device != null && !Objects.equals(mLeAudioActiveDevice, device)) {
                if (!Utils.isDualModeAudioEnabled()) {
                    setA2dpActiveDevice(null, /* stopAudio= */ false);
                    if (mHfpActiveDevice != null) {
                        setHfpActiveDevice(null);
                    }
                } else {
                    boolean isCsipSupported = Util.arrayContains(mAdapterService.getRemoteUuids(device),
                                                       BluetoothUuid.COORDINATED_SET);
                    if (isCsipSupported) {
                       Log.d(TAG, "set A2dp and HFP active device as null for csip");
                       setA2dpActiveDevice(null, true);
                       setHfpActiveDevice(null);
                    }
                }
                setHearingAidActiveDevice(null, /* stopAudio= */ false);
            }

            if (!Flags.admRemoveHapVariables()) {
                if (mLeHearingAidConnectedDevices.contains(device)) {
                    mLeHearingAidActiveDevice = device;
                }
            }

            // This covers the call audio routing case across classic BT and BLE.
            // Because there's only one active device at the same time. So if a device connect with
            // HFP & LE audio and when LE audio device is disconnected, we should fallback the
            // active device to the HFP.
            // LE case has isBroadcastingAudio which would set the active device to null when
            // broadcasting the audio. So we shouldn't try to change the active device in this case.
            if (device == null && !Utils.isDualModeAudioEnabled() && !isBroadcastingAudio()) {
                Log.d(TAG, "not falling back the active device");
                /* synchronized (mLock) {
                    setFallbackDeviceActiveLocked(mLeAudioActiveDevice /*recentlyRemovedDevice* /);
                }*/
            }

            mLeAudioActiveDevice = device;
            final var leAudioForGroup = mAdapterService.getLeAudioService();
            mLeAudioActiveGroupId = (device != null && leAudioForGroup.isPresent())
                    ? leAudioForGroup.get().getGroupId(device)
                    : BluetoothLeAudio.GROUP_ID_INVALID;
            Log.d(TAG, "handleLeAudioActiveDeviceChanged: mLeAudioActiveDevice=" + device
                    + " mLeAudioActiveGroupId=" + mLeAudioActiveGroupId);
        }
    }

    class BluetoothOnModeChangedListener implements AudioManager.OnModeChangedListener {
         @Override
        public void onModeChanged(int mode) {
            mAudioMode = mode;
        }
    }

    private BluetoothOnModeChangedListener mBluetoothOnModeChangedListener;


    ActiveDeviceManager(AdapterService service, BluetoothStorageManager storage) {
        mAdapterService = service;
        mStorage = requireNonNull(storage);
        mAudioManager = service.getSystemService(AudioManager.class);
        mBluetoothOnModeChangedListener = new BluetoothOnModeChangedListener();
    }

    void start() {
        Log.i(TAG, "start()");

        mHandlerThread = new HandlerThread("BluetoothActiveDeviceManager");
        BluetoothMethodProxy mp = BluetoothMethodProxy.getInstance();
        mp.threadStart(mHandlerThread);
        mHandler = new Handler(mp.handlerThreadGetLooper(mHandlerThread));

        mAdapterService.registerBluetoothStateCallback((command) -> mHandler.post(command), this);
        if (true) {
            mAudioManager.registerAudioDeviceCallback(mAudioManagerAudioDeviceCallback, mHandler);
        }
        mAudioManager.addOnModeChangedListener(
                    Executors.newSingleThreadExecutor(), mBluetoothOnModeChangedListener);
        LoadDualModePoliciesfromLocalStorage();

        mAudioMode = mAudioManager.getMode();
    }

    void cleanup() {
        Log.i(TAG, "cleanup()");

        mAdapterService.unregisterBluetoothStateCallback(this);
        if (true) {
            mAudioManager.unregisterAudioDeviceCallback(mAudioManagerAudioDeviceCallback);
        }
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            try {
                mHandlerThread.join(SM_THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                // Do not rethrow as we are shutting down anyway
            }
            mHandlerThread = null;
        }
        if (mBluetoothOnModeChangedListener != null) {
            mAudioManager.removeOnModeChangedListener(mBluetoothOnModeChangedListener);
        }
        mBluetoothOnModeChangedListener = null;
        resetState();
    }

    private class AudioManagerAudioDeviceCallback extends AudioDeviceCallback {
        private static boolean isWiredDeviceType(AudioDeviceInfo deviceInfo) {
            return switch (deviceInfo.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADSET,
                     AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                     AudioDeviceInfo.TYPE_USB_HEADSET -> true;
                default -> false;
            };
        }

        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            if (!true) {
                throw new IllegalStateException("admCentralizeActiveDeviceHandling");
            }
            if (!mAdapterService.isAvailable()) {
                Log.e(TAG, "Callback called when AdapterService is stopped");
                return;
            }

            if (Arrays.stream(addedDevices)
                    .anyMatch(AudioManagerAudioDeviceCallback::isWiredDeviceType)) {
                wiredAudioDeviceConnected();
                return;
            }

            for (AudioDeviceInfo deviceInfo : addedDevices) {
                String address = deviceInfo.getAddress();
                if (address == null || address.equals("00:00:00:00:00:00")) {
                    continue;
                }

                if (!isDeviceTypeSupported(deviceInfo.getType())) {
                    Log.v(TAG, "Unknown device type: " + deviceInfo.getType());
                    continue;
                }

                byte[] addressBytes = Util.getBytesFromAddress(address);
                BluetoothDevice device = mAdapterService.getDeviceFromByte(addressBytes);

                Log.i(
                        TAG,
                        "onAudioDevicesAdded: "
                                + "type: "
                                + Integer.toString(deviceInfo.getType())
                                + ", device: "
                                + device);

                switch (deviceInfo.getType()) {
                    case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                        mAdapterService
                                .getA2dpService()
                                .ifPresent(
                                        s -> {
                                            if (s.handleAudioDeviceAdded(device)) {
                                                handleA2dpActiveDeviceChanged(device);
                                            }
                                        });
                    }
                    case AudioDeviceInfo.TYPE_HEARING_AID -> {
                        mAdapterService
                                .getHearingAidService()
                                .ifPresent(
                                        s -> {
                                            if (s.handleAudioDeviceAdded()) {
                                                profileActiveDeviceChanged(
                                                        BluetoothProfile.HEARING_AID, device);
                                            }
                                        });
                    }
                    case AudioDeviceInfo.TYPE_BLE_HEADSET,
                            AudioDeviceInfo.TYPE_BLE_SPEAKER,
                            AudioDeviceInfo.TYPE_BLE_HEARING_AID -> {
                        mAdapterService
                                .getLeAudioService()
                                .ifPresent(
                                        s -> {
                                            if (s.handleAudioDeviceAdded(
                                                    device,
                                                    deviceInfo.getType(),
                                                    deviceInfo.isSink(),
                                                    deviceInfo.isSource())) {
                                                handleLeAudioActiveDeviceChanged(device);
                                            }
                                        });
                    }
                    case AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                        mAdapterService
                                .getHeadsetService()
                                .ifPresent(
                                        s -> {
                                            if (s.handleAudioDeviceAdded(device)) {
                                                handleHfpActiveDeviceChanged(device);
                                            }
                                        });
                    }
                    case AudioDeviceInfo.TYPE_WIRED_HEADSET,
                         AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                         AudioDeviceInfo.TYPE_USB_HEADSET -> {
                            wiredAudioDeviceConnected();
                    }
                    default -> {
                        // Do nothing
                    }
                }
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            if (!true) {
                throw new IllegalStateException("admCentralizeActiveDeviceHandling");
            }
            if (!mAdapterService.isAvailable()) {
                Log.e(TAG, "Callback called when AdapterService is stopped");
                return;
            }

            if (Arrays.stream(removedDevices)
                    .anyMatch(AudioManagerAudioDeviceCallback::isWiredDeviceType)) {
                synchronized (mLock) {
                    setFallbackDeviceActiveLocked(null);
                }
            }

            for (AudioDeviceInfo deviceInfo : removedDevices) {
                String address = deviceInfo.getAddress();
                if (address == null || address.equals("00:00:00:00:00:00")) {
                    continue;
                }

                if (!isDeviceTypeSupported(deviceInfo.getType())) {
                    Log.v(TAG, "Unknown device type: " + deviceInfo.getType());
                    continue;
                }

                byte[] addressBytes = Util.getBytesFromAddress(address);
                BluetoothDevice device = mAdapterService.getDeviceFromByte(addressBytes);

                Log.i(
                        TAG,
                        "onAudioDevicesRemoved: "
                                + "type: "
                                + Integer.toString(deviceInfo.getType())
                                + ", device: "
                                + device);

                switch (deviceInfo.getType()) {
                    case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                        mAdapterService
                                .getA2dpService()
                                .ifPresent(s -> s.handleAudioDeviceRemoved());
                    }
                    case AudioDeviceInfo.TYPE_HEARING_AID -> {
                        mAdapterService
                                .getHearingAidService()
                                .ifPresent(
                                        s -> {
                                            if (s.handleAudioDeviceRemoved()) {
                                                handleHearingAidActiveDeviceChanged(
                                                        s.getActiveDevice());
                                            }
                                        });
                    }
                    case AudioDeviceInfo.TYPE_BLE_HEADSET,
                            AudioDeviceInfo.TYPE_BLE_SPEAKER,
                            AudioDeviceInfo.TYPE_BLE_HEARING_AID -> {
                        CallAudio mCallAudio = CallAudio.get();
                        if (mCallAudio != null && isVoipLeaWarEnabled()) {
                            mCallAudio.updateActiveDevice(null, mCallAudio.LE_AUDIO_VOICE);
                        }
                        mAdapterService
                                .getLeAudioService()
                                .ifPresent(
                                        s -> {
                                                s.handleAudioDeviceRemoved(
                                                        device,
                                                        deviceInfo.getType(),
                                                        deviceInfo.isSink(),
                                                        deviceInfo.isSource());
                                                if (s.getActiveGroupId() !=
                                                  android.bluetooth.IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID) {
                                                  handleLeAudioActiveDeviceChanged(
                                                    s.getGroupDevices(s.getActiveGroupId(
                                                    )).get(0));
                                                } else {
                                                  handleLeAudioActiveDeviceChanged(null);
                                                }
                                             });
                    }
                    case AudioDeviceInfo.TYPE_BLE_BROADCAST -> {
                        mAdapterService
                                .getLeAudioService()
                                .ifPresent(
                                        s -> {
                                                s.handleAudioBroadcastDeviceRemoved(
                                                        device,
                                                        deviceInfo.getType());
                                             });
                    }
                    case AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                        CallAudio mCallAudio = CallAudio.get();
                        // Only clear the HFP active device in the isScoManagedByAudio
                        // regime, matching HeadsetService.handleAudioDeviceRemoved() below.
                        if (mCallAudio != null && isVoipLeaWarEnabled()
                                && mAudioManager.isScoManagedByAudio()) {
                            mCallAudio.updateActiveDevice(null, mCallAudio.HFP);
                        }
                        mAdapterService
                                .getHeadsetService()
                                .ifPresent(s -> s.handleAudioDeviceRemoved(device));
                    }
                    case AudioDeviceInfo.TYPE_WIRED_HEADSET,
                         AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                         AudioDeviceInfo.TYPE_USB_HEADSET -> {
                            synchronized (mLock) {
                                setFallbackDeviceActiveLocked(null);
                            }
                    }
                    default -> {
                        // Do nothing
                    }
                }
            }
        }
    }

    private void LoadDualModePoliciesfromLocalStorage() {
        Log.d(TAG, "LoadDualModePoliciesfromLocalStorage: ");
        Resources res = mAdapterService.getResources();

        final boolean is_dual_mode_bredr_le_enabled =
            SystemProperties.getBoolean(DUAL_MODE_BREDR_LE_MODES_ENABLED_PROPERTY, false);

        if (is_dual_mode_bredr_le_enabled == false) {
          Integer[] profile_val_le_audio = new Integer[2];
          profile_val_le_audio[0] = BluetoothProfile.LE_AUDIO;
          profile_val_le_audio[1] = BluetoothProfile.LE_AUDIO;
          contextToModeBundle.put(METADATA_CONVERSATIONAL, profile_val_le_audio);
          contextToModeBundle.put(METADATA_MEDIA, profile_val_le_audio);
          contextToModeBundle.put(METADATA_GAME, profile_val_le_audio);
          contextToModeBundle.put(METADATA_LIVE, profile_val_le_audio);
          contextToModeBundle.put(METADATA_UNSPECIFIED, profile_val_le_audio);
          return;
        }

        Integer[] profile_val_conv = new Integer[2];
        int[] profile_values = res.getIntArray(R.array.conversational_use_case_policy);
        profile_val_conv[0] = profile_values[0];
        profile_val_conv[1] = profile_values[1];
        contextToModeBundle.put(METADATA_CONVERSATIONAL, profile_val_conv);
        Log.d(TAG, "Policy for context CONVERSATIONAL: Output Only Mode = " + profile_val_conv[0] +
            ", Duplex Mode = " + profile_val_conv[1]);

        Integer[] profile_val_media = new Integer[2];
        profile_values = res.getIntArray(R.array.media_use_case_policy);
        profile_val_media[0] = profile_values[0];
        profile_val_media[1] = profile_values[1];
        contextToModeBundle.put(METADATA_MEDIA, profile_val_media);
        Log.d(TAG, "Policy for context MEDIA: Output Only Mode = " + profile_val_media[0] +
            ", Duplex Mode = " + profile_val_media[1]);

        Integer[] profile_val_game = new Integer[2];
        profile_values = res.getIntArray(R.array.game_use_case_policy);
        profile_val_game[0] = profile_values[0];
        profile_val_game[1] = profile_values[1];
        contextToModeBundle.put(METADATA_GAME, profile_val_game);
        Log.d(TAG, "Policy for context GAME: Output Only Mode = " + profile_val_game[0] +
            ", Duplex Mode = " + profile_val_game[1]);

        Integer[] profile_val_live = new Integer[2];
        profile_values = res.getIntArray(R.array.live_use_case_policy);
        profile_val_live[0] = profile_values[0];
        profile_val_live[1] = profile_values[1];
        contextToModeBundle.put(METADATA_LIVE, profile_val_live);
        Log.d(TAG, "Policy for context LIVE: Output Only Mode = " + profile_val_live[0] +
            ", Duplex Mode = " + profile_val_live[1]);

        Integer[] profile_val_unspec = new Integer[2];
        profile_values = res.getIntArray(R.array.default_use_case_policy);
        profile_val_unspec[0] = profile_values[0];
        profile_val_unspec[1] = profile_values[1];
        contextToModeBundle.put(METADATA_UNSPECIFIED, profile_val_unspec);
        Log.d(TAG,"Policy for context UNSEPCIFIED(default) Output Only Mode = "
            + profile_val_unspec[0] + ", Duplex Mode = " + profile_val_unspec[1]);
    }

    private boolean setA2dpActiveDevice(@Nullable BluetoothDevice device, boolean stopAudio) {
        Log.i(
                TAG,
                "setA2dpActiveDevice("
                        + device
                        + ")"
                        + (device == null ? " stopAudio=" + stopAudio : ""));
        synchronized (mLock) {
            if (mPendingClassicActiveDevice != null) {
                mHandler.removeCallbacksAndMessages(mPendingClassicActiveDevice);
                mPendingClassicActiveDevice = null;
            }
        }

        final var a2dp = mAdapterService.getA2dpService();
        if (a2dp.isEmpty()) {
            Log.e(TAG, "setA2dpActiveDevice: A2DP service not available");
            return false;
        }

        boolean success = false;
        if (device == null) {
            success = a2dp.get().removeActiveDevice(stopAudio);
        } else {
            success = a2dp.get().setActiveDevice(device);
        }

        if (!success) {
            Log.e(TAG, "setA2dpActiveDevice: failed for device " + device);
            return false;
        }

        synchronized (mLock) {
            mA2dpActiveDevice = device;
        }
        return true;
    }

    private boolean setHfpActiveDevice(BluetoothDevice device) {
        synchronized (mLock) {
            Log.i(TAG, "setHfpActiveDevice(" + device + ")");
            if (mPendingClassicActiveDevice != null) {
                mHandler.removeCallbacksAndMessages(mPendingClassicActiveDevice);
                mPendingClassicActiveDevice = null;
            }
            final var headset = mAdapterService.getHeadsetService();
            if (headset.isEmpty()) {
                Log.e(TAG, "setHfpActiveDevice: Headset service not available");
                return false;
            }
            BluetoothSinkAudioPolicy audioPolicy = headset.get().getHfpCallAudioPolicy(device);
            if (audioPolicy != null
                    && audioPolicy.getActiveDevicePolicyAfterConnection()
                            == BluetoothSinkAudioPolicy.POLICY_NOT_ALLOWED) {
                Log.e(TAG, "setHfpActiveDevice: failed for device " + device);
                return false;
            }
            if (!headset.get().setActiveDevice(device)) {
                Log.e(
                        TAG,
                        "setHfpActiveDevice: Service call setActiveDevice failed for device "
                                + device);
                return false;
            }
            mHfpActiveDevice = device;
        }
        return true;
    }

    private boolean setHearingAidActiveDevice(@Nullable BluetoothDevice device, boolean stopAudio) {
        Log.i(
                TAG,
                "setHearingAidActiveDevice("
                        + device
                        + ")"
                        + (device == null ? " stopAudio=" + stopAudio : ""));

        final var hearingAid = mAdapterService.getHearingAidService();
        if (hearingAid.isEmpty()) {
            Log.e(TAG, "setHearingAidActiveDevice: Hearing Aid service not available");
            return false;
        }

        synchronized (mLock) {
            if (device == null) {
                if (!hearingAid.get().removeActiveDevice(stopAudio)) {
                    Log.e(
                            TAG,
                            "setHearingAidActiveDevice: service call removeActiveDevice failed.");
                    return false;
                }
                mHearingAidActiveDevices.clear();
                return true;
            }

            long hiSyncId = hearingAid.get().getHiSyncId(device);
            if (getHearingAidActiveHiSyncIdLocked() == hiSyncId) {
                mHearingAidActiveDevices.add(device);
                return true;
            }

            if (!hearingAid.get().setActiveDevice(device)) {
                Log.e(
                        TAG,
                        "setHearingAidActiveDevice: service call setActiveDevice failed for device "
                                + device);
                return false;
            }
            mHearingAidActiveDevices.clear();
            mHearingAidActiveDevices.addAll(hearingAid.get().getConnectedPeerDevices(hiSyncId));
        }
        return true;
    }

    private boolean setLeAudioActiveDevice(@Nullable BluetoothDevice device, boolean stopAudio) {
        Log.i(
                TAG,
                "setLeAudioActiveDevice("
                        + device
                        + ")"
                        + (device == null ? " stopAudio=" + stopAudio : ""));
        synchronized (mLock) {
            final var leAudio = mAdapterService.getLeAudioService();
            if (leAudio.isEmpty()) {
                Log.e(TAG, "setLeAudioActiveDevice: Le Audio service not available");
                return false;
            }
            boolean success;
            if (device == null) {
                success = leAudio.get().removeActiveDevice(!stopAudio);
            } else {
                if ((mLeAudioActiveDevice != null)
                        && (Objects.equals(
                                mLeAudioActiveDevice, leAudio.get().getLeadDevice(device)))) {
                    /* If Lead device disconnects make member device as active device */
                    int connectionState =
                            leAudio.get().getConnectionState(mLeAudioActiveDevice);
                    if (connectionState == BluetoothProfile.STATE_DISCONNECTED ||
                                    connectionState == BluetoothProfile.STATE_DISCONNECTING) {
                        mLeAudioActiveDevice = device;
                        Log.d(TAG, "New LeAudioActiveDevice is " + mLeAudioActiveDevice);
                        return true;
                    }
                    /* Guard against stale mLeAudioActiveDevice: verify the group is
                     * actually active before skipping setActiveDevice(). A stale reference
                     * from a session where GROUP_STATUS_ACTIVE was never received satisfies
                     * the lead-device equality check above but the group remains inactive,
                     * preventing setActiveGroupWithDevice / groupSetActiveNative from ever
                     * running and leaving getActiveDevices as Lead[null]/member_1[null]. */
                    int groupId = leAudio.get().getGroupId(device);
                    boolean groupIsActive = (groupId != android.bluetooth.IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID)
                            && (groupId == leAudio.get().getActiveGroupId());
                    if (groupIsActive) {
                        Log.d(TAG, "New LeAudioDevice is a part of an active group");
                        return true;
                    }
                    Log.w(TAG, "Lead device is set but group " + groupId
                            + " is not active — stale mLeAudioActiveDevice reference, "
                            + "proceeding with full group activation");
                }
                success = leAudio.get().setActiveDevice(device);
            }

            if (!success) {
                Log.e(TAG, "setLeAudioActiveDevice: failed for device " + device);
                return false;
            }

            if (!Flags.admRemoveHapVariables()) {
                if (device == null) {
                    mLeHearingAidActiveDevice = null;
                    mPendingLeHearingAidActiveDevice.remove(device);
                }
            }
        }
        return true;
    }

    private boolean setLeHearingAidActiveDevice(BluetoothDevice device) {
        Log.i(TAG, "setLeHearingAidActiveDevice(" + device + ")");
        synchronized (mLock) {
            if (!Objects.equals(mLeAudioActiveDevice, device)) {
                if (!setLeAudioActiveDevice(device, /* stopAudio= */ true)) {
                    Log.e(TAG, "setLeHearingAidActiveDevice failed for device " + device);
                    return false;
                }
            }
            if (Objects.equals(mLeAudioActiveDevice, device)) {
                // setLeAudioActiveDevice succeed
                mLeHearingAidActiveDevice = device;
                mPendingLeHearingAidActiveDevice.remove(device);
                return true;
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    private boolean areSameGroupMembers(BluetoothDevice firstDevice, BluetoothDevice secondDevice) {
        if (firstDevice == null || secondDevice == null) {
            return false;
        }

        final var leAudio = mAdapterService.getLeAudioService();
        if (leAudio.isEmpty()) {
            Log.e(TAG, "LeAudioService not available");
            return false;
        }

        int groupIdFirst = leAudio.get().getGroupId(firstDevice);
        int groupIdSecond = leAudio.get().getGroupId(secondDevice);

        if (groupIdFirst == BluetoothLeAudio.GROUP_ID_INVALID
                || groupIdSecond == BluetoothLeAudio.GROUP_ID_INVALID) {
            return false;
        }

        return groupIdFirst == groupIdSecond;
    }

    /**
     * TODO: This method can return true when a fallback device for an unrelated profile is found.
     * Take disconnected profile as an argument, and find the exact fallback device. Also, split
     * this method to smaller methods for better readability.
     *
     * @return true when the fallback device is activated, false otherwise
     */
    @GuardedBy("mLock")
    private boolean setFallbackDeviceActiveLocked(BluetoothDevice recentlyRemovedDevice) {
        Log.i(TAG, "setFallbackDeviceActive, recently removed: " + recentlyRemovedDevice);

        /* Do not set fallback device as active when other device is pending to become active */
        if (Flags.admSuspendFallbackDuringChange()) {
            if (mPendingActiveDevice != null) {
                Log.i(TAG, "Pending active device: " + mPendingActiveDevice);
                return false;
            }
        }

        List<BluetoothDevice> connectedHearingAidDevices = new ArrayList<>();
        final var leAudio = mAdapterService.getLeAudioService();
        if (!mHearingAidConnectedDevices.isEmpty()) {
            connectedHearingAidDevices.addAll(mHearingAidConnectedDevices);
        }
        // Hearing Aids are prioritized as fallback devices.
        if (Flags.admRemoveHapVariables()) {
            if (leAudio.isPresent()) {
                for (BluetoothDevice dev : mLeAudioConnectedDevices) {
                    if (leAudio.get().isGroupAvailableForStream(leAudio.get().getGroupId(dev))
                            && isLeAudioHearingAidDevice(dev)) {
                        connectedHearingAidDevices.add(dev);
                    }
                }
            }
        } else {
            if (!mLeHearingAidConnectedDevices.isEmpty() && leAudio.isPresent()) {
                for (BluetoothDevice dev : mLeHearingAidConnectedDevices) {
                    if (leAudio.get().isGroupAvailableForStream(leAudio.get().getGroupId(dev))) {
                        connectedHearingAidDevices.add(dev);
                    }
                }
            }
        }

        if (!connectedHearingAidDevices.isEmpty()) {
            BluetoothDevice device =
                    mStorage.getMostRecentlyConnectedDeviceInList(connectedHearingAidDevices);
            if (device != null) {
                /* Check if fallback device shall be used. It should be used when a new
                 * device is connected. If the most recently connected device is the same as
                 * recently removed device, it means it just switched profile it is using and is
                 * not new one.
                 */
                boolean stopAudio =
                        (recentlyRemovedDevice != null
                                && device.equals(recentlyRemovedDevice)
                                && connectedHearingAidDevices.size() == 1);

                if (mHearingAidConnectedDevices.contains(device)) {
                    Log.i(TAG, "Found a hearing aid fallback device: " + device);
                    setHearingAidActiveDevice(device, /* stopAudio= */ true);
                    setA2dpActiveDevice(null, stopAudio);
                    setHfpActiveDevice(null);
                    setLeAudioActiveDevice(null, stopAudio);
                } else {
                    Log.i(TAG, "Found a LE hearing aid fallback device: " + device);
                    if (areSameGroupMembers(recentlyRemovedDevice, device)) {
                        Log.d(
                                TAG,
                                "Do nothing, removed device belong to the same group as the"
                                        + " fallback device.");
                        return true;
                    }
                    if (Flags.admRemoveHapVariables()) {
                        setLeAudioActiveDevice(device, /* stopAudio= */ true);
                    } else {
                        setLeHearingAidActiveDevice(device);
                    }
                    setHearingAidActiveDevice(null, stopAudio);
                    setA2dpActiveDevice(null, stopAudio);
                    setHfpActiveDevice(null);
                }
                return true;
            }
        }

        final var a2dp = mAdapterService.getA2dpService();
        BluetoothDevice a2dpFallbackDevice = null;
        if (a2dp.isPresent()) {
            a2dpFallbackDevice = a2dp.get().getFallbackDevice();
            Log.d(TAG, "a2dpFallbackDevice: " + a2dpFallbackDevice);
        }

        if (mA2dpActiveDevice != null &&
                !Objects.equals(mA2dpActiveDevice, recentlyRemovedDevice)) {
            if (a2dp.isPresent() &&
                    a2dp.get().getConnectionState(mA2dpActiveDevice) ==
                        BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Use current A2DP active device as fallback: " + mA2dpActiveDevice);
                a2dpFallbackDevice = mA2dpActiveDevice;
            }
        }

        final var headset = mAdapterService.getHeadsetService();
        BluetoothDevice headsetFallbackDevice = null;
        if (headset.isPresent()) {
            headsetFallbackDevice = headset.get().getFallbackDevice();
            Log.d(TAG, "headsetFallbackDevice: " + headsetFallbackDevice);
        }

        if (mHfpActiveDevice != null &&
                !Objects.equals(mHfpActiveDevice, recentlyRemovedDevice)) {
            if (headset.isPresent() &&
                    headset.get().getConnectionState(mHfpActiveDevice) ==
                        BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Use current HFP active device as fallback: " + mHfpActiveDevice);
                headsetFallbackDevice = mHfpActiveDevice;
            }
        }

        List<BluetoothDevice> connectedDevices = new ArrayList<>();
        if (leAudio.isPresent()) {
            for (BluetoothDevice dev : mLeAudioConnectedDevices) {
                if (leAudio.get().isGroupAvailableForStream(leAudio.get().getGroupId(dev))) {
                    connectedDevices.add(dev);
                }
            }
        }
        Log.d(TAG, "Audio mode: " + mAudioMode);
        switch (mAudioMode) {
            case AudioManager.MODE_NORMAL -> {
                if (a2dpFallbackDevice != null) {
                    connectedDevices.add(a2dpFallbackDevice);
                }
            }
            case AudioManager.MODE_RINGTONE -> {
                if (headsetFallbackDevice != null && headset.get().isInbandRingingEnabled()) {
                    connectedDevices.add(headsetFallbackDevice);
                }
            }
            default -> {
                if (headsetFallbackDevice != null) {
                    connectedDevices.add(headsetFallbackDevice);
                }
            }
        }
        if (Flags.admFixFallbackDeviceSearch()) {
            if (connectedDevices.isEmpty()) {
                return false;
            }
            for (BluetoothDevice device : mStorage.getMostRecentlyConnectedDevices()) {
                if (mAudioManager.getMode() == AudioManager.MODE_NORMAL) {
                    if (Objects.equals(a2dpFallbackDevice, device)) {
                        Log.i(TAG, "Found an A2DP fallback device: " + device);
                        setA2dpActiveDevice(device, /* stopAudio= */ true);
                        setHfpActiveDevice(headsetFallbackDevice);
                        /* If dual mode is enabled, LEA will be made active once all supported
                        classic audio profiles are made active for the device. */
                        if (!Utils.isDualModeAudioEnabled()) {
                            setLeAudioActiveDevice(null, /* stopAudio= */ false);
                        }
                        setHearingAidActiveDevice(null, /* stopAudio= */ false);
                        break;
                    } else {
                        Log.i(TAG, "Found a LE audio fallback device: " + device);
                        if (areSameGroupMembers(recentlyRemovedDevice, device)) {
                            Log.i(
                                    TAG,
                                    "Do nothing, removed device belong to the same group as the"
                                            + " fallback device.");
                            continue;
                        }

                        if (!setLeAudioActiveDevice(device, /* stopAudio= */ true)) {
                            return false;
                        }

                        if (!Utils.isDualModeAudioEnabled()) {
                            setA2dpActiveDevice(null, /* stopAudio= */ false);
                            setHfpActiveDevice(null);
                        }
                        setHearingAidActiveDevice(null, /* stopAudio= */ false);
                        break;
                    }
                } else {
                    if (Objects.equals(headsetFallbackDevice, device)) {
                        Log.i(TAG, "Found a HFP fallback device: " + device);
                        setHfpActiveDevice(device);
                        setA2dpActiveDevice(a2dpFallbackDevice, /* stopAudio= */ true);
                        if (!Utils.isDualModeAudioEnabled()) {
                            setLeAudioActiveDevice(null, /* stopAudio= */ false);
                        }
                        setHearingAidActiveDevice(null, /* stopAudio= */ false);
                        break;
                    } else {
                        Log.i(TAG, "Found an LE audio fallback device: " + device);
                        if (areSameGroupMembers(recentlyRemovedDevice, device)) {
                            Log.i(
                                    TAG,
                                    "Do nothing, removed device belong to the same group as the"
                                            + " fallback device.");
                            continue;
                        }

                        setLeAudioActiveDevice(device, /* stopAudio= */ true);
                        if (!Utils.isDualModeAudioEnabled()) {
                            setA2dpActiveDevice(null, /* stopAudio= */ false);
                            setHfpActiveDevice(null);
                        }
                        setHearingAidActiveDevice(null, /* stopAudio= */ false);
                        break;
                    }
                }
            }
            return true;
        }
        BluetoothDevice device = mStorage.getMostRecentlyConnectedDeviceInList(connectedDevices);
        if (device == null) {
            Log.d(TAG, "No fallback devices are found");
            return false;
        }
        Log.d(TAG, "Most recently connected device: " + device);
        if (mAudioMode == AudioManager.MODE_NORMAL) {
            if (Objects.equals(a2dpFallbackDevice, device)) {
                if (Objects.equals(mA2dpActiveDevice, device)) {
                    /* The fallback device is already the active A2DP device (it was never
                     * really deactivated). Skip the redundant remove + re-set, which would
                     * otherwise make the device flicker inactive -> active. */
                    Log.i(
                            TAG,
                            "A2DP fallback device is already active, skip re-activation: "
                                    + device);
                } else {
                    Log.i(TAG, "Found an A2DP fallback device: " + device + ", going for Music" +
                               "player pause");
                    setA2dpActiveDevice(null, /* stopAudio= */ false);
                    /* Clear LE Audio active device before setting A2DP fallback to ensure
                     * AudioPolicyService processes the LE Audio removal first.*/
                    if (!Utils.isDualModeAudioEnabled()) {
                        setLeAudioActiveDevice(null, /* stopAudio= */ true);
                    }
                    Log.d(TAG, "Setting A2DP fallback device as the Active Device");
                    setA2dpActiveDevice(device, /* stopAudio= */ true);
                }
                if (Objects.equals(headsetFallbackDevice, device)) {
                    setHfpActiveDevice(device);
                } else {
                    setHfpActiveDevice(null);
                }
                setHearingAidActiveDevice(null, /* stopAudio= */ false);
            } else {
                Log.i(TAG, "Found a LE audio fallback device: " + device);
                if (areSameGroupMembers(recentlyRemovedDevice, device)) {
                    Log.i(
                            TAG,
                            "Do nothing, removed device belong to the same group as the fallback"
                                    + " device.");
                    return true;
                }

                if (!Utils.isDualModeAudioEnabled()) {
                    setA2dpActiveDevice(null, /* stopAudio= */ false);
                    setHfpActiveDevice(null);
                }

                if (!setLeAudioActiveDevice(device, false)) {
                    return false;
                }

                setHearingAidActiveDevice(null, /* stopAudio= */ false);
            }
        } else {
            if (Objects.equals(headsetFallbackDevice, device)) {
                Log.i(TAG, "Found a HFP fallback device: " + device);
                setHfpActiveDevice(device);
                if (Objects.equals(a2dpFallbackDevice, device)) {
                    setA2dpActiveDevice(device, /* stopAudio= */ true);
                } else {
                    setA2dpActiveDevice(null, /* stopAudio= */ false);
                }
                if (!Utils.isDualModeAudioEnabled()) {
                    setLeAudioActiveDevice(null, /* stopAudio= */ false);
                }
                setHearingAidActiveDevice(null, /* stopAudio= */ false);
            } else {
                Log.i(TAG, "Found a LE audio fallback device: " + device);
                if (areSameGroupMembers(recentlyRemovedDevice, device)) {
                    Log.i(
                            TAG,
                            "Do nothing, removed device belong to the same group as the fallback"
                                    + " device.");
                    return true;
                }

                setLeAudioActiveDevice(device, /* stopAudio= */ true);
                if (!Utils.isDualModeAudioEnabled()) {
                    setA2dpActiveDevice(null, /* stopAudio= */ false);
                    setHfpActiveDevice(null);
                }
                setHearingAidActiveDevice(null, /* stopAudio= */ false);
            }
        }
        return true;
    }

    private void resetState() {
        synchronized (mLock) {
            mA2dpConnectedDevices.clear();
            mA2dpActiveDevice = null;

            mHfpConnectedDevices.clear();
            mHfpActiveDevice = null;

            mHearingAidConnectedDevices.clear();
            mHearingAidActiveDevices.clear();

            mLeAudioConnectedDevices.clear();
            mLeAudioActiveDevice = null;
            mLeAudioActiveGroupId = BluetoothLeAudio.GROUP_ID_INVALID;

            mLeHearingAidConnectedDevices.clear();
            mLeHearingAidActiveDevice = null;
            mPendingLeHearingAidActiveDevice.clear();
        }
    }

    @VisibleForTesting
    BluetoothDevice getA2dpActiveDevice() {
        synchronized (mLock) {
            return mA2dpActiveDevice;
        }
    }

    @VisibleForTesting
    BluetoothDevice getHfpActiveDevice() {
        synchronized (mLock) {
            return mHfpActiveDevice;
        }
    }

    @VisibleForTesting
    Set<BluetoothDevice> getHearingAidActiveDevices() {
        synchronized (mLock) {
            return mHearingAidActiveDevices;
        }
    }

    @VisibleForTesting
    BluetoothDevice getLeAudioActiveDevice() {
        synchronized (mLock) {
            return mLeAudioActiveDevice;
        }
    }

    @VisibleForTesting
    public BluetoothDevice fetchLeAudioActiveDevice() {
        synchronized (mLock) {
            return mLeAudioActiveDevice;
        }
    }

    @GuardedBy("mLock")
    private long getHearingAidActiveHiSyncIdLocked() {
        final var hearingAid = mAdapterService.getHearingAidService();
        if (hearingAid.isPresent() && !mHearingAidActiveDevices.isEmpty()) {
            return hearingAid.get().getHiSyncId(mHearingAidActiveDevices.iterator().next());
        }
        return BluetoothHearingAid.HI_SYNC_ID_INVALID;
    }

    /**
     * Checks if le audio broadcasting is ON
     *
     * @return {@code true} if is broadcasting audio, {@code false} otherwise
     */
    private boolean isBroadcastingAudio() {
        final var leAudio = mAdapterService.getLeAudioService();
        if (leAudio.isEmpty()) {
            Log.d(TAG, "isBroadcastingAudio: false - there is no LeAudioService");
            return false;
        }

        if (!leAudio.get().isBroadcastStarted()) {
            Log.d(TAG, "isBroadcastingAudio: false - getAllBroadcastMetadata is empty");
            return false;
        }

        Log.d(TAG, "isBroadcastingAudio: true");
        return true;
    }

    /**
     * Checks if device type is supported by ActiveDeviceManager
     *
     * @return {@code true} if type is known, {@code false} otherwise
     */
    private static boolean isDeviceTypeSupported(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_HEARING_AID,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_BLE_BROADCAST,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEARING_AID -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean stopBroadcastingAudio() {
        Log.d(TAG, "stopBroadcastingAudio");
        if (!isBroadcastingAudio()) {
            Log.w(TAG, "Broadcast audio is not active");
            return false;
        }

        final var leAudio = mAdapterService.getLeAudioService();
        if (leAudio.isEmpty()) {
            return false;
        }
        leAudio.get().setInactiveForBroadcast();
        return true;
    }

    /**
     * Called when a wired audio device is connected. It might be called multiple times each time a
     * wired audio device is connected.
     */
    @VisibleForTesting
    void wiredAudioDeviceConnected() {
        Log.d(TAG, "wiredAudioDeviceConnected");
        setA2dpActiveDevice(null, true);
        setHfpActiveDevice(null);
        setHearingAidActiveDevice(null, true);
        setLeAudioActiveDevice(null, true);
        stopBroadcastingAudio();
    }

    private void getDevicesInfo(
            StringBuilder sb, List<BluetoothDevice> devices, BluetoothDevice activeDevice) {
        for (BluetoothDevice dev : devices) {
            sb.append("      ");
            if (dev == null) {
                sb.append("NULL\n");
                continue;
            }
            sb.append(dev).append(": ").append(mAdapterService.getRemoteName(dev));
            if (activeDevice != null && Objects.equals(activeDevice, dev)) {
                sb.append(" <- ACTIVE");
            }
            sb.append("\n");
        }
    }

    private void getDevicesInfo(StringBuilder sb, BluetoothDevice device) {
        if (device == null) {
            sb.append("NULL\n");
            return;
        }
        sb.append(device).append(": ").append(mAdapterService.getRemoteName(device)).append("\n");
    }

    boolean isVoipLeaWarEnabled() {
        CallAudio mCallAudio = CallAudio.get();
        if (mCallAudio != null) {
            return mCallAudio.isVoipLeaWarEnabled();
        }
        return false;
    }    

    protected void dump(PrintWriter writer) {
        StringBuilder sb = new StringBuilder();

        synchronized (mLock) {
            sb.append("  Audio mode: ").append(mAudioManager.getMode()).append("\n");
            sb.append("  Dual mode audio: ").append(Utils.isDualModeAudioEnabled()).append("\n");
            sb.append("  Broadcasting: ").append(isBroadcastingAudio()).append("\n");
            sb.append("  Pending active device: ");
            getDevicesInfo(sb, mPendingActiveDevice);
            sb.append("  Pending classic active device: ");
            getDevicesInfo(sb, mPendingClassicActiveDevice);
            sb.append("  Classic device to be activated: ");
            getDevicesInfo(sb, mClassicDeviceToBeActivated);
            sb.append("  Classic device not to be activated: ");
            getDevicesInfo(sb, mClassicDeviceNotToBeActivated);

            sb.append("  A2DP:\n");
            sb.append("    Connected count: ").append(mA2dpConnectedDevices.size()).append("\n");
            getDevicesInfo(sb, mA2dpConnectedDevices, mA2dpActiveDevice);
            sb.append("    Active: ");
            getDevicesInfo(sb, mA2dpActiveDevice);
            sb.append("    Fallback: ");
            final var a2dp = mAdapterService.getA2dpService();
            BluetoothDevice a2dpFallbackDevice = null;
            if (a2dp.isPresent()) {
                a2dpFallbackDevice = a2dp.get().getFallbackDevice();
            }
            getDevicesInfo(sb, a2dpFallbackDevice);
            sb.append("    Most recent: ");
            BluetoothDevice recentlyConnectedA2dpDevice =
                    mStorage.getMostRecentlyConnectedDeviceInList(mA2dpConnectedDevices);
            getDevicesInfo(sb, recentlyConnectedA2dpDevice);

            sb.append("  HFP:\n");
            sb.append("    Connected count: ").append(mHfpConnectedDevices.size()).append("\n");
            getDevicesInfo(sb, mHfpConnectedDevices, mHfpActiveDevice);
            sb.append("    Active: ");
            getDevicesInfo(sb, mHfpActiveDevice);
            sb.append("    Fallback: ");
            final var headset = mAdapterService.getHeadsetService();
            BluetoothDevice headsetFallbackDevice = null;
            if (headset.isPresent()) {
                headsetFallbackDevice = headset.get().getFallbackDevice();
            }
            getDevicesInfo(sb, headsetFallbackDevice);
            sb.append("    Most recent: ");
            BluetoothDevice recentlyConnectedHfpDevice =
                    mStorage.getMostRecentlyConnectedDeviceInList(mHfpConnectedDevices);
            getDevicesInfo(sb, recentlyConnectedHfpDevice);

            sb.append("  HA:\n");
            sb.append("    Connected count: ")
                    .append(mHearingAidConnectedDevices.size())
                    .append("\n");
            getDevicesInfo(sb, mHearingAidConnectedDevices, null);
            sb.append("    Active: ").append(mHearingAidActiveDevices.size()).append("\n");
            getDevicesInfo(
                    sb, mHearingAidActiveDevices.stream().collect(Collectors.toList()), null);
            sb.append("    Most recent: ");
            BluetoothDevice recentlyConnectedHaDevice =
                    mStorage.getMostRecentlyConnectedDeviceInList(mHearingAidConnectedDevices);
            getDevicesInfo(sb, recentlyConnectedHaDevice);

            sb.append("  LE Audio:\n");
            sb.append("    Connected count: ").append(mLeAudioConnectedDevices.size()).append("\n");
            getDevicesInfo(sb, mLeAudioConnectedDevices, mLeAudioActiveDevice);
            sb.append("    Active: ");
            getDevicesInfo(sb, mLeAudioActiveDevice);
            sb.append("    Most recent: ");
            BluetoothDevice recentlyConnectedLeAudioDevice =
                    mStorage.getMostRecentlyConnectedDeviceInList(mLeAudioConnectedDevices);
            getDevicesInfo(sb, recentlyConnectedLeAudioDevice);

            sb.append("  LE HA:\n");
            if (Flags.admRemoveHapVariables()) {
                List<BluetoothDevice> connectedLeAudioHearingAidList =
                        mLeAudioConnectedDevices.stream()
                                .filter(p -> isLeAudioHearingAidDevice(p))
                                .collect(Collectors.toList());
                sb.append("    Connected count: ")
                        .append(mLeAudioConnectedDevices.size())
                        .append("\n");
                getDevicesInfo(sb, mLeAudioConnectedDevices, null);
                sb.append("    Active: ");
                if (isLeAudioHearingAidDevice(mLeAudioActiveDevice)) {
                    getDevicesInfo(sb, mLeAudioActiveDevice);
                } else {
                    sb.append("NULL\n");
                }
                sb.append("    Most recent: ");
                BluetoothDevice recentlyConnectedLeHaDevice =
                        mStorage.getMostRecentlyConnectedDeviceInList(
                                connectedLeAudioHearingAidList);
                getDevicesInfo(sb, recentlyConnectedLeHaDevice);
            } else {
                sb.append("    Connected count: ")
                        .append(mLeHearingAidConnectedDevices.size())
                        .append("\n");
                getDevicesInfo(sb, mLeHearingAidConnectedDevices, null);
                sb.append("    Active: ");
                getDevicesInfo(sb, mLeHearingAidActiveDevice);
                sb.append("    Most recent: ");
                BluetoothDevice recentlyConnectedLeHaDevice =
                        mStorage.getMostRecentlyConnectedDeviceInList(
                                mLeHearingAidConnectedDevices);
                getDevicesInfo(sb, recentlyConnectedLeHaDevice);
                sb.append("    Pending active: ")
                        .append(mPendingLeHearingAidActiveDevice.size())
                        .append("\n");
                getDevicesInfo(sb, mPendingLeHearingAidActiveDevice, null);
            }
        }

        sb.append("\n\n");
        mEventLogger.dump(sb);

        writer.println(TAG);
        writer.println(sb);
        writer.println();
    }
}
