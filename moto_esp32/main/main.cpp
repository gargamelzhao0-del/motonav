#include "navigation_protocol.h"
#include <cassert>
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "nvs_flash.h"
#include "esp_mac.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "bsp/esp-bsp.h"
#include "bsp/display.h"
#include "lvgl.h"
extern "C" {
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_sm.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
void ble_store_config_init(void);
}

static motonav::NavigationReceiver receiver;
static SemaphoreHandle_t state_mutex;
static uint16_t tx_handle;
static uint8_t address_type;
static bool pairing;
static uint32_t passkey;
static char device_name[20];
static lv_obj_t *direction_label, *distance_label, *speed_label, *remaining_label, *connection_label, *arrow;
static lv_point_precise_t arrow_points[6];

static const ble_uuid128_t service_uuid = BLE_UUID128_INIT(0x9e,0xca,0xdc,0x24,0x0e,0xe5,0xa9,0xe0,0x93,0xf3,0xa3,0xb5,0x01,0x00,0x40,0x6e);
static const ble_uuid128_t rx_uuid = BLE_UUID128_INIT(0x9e,0xca,0xdc,0x24,0x0e,0xe5,0xa9,0xe0,0x93,0xf3,0xa3,0xb5,0x02,0x00,0x40,0x6e);
static const ble_uuid128_t tx_uuid = BLE_UUID128_INIT(0x9e,0xca,0xdc,0x24,0x0e,0xe5,0xa9,0xe0,0x93,0xf3,0xa3,0xb5,0x03,0x00,0x40,0x6e);

static uint64_t now_ms() { return static_cast<uint64_t>(esp_timer_get_time() / 1000); }

static int write_navigation(uint16_t connection, uint16_t attribute, ble_gatt_access_ctxt *ctx, void *arg) {
    (void)attribute; (void)arg;
    if (ctx->op != BLE_GATT_ACCESS_OP_WRITE_CHR) return BLE_ATT_ERR_WRITE_NOT_PERMITTED;
    uint8_t bytes[512];
    uint16_t length = OS_MBUF_PKTLEN(ctx->om);
    if (length == 0 || length > sizeof(bytes) || ble_hs_mbuf_to_flat(ctx->om, bytes, sizeof(bytes), nullptr) != 0)
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    for (uint16_t i = 0; i < length; ++i) {
        char reply[32];
        xSemaphoreTake(state_mutex, portMAX_DELAY);
        bool complete = receiver.feed(static_cast<char>(bytes[i]), now_ms(), reply);
        xSemaphoreGive(state_mutex);
        if (complete) {
            os_mbuf *packet = ble_hs_mbuf_from_flat(reply, std::strlen(reply));
            if (!packet) return BLE_ATT_ERR_INSUFFICIENT_RES;
            if (ble_gatts_notify_custom(connection, tx_handle, packet) != 0) return BLE_ATT_ERR_UNLIKELY;
        }
    }
    return 0;
}

static const ble_gatt_chr_def characteristics[] = {
    {.uuid = &rx_uuid.u, .access_cb = write_navigation,
     .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_WRITE_AUTHEN},
    {.uuid = &tx_uuid.u, .flags = BLE_GATT_CHR_F_NOTIFY, .val_handle = &tx_handle},
    {}
};
static const ble_gatt_svc_def services[] = {
    {.type = BLE_GATT_SVC_TYPE_PRIMARY, .uuid = &service_uuid.u, .characteristics = characteristics},
    {}
};

static void advertise();

static int gap_event(ble_gap_event *event, void *arg) {
    (void)arg;
    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            if (event->connect.status != 0) advertise();
            return 0;
        case BLE_GAP_EVENT_DISCONNECT:
            xSemaphoreTake(state_mutex, portMAX_DELAY);
            receiver.disconnect(); pairing = false;
            xSemaphoreGive(state_mutex);
            advertise();
            return 0;
        case BLE_GAP_EVENT_ADV_COMPLETE:
            advertise();
            return 0;
        case BLE_GAP_EVENT_PASSKEY_ACTION: {
            if (event->passkey.params.action != BLE_SM_IOACT_DISP) return BLE_HS_ENOTSUP;
            ble_sm_io io{};
            io.action = BLE_SM_IOACT_DISP;
            io.passkey = esp_random() % 1000000;
            xSemaphoreTake(state_mutex, portMAX_DELAY);
            passkey = io.passkey; pairing = true;
            xSemaphoreGive(state_mutex);
            return ble_sm_inject_io(event->passkey.conn_handle, &io);
        }
        case BLE_GAP_EVENT_ENC_CHANGE:
            xSemaphoreTake(state_mutex, portMAX_DELAY);
            pairing = false;
            xSemaphoreGive(state_mutex);
            return 0;
        default:
            return 0;
    }
}

