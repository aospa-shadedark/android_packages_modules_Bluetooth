/*
 * Copyright 2022 The Android Open Source Project
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

#include "btif/include/btif_hh.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <future>
#include <thread>
#include <vector>

#include "stack/l2cap/internal/l2c_api.h"
#include "bta/ag/bta_ag_int.h"
#include "bta/include/bta_ag_api.h"
#include "bta/include/bta_hh_api.h"
#include "btcore/include/module.h"
#include "btif_status.h"
#include "include/hardware/bt_hh.h"
#include "stack/include/gatt_api.h"
#include "test/common/core_interface.h"
#include "test/common/mock_functions.h"

using namespace std::chrono_literals;

void gatt_set_debug_conn_state_cb(void (*)(const RawAddress&, bool, const tGATT_DISCONN_REASON)) {}

namespace bluetooth::testing {
void set_hal_cbacks(bt_callbacks_t* callbacks);
}  // namespace bluetooth::testing

bool bta_ag_is_call_present(const RawAddress* peer_addr) { return true; }
tBTA_AG_SCB* bta_ag_scb_by_idx(uint16_t idx) {return nullptr;}
bool bta_ag_inband_enabled(tBTA_AG_SCB* p_scb) {return true;}

bool L2CA_Echo(const RawAddress& p_bd_addr, BT_HDR* p_data,
               tL2CA_ECHO_DATA_CB* p_callback) { return true; }
bool L2CA_Ping(const RawAddress& p_bd_addr,
               tL2CA_ECHO_RSP_CB* p_callback) { return true; }

// Used the legacy stack manager
module_t gd_shim_module;
module_t osi_module;

const tBTA_AG_RES_DATA tBTA_AG_RES_DATA::kEmpty = {};

namespace bluetooth {
namespace legacy {
namespace testing {

void bte_hh_evt(tBTA_HH_EVT event, tBTA_HH* p_data);

}  // namespace testing
}  // namespace legacy
}  // namespace bluetooth

namespace test {
namespace mock {
extern bool bluetooth_shim_is_gd_stack_started_up;
}
}  // namespace test

namespace {
std::array<uint8_t, 32> data32 = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b,
        0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16,
        0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20,
};

const RawAddress kDeviceAddress("11:22:33:44:55:66");
const RawAddress kDeviceAddressConnecting("66:55:44:33:22:11");
const uint16_t kHhHandle = 123;
const tBLE_ADDR_TYPE kDeviceAddrType = BLE_ADDR_PUBLIC;
const tBT_TRANSPORT kDeviceTransport = BT_TRANSPORT_AUTO;
const AclLinkSpec kDeviceConnecting = {.addrt.type = kDeviceAddrType,
                                       .addrt.bda = kDeviceAddressConnecting,
                                       .transport = kDeviceTransport};

// CR 4611672 repro fixture — HOGP (LE HID over GATT) device
const RawAddress kHogpDeviceAddress("aa:bb:cc:dd:1e:2f");
const AclLinkSpec kHogpDeviceLinkSpec = {.addrt.type = BLE_ADDR_PUBLIC,
                                         .addrt.bda = kHogpDeviceAddress,
                                         .transport = BT_TRANSPORT_LE};
// Callback parameters grouped into a structure
struct get_report_cb_t {
  RawAddress raw_address;
  bthh_status_t status;
  std::vector<uint8_t> data;
} get_report_cb_;

struct connection_state_cb_t {
  RawAddress raw_address;
  bthh_connection_state_t state;
};

// Globals allow usage within function pointers
std::promise<bt_cb_thread_evt> g_thread_evt_promise;
std::promise<BtStatus> g_status_promise;
std::promise<get_report_cb_t> g_bthh_callbacks_get_report_promise;
std::promise<connection_state_cb_t> g_bthh_connection_state_promise;

}  // namespace

bt_callbacks_t bt_callbacks = {
        .size = sizeof(bt_callbacks_t),
        .adapter_state_changed_cb = nullptr,     // adapter_state_changed_callback
        .adapter_properties_cb = nullptr,        // adapter_properties_callback
        .remote_device_properties_cb = nullptr,  // remote_device_properties_callback
        .device_found_cb = nullptr,              // device_found_callback
        .discovery_state_changed_cb = nullptr,   // discovery_state_changed_callback
        .pin_request_cb = nullptr,               // pin_request_callback
        .ssp_request_cb = nullptr,               // ssp_request_callback
        .bond_state_changed_cb = nullptr,        // bond_state_changed_callback
        .address_consolidate_cb = nullptr,       // address_consolidate_callback
        .le_address_associate_cb = nullptr,      // le_address_associate_callback
        .acl_state_changed_cb = nullptr,         // acl_state_changed_callback
        .thread_evt_cb = nullptr,                // callback_thread_event
        .dut_mode_recv_cb = nullptr,             // dut_mode_recv_callback
        .le_test_mode_cb = nullptr,              // le_test_mode_callback
        .energy_info_cb = nullptr,               // energy_info_callback
        .link_quality_report_cb = nullptr,       // link_quality_report_callback
        .generate_local_oob_data_cb = nullptr,   // generate_local_oob_data_callback
        .switch_buffer_size_cb = nullptr,        // switch_buffer_size_callback
        .switch_codec_cb = nullptr,              // switch_codec_callback
        .le_rand_cb = nullptr,                   // le_rand_callback
};

bthh_callbacks_t bthh_callbacks = {
        .size = sizeof(bthh_callbacks_t),
        .connection_state_cb = nullptr,  // bthh_connection_state_callback
        .hid_info_cb = nullptr,          // bthh_hid_info_callback
        .protocol_mode_cb = nullptr,     // bthh_protocol_mode_callback
        .idle_time_cb = nullptr,         // bthh_idle_time_callback
        .get_report_cb = nullptr,        // bthh_get_report_callback
        .virtual_unplug_cb = nullptr,    // bthh_virtual_unplug_callback
        .handshake_cb = nullptr,         // bthh_handshake_callback
};

class BtifHhWithMockTest : public ::testing::Test {
protected:
  void SetUp() override { reset_mock_function_count_map(); }

  void TearDown() override {}
};

class BtifHhWithHalCallbacksTest : public BtifHhWithMockTest {
protected:
  void SetUp() override {
    BtifHhWithMockTest::SetUp();
    g_thread_evt_promise = std::promise<bt_cb_thread_evt>();
    auto future = g_thread_evt_promise.get_future();
    bt_callbacks.thread_evt_cb = [](bt_cb_thread_evt evt) { g_thread_evt_promise.set_value(evt); };
    bluetooth::testing::set_hal_cbacks(&bt_callbacks);
    // Start the jni callback thread
    InitializeCoreInterface();
    ASSERT_EQ(std::future_status::ready, future.wait_for(2s));
    ASSERT_EQ(ASSOCIATE_JVM, future.get());

    bt_callbacks.thread_evt_cb = [](bt_cb_thread_evt /* evt */) {};
  }

  void TearDown() override {
    g_thread_evt_promise = std::promise<bt_cb_thread_evt>();
    auto future = g_thread_evt_promise.get_future();
    bt_callbacks.thread_evt_cb = [](bt_cb_thread_evt evt) { g_thread_evt_promise.set_value(evt); };
    CleanCoreInterface();
    ASSERT_EQ(std::future_status::ready, future.wait_for(2s));
    ASSERT_EQ(DISASSOCIATE_JVM, future.get());

    bt_callbacks.thread_evt_cb = [](bt_cb_thread_evt /* evt */) {};
    BtifHhWithMockTest::TearDown();
  }
};

