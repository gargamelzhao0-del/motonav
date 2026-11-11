#include "navigation_ui.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "nvs_flash.h"
#include "esp_mac.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_app_desc.h"
#include "bsp/esp-bsp.h"
extern "C" {
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_sm.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
void ble_store_config_init(void);
}

namespace {
constexpr const char *Tag = "motonav";
motonav::NavigationReceiver receiver;
SemaphoreHandle_t state_mutex;
motonav::ScreenSnapshot state;
uint16_t connection_handle = BLE_HS_CONN_HANDLE_NONE, tx_handle;
uint8_t address_type;
char device_name[20];
ble_npl_callout advertising_retry;

const ble_uuid128_t service_uuid = BLE_UUID128_INIT(0x9e,0xca,0xdc,0x24,0x0e,0xe5,0xa9,0xe0,0x93,0xf3,0xa3,0xb5,0x01,0x00,0x40,0x6e);
const ble_uuid128_t rx_uuid = BLE_UUID128_INIT(0x9e,0xca,0xdc,0x24,0x0e,0xe5,0xa9,0xe0,0x93,0xf3,0xa3,0xb5,0x02,0x00,0x40,0x6e);
const ble_uuid128_t tx_uuid = BLE_UUID128_INIT(0x9e,0xca,0xdc,0x24,0x0e,0xe5,0xa9,0xe0,0x93,0xf3,0xa3,0xb5,0x03,0x00,0x40,0x6e);
const ble_uuid16_t info_uuid = BLE_UUID16_INIT(0x180a);
const ble_uuid16_t revision_uuid = BLE_UUID16_INIT(0x2a26);

uint64_t now_ms() { return static_cast<uint64_t>(esp_timer_get_time() / 1000); }

motonav::ScreenSnapshot read_snapshot() {
    xSemaphoreTake(state_mutex, portMAX_DELAY);
    auto result = state;
    uint64_t now = now_ms();
    result.guidance = receiver.guidance(now);
    result.handshaken = receiver.handshaken();
    result.has_guidance = receiver.has_guidance();
    result.stream_fresh = receiver.connected(now);
    result.source_expired = receiver.source_expired(now);
    result.packets = receiver.stats();
    xSemaphoreGive(state_mutex);
    return result;
}

int read_revision(uint16_t, uint16_t, ble_gatt_access_ctxt *ctx, void *) {
    if (ctx->op != BLE_GATT_ACCESS_OP_READ_CHR) return BLE_ATT_ERR_READ_NOT_PERMITTED;
    const char *version = esp_app_get_description()->version;
    return os_mbuf_append(ctx->om, version, std::strlen(version)) ? BLE_ATT_ERR_INSUFFICIENT_RES : 0;
}

int write_navigation(uint16_t connection, uint16_t, ble_gatt_access_ctxt *ctx, void *) {
    if (ctx->op != BLE_GATT_ACCESS_OP_WRITE_CHR) return BLE_ATT_ERR_WRITE_NOT_PERMITTED;
    if (connection != connection_handle || !state.subscribed) return BLE_ATT_ERR_UNLIKELY;
    uint8_t bytes[512];
    uint16_t length = OS_MBUF_PKTLEN(ctx->om);
    if (length == 0 || length > sizeof(bytes) || ble_hs_mbuf_to_flat(ctx->om, bytes, sizeof(bytes), nullptr))
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    for (uint16_t i = 0; i < length; ++i) {
        char reply[32];
        xSemaphoreTake(state_mutex, portMAX_DELAY);
        bool complete = receiver.feed(static_cast<char>(bytes[i]), now_ms(), reply);
        xSemaphoreGive(state_mutex);
        if (!complete) continue;
        os_mbuf *packet = ble_hs_mbuf_from_flat(reply, std::strlen(reply));
        if (!packet) return BLE_ATT_ERR_INSUFFICIENT_RES;
        if (ble_gatts_notify_custom(connection, tx_handle, packet)) return BLE_ATT_ERR_UNLIKELY;
    }
    return 0;
}

const ble_gatt_chr_def characteristics[] = {
    {.uuid = &rx_uuid.u, .access_cb = write_navigation,
     .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_WRITE_AUTHEN},
    {.uuid = &tx_uuid.u, .flags = BLE_GATT_CHR_F_NOTIFY, .val_handle = &tx_handle}, {}
};
const ble_gatt_chr_def info_characteristics[] = {
    {.uuid = &revision_uuid.u, .access_cb = read_revision, .flags = BLE_GATT_CHR_F_READ}, {}
};
const ble_gatt_svc_def services[] = {
    {.type = BLE_GATT_SVC_TYPE_PRIMARY, .uuid = &service_uuid.u, .characteristics = characteristics},
    {.type = BLE_GATT_SVC_TYPE_PRIMARY, .uuid = &info_uuid.u, .characteristics = info_characteristics}, {}
};

void advertise();
void reset_connection() {
    xSemaphoreTake(state_mutex, portMAX_DELAY);
    receiver.disconnect(); state = {};
    connection_handle = BLE_HS_CONN_HANDLE_NONE;
    xSemaphoreGive(state_mutex);
}

int gap_event(ble_gap_event *event, void *) {
    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            if (event->connect.status) { advertise(); return 0; }
            xSemaphoreTake(state_mutex, portMAX_DELAY);
            connection_handle = event->connect.conn_handle; state.linked = true;
            xSemaphoreGive(state_mutex);
            ESP_LOGI(Tag, "Phone connected"); return 0;
        case BLE_GAP_EVENT_DISCONNECT:
            reset_connection(); advertise();
            ESP_LOGI(Tag, "Phone disconnected; reason=%d", event->disconnect.reason); return 0;
        case BLE_GAP_EVENT_ADV_COMPLETE:
            advertise(); return 0;
        case BLE_GAP_EVENT_SUBSCRIBE:
            if (event->subscribe.attr_handle == tx_handle) {
                xSemaphoreTake(state_mutex, portMAX_DELAY);
                state.subscribed = event->subscribe.cur_notify;
                if (!state.subscribed) receiver.disconnect();
                xSemaphoreGive(state_mutex);
            }
            return 0;
        case BLE_GAP_EVENT_PASSKEY_ACTION: {
            if (event->passkey.params.action != BLE_SM_IOACT_DISP) return BLE_HS_ENOTSUP;
            ble_sm_io io{}; io.action = BLE_SM_IOACT_DISP; io.passkey = esp_random() % 1000000;
            xSemaphoreTake(state_mutex, portMAX_DELAY);
            state.passkey = io.passkey; state.pairing = true;
            xSemaphoreGive(state_mutex);
            return ble_sm_inject_io(event->passkey.conn_handle, &io);
        }
        case BLE_GAP_EVENT_ENC_CHANGE: {
            ble_gap_conn_desc peer{};
            bool secure = event->enc_change.status == 0 && ble_gap_conn_find(event->enc_change.conn_handle, &peer) == 0
                && peer.sec_state.encrypted && peer.sec_state.authenticated;
            xSemaphoreTake(state_mutex, portMAX_DELAY);
            state.pairing = false; state.encrypted = secure;
            if (!secure) receiver.disconnect();
            xSemaphoreGive(state_mutex);
            return 0;
        }
        case BLE_GAP_EVENT_REPEAT_PAIRING: {
            // Phone-side bond removal requires a new visible PIN exchange.
            ble_gap_conn_desc peer{};
            if (ble_gap_conn_find(event->repeat_pairing.conn_handle, &peer)) return BLE_GAP_REPEAT_PAIRING_IGNORE;
            if (ble_store_util_delete_peer(&peer.peer_id_addr)) return BLE_GAP_REPEAT_PAIRING_IGNORE;
            return BLE_GAP_REPEAT_PAIRING_RETRY;
        }
        default: return 0;
    }
}

