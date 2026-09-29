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

import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;

/** The fragment holds the reflector of channel sounding. */
@SuppressWarnings("SetTextI18n")
public class ReflectorFragment extends Fragment {
    private BleConnectionViewModel mBleConnectionViewModel;
    private TextView mLogText;
    private LinearLayout mLayoutConnectedDevices;
    
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_reflector, container, false);

        Fragment bleConnectionFragment = new BleConnectionFragment();
        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        transaction.replace(R.id.ref_ble_connection_container, bleConnectionFragment).commit();
        
        mLogText = (TextView) root.findViewById(R.id.text_log);
        mLayoutConnectedDevices = root.findViewById(R.id.layout_connected_devices_container);
        
        return root;
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mBleConnectionViewModel = new ViewModelProvider(requireActivity()).get(BleConnectionViewModel.class);
        mBleConnectionViewModel.setShowTxPower(true);
        mBleConnectionViewModel
                .getLogText()
                .observe(
                        getViewLifecycleOwner(),
                        log -> {
                            mLogText.setText(log);
                        });

        mBleConnectionViewModel.getConnectedDevices().observe(getViewLifecycleOwner(), devices -> {
            mLayoutConnectedDevices.removeAllViews();
            if (devices != null) {
                for (BluetoothDevice device : devices) {
                    addDeviceRow(device);
                }
            }
        });
    }

    private void addDeviceRow(BluetoothDevice device) {
        View rowView = LayoutInflater.from(getContext()).inflate(R.layout.row_connected_device, mLayoutConnectedDevices, false);
        TextView deviceAddress = rowView.findViewById(R.id.device_address);
        Button btnOpenControl = rowView.findViewById(R.id.btn_open_control);
        TextView distanceText = rowView.findViewById(R.id.distance_text);

        deviceAddress.setText(device.getAddress());

        // Note: Reflector currently navigates to DeviceControlFragment just like Initiator
        // This allows it to act as Initiator for other Reflectors as requested.
        btnOpenControl.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString("device_address", device.getAddress());
            // We need to ensure the nav graph supports this action from ReflectorFragment
            // Based on nav_graph.xml read earlier, ReflectorFragment does NOT have an action to DeviceControlFragment.
            // We need to add it or use global action / explicit navigation.
            // Let's check nav_graph.xml again.
            // It has action_ReflectorFragment_to_RoleSelectionFragment.
            // DeviceControlFragment is a destination.
            // We can navigate to it directly by ID if we don't have an action, but safe args/actions are better.
            Navigation.findNavController(getView()).navigate(R.id.action_ReflectorFragment_to_DeviceControlFragment, bundle);
        });

        mLayoutConnectedDevices.addView(rowView);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mBleConnectionViewModel.checkstopadvertiser();
    }
}
