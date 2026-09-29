/*
 * Copyright (C) 2016 The Linux Foundation. All rights reserved
 * Not a Contribution.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted (subject to the limitations in the
 * disclaimer below) provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *
 * * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE
 * GRANTED BY THIS LICENSE. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT
 * HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

/*
 *  Copyright (C) 2009-2012 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

/******************************************************************************
 * Changes from Qualcomm Innovation Center are provided under the following
 * license:
 *
 * Copyright (c) 2024 Qualcomm Innovation Center, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 *
 ******************************************************************************/

/************************************************************************************
 *
 *  Filename:      btif_vendor.cc
 *
 *  Description:   Vendor Bluetooth Interface
 *
 *
 ***********************************************************************************/

#if TEST_APP_INTERFACE == TRUE
#include <hardware/bt_vendor.h>
#include <stdlib.h>
#include <string.h>

#include <vector>

#undef LOG_TAG
#define LOG_TAG "bt_btif_vendor"

#include <base/bind.h>
#include <base/callback.h>
#include <base/location.h>
#include <bluetooth/log.h>
#include <cutils/properties.h>

#include "btif_api.h"
#include "btif_common.h"
#include "btif_vendor.h"
#include "osi/include/allocator.h"
#include "osi/include/osi.h"
#include "osi/include/properties.h"
#include "stack/btm/internal/btm_api.h"
#include "stack/include/btm_client_interface.h"
#if TEST_APP_INTERFACE == TRUE
#include <bt_testapp.h>
#include "stack/include/hcimsgs.h"
#include "main/shim/entry.h"
#include "hci/hci_layer.h"
#include "os/handler.h"
#include "stack/include/btm_ble_api.h"
#include "stack/include/stack_le_connection.h"
#include "bluetooth/types/address.h"
#include "gd/hci/controller.h"
#include <array>
#endif
using namespace bluetooth;
using namespace bluetooth::hci;
using namespace bluetooth::os;

extern bool interface_ready(void);
#define SOC_NAME_MAX_SIZE 15

extern const btl2cap_interface_t* btif_l2cap_get_interface(void);
extern const btgatt_test_interface_t* btif_gatt_test_get_interface(void);
extern const btsmp_interface_t* btif_smp_get_interface(void);
extern const btgap_interface_t* btif_gap_get_interface(void);
#endif

btvendor_callbacks_t* bt_vendor_callbacks = NULL;

/*******************************************************************************
** VENDOR INTERFACE FUNCTIONS
*******************************************************************************/

/*******************************************************************************
**
** Function         btif_vendor_init
**
** Description     initializes the vendor interface
**
** Returns         bt_status_t
**
*******************************************************************************/
static bt_status_t init(btvendor_callbacks_t* callbacks) {
  bt_vendor_callbacks = callbacks;
  char socName[SOC_NAME_MAX_SIZE];
  osi_property_get("persist.vendor.qcom.bluetooth.soc", socName, "");
  if (!strcmp(socName, "cherokee")) {
    // when socName is Cherokee
    osi_property_set("persist.bluetooth.asha.enabled", "false");
  } else {
    osi_property_set("persist.bluetooth.asha.enabled", "true");
  }
  log::info("init done");
  return BT_STATUS_SUCCESS;
}

void btif_vendor_update_add_on_features_to_jni() {
  uint8_t soc_add_on_features_len = 0;
  uint8_t host_add_on_features_len = 0;
  bt_vendor_property_t vnd_prop;
  char s_buf[SOC_ADD_ON_FEATURES_MAX_SIZE];
  char h_buf[HOST_ADD_ON_FEATURES_MAX_SIZE];
  const bt_device_soc_add_on_features_t* soc_add_on_features =
          get_btm_client_interface().vendor.BTM_GetSocAddOnFeatures(&soc_add_on_features_len);
  const bt_device_host_add_on_features_t* host_add_on_features =
          get_btm_client_interface().vendor.BTM_GetHostAddOnFeatures(&host_add_on_features_len);

  if (soc_add_on_features && soc_add_on_features_len > 0) {
    vnd_prop.len = soc_add_on_features_len;
    vnd_prop.type = BT_VENDOR_PROPERTY_SOC_ADD_ON_FEATURES;
    vnd_prop.val = (void*)s_buf;
    memcpy(vnd_prop.val, soc_add_on_features, soc_add_on_features_len);

    HAL_CBACK(bt_vendor_callbacks, adapter_vendor_prop_cb, BT_STATUS_SUCCESS, 1, &vnd_prop);
  }

  if (host_add_on_features && host_add_on_features_len > 0) {
    vnd_prop.len = host_add_on_features_len;
    vnd_prop.type = BT_VENDOR_PROPERTY_HOST_ADD_ON_FEATURES;
    vnd_prop.val = (void*)h_buf;
    memcpy(vnd_prop.val, host_add_on_features, host_add_on_features_len);
    HAL_CBACK(bt_vendor_callbacks, adapter_vendor_prop_cb, BT_STATUS_SUCCESS, 1, &vnd_prop);
  }
}
void btif_vendor_update_add_on_features() {
  do_in_jni_thread(base::BindOnce(btif_vendor_update_add_on_features_to_jni));
}