class BtifHhAdapterReady : public BtifHhWithHalCallbacksTest {
protected:
  void SetUp() override {
    BtifHhWithHalCallbacksTest::SetUp();
    test::mock::bluetooth_shim_is_gd_stack_started_up = true;
    ASSERT_EQ(BtifStatus(), btif_hh_get_interface()->init(&bthh_callbacks));
  }

  void TearDown() override {
    test::mock::bluetooth_shim_is_gd_stack_started_up = false;
    BtifHhWithHalCallbacksTest::TearDown();
  }
};

class BtifHhWithDevice : public BtifHhAdapterReady {
protected:
  void SetUp() override {
    BtifHhAdapterReady::SetUp();

    // Short circuit a connected device
    btif_hh_cb.devices[0].link_spec.addrt.bda = kDeviceAddress;
    btif_hh_cb.devices[0].link_spec.addrt.type = kDeviceAddrType;
    btif_hh_cb.devices[0].link_spec.transport = kDeviceTransport;
    btif_hh_cb.devices[0].state = BTHH_CONN_STATE_CONNECTED;
    btif_hh_cb.devices[0].dev_handle = kHhHandle;
  }

  void TearDown() override { BtifHhAdapterReady::TearDown(); }
};

TEST_F(BtifHhAdapterReady, lifecycle) {}