static void advertise() {
    ble_hs_adv_fields fields{};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.uuids128 = const_cast<ble_uuid128_t *>(&service_uuid);
    fields.num_uuids128 = 1;
    fields.uuids128_is_complete = 1;
    assert(ble_gap_adv_set_fields(&fields) == 0);
    ble_hs_adv_fields scan{};
    scan.name = reinterpret_cast<const uint8_t *>(device_name);
    scan.name_len = std::strlen(device_name);
    scan.name_is_complete = 1;
    assert(ble_gap_adv_rsp_set_fields(&scan) == 0);
    ble_gap_adv_params params{};
    params.conn_mode = BLE_GAP_CONN_MODE_UND;
    params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    assert(ble_gap_adv_start(address_type, nullptr, BLE_HS_FOREVER, &params, gap_event, nullptr) == 0);
}

static void ble_synced() {
    assert(ble_hs_id_infer_auto(0, &address_type) == 0);
    advertise();
}

static void ble_task(void *) {
    nimble_port_run();
    nimble_port_freertos_deinit();
}

static lv_obj_t *label(lv_obj_t *parent, int y, const lv_font_t *font, uint32_t color) {
    lv_obj_t *obj = lv_label_create(parent);
    lv_obj_set_width(obj, 360);
    lv_obj_set_style_text_align(obj, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_style_text_font(obj, font, 0);
    lv_obj_set_style_text_color(obj, lv_color_hex(color), 0);
    lv_obj_align(obj, LV_ALIGN_TOP_MID, 0, y);
    return obj;
}

static const char *maneuver(int icon) {
    switch (icon) {
        case 2: return "TURN LEFT";
        case 3: return "TURN RIGHT";
        case 4: return "BEAR LEFT";
        case 5: return "BEAR RIGHT";
        case 6: return "SHARP LEFT";
        case 7: return "SHARP RIGHT";
        case 8: case 19: return "U-TURN";
        case 9: return "STRAIGHT";
        case 10: return "WAYPOINT";
        case 11: case 17: return "ENTER ROUNDABOUT";
        case 12: case 18: return "EXIT ROUNDABOUT";
        case 13: return "SERVICE AREA";
        case 14: return "TOLL GATE";
        case 15: return "DESTINATION";
        case 16: return "TUNNEL";
        case 20: return "FOLLOW ROAD";
        case 21: case 25: return "ROUNDABOUT LEFT";
        case 22: case 26: return "ROUNDABOUT RIGHT";
        case 23: case 27: return "ROUNDABOUT AHEAD";
        case 24: case 28: return "ROUNDABOUT U-TURN";
        case 65: return "KEEP LEFT";
        case 66: return "KEEP RIGHT";
        default: return "CHECK PHONE MAP";
    }
}

static void draw_arrow(int icon) {
    // Only draw shapes whose meaning is unambiguous; other maneuvers use the SDK type label.
    bool left = icon == 2, right = icon == 3, straight = icon == 9;
    if (!left && !right && !straight) { lv_obj_add_flag(arrow, LV_OBJ_FLAG_HIDDEN); return; }
    lv_obj_remove_flag(arrow, LV_OBJ_FLAG_HIDDEN);
    if (straight) {
        arrow_points[0] = {90, 130}; arrow_points[1] = {90, 25};
        arrow_points[2] = {50, 65}; arrow_points[3] = {90, 25}; arrow_points[4] = {130, 65};
        lv_line_set_points(arrow, arrow_points, 5);
    } else {
        int tip = left ? 25 : 155, back = left ? 65 : 115;
        arrow_points[0] = {90, 130}; arrow_points[1] = {90, 55}; arrow_points[2] = {static_cast<lv_value_precise_t>(tip), 55};
        arrow_points[3] = {static_cast<lv_value_precise_t>(back), 20}; arrow_points[4] = arrow_points[2];
        arrow_points[5] = {static_cast<lv_value_precise_t>(back), 90};
        lv_line_set_points(arrow, arrow_points, 6);
    }
}

static void refresh_ui(lv_timer_t *) {
    xSemaphoreTake(state_mutex, portMAX_DELAY);
    uint64_t now = now_ms();
    motonav::Guidance data = receiver.guidance(now);
    bool connected = receiver.connected(now), show_pair = pairing;
    uint32_t code = passkey;
    xSemaphoreGive(state_mutex);
    if (show_pair) {
        lv_label_set_text(direction_label, "PAIR WITH PHONE");
        lv_label_set_text_fmt(distance_label, "%06lu", static_cast<unsigned long>(code));
        lv_label_set_text(speed_label, "Enter this code on your phone");
        lv_label_set_text(remaining_label, "");
        lv_label_set_text(connection_label, device_name);
        lv_obj_add_flag(arrow, LV_OBJ_FLAG_HIDDEN);
        return;
    }
    lv_label_set_text(connection_label, connected ? "PHONE CONNECTED" : device_name);
    if (data.mode == motonav::Live) {
        lv_label_set_text(direction_label, maneuver(data.icon));
        lv_label_set_text_fmt(distance_label, "%d m", data.turn_m);
        if (data.speed_kmh >= 0) lv_label_set_text_fmt(speed_label, "%d km/h", data.speed_kmh);
        else lv_label_set_text(speed_label, "Speed unavailable");
        lv_label_set_text_fmt(remaining_label, "%.1f km  /  %d min", data.remaining_m / 1000.0, (data.remaining_s + 59) / 60);
        draw_arrow(data.icon);
    } else {
        lv_label_set_text(direction_label, data.mode == motonav::Arrived ? "ARRIVED" : data.mode == motonav::Stopped ? "NAVIGATION ENDED"
                : connected ? "WAITING FOR GPS" : "CONNECT PHONE");
        lv_label_set_text(distance_label, "--");
        lv_label_set_text(speed_label, ""); lv_label_set_text(remaining_label, "");
        lv_obj_add_flag(arrow, LV_OBJ_FLAG_HIDDEN);
    }
}

extern "C" void app_main() {
    ESP_ERROR_CHECK(nvs_flash_init());
    state_mutex = xSemaphoreCreateMutex();
    assert(state_mutex);
    uint8_t mac[6];
    ESP_ERROR_CHECK(esp_read_mac(mac, ESP_MAC_BT));
    std::snprintf(device_name, sizeof(device_name), "MotoNav-%02X%02X", mac[4], mac[5]);
    assert(bsp_display_start());
    ESP_ERROR_CHECK(bsp_display_brightness_set(70));
    ESP_ERROR_CHECK(bsp_display_lock(portMAX_DELAY));
    lv_obj_t *screen = lv_screen_active();
    lv_obj_set_style_bg_color(screen, lv_color_hex(0x101411), 0);
    lv_obj_remove_flag(screen, LV_OBJ_FLAG_SCROLLABLE);
    connection_label = label(screen, 55, &lv_font_montserrat_20, 0x96a28f);
    direction_label = label(screen, 95, &lv_font_montserrat_28, 0xc4fa64);
    arrow = lv_line_create(screen);
    lv_obj_set_pos(arrow, 143, 135);
    lv_obj_set_style_line_width(arrow, 13, 0);
    lv_obj_set_style_line_color(arrow, lv_color_hex(0xc4fa64), 0);
    lv_obj_set_style_line_rounded(arrow, true, 0);
    distance_label = label(screen, 270, &lv_font_montserrat_48, 0xf2f3e9);
    speed_label = label(screen, 335, &lv_font_montserrat_28, 0xf2f3e9);
    remaining_label = label(screen, 380, &lv_font_montserrat_20, 0x96a28f);
    refresh_ui(nullptr);
    lv_timer_create(refresh_ui, 100, nullptr);
    bsp_display_unlock();

    ESP_ERROR_CHECK(nimble_port_init());
    ble_hs_cfg.sync_cb = ble_synced;
    ble_hs_cfg.sm_io_cap = BLE_HS_IO_DISPLAY_ONLY;
    ble_hs_cfg.sm_bonding = 1; ble_hs_cfg.sm_mitm = 1; ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_svc_gap_init(); ble_svc_gatt_init();
    assert(ble_svc_gap_device_name_set(device_name) == 0);
    assert(ble_gatts_count_cfg(services) == 0);
    assert(ble_gatts_add_svcs(services) == 0);
    ble_store_config_init();
    nimble_port_freertos_init(ble_task);
}