void btif_vendor_update_ssr_event_to_jni() {
  HAL_CBACK(bt_vendor_callbacks, ssr_vendor_cb);
}

void btif_vendor_update_ssr_event() {
  do_in_jni_thread(base::BindOnce(btif_vendor_update_ssr_event_to_jni));
}
static void set_wifi_state(bool status) {
  log::info("setWifiState :{}", status);
}

static void set_Power_back_off_state(bool status) {
  log::info("setPowerBackOffState :{}", status);
  get_btm_client_interface().vendor.BTM_SetPowerBackOffState(status);
}

static void cleanup(void) {
  log::info("cleanup");
  if (bt_vendor_callbacks) {
    bt_vendor_callbacks = NULL;
  }
}

static void ble_start_enc_v2_wrapper(uint16_t handle, Octet8 rand,
                                     uint16_t ediv, Octet16 ltk,
                                     uint8_t hdt_mic_length,
                                     uint8_t enc_type) {
  btsnd_hcic_ble_start_enc_v2(handle, rand, ediv, ltk, hdt_mic_length, enc_type);
  log::info("Sent btsnd_hcic_ble_start_enc_v2 command from wrapper.");
}

static void le_set_hdt_default_parameters_wrapper(uint8_t preferred_mic_length,
                                                  uint8_t preferred_packet_format,
                                                  uint16_t preferred_acl_rates) {
  btsnd_hcic_le_set_hdt_default_parameters(preferred_mic_length, preferred_packet_format,
                                            preferred_acl_rates);
  log::info("Sent btsnd_hcic_le_set_hdt_default_parameters command from wrapper.");
}

static void le_set_data_length_wrapper(uint16_t handle,
                                                  uint16_t tx_pdu_length, uint16_t tx_time) { 
  btsnd_hcic_ble_set_data_length(handle, tx_pdu_length, tx_time);
  log::info("Sent btsnd_hcic_le_set_hdt_default_parameters command from wrapper.");
}

static void le_read_maximum_data_length_v2_complete_handler(CommandCompleteView view) {
  log::warn("Received command complete for LeReadMaximumDataLengthV2");
  auto le_maximum_data_length_v2_ = bluetooth::shim::GetController()->GetLeMaximumDataLengthV2();
  auto complete_view = LeReadMaximumDataLengthV2CompleteView::Create(view);
  if (complete_view.IsValid()) {
    log::info("LeReadMaximumDataLengthV2Builder command complete. Status: {}",
              ErrorCodeText(complete_view.GetStatus()));
    bluetooth::shim::GetController()->GetLeMaximumDataLengthV2() = complete_view.GetLeMaximumDataLengthV2();
  } else {
    log::error("LeReadMaximumDataLengthV2Builder command complete, but view is invalid.");
  }
}

static void le_read_maximum_data_length_v2_wrapper(uint8_t phy) {
  HciInterface* hci = bluetooth::shim::GetHciLayer();
  Handler* handler = bluetooth::shim::GetGdShimHandler();

  if (hci != nullptr && handler != nullptr) {
    std::unique_ptr<LeReadMaximumDataLengthV2Builder> command =
        LeReadMaximumDataLengthV2Builder::Create(phy);
    hci->EnqueueCommand(
        std::move(command),
        handler->BindOnce(le_read_maximum_data_length_v2_complete_handler));
    log::info("Enqueued LeReadMaximumDataLengthV2Builder command.");
  } else {
    log::error("Failed to get HciLayer or Handler for LeReadMaximumDataLengthV2Builder command.");
  }
}

