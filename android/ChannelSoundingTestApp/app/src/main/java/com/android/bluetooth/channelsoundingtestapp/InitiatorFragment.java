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
 *
 * Changes from Qualcomm Innovation Center, Inc. are provided under the following license:
 * Copyright (c) 2024 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.bluetooth.channelsoundingtestapp;

import android.bluetooth.BluetoothGatt;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
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
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;
import com.android.bluetooth.channelsoundingtestapp.InitiatorViewModel.FileAppender;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The fragment holds the initiator of channel sounding. */
@SuppressWarnings("SetTextI18n")
public class InitiatorFragment extends Fragment {
  private static final DecimalFormat DISTANCE_DECIMAL_FMT = new DecimalFormat("0.0");

  private ArrayAdapter<String> mDmMethodArrayAdapter;
  private ArrayAdapter<String> msecurityModeAdapter;
  private ArrayAdapter<String> msetfrequencyAdapter;
  private ArrayList<String> securityModes;
  private ArrayList<String> Conn_Interval;
  private Button seclevelset;
  private Button freqset;
  private Button durset;
  private Button methoddist;
  private Button distancemarker;        // for storing the distance in the file
  private Button distancemarkerlog;    //for adding marker in log
  private double curr_distance;
  private EditText dis_meas;
  private Spinner mSpinnerSecurityMode;
  private ArrayList<String> frequency;
  private TextView mDistanceText;
  private CanvasView mDistanceCanvasView;
  private Spinner mSpinnerDmMethod;
  private Button mButtonCs;
  private LinearLayout mDistanceViewLayout;
  private Spinner mConnUpSpinner;
  private Button mConnUpButton;
  private TextView mLogText;
  private BleConnectionViewModel mBleConnectionViewModel;
  private InitiatorViewModel mInitiatorViewModel;
  private Button mSeeMoreButton;

  private ArrayAdapter<String> mFreqArrayAdapter;
  private Spinner mSpinnerFreq;
  private ArrayAdapter<String> mDurationArrayAdapter;
  private Spinner mSpinnerDuration;

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View root = inflater.inflate(R.layout.fragment_initiator, container, false);
    Fragment bleConnectionFragment = new BleConnectionFragment();
    FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
    transaction.replace(R.id.init_ble_connection_container, bleConnectionFragment).commit();
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
    mSeeMoreButton = (Button) root.findViewById(R.id.btn_see_more);
    if (mSeeMoreButton != null) {
        mSeeMoreButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getActivity(), SeeMoreActivity.class);
                intent.putExtra("distance", curr_distance);
                startActivity(intent);
            }
        });
    }
    return root;
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

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

        mSeeMoreButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getActivity(), SeeMoreActivity.class);
                intent.putExtra("distance", curr_distance);
                startActivity(intent);
            }
        });

        mInitiatorViewModel = new ViewModelProvider(this).get(InitiatorViewModel.class);
        mBleConnectionViewModel = new ViewModelProvider(this).get(BleConnectionViewModel.class);
        mBleConnectionViewModel
                .getLogText()
                .observe(
                        getActivity(),
                        log -> {
                            mLogText.setText(log);
                        });
        mBleConnectionViewModel
                .getTargetDevice()
                .observe(
                        getActivity(),
                        targetDevice -> {
                            mInitiatorViewModel.setTargetDevice(targetDevice);
                        });

        List<String> securityModes = Arrays.asList("1", "2", "3", "4");
        mSpinnerSecurityMode.setAdapter(
            new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, securityModes));

        List<String> frequency = Arrays.asList(
            "REPORT_FREQUENCY_LOW", "REPORT_FREQUENCY_MEDIUM", "REPORT_FREQUENCY_HIGH");

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
          }
        });

        mInitiatorViewModel
                .getCsStarted()
                .observe(
                        getActivity(),
                        started -> {
                            if (started) {
                                mButtonCs.setText("Stop Distance Measurement");
                                mDistanceCanvasView.cleanUp();
                            } else {
                                mButtonCs.setText("Start Distance Measurement");
                            }
                        });
        mInitiatorViewModel
                .getLogText()
                .observe(
                        getActivity(),
                        log -> {
                            mLogText.setText(log);
                        });

        mInitiatorViewModel
                .getDistanceResult()
                .observe(
                        getActivity(),
                        distanceMeters -> {
                            mDistanceCanvasView.addNode(Math.round(distanceMeters * 100.0) / 100.0, /* abort= */ false);
                            mDistanceText.setText(
                                    DISTANCE_DECIMAL_FMT.format(distanceMeters) + " m");
                            curr_distance = distanceMeters;
                            String timestamp = LocalDateTime.now().format(
                                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                            FileAppender.appendToFile(getActivity(), "myfile.csv",
                                distanceMeters + "," + timestamp + "\n");
                        });

        mDmMethodArrayAdapter.addAll(mInitiatorViewModel.getSupportedDmMethods());
        mFreqArrayAdapter.addAll(mInitiatorViewModel.getMeasurementFreqs());
        mDurationArrayAdapter.addAll(mInitiatorViewModel.getMeasurementDurations());
        int position = mDmMethodArrayAdapter.getPosition("Channel Sounding");
        if (position != -1) {
            printLog("Found method Channel Sounding");
            mSpinnerDmMethod.setSelection(position);
        }

        mButtonCs.setOnClickListener(v -> {
        if(!mBleConnectionViewModel.isconnected()) {
            printLog("Do Gatt Connect First");
            return;
        }
          String methodName = mSpinnerDmMethod.getSelectedItem().toString();
          String freq = mSpinnerFreq.getSelectedItem().toString();
          String sec_mode_selected = mSpinnerSecurityMode.getSelectedItem().toString();
          int duration_selected = Integer.parseInt(mSpinnerDuration.getSelectedItem().toString());
          int conn_state = (int) mConnUpSpinner.getSelectedItemId();
          String conn_priority = mConnUpSpinner.getSelectedItem().toString();
          mBleConnectionViewModel.updateconnectioninterval(conn_priority);
          
            mInitiatorViewModel.toggleCsStartStop(
                methodName, freq, sec_mode_selected, "REPORT_FREQUENCY_LOW", duration_selected);
        });

        distancemarker.setOnClickListener(v -> {
          String dist_meas = dis_meas.getText().toString();
          mInitiatorViewModel.actualDistance(dist_meas);
          FileAppender.appendToFile(
              getActivity(), "myfile.csv", "changing distance to " + dist_meas + "\n");
        });

        distancemarkerlog.setOnClickListener(v -> {
          mInitiatorViewModel.logMarker();
        });
    }

    private void printLog(String logMessage) {
        mLogText.setText("LOG: " + logMessage);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    public void onAdapterStateOff() {
        if (mInitiatorViewModel != null) {
            mInitiatorViewModel.restoreToDefaultParams();
        }

        if (mBleConnectionViewModel != null) {
            mBleConnectionViewModel.restoreToDefaultParams();
        }
    }

    public void onAdapterStateOn() {
        printLog("Bluetooth turned on ");
    }
}
