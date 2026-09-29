/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *        * Redistributions of source code must retain the above copyright
 *          notice, this list of conditions and the following disclaimer.
 *        * Redistributions in binary form must reproduce the above copyright
 *            notice, this list of conditions and the following disclaimer in
 * the documentation and/or other materials provided with the distribution.
 *        * Neither the name of The Linux Foundation nor
 *            the names of its contributors may be used to endorse or promote
 *            products derived from this software without specific prior written
 *            permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.    IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Changes from Qualcomm Innovation Center are provided under the following
 * license:
 *
 * Copyright (c) 2024 Qualcomm Innovation Center, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 *
 ******************************************************************************/

#ifdef TEST_APP_INTERFACE
#ifndef ANDROID_INCLUDE_BT_TESTAPP_H
#define ANDROID_INCLUDE_BT_TESTAPP_H
#include <android/log.h>
#include <arpa/inet.h>
#include <ctype.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <hardware/bluetooth.h>
#include <hardware/hardware.h>
#include <linux/capability.h>
#include <netdb.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <unistd.h>

#include "stack/include/bt_types.h"
#include "stack/btm/internal/btm_api.h"
#include "stack/include/gap_api.h"
#include "stack/include/gatt_api.h"
#include "stack/l2cap/internal/l2c_api.h"
#include "stack/include/smp_api_types.h"
#include "stack/include/smp_status.h"
#include "stack/include/stack_app.h"

__BEGIN_DECLS

typedef void(tREMOTE_DEVICE_NAME_CB)(void* p1);

enum { SUCCESS, FAIL };
static bool pts_send_rr_s_frame = false;
static bool send_l2ca_conn_rsp_with_sec_block = false;
typedef enum {
  TEST_APP_L2CAP,
  TEST_APP_GATT,
  TEST_APP_GAP,
  TEST_APP_SMP,
  TEST_APP_HCI
} test_app_profile;

typedef struct {
  size_t size;
  void (*ble_start_enc_v2)(uint16_t handle, Octet8 rand, uint16_t ediv,
                           Octet16 ltk, uint8_t hdt_mic_length,
                           uint8_t enc_type);
  void (*le_set_hdt_default_parameters)(uint8_t preferred_mic_length,
                                        uint8_t preferred_packet_format,
                                        uint16_t preferred_acl_rates);
  void (*le_read_maximum_data_length_v2)(uint8_t phy);
  void (*ble_set_phy)(RawAddress address ,uint16_t handle, uint8_t all_phys, uint8_t tx_phys,
                      uint8_t rx_phys, uint16_t phy_options);
  void (*ble_set_data_length)(uint16_t handle, uint16_t tx_pdu_len, uint16_t tx_time);
  void (*ble_set_default_phy)(uint8_t all_phys, uint8_t tx_phys, uint8_t rx_phys);
  void (*refresh_enc_key_v2)(uint16_t handle, uint8_t hdt_mic_length);
  void (*ble_set_data_length_v2)(uint16_t handle, uint16_t tx_pdu_len, uint16_t tx_time,
                                uint8_t phys);
  void (*set_hdt_plus_features_enable)(uint16_t handle, uint64_t hdt_plus_features,
                                               uint8_t br_c_to_p, uint8_t br_p_to_c,
                                               uint16_t rates_c_to_p, uint16_t rates_p_to_c);
} bthci_test_interface_t;