static uint8_t report_data[sizeof(BT_HDR) + data32.size()];

TEST_F(BtifHhWithDevice, BTA_HH_GET_RPT_EVT) {
  tBTA_HH data = {
          .hs_data =
                  {
                          .status = BTHH_OK,
                          .handle = kHhHandle,
                          .rsp_data =
                                  {
                                          .p_rpt_data = reinterpret_cast<BT_HDR*>(report_data),
                                  },
                  },
  };

  // Fill out the deep copy data
  data.hs_data.rsp_data.p_rpt_data->len = static_cast<uint16_t>(data32.size());
  std::copy(data32.begin(), data32.begin() + data32.size(),
            reinterpret_cast<uint8_t*>(data.hs_data.rsp_data.p_rpt_data + 1));

  g_bthh_callbacks_get_report_promise = std::promise<get_report_cb_t>();
  auto future = g_bthh_callbacks_get_report_promise.get_future();
  bthh_callbacks.get_report_cb = [](RawAddress bd_addr, tBLE_ADDR_TYPE /* addr_type */,
                                    tBT_TRANSPORT /* transport */, bthh_status_t hh_status,
                                    uint8_t* rpt_data, int rpt_size) {
    get_report_cb_t report = {
            .raw_address = bd_addr,
            .status = hh_status,
            .data = std::vector<uint8_t>(),
    };
    report.data.assign(rpt_data, rpt_data + rpt_size),
            g_bthh_callbacks_get_report_promise.set_value(report);
  };

  bluetooth::legacy::testing::bte_hh_evt(BTA_HH_GET_RPT_EVT, &data);

  ASSERT_EQ(std::future_status::ready, future.wait_for(2s));
  auto report = future.get();

  // Verify data was delivered
  ASSERT_STREQ(kDeviceAddress.ToString().c_str(), report.raw_address.ToString().c_str());
  ASSERT_EQ(BTHH_OK, report.status);
  int i = 0;
  for (const auto& data : data32) {
    ASSERT_EQ(data, report.data[i++]);
  }
}

class BtifHHVirtualUnplugTest : public BtifHhAdapterReady {
protected:
  void SetUp() override {
    BtifHhAdapterReady::SetUp();
    bthh_callbacks.connection_state_cb =
            [](RawAddress bd_addr, tBLE_ADDR_TYPE /* addr_type */, tBT_TRANSPORT /* transport */,
               bthh_connection_state_t state, bthh_status_t /* hh_status */) {
              connection_state_cb_t connection_state = {
                      .raw_address = bd_addr,
                      .state = state,
              };
              g_bthh_connection_state_promise.set_value(connection_state);
            };
  }

  void TearDown() override {
    bthh_callbacks.connection_state_cb =
            [](RawAddress /* bd_addr */, tBLE_ADDR_TYPE /* addr_type */,
               tBT_TRANSPORT /* transport */, bthh_connection_state_t /* state */,
               bthh_status_t /* hh_status */) {};
    BtifHhAdapterReady::TearDown();
  }
};