static void ble_set_phy_wrapper(RawAddress address, uint16_t handle, uint8_t all_phys, uint8_t tx_phys,
                                uint8_t rx_phys, uint16_t phy_options) {

              
  stack::leConnectionSetPhy(address, tx_phys, rx_phys, phy_options);
  log::info("Sent BTM_BleSetPhy to wrapper.");
}

static void le_set_default_phy_wrapper(uint8_t all_phys, uint8_t tx_phys, uint8_t rx_phys) {
  btsnd_hci_ble_set_default_phy(all_phys, tx_phys, rx_phys);
  log::info("Sent btsnd_hci_ble_set_default_phy command from wrapper.");
}
static void refresh_enc_key_v2_wrapper(uint16_t handle, uint8_t hdt_mic_length) {
  btsnd_hcic_refresh_enc_key_v2(handle, hdt_mic_length);
  log::info("Sent btsnd_hcic_refresh_enc_key_v2 command from wrapper.");
}

static void le_set_data_length_v2_wrapper(uint16_t handle, uint16_t tx_pdu_length,
                                       uint16_t tx_time, uint8_t phys) { 
  btsnd_hcic_ble_set_data_length_v2(handle, tx_pdu_length, tx_time, phys);
  log::info("Sent btsnd_hcic_le_set_hdt_default_parameters command from wrapper.");
}

static void set_hdt_plus_features_enable_wrapper(uint16_t handle,
                                                  uint64_t hdt_plus_features,
                                                  uint8_t br_c_to_p, uint8_t br_p_to_c,
                                                  uint16_t rates_c_to_p,
                                                  uint16_t rates_p_to_c) {
  btsnd_hcic_set_hdt_plus_features_enable(handle, hdt_plus_features, br_c_to_p,
                                          br_p_to_c, rates_c_to_p, rates_p_to_c);
  log::info("Sent btsnd_hcic_set_hdt_plus_features_enable command from wrapper.");
}

static const bthci_test_interface_t bthciTestInterface = {
    sizeof(bthciTestInterface),
    ble_start_enc_v2_wrapper,
    le_set_hdt_default_parameters_wrapper,
    le_read_maximum_data_length_v2_wrapper,
    ble_set_phy_wrapper,
    le_set_data_length_wrapper,
    le_set_default_phy_wrapper,
    refresh_enc_key_v2_wrapper,
    le_set_data_length_v2_wrapper,
    set_hdt_plus_features_enable_wrapper,
};

const bthci_test_interface_t* btif_hci_test_get_interface(void) {
  return &bthciTestInterface;
}

/*******************************************************************************
**
** Function         get_testapp_interface
**
** Description      Get the Test interface
**
** Returns          btvendor_interface_t
**
*******************************************************************************/
#if TEST_APP_INTERFACE == TRUE
static const void* get_testapp_interface(int test_app_profile) {
  log::info("{} : ", test_app_profile);
  switch (test_app_profile) {
    case TEST_APP_L2CAP:
      return btif_l2cap_get_interface();
    case TEST_APP_GATT:
      return btif_gatt_test_get_interface();
    case TEST_APP_SMP:
      return btif_smp_get_interface();
    case TEST_APP_GAP:
      return btif_gap_get_interface();
    case TEST_APP_HCI:
      return btif_hci_test_get_interface();
    default:
      return NULL;
  }
  return NULL;
}
#endif

static const btvendor_interface_t btvendorInterface = {
    sizeof(btvendorInterface),
    init,
#if TEST_APP_INTERFACE == TRUE
    get_testapp_interface,
#else
    NULL,
#endif
    set_wifi_state,
    set_Power_back_off_state,
    cleanup,
};

/*******************************************************************************
** LOCAL FUNCTIONS
*******************************************************************************/

/*******************************************************************************
**
** Function         btif_vendor_get_interface
**
** Description      Get the vendor callback interface
**
** Returns          btvendor_interface_t
**
*******************************************************************************/
const btvendor_interface_t* btif_vendor_get_interface() {
  log::info("");
  return &btvendorInterface;
}