typedef struct {
  /** set to sizeof(Btl2capInterface) */
  size_t size;
  /** Register the L2cap callbacks  */
  bt_status_t (*Init)(tL2CAP_APPL_INFO* callbacks);
  bt_status_t (*RegisterPsm)(uint16_t psm, bool conn_type,
                             tL2CAP_ERTM_INFO* p_ertm_info, uint16_t my_mtu,
                             uint16_t required_remote_mtu, uint16_t sec_level);
  bt_status_t (*Deregister)(uint16_t psm);
  uint16_t (*AllocatePsm)(void);
  uint16_t (*Connect)(uint16_t psm, RawAddress* bd_addr);
  bool (*ConnectRsp)(RawAddress p_bd_addr, uint8_t id, uint16_t lcid,
                     uint16_t result, uint16_t status);
  uint16_t (*ErtmConnectReq)(uint16_t psm, RawAddress p_bd_addr,
                             tL2CAP_ERTM_INFO* p_ertm_info);
  bool (*ErtmConnectRsp)(RawAddress p_bd_addr, uint8_t id, uint16_t lcid,
                         uint16_t result, uint16_t status,
                         tL2CAP_ERTM_INFO* p_ertm_info);
  bool (*ConfigReq)(uint16_t cid, tL2CAP_CFG_INFO* p_cfg);
  bool (*ConfigRsp)(uint16_t cid, tL2CAP_CFG_INFO* p_cfg);
  bool (*DisconnectReq)(uint16_t cid);
  bool (*DisconnectRsp)(uint16_t cid);
  uint8_t (*DataWrite)(uint16_t cid, char* p_data, uint32_t len);
  bool (*Ping)(RawAddress p_bd_addr, tL2CA_ECHO_RSP_CB* p_cb);
  bool (*Echo)(RawAddress p_bd_addr, BT_HDR *p_data, tL2CA_ECHO_DATA_CB *p_callback);
  bool (*SetIdleTimeout)(uint16_t cid, uint16_t timeout, bool is_global);
  bool (*SetIdleTimeoutByBdAddr)(RawAddress bd_addr, uint16_t timeout);
  uint8_t (*SetDesireRole)(uint8_t new_role);
  void (*SetSecConnOnlyMode)(bool secvalue);
  uint16_t (*LocalLoopbackReq)(uint16_t psm, uint16_t handle,
                               RawAddress p_bd_addr);
  uint16_t (*FlushChannel)(uint16_t lcid, uint16_t num_to_flush);
  bool (*SetAclPriority)(RawAddress bd_addr, uint8_t priority);
  bool (*FlowControl)(uint16_t cid, bool data_enabled);
  bool (*SendTestSFrame)(uint16_t cid, bool rr_or_rej, uint8_t back_track);
  bool (*SetTxPriority)(uint16_t cid, tL2CAP_CHNL_PRIORITY priority);
  bool (*SetChnlDataRate)(uint16_t cid, tL2CAP_CHNL_DATA_RATE tx,
                          tL2CAP_CHNL_DATA_RATE rx);
  bool (*SetFlushTimeout)(RawAddress bd_addr, uint16_t flush_tout);
  uint8_t (*DataWriteEx)(uint16_t cid, BT_HDR* p_data, uint16_t flags);
  bool (*SetChnlFlushability)(uint16_t cid, bool is_flushable);
  bool (*GetPeerFeatures)(RawAddress bd_addr, uint32_t* p_ext_feat,
                          uint8_t* p_chnl_mask);
  bool (*GetBDAddrbyHandle)(uint16_t handle, RawAddress bd_addr);
  uint8_t (*GetChnlFcrMode)(uint16_t lcid);
  uint16_t (*SendFixedChnlData)(uint16_t fixed_cid, RawAddress rem_bda,
                                BT_HDR* p_buf);
  void (*Cleanup)(void);
  bt_status_t (*RegisterLePsm)(uint16_t le_psm, bool ConnType,
                               uint16_t SecLevel, uint8_t enc_key_size,
                               const tL2CAP_APPL_INFO& p_cb_info,
                               tL2CAP_LE_CFG_INFO cfg);
  bt_status_t (*LeDeregister)(uint16_t psm);
  uint16_t (*LeConnect)(uint16_t le_psm, RawAddress address,
                        tL2CAP_LE_CFG_INFO* p_cfg);
  bool (*LeFlowControl)(uint16_t lcid, uint16_t credits);
  void (*LeFreeBuf)(BT_HDR* p_buf);
  std::vector<uint16_t> (*ConnectCocReq)(uint16_t psm,
                                         const RawAddress& p_bd_addr,
                                         tL2CAP_LE_CFG_INFO* p_cfg);
  bool (*ConnectCocRsp)(const RawAddress& p_bd_addr, uint8_t id,
                        std::vector<uint16_t>& accepted_lcids, uint16_t result,
                        tL2CAP_LE_CFG_INFO* p_cfg);
  bt_status_t (*RegisterCocPsm)(uint16_t psm, const tL2CAP_APPL_INFO& p_cb_info,
                                uint16_t secLevel, tL2CAP_LE_CFG_INFO cfg);
  BT_HDR* (*ReadData)(uint16_t cid);
  bool (*ReconfigConnectCocReq)(const RawAddress& p_bd_addr, std::vector<uint16_t>& chnl_id,
                        tL2CAP_LE_CFG_INFO* p_cfg);
} btl2cap_interface_t;

