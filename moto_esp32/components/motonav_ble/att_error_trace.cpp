#include "esp_log.h"
extern "C" {
#include "host/ble_hs.h"
int __real_ble_att_tx(uint16_t connection, uint16_t cid, os_mbuf *packet);

// The diagnostic build wraps the ESP-IDF 5.5 NimBLE transmit boundary. Only the
// five-byte ATT error header is read; keys, PINs and navigation are never logged.
int __wrap_ble_att_tx(uint16_t connection, uint16_t cid, os_mbuf *packet) {
    uint8_t header[5];
    if (packet && OS_MBUF_PKTLEN(packet) == sizeof(header)
            && !os_mbuf_copydata(packet, 0, sizeof(header), header) && header[0] == 0x01) {
        ESP_LOGW("motonav_ble", "ATT error: request=0x%02x handle=%u error=0x%02x",
                 header[1], unsigned(header[2] | header[3] << 8), header[4]);
    }
    return __real_ble_att_tx(connection, cid, packet);
}
}
