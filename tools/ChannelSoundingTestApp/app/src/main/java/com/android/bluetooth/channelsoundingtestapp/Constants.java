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

import java.util.UUID;

abstract class Constants {
    static final UUID CS_TEST_SERVICE_UUID =
            UUID.fromString("f81d4fae-7ccc-eeee-a765-00aaaaaaaaaa");

    // Standard Bluetooth SIG Ranging Service UUID (0x185B), required in the Reflector's
    // advertising data per the Ranging Application Profile (RAP) so that RAP-compliant
    // Initiators can discover this device via a UUID-filtered scan.
    static final UUID RANGING_SERVICE_UUID =
            UUID.fromString("0000185B-0000-1000-8000-00805F9B34FB");

    enum GattState {
        DISCONNECTED,
        SCANNING,
        CONNECTED_DIRECT,
        CONNECTED_SCAN,
    }
}
