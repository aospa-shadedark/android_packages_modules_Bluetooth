/******************************************************************************
 *
 *  Copyright 2014 Google, Inc.
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

#define LOG_TAG "bt_stack_config"

#include "internal_include/stack_config.h"

#include <bluetooth/log.h>

#include "osi/include/config.h"
#include "osi/include/future.h"
#include "osi/include/properties.h"

using namespace bluetooth;

namespace {
const char* PTS_AVRCP_TEST = "PTS_AvrcpTest";
const char* PTS_SECURE_ONLY_MODE = "PTS_SecurePairOnly";
const char* PTS_LE_CONN_UPDATED_DISABLED = "PTS_DisableConnUpdates";
const char* PTS_DISABLE_SDP_LE_PAIR = "PTS_DisableSDPOnLEPair";
const char* PTS_SMP_PAIRING_OPTIONS_KEY = "PTS_SmpOptions";
const char* PTS_SMP_FAILURE_CASE_KEY = "PTS_SmpFailureCase";
const char* PTS_FORCE_EATT_FOR_NOTIFICATIONS = "PTS_ForceEattForNotifications";
const char* PTS_CONNECT_EATT_UNCONDITIONALLY = "PTS_ConnectEattUncondictionally";
const char* PTS_CONNECT_EATT_UNENCRYPTED = "PTS_ConnectEattUnencrypted";
const char* PTS_BROADCAST_UNENCRYPTED = "PTS_BroadcastUnencrypted";
const char* PTS_FORCE_LE_AUDIO_MULTIPLE_CONTEXTS_METADATA =
        "PTS_ForceLeAudioMultipleContextsMetadata";
const char* PTS_EATT_PERIPHERAL_COLLISION_SUPPORT = "PTS_EattPeripheralCollionSupport";
const char* PTS_EATT_USE_FOR_ALL_SERVICES = "PTS_UseEattForAllServices";
const char* PTS_L2CAP_ECOC_UPPER_TESTER = "PTS_L2capEcocUpperTester";
const char* PTS_L2CAP_ECOC_MIN_KEY_SIZE = "PTS_L2capEcocMinKeySize";
const char* PTS_L2CAP_ECOC_INITIAL_CHAN_CNT = "PTS_L2capEcocInitialChanCnt";
const char* PTS_RFCOMM_SEND_RLS = "PTS_RFCOMM_send_rls";
const char* PTS_REJ_WRITE_REQ = "PTS_BCS_Rej_Write_req";
const char* PTS_L2CAP_SILENT_DISCARD = "PTS_L2CAP_SilentDiscard";
const char* PTS_BCS_RANGING_SELECT = "PTS_BCS_RangingSelect";
const char* PTS_ENB_IND_RAS = "PTS_BCSenablingind";
const char* PTS_FOC_DATA_WRITE = "PTS_FOCdatawrite";
const char* PTS_L2CAP_ECOC_CONNECT_REMAINING = "PTS_L2capEcocConnectRemaining";
const char* PTS_L2CAP_ECOC_SEND_NUM_OF_SDU = "PTS_L2capEcocSendNumOfSdu";
const char* PTS_L2CAP_ECOC_RECONFIGURE = "PTS_L2capEcocReconfigure";
const char* PTS_BROADCAST_AUDIO_CONFIG_OPTION = "PTS_BroadcastAudioConfigOption";
const char* PTS_LE_AUDIO_SUSPEND_STREAMING = "PTS_LeAudioSuspendStreaming";
const char* PTS_GATT_SKIP_SERVICE_DISCOVERY_DURING_CONN =
        "PTS_SkipServiceDiscoveryDuringConnection";
const char* PTS_CONFIGURE_SERVICE_CHG_INDICATION = "PTS_ConfigureServiceChangeIndication";
const char* PTS_DB_OUT_OF_SYNC = "PTS_DBOutOfSync";
const char* PTS_GATT_READ_MULTIPLE_NOT_SUPPORTED_DURING_DISCOVERY =
        "PTS_GattReadMultipleNotSupportedDuringDiscovery";
const char* PTS_L2CAP_LE_MALFORMED_PDU = "PTS_L2capLeMalformedPdu";
const char* PTS_LE_DISABLE_ENCRYP = "PTS_LeDisableEncryp";
const char* PTS_L2CAP_LE_INSUFF_ENC = "PTS_L2capLeInsuffEnc";
const char* PTS_ENABLE_AUTHORIZATION_ENCR_DATA_KEY = "PTS_EnableAuthorizationEncrDataKey";
const char* PTS_GATT_DISABLE_CL_CACHING_BIT_RESET = "PTS_GattDisableClCachingBitReset";
const char* PTS_VAP_NOTIFY_CHARACTERISTICS = "PTS_VapNotifyCharacteristics";
const char* PTS_SMP_BREDR_PAIRING_PRESERVE_LE_KEYS = "PTS_SmpBredrPairingPreserveLeKeys";

static std::unique_ptr<config_t> config;
}  // namespace

// Module lifecycle functions

static future_t* init() {
// TODO(armansito): Find a better way than searching by a hardcoded path.
#if defined(TARGET_FLOSS)
  const char* path = "/etc/bluetooth/bt_stack.conf";
#elif defined(__ANDROID__)
  const char* path = "/apex/com.android.bt/etc/bluetooth/bt_stack.conf";
#else   // !defined(__ANDROID__)
  const char* path = "bt_stack.conf";
#endif  // defined(__ANDROID__)
  log::assert_that(path != NULL, "assert failed: path != NULL");

  bool running_pts = osi_property_get_bool("persist.bluetooth.pts.supported", false);

  if (!running_pts) {
    log::info("attempt to load stack conf from {}", path);

    config = config_new(path);
  } else {
    const char* pts_path = "/data/misc/bluedroid/bt_stack.conf";

    log::info("attempt to load stack conf from {}", pts_path);

    config = config_new(pts_path);
  }

  if (!config) {
    log::info("file >{}< not found", path);
    config = config_new_empty();
  }

  return future_new_immediate(FUTURE_SUCCESS);
}

static future_t* clean_up() {
  config.reset();
  return future_new_immediate(FUTURE_SUCCESS);
}

EXPORT_SYMBOL extern const module_t stack_config_module = {.name = STACK_CONFIG_MODULE,
                                                           .init = init,
                                                           .start_up = NULL,
                                                           .shut_down = NULL,
                                                           .clean_up = clean_up,
                                                           .dependencies = {NULL}};

// Interface functions
static bool get_pts_avrcp_test(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_AVRCP_TEST, false);
}

static bool get_pts_secure_only_mode(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_SECURE_ONLY_MODE, false);
}

static bool get_pts_conn_updates_disabled(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_LE_CONN_UPDATED_DISABLED, false);
}

static bool get_pts_crosskey_sdp_disable(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_DISABLE_SDP_LE_PAIR, false);
}

static const std::string* get_pts_smp_options(void) {
  return config_get_string(*config, CONFIG_DEFAULT_SECTION, PTS_SMP_PAIRING_OPTIONS_KEY, NULL);
}

static int get_pts_smp_failure_case(void) {
  return config_get_int(*config, CONFIG_DEFAULT_SECTION, PTS_SMP_FAILURE_CASE_KEY, 0);
}

static bool get_pts_force_eatt_for_notifications(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_FORCE_EATT_FOR_NOTIFICATIONS, false);
}

static bool get_pts_connect_eatt_unconditionally(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_CONNECT_EATT_UNCONDITIONALLY, false);
}

static bool get_pts_connect_eatt_before_encryption(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_CONNECT_EATT_UNENCRYPTED, false);
}

static bool get_pts_unencrypt_broadcast(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_BROADCAST_UNENCRYPTED, false);
}

static bool get_pts_eatt_peripheral_collision_support(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_EATT_PERIPHERAL_COLLISION_SUPPORT,
                         false);
}

static bool get_pts_use_eatt_for_all_services(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_EATT_USE_FOR_ALL_SERVICES, false);
}

static bool get_pts_force_le_audio_multiple_contexts_metadata(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION,
                         PTS_FORCE_LE_AUDIO_MULTIPLE_CONTEXTS_METADATA, false);
}

static bool get_pts_l2cap_ecoc_upper_tester(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_L2CAP_ECOC_UPPER_TESTER, false);
}

static int get_pts_l2cap_ecoc_min_key_size(void) {
  return config_get_int(*config, CONFIG_DEFAULT_SECTION, PTS_L2CAP_ECOC_MIN_KEY_SIZE, -1);
}

static int get_pts_l2cap_ecoc_initial_chan_cnt(void) {
  return config_get_int(*config, CONFIG_DEFAULT_SECTION, PTS_L2CAP_ECOC_INITIAL_CHAN_CNT, -1);
}

static bool get_pts_l2cap_ecoc_connect_remaining(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_L2CAP_ECOC_CONNECT_REMAINING, false);
}

static bool get_pts_rfcomm_rls_check(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_RFCOMM_SEND_RLS, false);
}

static bool get_pts_bcs_rej_write_req(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_REJ_WRITE_REQ, false);
}

static bool get_pts_l2cap_silent_discard(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_L2CAP_SILENT_DISCARD, false);
}

static bool get_pts_bcs_ranging_select(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_BCS_RANGING_SELECT, false);
}

static bool get_pts_foc_data_write(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_FOC_DATA_WRITE, false);
}

static bool get_enable_ind_ras_real_time(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_ENB_IND_RAS, false);
}

static int get_pts_l2cap_ecoc_send_num_of_sdu(void) {
  return config_get_int(*config, CONFIG_DEFAULT_SECTION, PTS_L2CAP_ECOC_SEND_NUM_OF_SDU, -1);
}

static bool get_pts_l2cap_ecoc_reconfigure(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_L2CAP_ECOC_RECONFIGURE, false);
}

static const std::string* get_pts_broadcast_audio_config_options(void) {
  if (!config) {
    log::info("Config isn't ready, use default option");
    return NULL;
  }
  return config_get_string(*config, CONFIG_DEFAULT_SECTION, PTS_BROADCAST_AUDIO_CONFIG_OPTION,
                           NULL);
}

static bool get_pts_le_audio_disable_ases_before_stopping(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_LE_AUDIO_SUSPEND_STREAMING, false);
}

static bool get_pts_configure_svc_chg_indication(void) {
  // This function determines whether to use the service changed CCCD value
  // when sending service changed indications. When enabled, the system will
  // check the CCCD value before sending indications.
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_CONFIGURE_SERVICE_CHG_INDICATION, false);
}

static bool get_pts_DB_out_of_sync(void){
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_DB_OUT_OF_SYNC, false);
 }

static bool get_pts_gatt_read_multiple_not_supported_during_discovery(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION,
                         PTS_GATT_READ_MULTIPLE_NOT_SUPPORTED_DURING_DISCOVERY, false);
}

static bool get_pts_gatt_skip_service_discovery(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_GATT_SKIP_SERVICE_DISCOVERY_DURING_CONN, false);
}

static bool get_pts_l2cap_le_malformed_pdu(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_L2CAP_LE_MALFORMED_PDU, false);
}

static bool get_pts_le_disable_encryp(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_LE_DISABLE_ENCRYP, false);
}

static int get_pts_l2cap_le_insuff_enc(void) {
  return config_get_int(*config, CONFIG_DEFAULT_SECTION, PTS_L2CAP_LE_INSUFF_ENC, 0);
}

static bool get_pts_enable_authorization_encr_data_key(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_ENABLE_AUTHORIZATION_ENCR_DATA_KEY, false);
}

static bool get_pts_gatt_disable_cl_caching_bit_reset(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_GATT_DISABLE_CL_CACHING_BIT_RESET,
                         false);
}

static bool get_pts_vap_notify_characteristics(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_VAP_NOTIFY_CHARACTERISTICS, false);
}

static bool get_pts_smp_bredr_pairing_preserve_le_keys(void) {
  return config_get_bool(*config, CONFIG_DEFAULT_SECTION, PTS_SMP_BREDR_PAIRING_PRESERVE_LE_KEYS, false);
}

const stack_config_t interface = {get_pts_avrcp_test,
                                  get_pts_secure_only_mode,
                                  get_pts_conn_updates_disabled,
                                  get_pts_crosskey_sdp_disable,
                                  get_pts_smp_options,
                                  get_pts_smp_failure_case,
                                  get_pts_force_eatt_for_notifications,
                                  get_pts_connect_eatt_unconditionally,
                                  get_pts_connect_eatt_before_encryption,
                                  get_pts_unencrypt_broadcast,
                                  get_pts_eatt_peripheral_collision_support,
                                  get_pts_use_eatt_for_all_services,
                                  get_pts_force_le_audio_multiple_contexts_metadata,
                                  get_pts_l2cap_ecoc_upper_tester,
                                  get_pts_l2cap_ecoc_min_key_size,
                                  get_pts_l2cap_ecoc_initial_chan_cnt,
                                  get_pts_l2cap_ecoc_connect_remaining,
                                  get_pts_rfcomm_rls_check,
                                  get_pts_bcs_rej_write_req,
                                  get_pts_l2cap_silent_discard,
                                  get_pts_bcs_ranging_select,
                                  get_enable_ind_ras_real_time,
                                  get_pts_foc_data_write,
                                  get_pts_l2cap_ecoc_send_num_of_sdu,
                                  get_pts_l2cap_ecoc_reconfigure,
                                  get_pts_broadcast_audio_config_options,
                                  get_pts_le_audio_disable_ases_before_stopping,
                                  get_pts_gatt_skip_service_discovery,
                                  get_pts_configure_svc_chg_indication,
                                  get_pts_DB_out_of_sync,
                                  get_pts_gatt_read_multiple_not_supported_during_discovery,
                                  get_pts_l2cap_le_malformed_pdu,
                                  get_pts_le_disable_encryp,
                                  get_pts_l2cap_le_insuff_enc,
                                  get_pts_enable_authorization_encr_data_key,
                                  get_pts_gatt_disable_cl_caching_bit_reset,
                                  get_pts_vap_notify_characteristics,
                                  get_pts_smp_bredr_pairing_preserve_le_keys};

const stack_config_t* stack_config_get_interface(void) { return &interface; }