typedef struct {
  size_t size;
  // GATT common APIs (Both client and server)
  tGATT_IF (*Register)(bluetooth::Uuid& p_app_uuid128, bluetooth::stack::tGATT_CBACK* p_cb_info,
                       bool eatt_support);
  void (*Deregister)(tGATT_IF gatt_if);
  void (*StartIf)(tGATT_IF gatt_if);
  bool (*Connect)(tGATT_IF gatt_if, RawAddress bd_addr, bool is_direct,
                  tBT_TRANSPORT transport);
  tGATT_STATUS (*Disconnect)(uint16_t conn_id);
  bool (*Listen)(tGATT_IF gatt_if, bool start, RawAddress& bd_addr);

  // GATT Client APIs
  tGATT_STATUS (*cConfigureMTU)(uint16_t conn_id, uint16_t mtu);
  tGATT_STATUS (*cDiscover)(uint16_t conn_id, tGATT_DISC_TYPE disc_type,
                            uint16_t start_handle, uint16_t end_handle,
                            const bluetooth::Uuid& uuid);
  tGATT_STATUS (*cRead)(uint16_t conn_id, tGATT_READ_TYPE type,
                        tGATT_READ_PARAM* p_read);
  tGATT_STATUS (*cWrite)(uint16_t conn_id, tGATT_WRITE_TYPE type,
                         tGATT_VALUE* p_write);
  tGATT_STATUS (*cExecuteWrite)(uint16_t conn_id, bool is_execute);
  tGATT_STATUS (*cSendHandleValueConfirm)(uint16_t conn_id, uint16_t handle);
  void (*cSetVisibility)(uint16_t disc_mode, uint16_t conn_mode);

  // GATT Server APIs
  // TODO - Add api on the need basis
  tGATT_STATUS (*sSendMultiNotification)(
      uint16_t conn_id, uint8_t num_attr, uint16_t handles[], uint16_t lens[],
      std::vector<std::vector<uint8_t>> values);

} btgatt_test_interface_t;

typedef struct {
  size_t size;
  void (*init)(void);
  bool (*Register)(tSMP_CALLBACK* p_cback);
  tSMP_STATUS (*Pair)(RawAddress bd_addr);
  bool (*PairCancel)(RawAddress bd_addr);
  void (*SecurityGrant)(RawAddress bd_addr, tSMP_STATUS res);
  void (*PasskeyReply)(RawAddress bd_addr, uint8_t res, uint32_t passkey);
  Octet16 (*Encrypt)(Octet16 key, Octet16 message);
} btsmp_interface_t;
typedef struct {
  size_t size;
  void (*Gap_AttrInit)();
  void (*Gap_BleAttrDBUpdate)(RawAddress bd_addr, uint16_t int_min,
                              uint16_t int_max, uint16_t latency,
                              uint16_t sp_tout);
  bool (*Gap_IsBonded)(const RawAddress& bd_addr, tBT_TRANSPORT transport);
} btgap_interface_t;

#endif

__END_DECLS

#endif /* ANDROID_INCLUDE_BT_TESTAPP_H */
