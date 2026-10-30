/*
 * Copyright 2024 The Android Open Source Project
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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.android.bluetooth.channelsoundingtestapp.InitiatorViewModel.FileAppender;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The fragment holds the initiator controls for a specific device. */
@SuppressWarnings("SetTextI18n")
public class DeviceControlFragment extends Fragment {
  private static final DecimalFormat DISTANCE_DECIMAL_FMT = new DecimalFormat("0.0");

  private ArrayAdapter<String> mDmMethodArrayAdapter;
  private ArrayAdapter<String> mFreqArrayAdapter;
  private ArrayAdapter<String> mDurationArrayAdapter;
  
  private Spinner mSpinnerSecurityMode;
  private Spinner mSpinnerDmMethod;
  private Spinner mSpinnerFreq;
  private Spinner mSpinnerDuration;
  private Spinner mConnUpSpinner;
  
  private Button mButtonCs;
  private Button distancemarker;
  private Button distancemarkerlog;
  private Button mConnUpButton;
  
  private EditText dis_meas;
  private TextView mDistanceText;
  private TextView mLogText;
  private CanvasView mDistanceCanvasView;
  private LinearLayout mDistanceViewLayout;
  
  private BleConnectionViewModel mBleConnectionViewModel;
  private InitiatorViewModel mInitiatorViewModel;
  
