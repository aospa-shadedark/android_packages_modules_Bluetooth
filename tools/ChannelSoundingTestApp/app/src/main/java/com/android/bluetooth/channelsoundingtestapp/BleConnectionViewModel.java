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
 */

package com.android.bluetooth.channelsoundingtestapp;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import android.annotation.SuppressLint;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.ParcelUuid;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.bluetooth.channelsoundingtestapp.Constants.GattState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The ViewModel for the BLE GATT connection. */
@SuppressLint("MissingPermission") // permissions are checked upfront
public class BleConnectionViewModel extends AndroidViewModel {
    private static final int GATT_MTU_SIZE = 512;
    private boolean is_advertising = false;
    private final BluetoothAdapter mBluetoothAdapter;
    private final BluetoothManager mBluetoothManager;
    private Map<String, BluetoothGatt> mBluetoothGattMap = new HashMap<>();
    private MutableLiveData<Boolean> mIsAdvertising = new MutableLiveData<>(false);
    private MutableLiveData<String> mLogText = new MutableLiveData<>();
    private MutableLiveData<BluetoothDevice> mTargetDevice = new MutableLiveData<>();
    // scanner
    private final MutableLiveData<List<String>> mBondedBtDeviceAddresses = new MutableLiveData<>();
    private final MutableLiveData<GattState> mGattState =
            new MutableLiveData<>(GattState.DISCONNECTED);
    private String mTargetBtAddress = "";
    private int mTxPowerLevel = AdvertisingSetParameters.TX_POWER_HIGH;
    private int mPendingTxPowerLevel = -1; // -1 means no pending change
    private MutableLiveData<Boolean> mShowTxPower = new MutableLiveData<>(true);
    private MutableLiveData<List<BluetoothDevice>> mConnectedDevices = new MutableLiveData<>(new ArrayList<>());

    private GattState mExpectedGattState = GattState.DISCONNECTED;
    private Map<String, GattState> mDeviceGattStateMap = new HashMap<>();

