#include "motonav/drivers/ble_transport.h"
#include "channel_state.h"
#include "notification_queue.h"
#include "esp_random.h"
#include "esp_log.h"
#include "sdkconfig.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include <atomic>
#include <cstring>
static_assert(CONFIG_BT_NIMBLE_HOST_TASK_STACK_SIZE >= 8192,
              "Set CONFIG_BT_NIMBLE_HOST_TASK_STACK_SIZE=8192 in sdkconfig (and defaults)");
#ifndef CONFIG_BT_NIMBLE_GATT_CLIENT
#error "Enable CONFIG_BT_NIMBLE_ROLE_CENTRAL and CONFIG_BT_NIMBLE_GATT_CLIENT to confirm Android indications"
#endif
extern "C" {
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_sm.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
void ble_store_config_init(void);
}

namespace motonav::drivers {
namespace {
constexpr const char *Tag = "motonav";
BleCallbacks callbacks;
BleDiagnosticCallbacks diagnostic_callbacks;
bool secure = false;
char revision[33];
uint16_t connection_handle = BLE_HS_CONN_HANDLE_NONE, tx_handle, map_tx_handle, diagnostic_rx_handle;
std::atomic<uint32_t> host_stack_min{0}, diagnostic_writes{0}, diagnostic_rejected{0};
std::atomic<uint16_t> published_diagnostic_handle{0};
// The single BLE host owns these independent buffers. NimBLE copies outgoing
// data before return; incoming data remains borrowed only for receive().
uint8_t receive_bytes[512], notification_bytes[BleNotificationMaxBytes];
BleChannelState channels;
uint8_t address_type;
char device_name[20];
ble_npl_callout advertising_retry;
ble_npl_callout notification_retry;
NotificationQueue notifications;
unsigned notification_channel = 0;
unsigned notification_failures = 0;
unsigned termination_failures = 0;
bool closing = false;
void sample_host_stack() {
    const uint32_t remaining = uxTaskGetStackHighWaterMark(nullptr); // ESP-IDF reports bytes
    const uint32_t previous = host_stack_min.load(std::memory_order_relaxed);
    if (!previous || remaining < previous) host_stack_min.store(remaining, std::memory_order_relaxed);
}

const ble_uuid128_t service_uuid = BLE_UUID128_INIT(0x9e,0xca,0xdc,0x24,0x0e,0xe5,0xa9,0xe0,0x93,0xf3,0xa3,0xb5,0x01,0x00,0x40,0x6e);
const ble_uuid128_t rx_uuid = BLE_UUID128_INIT(0x9e,0xca,0xdc,0x24,0x0e,0xe5,0xa9,0xe0,0x93,0xf3,0xa3,0xb5,0x02,0x00,0x40,0x6e);
const ble_uuid128_t tx_uuid = BLE_UUID128_INIT(0x9e,0xca,0xdc,0x24,0x0e,0xe5,0xa9,0xe0,0x93,0xf3,0xa3,0xb5,0x03,0x00,0x40,0x6e);
const ble_uuid128_t map_service_uuid = BLE_UUID128_INIT(0x30,0xa4,0xf8,0xba,0xe0,0x52,0x17,0xb8,0x1a,0x4f,0x5d,0x6b,0x01,0x00,0xc4,0x91);
const ble_uuid128_t map_rx_uuid = BLE_UUID128_INIT(0x30,0xa4,0xf8,0xba,0xe0,0x52,0x17,0xb8,0x1a,0x4f,0x5d,0x6b,0x02,0x00,0xc4,0x91);
const ble_uuid128_t map_tx_uuid = BLE_UUID128_INIT(0x30,0xa4,0xf8,0xba,0xe0,0x52,0x17,0xb8,0x1a,0x4f,0x5d,0x6b,0x03,0x00,0xc4,0x91);
const ble_uuid128_t diag_service_uuid = BLE_UUID128_INIT(0x30,0xa4,0xf8,0xba,0xe0,0x52,0x17,0xb8,0x1a,0x4f,0x5d,0x6b,0x01,0x00,0xd1,0x91);
const ble_uuid128_t diag_rx_uuid = BLE_UUID128_INIT(0x30,0xa4,0xf8,0xba,0xe0,0x52,0x17,0xb8,0x1a,0x4f,0x5d,0x6b,0x02,0x00,0xd1,0x91);
const ble_uuid16_t info_uuid = BLE_UUID16_INIT(0x180a);
const ble_uuid16_t revision_uuid = BLE_UUID16_INIT(0x2a26);

void emit(BleEventType type, uint32_t value = 0) {
    if (diagnostic_callbacks.reset && (type == BleEventType::Connected || type == BleEventType::Disconnected ||
            type == BleEventType::PairingPin || (type == BleEventType::Security && !value)))
        diagnostic_callbacks.reset(diagnostic_callbacks.context);
    if (callbacks.event) callbacks.event(callbacks.context, {type, value});
}

void clear_notifications() {
    ble_npl_callout_stop(&notification_retry);
    notifications.clear();
    notification_failures = 0;
    notification_channel = 0;
}
void terminate_failed_link() {
    const int result = ble_gap_terminate(connection_handle, BLE_ERR_REM_USER_CONN_TERM);
    if (result && result != BLE_HS_ENOTCONN) {
        ESP_LOGE(Tag, "Link termination delayed; rc=%d", result);
        if (++termination_failures >= 10 ||
            ble_npl_callout_reset(&notification_retry, ble_npl_time_ms_to_ticks32(20)))
            ble_hs_sched_reset(result);
    }
}
void close_failed_link(int reason) {
    clear_notifications();
    closing = true;
    termination_failures = 0;
    secure = false;
    emit(BleEventType::Security, 0);
    ESP_LOGE(Tag, "Notification delivery failed; rc=%d; closing link", reason);
    terminate_failed_link();
}
void send_notifications(ble_npl_event *) {
    if (closing) {
        terminate_failed_link();
        return;
    }
    if (!secure || !channels.subscribed(notification_channel) || notification_channel != channels.active() || connection_handle == BLE_HS_CONN_HANDLE_NONE) {
        clear_notifications();
        return;
    }
    // Yield back to the host after a bounded burst; never spin on mbuf/HCI pressure.
    for (unsigned sent = 0; sent < 8 && !notifications.empty(); ++sent) {
        const auto length = notifications.front(notification_bytes, sizeof(notification_bytes));
        os_mbuf *packet = ble_hs_mbuf_from_flat(notification_bytes, length);
        // NimBLE consumes packet on both success and failure. Keep our copy
        // until it accepts the notification so retry never reuses a freed mbuf.
        const int result = packet ? ble_gatts_notify_custom(connection_handle, notification_channel == 4 ? map_tx_handle : tx_handle, packet) : BLE_HS_ENOMEM;
        sample_host_stack();
        if (result) {
            const bool temporary = result == BLE_HS_ENOMEM || result == BLE_HS_EAGAIN ||
                                   result == BLE_HS_EBUSY || result == BLE_HS_ENOMEM_EVT;
            if (!temporary || ++notification_failures >= 50 ||
                ble_npl_callout_reset(&notification_retry, ble_npl_time_ms_to_ticks32(20)))
                close_failed_link(result);
            return;
        }
        notifications.pop();
        notification_failures = 0;
    }
    if (!notifications.empty() && ble_npl_callout_reset(&notification_retry, ble_npl_time_ms_to_ticks32(1)))
        close_failed_link(BLE_HS_EAGAIN);
}

int read_revision(uint16_t, uint16_t, ble_gatt_access_ctxt *ctx, void *) {
    if (ctx->op != BLE_GATT_ACCESS_OP_READ_CHR) return BLE_ATT_ERR_READ_NOT_PERMITTED;
    const char *version = revision;
    return os_mbuf_append(ctx->om, version, std::strlen(version)) ? BLE_ATT_ERR_INSUFFICIENT_RES : 0;
}

int write_bytes(uint16_t connection, uint16_t, ble_gatt_access_ctxt *ctx, void *channel_arg) {
    if (ctx->op != BLE_GATT_ACCESS_OP_WRITE_CHR) return BLE_ATT_ERR_WRITE_NOT_PERMITTED;
    if (connection != connection_handle || !secure || closing) return BLE_ATT_ERR_UNLIKELY;
    uint16_t length = OS_MBUF_PKTLEN(ctx->om);
    if (!length || length > sizeof(receive_bytes) || ble_hs_mbuf_to_flat(ctx->om, receive_bytes, sizeof(receive_bytes), nullptr))
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    unsigned channel = reinterpret_cast<uintptr_t>(channel_arg);
    bool first_write = channels.active() == 0;
    if (!channels.select(channel)) return BLE_ATT_ERR_UNLIKELY;
    if (first_write) {
        clear_notifications();
        ESP_LOGI(Tag, "BLE selected NAV%u from authenticated RX", channel);
        emit(BleEventType::Protocol, channel); emit(BleEventType::Subscription, 1);
    }
    switch (callbacks.receive(callbacks.context, receive_bytes, length)) {
        case BleReceiveResult::Accepted: return 0;
        case BleReceiveResult::NoMemory: return BLE_ATT_ERR_INSUFFICIENT_RES;
        default: return BLE_ATT_ERR_UNLIKELY;
    }
}

int notification_access(uint16_t, uint16_t, ble_gatt_access_ctxt *ctx, void *) {
    // NimBLE requires a callback even for a notify-only characteristic.
    // Replies are sent through ble_gatts_notify_custom, never by ATT reads.
    return ctx->op == BLE_GATT_ACCESS_OP_READ_CHR ? BLE_ATT_ERR_READ_NOT_PERMITTED : BLE_ATT_ERR_WRITE_NOT_PERMITTED;
}

int write_diagnostic(uint16_t connection, uint16_t, ble_gatt_access_ctxt *ctx, void *) {
    if (ctx->op != BLE_GATT_ACCESS_OP_WRITE_CHR) return BLE_ATT_ERR_WRITE_NOT_PERMITTED;
    if (connection != connection_handle || !secure || closing || !diagnostic_callbacks.receive) return BLE_ATT_ERR_UNLIKELY;
    uint8_t data[20];
    const uint16_t length = OS_MBUF_PKTLEN(ctx->om);
    if (!length || length > sizeof(data) || ble_hs_mbuf_to_flat(ctx->om, data, sizeof(data), nullptr))
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    ++diagnostic_writes;
    const auto result = diagnostic_callbacks.receive(diagnostic_callbacks.context, data, length);
    if (result != BleReceiveResult::Accepted) ++diagnostic_rejected;
    switch (result) {
        case BleReceiveResult::Accepted: return 0;
        case BleReceiveResult::NoMemory: return BLE_ATT_ERR_INSUFFICIENT_RES;
        default: return BLE_ATT_ERR_UNLIKELY;
    }
}

const ble_gatt_chr_def characteristics[] = {
    {.uuid = &rx_uuid.u, .access_cb = write_bytes, .arg = reinterpret_cast<void *>(3),
     .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_WRITE_AUTHEN},
    {.uuid = &tx_uuid.u, .access_cb = notification_access, .flags = BLE_GATT_CHR_F_NOTIFY, .val_handle = &tx_handle}, {}
};
const ble_gatt_chr_def map_characteristics[] = {
    {.uuid = &map_rx_uuid.u, .access_cb = write_bytes, .arg = reinterpret_cast<void *>(4),
     .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_WRITE_AUTHEN},
    {.uuid = &map_tx_uuid.u, .access_cb = notification_access, .flags = BLE_GATT_CHR_F_NOTIFY, .val_handle = &map_tx_handle}, {}
};
const ble_gatt_chr_def info_characteristics[] = {
    {.uuid = &revision_uuid.u, .access_cb = read_revision, .flags = BLE_GATT_CHR_F_READ}, {}
};
const ble_gatt_svc_def services[] = {
    {.type = BLE_GATT_SVC_TYPE_PRIMARY, .uuid = &service_uuid.u, .characteristics = characteristics},
    {.type = BLE_GATT_SVC_TYPE_PRIMARY, .uuid = &info_uuid.u, .characteristics = info_characteristics},
    {.type = BLE_GATT_SVC_TYPE_PRIMARY, .uuid = &map_service_uuid.u, .characteristics = map_characteristics}, {}
};
const ble_gatt_chr_def diagnostic_characteristics[] = {
    {.uuid = &diag_rx_uuid.u, .access_cb = write_diagnostic,
     .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_WRITE_AUTHEN, .val_handle = &diagnostic_rx_handle}, {}
};
const ble_gatt_svc_def diagnostic_services[] = {
    {.type = BLE_GATT_SVC_TYPE_PRIMARY, .uuid = &diag_service_uuid.u, .characteristics = diagnostic_characteristics}, {}
};

void advertise();
void reset_connection() {
    clear_notifications();
    closing = false;
    connection_handle = BLE_HS_CONN_HANDLE_NONE;
    secure = false;
    channels.reset();
    emit(BleEventType::Disconnected);
}

int gap_event(ble_gap_event *event, void *) {
    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            if (event->connect.status) { advertise(); return 0; }
            connection_handle = event->connect.conn_handle;
            clear_notifications();
            closing = false;
            secure = false;
            channels.reset();
            emit(BleEventType::Connected);
            ESP_LOGI(Tag, "Phone connected"); return 0;
        case BLE_GAP_EVENT_DISCONNECT:
            reset_connection(); advertise();
            ESP_LOGI(Tag, "Phone disconnected; reason=%d", event->disconnect.reason); return 0;
        case BLE_GAP_EVENT_ADV_COMPLETE:
            advertise(); return 0;
        case BLE_GAP_EVENT_SUBSCRIBE:
            ESP_LOGI(Tag, "BLE subscription: handle=%u notify=%u indicate=%u reason=%u",
                     event->subscribe.attr_handle, event->subscribe.cur_notify,
                     event->subscribe.cur_indicate, event->subscribe.reason);
            if (event->subscribe.attr_handle == tx_handle || event->subscribe.attr_handle == map_tx_handle) {
                unsigned channel=event->subscribe.attr_handle==map_tx_handle?4:3;
                if (channels.subscription(channel, event->subscribe.cur_notify)) {
                    if (!closing) clear_notifications();
                    emit(BleEventType::Subscription, 0);
                }
            }
            return 0;
        case BLE_GAP_EVENT_PASSKEY_ACTION: {
            if (closing) return BLE_HS_EBUSY;
            if (event->passkey.params.action != BLE_SM_IOACT_DISP) return BLE_HS_ENOTSUP;
            ble_sm_io io{}; io.action = BLE_SM_IOACT_DISP; io.passkey = esp_random() % 1000000;
            secure = false;
            clear_notifications();
            emit(BleEventType::PairingPin, io.passkey);
            return ble_sm_inject_io(event->passkey.conn_handle, &io);
        }
        case BLE_GAP_EVENT_ENC_CHANGE: {
            ble_gap_conn_desc peer{};
            secure = !closing && event->enc_change.status == 0 && ble_gap_conn_find(event->enc_change.conn_handle, &peer) == 0
                && peer.sec_state.encrypted && peer.sec_state.authenticated;
            ESP_LOGI(Tag, "BLE security: status=%d encrypted=%u authenticated=%u bonded=%u",
                     event->enc_change.status, peer.sec_state.encrypted,
                     peer.sec_state.authenticated, peer.sec_state.bonded);
            if (!secure && !closing) clear_notifications();
            emit(BleEventType::Security, secure);
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
void ble_synced() {
    ESP_ERROR_CHECK(ble_hs_id_infer_auto(0, &address_type));
    if (diagnostic_callbacks.receive && !diagnostic_rx_handle) ESP_ERROR_CHECK(ESP_FAIL);
    published_diagnostic_handle.store(diagnostic_rx_handle, std::memory_order_relaxed);
    sample_host_stack();
    ESP_LOGI(Tag, "BLE handles: NAV3_TX=%u NAV4_TX=%u DIAG1_RX=%u host_stack_bytes=%u",
             tx_handle, map_tx_handle, diagnostic_rx_handle, CONFIG_BT_NIMBLE_HOST_TASK_STACK_SIZE);
    // NimBLE persists this indication for bonded peers that are not connected.
    // Tell phones about newly installed services before advertising after boot.
    ble_svc_gatt_changed(1,0xffff);
    advertise();
}
void ble_reset(int reason) { reset_connection(); ESP_LOGW(Tag, "Bluetooth host reset; reason=%d", reason); }
void ble_task(void *) { nimble_port_run(); nimble_port_freertos_deinit(); }
} // namespace

BleRuntimeStatus ble_runtime_status() {
    return {host_stack_min.load(std::memory_order_relaxed), diagnostic_writes.load(std::memory_order_relaxed),
            diagnostic_rejected.load(std::memory_order_relaxed), published_diagnostic_handle.load(std::memory_order_relaxed)};
}

esp_err_t ble_start(const char *name, const char *version, const BleCallbacks &handlers, const BleDiagnosticCallbacks &diagnostics) {
    static bool attempted = false;
    if (!name || !version || !handlers.receive || !handlers.event ||
        !*name || std::strlen(name) >= sizeof(device_name) || std::strlen(version) >= sizeof(revision))
        return ESP_ERR_INVALID_ARG;
    if (attempted) return ESP_ERR_INVALID_STATE;
    attempted = true;
    std::strcpy(device_name, name); std::strcpy(revision, version); callbacks = handlers; diagnostic_callbacks = diagnostics;
    if (nimble_port_init()) return ESP_FAIL;
    if (ble_npl_callout_init(&advertising_retry, nimble_port_get_dflt_eventq(), retry_advertising, nullptr)) return ESP_FAIL;
    if (ble_npl_callout_init(&notification_retry, nimble_port_get_dflt_eventq(), send_notifications, nullptr)) return ESP_FAIL;
    ble_hs_cfg.sync_cb = ble_synced; ble_hs_cfg.reset_cb = ble_reset;
    ble_hs_cfg.sm_io_cap = BLE_HS_IO_DISPLAY_ONLY;
    ble_hs_cfg.sm_bonding = 1; ble_hs_cfg.sm_mitm = 1; ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_svc_gap_init(); ble_svc_gatt_init();
    if (ble_svc_gap_device_name_set(device_name)) return ESP_FAIL;
    if (ble_gatts_count_cfg(services)) return ESP_FAIL;
    if (ble_gatts_add_svcs(services)) return ESP_FAIL;
    if (diagnostic_callbacks.receive) {
        if (ble_gatts_count_cfg(diagnostic_services) || ble_gatts_add_svcs(diagnostic_services)) return ESP_FAIL;
    }
    ble_store_config_init(); nimble_port_freertos_init(ble_task);
    return ESP_OK;
}
esp_err_t ble_notify(const uint8_t *data, size_t length) {
    const BleNotification message{data, length};
    return ble_notify_batch(&message, 1);
}
esp_err_t ble_notify_batch(const BleNotification *messages, size_t count) {
    const auto result = notifications.check(messages, count);
    if (result == NotificationQueue::Result::Invalid) return ESP_ERR_INVALID_ARG;
    if (connection_handle == BLE_HS_CONN_HANDLE_NONE || !channels.subscribed(channels.active()) || !secure || closing)
        return ESP_ERR_INVALID_STATE;
    const size_t maximum = ble_att_mtu(connection_handle) - 3;
    for (size_t i = 0; i < count; ++i)
        if (messages[i].length > maximum) return ESP_ERR_INVALID_SIZE;
    if (result == NotificationQueue::Result::Full) return ESP_ERR_NO_MEM;
    // Schedule BEFORE mutation, so a scheduler error also queues nothing.
    if (notifications.empty() &&
        ble_npl_callout_reset(&notification_retry, ble_npl_time_ms_to_ticks32(1))) return ESP_FAIL;
    notification_channel = channels.active();
    notifications.enqueue(messages, count);
    return ESP_OK;
}
} // namespace motonav::drivers