void retry_advertising(ble_npl_event *) { advertise(); }
void advertise() {
    if (connection_handle != BLE_HS_CONN_HANDLE_NONE || ble_gap_adv_active()) return;
    ble_hs_adv_fields fields{};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.uuids128 = const_cast<ble_uuid128_t *>(&service_uuid);
    fields.num_uuids128 = 1; fields.uuids128_is_complete = 1;
    int rc = ble_gap_adv_set_fields(&fields);
    ble_hs_adv_fields scan{};
    scan.name = reinterpret_cast<const uint8_t *>(device_name);
    scan.name_len = std::strlen(device_name); scan.name_is_complete = 1;
    if (!rc) rc = ble_gap_adv_rsp_set_fields(&scan);
    ble_gap_adv_params params{};
    params.conn_mode = BLE_GAP_CONN_MODE_UND; params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    if (!rc) rc = ble_gap_adv_start(address_type, nullptr, BLE_HS_FOREVER, &params, gap_event, nullptr);
    if (rc) {
        ESP_LOGW(Tag, "Advertising delayed; rc=%d", rc);
        ble_npl_callout_reset(&advertising_retry, ble_npl_time_ms_to_ticks32(1000));
    }
}
void ble_synced() { ESP_ERROR_CHECK(ble_hs_id_infer_auto(0, &address_type)); advertise(); }
void ble_reset(int reason) { reset_connection(); ESP_LOGW(Tag, "Bluetooth host reset; reason=%d", reason); }
void ble_task(void *) { nimble_port_run(); nimble_port_freertos_deinit(); }
} // namespace