    private final BroadcastReceiver mBluetoothStateReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                        return;
                    }

                    int state =
                            intent.getIntExtra(
                                    BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    if (state == BluetoothAdapter.STATE_TURNING_OFF
                            || state == BluetoothAdapter.STATE_OFF
                            || state == BluetoothAdapter.STATE_TURNING_ON) {
                        mExpectedGattState = GattState.DISCONNECTED;
                        mGattState.postValue(mExpectedGattState);
                        for (BluetoothGatt bluetoothGatt : mBluetoothGattMap.values()) {
                            if (bluetoothGatt != null) {
                                try {
                                    bluetoothGatt.close();
                                } catch (RuntimeException e) {
                                    // Ignore stale binder during BT reset.
                                }
                            }
                        }
                        mBluetoothGattMap.clear();
                        mTargetDevice.postValue(null);
                    }
                }
            };

    /** Constructor */
    public BleConnectionViewModel(@NonNull Application application) {
        super(application);
        mBluetoothManager = application.getSystemService(BluetoothManager.class);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        application.registerReceiver(
                mBluetoothStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    LiveData<Boolean> getIsAdvertising() {
        return mIsAdvertising;
    }

    LiveData<String> getLogText() {
        return mLogText;
    }

    LiveData<GattState> getGattState() {
        return mGattState;
    }

    LiveData<List<String>> getBondedBtDeviceAddresses() {
        return mBondedBtDeviceAddresses;
    }

    LiveData<BluetoothDevice> getTargetDevice() {
        return mTargetDevice;
    }

    LiveData<List<BluetoothDevice>> getConnectedDevices() {
        return mConnectedDevices;
    }

    LiveData<Boolean> getShowTxPower() {
        return mShowTxPower;
    }

    void setShowTxPower(boolean show) {
        mShowTxPower.setValue(show);
    }

    void toggleAdvertising() {
        if (mIsAdvertising.getValue()) {
            stopAdvertising();
        } else {
            startConnectableAdvertising();
        }
    }

    AdvertisingSetCallback mAdvertisingSetCallback = new AdvertisingSetCallback() {
      @Override
      public void onAdvertisingSetStarted(AdvertisingSet advertisingSet, int txPower, int status) {
        printLog("onAdvertisingSetStarted(): txPower:" + txPower + " , status: " + status);
        if (status == 0) {
          mIsAdvertising.postValue(true);
        }
      }

      @Override
      public void onAdvertisingDataSet(AdvertisingSet advertisingSet, int status) {
        printLog("onAdvertisingDataSet() :status:" + status);
      }

      @Override
      public void onScanResponseDataSet(AdvertisingSet advertisingSet, int status) {
        printLog("onScanResponseDataSet(): status:" + status);
      }

      @Override
      public void onAdvertisingSetStopped(AdvertisingSet advertisingSet) {
        printLog("onAdvertisingSetStopped():");
        mIsAdvertising.postValue(false);
        is_advertising = false;  // Fix: Reset the callback registration flag
      }
    };
    public void updateconnectioninterval(String conn_priority) {
      // Use the currently selected target address
      if (TextUtils.isEmpty(mTargetBtAddress)) return;
      BluetoothGatt gatt = mBluetoothGattMap.get(mTargetBtAddress);
      updateConnectionInterval(gatt, conn_priority);
    }

    public void updateConnectionInterval(BluetoothDevice device, String conn_priority) {
         if (device == null) return;
         updateConnectionInterval(mBluetoothGattMap.get(device.getAddress()), conn_priority);
    }

    public void updateConnectionInterval(BluetoothGatt gatt, String conn_priority) {
      if(gatt == null) {
        printLog("Bluetooth Gatt is null");
        return;
      }
      switch (conn_priority) {
        case "Balanced":
          gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
          break;
        case "High Priority":
          gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
          break;
        case "Low Power":
          gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);
          break;
      }
    }

    private void startConnectableAdvertising() {
        if (mIsAdvertising.getValue() || is_advertising) {
            printLog("Advertising already in progress. Ignoring new request.");
            return;
        }

        if (mPendingTxPowerLevel != -1) {
            mTxPowerLevel = getTxPowerConstant(mPendingTxPowerLevel);
            printLog("Applied pending TX Power: " + getTxPowerName(mPendingTxPowerLevel));
            mPendingTxPowerLevel = -1;
        }

        BluetoothLeAdvertiser advertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            printLog("Failed to start advertising: Bluetooth is OFF.");
            return;
        }
        AdvertisingSetParameters parameters =
                new AdvertisingSetParameters.Builder()
                        .setLegacyMode(false) // True by default, but set here as a reminder.
                        .setConnectable(true)
                        .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                        .setTxPowerLevel(mTxPowerLevel)
                        .build();

        BluetoothGattServerCallback gattServerCallback =
                new BluetoothGattServerCallback() {
                    @Override
                    public void onConnectionStateChange(
                            BluetoothDevice device, int status, int newState) {
                        super.onConnectionStateChange(device, status, newState);
                        if (newState == STATE_CONNECTED) {
                            printLog("Device connected: " + device.getName());
                            mTargetDevice.postValue(device);
                        } else if (newState == STATE_DISCONNECTED) {
                            printLog("Device disconnected: " + device.getName());
                            mTargetDevice.postValue(null);
                        }
                    }
                };

        BluetoothGattServer bluetoothGattServer =
                mBluetoothManager.openGattServer(
                        getApplication().getApplicationContext(), gattServerCallback);
        AdvertiseData advertiseData =
                new AdvertiseData.Builder()
                        .setIncludeDeviceName(true)
                        .addServiceUuid(new ParcelUuid(Constants.CS_TEST_SERVICE_UUID))
                        .addServiceUuid(new ParcelUuid(Constants.RANGING_SERVICE_UUID))
                        .build();

        printLog("Start connectable advertising");

        is_advertising = true; // Set flag immediately before API call to avoid race condition
        advertiser.startAdvertisingSet(
                parameters, advertiseData, null, null, null, 0, 0, mAdvertisingSetCallback);
    }

    private void stopAdvertising() {
        BluetoothLeAdvertiser advertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        if(advertiser != null) {
            advertiser.stopAdvertisingSet(mAdvertisingSetCallback);
            printLog("stop advertising");
        }
    }
    
    void checkstopadvertiser() {
        if(mIsAdvertising.getValue()) {
            mIsAdvertising.postValue(false);
            stopAdvertising();
        }
    }

    void updateBondedDevices() {
        List<String> addresses = new ArrayList<>();
        Set<BluetoothDevice> bonded_devices = mBluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : bonded_devices) {
            addresses.add(device.getAddress());
        }
        mBondedBtDeviceAddresses.setValue(addresses);
    }

    void setCsTargetAddress(String btAddress) {
        printLog("set target address: " + btAddress);
        mTargetBtAddress = btAddress;
        GattState state = mDeviceGattStateMap.getOrDefault(btAddress, GattState.DISCONNECTED);
        mGattState.setValue(state);
        mExpectedGattState = state; 
        // Update mTargetDevice based on connection status? 
        // Or just let it be null if not connected, and the device object if connected?
        if (mBluetoothGattMap.containsKey(btAddress)) {
             mTargetDevice.setValue(mBluetoothGattMap.get(btAddress).getDevice());
        } else {
             mTargetDevice.setValue(null);
        }
    }

    void setTxPowerLevel(int levelIndex) {
        if (mIsAdvertising.getValue()) {
            mPendingTxPowerLevel = levelIndex;
            printLog("TX Power will change to " + getTxPowerName(levelIndex) + " when advertising is restarted");
        } else {
            mTxPowerLevel = getTxPowerConstant(levelIndex);
            mPendingTxPowerLevel = -1;
            printLog("TX Power set to " + getTxPowerName(levelIndex));
        }
    }

    private int getTxPowerConstant(int levelIndex) {
        switch (levelIndex) {
            case 0:
                return AdvertisingSetParameters.TX_POWER_ULTRA_LOW;
            case 1:
                return AdvertisingSetParameters.TX_POWER_LOW;
            case 2:
                return AdvertisingSetParameters.TX_POWER_MEDIUM;
            case 3:
            default:
                return AdvertisingSetParameters.TX_POWER_HIGH;
        }
    }

    private String getTxPowerName(int levelIndex) {
        switch (levelIndex) {
            case 0:
                return "Ultra Low (-21 dBm)";
            case 1:
                return "Low (-15 dBm)";
            case 2:
                return "Medium (-7 dBm)";
            case 3:
            default:
                return "High (1 dBm)";
        }
    }

    void toggleGattConnection() {
        // Use the current target address state
        GattState currentState = mDeviceGattStateMap.getOrDefault(mTargetBtAddress, GattState.DISCONNECTED);
        
        if (currentState == GattState.DISCONNECTED) {
            if (TextUtils.isEmpty(mTargetBtAddress)) {
                printLog("Pair and select a target device first!");
                return;
            }
            connectGatt();
        } else if (currentState == GattState.CONNECTED_DIRECT) {
            disconnectGatt();
        }
    }

    public boolean isconnected() {
        // Check if current target is connected
        GattState currentState = mDeviceGattStateMap.getOrDefault(mTargetBtAddress, GattState.DISCONNECTED);
        return currentState != GattState.DISCONNECTED;
    }

    private BluetoothGattCallback mGattCallback =
            new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    String address = gatt.getDevice().getAddress();
                    printLog("onConnectionStateChange " + address + " status:" + status + ", newState:" + newState);
                    
                    if (newState == STATE_CONNECTED) {
                        printLog(gatt.getDevice().getName() + " is connected");
                        gatt.requestMtu(GATT_MTU_SIZE);
                        mBluetoothGattMap.put(address, gatt);
                        mDeviceGattStateMap.put(address, GattState.CONNECTED_DIRECT);
                        updateConnectedDevicesList();
                        
                        // Only update UI if this is the currently selected device
                        if (address.equals(mTargetBtAddress)) {
                             mGattState.postValue(GattState.CONNECTED_DIRECT);
                             mTargetDevice.postValue(gatt.getDevice());
                        }
                    } else if (newState == STATE_DISCONNECTED) {
                        printLog("disconnected from " + gatt.getDevice().getName());
                        mDeviceGattStateMap.put(address, GattState.DISCONNECTED);
                        mBluetoothGattMap.remove(address);
                        gatt.close();
                        updateConnectedDevicesList();

                        if (address.equals(mTargetBtAddress)) {
                            mGattState.postValue(GattState.DISCONNECTED);
                            mTargetDevice.postValue(null);
                        }
                    }
                }

                public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        printLog("MTU changed to: " + mtu + " for " + gatt.getDevice().getName());
                    } else {
                        printLog("MTU change failed: " + status);
                    }
                }
            };

    private void updateConnectedDevicesList() {
        List<BluetoothDevice> devices = new ArrayList<>();
        for (BluetoothGatt gatt : mBluetoothGattMap.values()) {
            devices.add(gatt.getDevice());
        }
        mConnectedDevices.postValue(devices);
    }

    private void connectGatt() {
        BluetoothDevice btDevice = mBluetoothAdapter.getRemoteDevice(mTargetBtAddress);
        printLog("Connect gatt to " + btDevice.getName());
        // We don't set mExpectedGattState globally anymore, but per device logic relies on callback
        // mDeviceGattStateMap.put(mTargetBtAddress, GattState.CONNECTING); // Optional: add connecting state
        btDevice.connectGatt(
                getApplication().getApplicationContext(),
                false,
                mGattCallback,
                BluetoothDevice.TRANSPORT_LE);
    }

    private void disconnectGatt() {
        BluetoothGatt gatt = mBluetoothGattMap.get(mTargetBtAddress);
        if (gatt != null) {
            printLog("disconnect from " + gatt.getDevice().getName());
            gatt.disconnect();
        }
    }

    void toggleScanConnect() {
        if (mGattState.getValue() == GattState.DISCONNECTED) {
            connectGattByScanning();
        } else if (mGattState.getValue() == GattState.SCANNING) {
            stopScanning();
        } else if (mGattState.getValue() == GattState.CONNECTED_SCAN) {
            disconnectGatt();
        }
    }

    private ScanCallback mScanCallback =
            new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    List<ParcelUuid> serviceUuids = result.getScanRecord().getServiceUuids();
                    if (serviceUuids != null) {
                        for (ParcelUuid parcelUuid : serviceUuids) {
                            BluetoothDevice btDevice = result.getDevice();
                            printLog("found device - " + btDevice.getName());
                            if (parcelUuid.getUuid().equals(Constants.CS_TEST_SERVICE_UUID)) {
                                mExpectedGattState = GattState.CONNECTED_SCAN;
                                stopScanning();
                                printLog("connect GATT to: " + btDevice.getName());
                                // Connect to the GATT server
                                btDevice.connectGatt(
                                        getApplication().getApplicationContext(),
                                        false,
                                        mGattCallback,
                                        BluetoothDevice.TRANSPORT_LE);
                            }
                        }
                    }
                }
            };

    private void connectGattByScanning() {
        BluetoothLeScanner bluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            printLog("Cannot scan: Bluetooth is OFF.");
            return;
        }

        List<ScanFilter> filters = new ArrayList<>();
        ScanFilter filter =
                new ScanFilter.Builder()
                        .setServiceUuid(
                                new ParcelUuid(
                                        Constants.CS_TEST_SERVICE_UUID)) // Filter by service UUID
                        .build();
        filters.add(filter);

        ScanSettings settings =
                new ScanSettings.Builder()
                        .setLegacy(false)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setReportDelay(0)
                        .build();

        printLog("start scanning...");

        // Start scanning
        bluetoothLeScanner.startScan(filters, settings, mScanCallback);
        mExpectedGattState = GattState.SCANNING;
        mGattState.setValue(mExpectedGattState);
    }

    private void stopScanning() {
        BluetoothLeScanner bluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(mScanCallback);
            if (mExpectedGattState == GattState.SCANNING) {
                mExpectedGattState = GattState.DISCONNECTED;
                mGattState.setValue(mExpectedGattState);
            }
        }
    }

    @Override
    protected void onCleared() {
        getApplication().unregisterReceiver(mBluetoothStateReceiver);
        super.onCleared();
    }
    // Add method to retrieve Gatt for specific device (used by InitiatorViewModel/Fragment)
    public BluetoothGatt getGattForDevice(String address) {
        return mBluetoothGattMap.get(address);
    }

    private void printLog(@NonNull String logMsg) {
        mLogText.postValue("BT Log: " + logMsg);
    }
}