TEST_F(BtifHHVirtualUnplugTest, test_btif_hh_virtual_unplug_device_not_open) {
  g_bthh_connection_state_promise = std::promise<connection_state_cb_t>();

  auto future = g_bthh_connection_state_promise.get_future();

  /* Make device in connecting state */
  ASSERT_EQ(btif_hh_connect(kDeviceConnecting, true), BtifStatus());

  ASSERT_EQ(std::future_status::ready, future.wait_for(2s));

  auto res = future.get();
  ASSERT_STREQ(kDeviceAddressConnecting.ToString().c_str(), res.raw_address.ToString().c_str());
  ASSERT_EQ(BTHH_CONN_STATE_CONNECTING, res.state);

  g_bthh_connection_state_promise = std::promise<connection_state_cb_t>();
  future = g_bthh_connection_state_promise.get_future();
  btif_hh_virtual_unplug(kDeviceConnecting);

  ASSERT_EQ(std::future_status::ready, future.wait_for(2s));

  // Verify data was delivered
  res = future.get();
  ASSERT_STREQ(kDeviceAddressConnecting.ToString().c_str(), res.raw_address.ToString().c_str());
  ASSERT_EQ(BTHH_CONN_STATE_DISCONNECTED, res.state);
}

// Repro fixture for CR 4611672 — [vivo][Android 17] DUT unable to reconnect to a Razer
// BLE mouse (HOGP / HID over GATT). Root cause: hh_open_handler() unconditionally and
// synchronously re-issues BTA_HhOpen() on every failed LE HOGP open completion
// (BTHH_ERR), with no backoff/dedup guard, racing connection_manager bookkeeping and
// producing an unbounded host-side retry loop (~900 iterations in ~150ms observed in the
// field; btsnoop confirmed zero HCI traffic to the controller during the loop).
class BtifHhHogpReconnectStormTest : public BtifHhAdapterReady {
protected:
  void SetUp() override {
    BtifHhAdapterReady::SetUp();
    bthh_callbacks.connection_state_cb =
            [](RawAddress bd_addr, tBLE_ADDR_TYPE /* addr_type */, tBT_TRANSPORT /* transport */,
               bthh_connection_state_t state, bthh_status_t /* hh_status */) {
              connection_state_cb_t connection_state = {
                      .raw_address = bd_addr,
                      .state = state,
              };
              g_bthh_connection_state_promise.set_value(connection_state);
            };

    // Seed a bonded HOGP device with reconnection enabled, matching the Razer mouse in
    // CR 4611672 — this is what makes hh_open_handler() eligible to resume the background
    // connection on every failed open completion for this device. This call synchronously
    // invokes BTHH_STATE_UPDATE (BTHH_CONN_STATE_ACCEPTING), so arm the promise first.
    g_bthh_connection_state_promise = std::promise<connection_state_cb_t>();
    auto future = g_bthh_connection_state_promise.get_future();

    tBTA_HH_DEV_DSCP_INFO dscp_info = {};
    btif_hh_load_bonded_dev(kHogpDeviceLinkSpec, /* attr_mask */ 0, /* sub_class */ 0,
                            /* app_id */ 0, dscp_info, /* reconnect_allowed */ true);

    ASSERT_EQ(std::future_status::ready, future.wait_for(2s));
    auto res = future.get();
    ASSERT_EQ(BTHH_CONN_STATE_ACCEPTING, res.state);

    // Discard any BTA_HhOpen() call issued by the load above so the test below measures
    // only the retry storm triggered by the failure burst.
    reset_mock_function_count_map();
  }

  void TearDown() override {
    bthh_callbacks.connection_state_cb =
            [](RawAddress /* bd_addr */, tBLE_ADDR_TYPE /* addr_type */,
               tBT_TRANSPORT /* transport */, bthh_connection_state_t /* state */,
               bthh_status_t /* hh_status */) {};
    BtifHhAdapterReady::TearDown();
  }
};