extern "C" void app_main() {
    // Do not erase existing flash/NVS automatically if initialization fails.
    ESP_ERROR_CHECK(nvs_flash_init());
    state_mutex = xSemaphoreCreateMutex();
    ESP_ERROR_CHECK(state_mutex ? ESP_OK : ESP_ERR_NO_MEM);
    uint8_t mac[6]; ESP_ERROR_CHECK(esp_read_mac(mac, ESP_MAC_BT));
    std::snprintf(device_name, sizeof(device_name), "MotoNav-%02X%02X", mac[4], mac[5]);
    bsp_display_cfg_t cfg{};
    cfg.lv_adapter_cfg = ESP_LV_ADAPTER_DEFAULT_CONFIG(); cfg.lv_adapter_cfg.task_core_id = 1;
    cfg.rotation = ESP_LV_ADAPTER_ROTATE_0; cfg.tear_avoid_mode = ESP_LV_ADAPTER_TEAR_AVOID_MODE_NONE;
    cfg.touch_flags.mirror_x = cfg.touch_flags.mirror_y = 1;
    lv_display_t *display = bsp_display_start_with_config(&cfg);
    ESP_ERROR_CHECK(display ? ESP_OK : ESP_FAIL);
    ESP_ERROR_CHECK(bsp_display_brightness_set(70));
    ESP_ERROR_CHECK(bsp_display_lock(portMAX_DELAY));
    motonav::create_navigation_ui(display, device_name, read_snapshot);
    bsp_display_unlock();

    ESP_ERROR_CHECK(nimble_port_init());
    ESP_ERROR_CHECK(ble_npl_callout_init(&advertising_retry, nimble_port_get_dflt_eventq(), retry_advertising, nullptr));
    ble_hs_cfg.sync_cb = ble_synced; ble_hs_cfg.reset_cb = ble_reset;
    ble_hs_cfg.sm_io_cap = BLE_HS_IO_DISPLAY_ONLY;
    ble_hs_cfg.sm_bonding = 1; ble_hs_cfg.sm_mitm = 1; ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_svc_gap_init(); ble_svc_gatt_init();
    ESP_ERROR_CHECK(ble_svc_gap_device_name_set(device_name));
    ESP_ERROR_CHECK(ble_gatts_count_cfg(services)); ESP_ERROR_CHECK(ble_gatts_add_svcs(services));
    ble_store_config_init(); nimble_port_freertos_init(ble_task);
    ESP_LOGI(Tag, "Firmware %s / NAV3 / %s", esp_app_get_description()->version, device_name);
    while (true) {
        vTaskDelay(pdMS_TO_TICKS(5000));
        auto s = read_snapshot();
        ESP_LOGI(Tag, "state=%d link=%d secure=%d rx=%lu dup=%lu rejected=%lu heap=%lu",
            static_cast<int>(motonav::screen_mode(s)), s.linked, s.encrypted,
            static_cast<unsigned long>(s.packets.accepted), static_cast<unsigned long>(s.packets.duplicates),
            static_cast<unsigned long>(s.packets.rejected), static_cast<unsigned long>(esp_get_free_heap_size()));
    }
}
