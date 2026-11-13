#pragma once
#include "esp_err.h"
#include <cstddef>
#include <cstdint>
inline void test_log(const char *,const char *,...) {}
#define ESP_LOGI(...) test_log(__VA_ARGS__)
#define ESP_LOGW(...) test_log(__VA_ARGS__)
#define ESP_LOGE(...) test_log(__VA_ARGS__)
#define ESP_ERROR_CHECK(value) do { if ((value) != 0) std::abort(); } while (0)
#include <cstdlib>
struct ble_uuid_t { uint8_t type; };
struct ble_uuid128_t { ble_uuid_t u; uint8_t value[16]; };
struct ble_uuid16_t { ble_uuid_t u; uint16_t value; };
#define BLE_UUID128_INIT(...) ble_uuid128_t{{128}, {__VA_ARGS__}}
#define BLE_UUID16_INIT(v) ble_uuid16_t{{16}, v}
struct os_mbuf { uint16_t length; uint8_t bytes[512]; };
#define OS_MBUF_PKTLEN(p) ((p)->length)
struct ble_gatt_access_ctxt { int op; os_mbuf *om; };
using Access = int (*)(uint16_t,uint16_t,ble_gatt_access_ctxt *,void *);
struct ble_gatt_chr_def { const ble_uuid_t *uuid; Access access_cb; void *arg = nullptr; uint16_t flags; uint16_t *val_handle = nullptr; };
struct ble_gatt_svc_def { int type; const ble_uuid_t *uuid; const ble_gatt_chr_def *characteristics; };
struct ble_npl_event {};
struct ble_npl_callout { void (*cb)(ble_npl_event *); bool active; uint32_t ticks; };
struct ble_gap_conn_desc { struct { bool encrypted, authenticated, bonded; } sec_state; int peer_id_addr; };
struct ble_sm_io { int action; uint32_t passkey; };
struct ble_gap_event {
    int type;
    struct { int status; uint16_t conn_handle; } connect;
    struct { int reason; } disconnect;
    struct { uint16_t attr_handle; bool cur_notify; bool cur_indicate = false; unsigned reason = 0; } subscribe;
    struct { struct { int action; } params; uint16_t conn_handle; } passkey;
    struct { int status; uint16_t conn_handle; } enc_change;
    struct { uint16_t conn_handle; } repeat_pairing;
};
struct ble_gap_adv_fields {
    int flags;
    ble_uuid128_t *uuids128;
    int num_uuids128, uuids128_is_complete;
    const uint8_t *name;
    size_t name_len;
    int name_is_complete;
};
using ble_hs_adv_fields = ble_gap_adv_fields;
struct ble_gap_adv_params { int conn_mode, disc_mode; };
struct ble_hs_config {
    void (*sync_cb)(); void (*reset_cb)(int);
    int sm_io_cap, sm_bonding, sm_mitm, sm_sc, sm_our_key_dist, sm_their_key_dist;
    void (*store_status_cb)();
};
extern ble_hs_config ble_hs_cfg;
constexpr uint16_t BLE_HS_CONN_HANDLE_NONE = 0xffff;
enum { BLE_GATT_ACCESS_OP_READ_CHR=1, BLE_GATT_ACCESS_OP_WRITE_CHR,
    BLE_ATT_ERR_READ_NOT_PERMITTED, BLE_ATT_ERR_INSUFFICIENT_RES, BLE_ATT_ERR_WRITE_NOT_PERMITTED,
    BLE_ATT_ERR_UNLIKELY, BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN };
enum { BLE_GATT_CHR_F_WRITE=1, BLE_GATT_CHR_F_WRITE_ENC=2, BLE_GATT_CHR_F_WRITE_AUTHEN=4,
    BLE_GATT_CHR_F_NOTIFY=8, BLE_GATT_CHR_F_READ=16 };
enum { BLE_GATT_SVC_TYPE_PRIMARY=1, BLE_GAP_EVENT_CONNECT, BLE_GAP_EVENT_DISCONNECT,
    BLE_GAP_EVENT_ADV_COMPLETE, BLE_GAP_EVENT_SUBSCRIBE, BLE_GAP_EVENT_PASSKEY_ACTION,
    BLE_GAP_EVENT_ENC_CHANGE, BLE_GAP_EVENT_REPEAT_PAIRING };
enum { BLE_HS_EAGAIN=1, BLE_HS_ENOMEM=6, BLE_HS_ENOTCONN=7, BLE_HS_ENOTSUP=8,
    BLE_HS_EBUSY=15, BLE_HS_ENOMEM_EVT=20, BLE_ERR_REM_USER_CONN_TERM=19 };
enum { BLE_SM_IOACT_DISP=1, BLE_GAP_REPEAT_PAIRING_IGNORE=0, BLE_GAP_REPEAT_PAIRING_RETRY=1,
    BLE_HS_ADV_F_DISC_GEN=2, BLE_HS_ADV_F_BREDR_UNSUP=4, BLE_GAP_CONN_MODE_UND=1,
    BLE_GAP_DISC_MODE_GEN=1, BLE_HS_FOREVER=-1, BLE_HS_IO_DISPLAY_ONLY=1,
    BLE_SM_PAIR_KEY_DIST_ENC=1, BLE_SM_PAIR_KEY_DIST_ID=2 };
extern "C" {
uint32_t esp_random();
int os_mbuf_append(os_mbuf *,const void *,size_t);
int ble_hs_mbuf_to_flat(os_mbuf *,void *,uint16_t,uint16_t *);
os_mbuf *ble_hs_mbuf_from_flat(const void *,uint16_t);
int ble_gatts_notify_custom(uint16_t,uint16_t,os_mbuf *);
uint16_t ble_att_mtu(uint16_t);
int ble_gap_terminate(uint16_t,uint8_t);
int ble_sm_inject_io(uint16_t,ble_sm_io *);
int ble_gap_conn_find(uint16_t,ble_gap_conn_desc *);
int ble_store_util_delete_peer(const int *);
void ble_store_util_status_rr();
int ble_gap_adv_active();
int ble_gap_adv_set_fields(const ble_gap_adv_fields *);
int ble_gap_adv_rsp_set_fields(const ble_gap_adv_fields *);
int ble_gap_adv_start(uint8_t,const void *,int,const ble_gap_adv_params *,int (*)(ble_gap_event *,void *),void *);
int ble_hs_id_infer_auto(int,uint8_t *);
void ble_hs_sched_reset(int);
int ble_npl_callout_init(ble_npl_callout *,void *,void (*)(ble_npl_event *),void *);
int ble_npl_callout_reset(ble_npl_callout *,uint32_t);
void ble_npl_callout_stop(ble_npl_callout *);
uint32_t ble_npl_time_ms_to_ticks32(uint32_t);
void *nimble_port_get_dflt_eventq();
int nimble_port_init();
void nimble_port_run();
void nimble_port_freertos_init(void (*)(void *));
void nimble_port_freertos_deinit();
void ble_svc_gap_init();
void ble_svc_gatt_init();
void ble_svc_gatt_changed(uint16_t, uint16_t);
int ble_svc_gap_device_name_set(const char *);
int ble_gatts_count_cfg(const ble_gatt_svc_def *);
int ble_gatts_add_svcs(const ble_gatt_svc_def *);
void ble_store_config_init();
}