TEST_F(BtifHhHogpReconnectStormTest, cr_4611672_hogp_open_failure_does_not_retry_unbounded) {
  // Simulate a burst of consecutive failed LE HOGP open completions for the same device —
  // this is exactly what the field logs showed: bta_gattc_conn_cback() returning
  // GATT_ERROR, flowing through BTA_HH_SDP_CMPL_EVT/BTHH_ERR, into hh_open_handler().
  // Each iteration below is one full failure-completion round trip; production shows
  // hundreds of these firing back-to-back with zero delay.
  constexpr int kFailureBurstSize = 20;

  tBTA_HH data = {
          .conn =
                  {
                          .link_spec = kHogpDeviceLinkSpec,
                          .status = BTHH_ERR,
                          .handle = BTA_HH_INVALID_HANDLE,
                  },
  };

  for (int i = 0; i < kFailureBurstSize; i++) {
    g_bthh_connection_state_promise = std::promise<connection_state_cb_t>();
    auto future = g_bthh_connection_state_promise.get_future();

    bluetooth::legacy::testing::bte_hh_evt(BTA_HH_OPEN_EVT, &data);

    ASSERT_EQ(std::future_status::ready, future.wait_for(2s))
            << "hh_open_handler did not complete failure iteration " << i;
    auto res = future.get();
    ASSERT_STREQ(kHogpDeviceAddress.ToString().c_str(), res.raw_address.ToString().c_str());
    ASSERT_EQ(BTHH_CONN_STATE_DISCONNECTED, res.state);
  }

  // Pre-fix: hh_open_handler() re-issued BTA_HhOpen() unconditionally on every failed
  // completion -- call count would equal kFailureBurstSize, reproducing CR 4611672.
  // Post-fix: the alarm-based guard schedules the timer on the first failure and
  // suppresses all subsequent retries from within the synchronous callback.  Because
  // the mloop timer does NOT fire during this synchronous test loop (no real mloop
  // thread is pumped), zero BTA_HhOpen() calls should be observed here; the single
  // deferred retry will fire later when the alarm fires on the main loop.
  int open_calls = get_func_call_count("BTA_HhOpen");
  EXPECT_EQ(0, open_calls)
          << "BTA_HhOpen() was called " << open_calls << " times synchronously for "
          << kFailureBurstSize << " consecutive failed HOGP open completions -- "
          << "hh_open_handler() is retrying without the alarm-based dedup guard "
          << "(CR 4611672 repro).";
}

// btif_hh_acl_disconnected() has its own, independent unconditional-retry path
// (distinct from hh_open_handler()'s BTHH_ERR path above): every LE ACL disconnect
// for a bonded, reconnect_allowed device unconditionally and synchronously called
// BTA_HhOpen() to "Rearm HoGP reconnection", with no dedup against a retry already
// in flight. A tight sequence of ACL disconnects (e.g. a flaky link repeatedly
// connecting and immediately dropping) reproduces the identical unbounded
// host-side retry-storm mechanism as CR 4611672, just triggered from the
// disconnect path instead of the open-failure path.
class BtifHhHogpAclDisconnectStormTest : public BtifHhHogpReconnectStormTest {};

TEST_F(BtifHhHogpAclDisconnectStormTest, cr_4611672_acl_disconnect_does_not_retry_unbounded) {
  // Simulate a burst of consecutive LE ACL disconnects for the same bonded device --
  // each call is what btm_acl.cc invokes on link loss for a device with an active
  // background HOGP reconnect policy.
  constexpr int kDisconnectBurstSize = 20;

  for (int i = 0; i < kDisconnectBurstSize; i++) {
    btif_hh_acl_disconnected(kHogpDeviceAddress, BT_TRANSPORT_LE);
  }

  // Pre-fix: btif_hh_acl_disconnected() re-issued BTA_HhOpen() unconditionally on every
  // disconnect -- call count would equal kDisconnectBurstSize.
  // Post-fix: the same alarm-based dedup guard hh_open_handler() uses schedules the
  // timer on the first disconnect and suppresses all subsequent retries from within
  // this synchronous loop. Because the mloop timer does NOT fire during this
  // synchronous test loop, zero BTA_HhOpen() calls should be observed here.
  int open_calls = get_func_call_count("BTA_HhOpen");
  EXPECT_EQ(0, open_calls)
          << "BTA_HhOpen() was called " << open_calls << " times synchronously for "
          << kDisconnectBurstSize << " consecutive LE ACL disconnects -- "
          << "btif_hh_acl_disconnected() is rearming without the alarm-based dedup "
          << "guard (CR 4611672 repro, ACL-disconnect path).";
}