  private String mDeviceAddress;
  private double curr_distance;
  private ArrayList<String> Conn_Interval;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      if (getArguments() != null) {
          mDeviceAddress = getArguments().getString("device_address");
      }
  }

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View root = inflater.inflate(R.layout.activity_device_control, container, false);
    
    mSpinnerSecurityMode = (Spinner) root.findViewById(R.id.spinner_security_mode);
    distancemarker = (Button) root.findViewById(R.id.marker_dist);
    distancemarkerlog = (Button) root.findViewById(R.id.marker_log);
    dis_meas = (EditText) root.findViewById(R.id.distance_meas);
    mButtonCs = (Button) root.findViewById(R.id.btn_cs);
    mSpinnerDmMethod = (Spinner) root.findViewById(R.id.spinner_dm_method);
    mSpinnerFreq = (Spinner) root.findViewById(R.id.spinner_freq);
    mSpinnerDuration = (Spinner) root.findViewById(R.id.spinner_duration);
    mDistanceViewLayout = (LinearLayout) root.findViewById(R.id.layout_distance_view);
    mDistanceText = new TextView(getContext());
    mDistanceViewLayout.addView(mDistanceText);
    mDistanceText.setText("0.00 m");
    mDistanceText.setTextSize(96);
    mDistanceText.setGravity(Gravity.END);
    mDistanceCanvasView = new CanvasView(getContext(), "Distance");
    mDistanceViewLayout.addView(mDistanceCanvasView);
    mDistanceViewLayout.setPadding(0, 0, 0, 600);
    mLogText = (TextView) root.findViewById(R.id.text_log);
    mConnUpSpinner = (Spinner) root.findViewById(R.id.conn_up_spinner);
    mConnUpButton = (Button) root.findViewById(R.id.conn_up_button);

    return root;
  }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (mDeviceAddress == null) {
            printLog("Error: No device address provided.");
            return;
        }

        mDmMethodArrayAdapter =
                new ArrayAdapter<String>(
                        getContext(), android.R.layout.simple_spinner_item, new ArrayList<>());
        mDmMethodArrayAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mSpinnerDmMethod.setAdapter(mDmMethodArrayAdapter);
        
        mFreqArrayAdapter =
                new ArrayAdapter<String>(
                        getContext(), android.R.layout.simple_spinner_item, new ArrayList<>());
        mFreqArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpinnerFreq.setAdapter(mFreqArrayAdapter);
        
        mDurationArrayAdapter =
                new ArrayAdapter<String>(
                        getContext(), android.R.layout.simple_spinner_item, new ArrayList<>());
        mDurationArrayAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mSpinnerDuration.setAdapter(mDurationArrayAdapter);

        // Get ViewModels
        // Use requireActivity() for BleConnectionViewModel to share with MainActivity/InitiatorFragment
        mBleConnectionViewModel = new ViewModelProvider(requireActivity()).get(BleConnectionViewModel.class);
        // Use activity scope so distance measurement continues when leaving this control screen.
        mInitiatorViewModel = new ViewModelProvider(requireActivity()).get(InitiatorViewModel.class);
        // Attach so the heartbeat liveness-monitoring mitigation can be driven per session.
        mInitiatorViewModel.attachConnectionViewModel(mBleConnectionViewModel);

        // Get the BluetoothDevice object.
        BluetoothGatt gatt = mBleConnectionViewModel.getGattForDevice(mDeviceAddress);
        if (gatt == null) {
            printLog("Error: Device not connected or not found in GATT map.");
            Toast.makeText(getContext(), "Device not connected!", Toast.LENGTH_SHORT).show();
            // Consider disabling UI or navigating back.
            return;
        }
        BluetoothDevice device = gatt.getDevice();
        printLog("Target device set: " + device.getName() + " (" + mDeviceAddress + ")");

        mBleConnectionViewModel
                .getLogText()
                .observe(
                        getViewLifecycleOwner(),
                        log -> {
                            // Optionally append global logs? Or ignore?
                            // mLogText.setText(log); 
                        });

        List<String> securityModes = Arrays.asList("1", "2", "3", "4");
        mSpinnerSecurityMode.setAdapter(
            new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, securityModes));

        Conn_Interval = new ArrayList<>();
        Conn_Interval.add("Balanced");
        Conn_Interval.add("High Priority");
        Conn_Interval.add("Low Power");

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
            getContext(), android.R.layout.simple_spinner_item, Conn_Interval);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mConnUpSpinner.setAdapter(adapter);

        mConnUpButton.setOnClickListener(new View.OnClickListener() {
          @Override
          public void onClick(View view) {
               String conn_priority = mConnUpSpinner.getSelectedItem().toString();
               BluetoothGatt gatt = mBleConnectionViewModel.getGattForDevice(mDeviceAddress);
               if (gatt != null) {
                   mBleConnectionViewModel.updateConnectionInterval(gatt.getDevice(), conn_priority);
               }
          }
        });

        // Observe device-specific LiveData from the ViewModel.
        mInitiatorViewModel
                .getCsStarted(device)
                .observe(getViewLifecycleOwner(), started -> {
                    if (started) {
                        mButtonCs.setText("Stop Distance Measurement");
                    } else {
                        mButtonCs.setText("Start Distance Measurement");
                    }
                });

        mInitiatorViewModel
                .getLogText(device)
                .observe(getViewLifecycleOwner(), log -> {
                    // Only update if the log is for this device to avoid seeing other devices' logs.
                    if (log != null && log.contains(mDeviceAddress)) {
                         mLogText.setText(log);
                    }
                });

        mInitiatorViewModel
                .getDistanceResult(device)
                .observe(getViewLifecycleOwner(), distanceMeters -> {
                    if (distanceMeters == null) return;
                    mDistanceCanvasView.addNode(Math.round(distanceMeters * 100.0) / 100.0, false);
                    mDistanceText.setText(DISTANCE_DECIMAL_FMT.format(distanceMeters) + " m");
                    curr_distance = distanceMeters;
                    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    FileAppender.appendToFile(getActivity(), "myfile_" + mDeviceAddress + ".csv",
                        distanceMeters + "," + timestamp + "\n");
                });

        // Populate spinners with data from the device's session.
        mDmMethodArrayAdapter.addAll(mInitiatorViewModel.getSupportedDmMethods(device));
        mFreqArrayAdapter.addAll(mInitiatorViewModel.getMeasurementFreqs(device));
        mDurationArrayAdapter.addAll(mInitiatorViewModel.getMeasurementDurations(device));
        int position = mDmMethodArrayAdapter.getPosition("Channel Sounding");
        if (position != -1) {
            mSpinnerDmMethod.setSelection(position);
        }

        mButtonCs.setOnClickListener(v -> {
          BluetoothGatt currentGatt = mBleConnectionViewModel.getGattForDevice(mDeviceAddress);
          if (currentGatt == null) {
            printLog("Device disconnected.");
            return;
          }

          String methodName = mSpinnerDmMethod.getSelectedItem().toString();
          String freq = mSpinnerFreq.getSelectedItem().toString();
          String sec_mode_selected = mSpinnerSecurityMode.getSelectedItem().toString();
          int duration_selected = Integer.parseInt(mSpinnerDuration.getSelectedItem().toString());
          
          String conn_priority = mConnUpSpinner.getSelectedItem().toString();
          mBleConnectionViewModel.updateConnectionInterval(currentGatt.getDevice(), conn_priority);

          Boolean csStarted = mInitiatorViewModel.getCsStarted(currentGatt.getDevice()).getValue();
          if (csStarted == null || !csStarted) {
              mDistanceCanvasView.cleanUp();
          }

          mInitiatorViewModel.toggleCsStartStop(
                currentGatt.getDevice(), methodName, freq, sec_mode_selected, "REPORT_FREQUENCY_LOW", duration_selected);
        });

        distancemarker.setOnClickListener(v -> {
          BluetoothGatt currentGatt = mBleConnectionViewModel.getGattForDevice(mDeviceAddress);
          if (currentGatt == null) return;
          String dist_meas = dis_meas.getText().toString();
          mInitiatorViewModel.actualDistance(currentGatt.getDevice(), dist_meas);
          FileAppender.appendToFile(
              getActivity(), "myfile_" + mDeviceAddress + ".csv", "changing distance to " + dist_meas + "\n");
        });

        distancemarkerlog.setOnClickListener(v -> {
          BluetoothGatt currentGatt = mBleConnectionViewModel.getGattForDevice(mDeviceAddress);
          if (currentGatt == null) return;
          mInitiatorViewModel.logMarker(currentGatt.getDevice());
        });
    }

    private void printLog(String logMessage) {
        mLogText.setText("LOG: " + logMessage);
    }
}
