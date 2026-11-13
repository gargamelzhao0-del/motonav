#include "qspi_panel.h"
#include "bsp/esp-bsp.h"
#include "driver/spi_master.h"
#include "esp_check.h"
#include "esp_heap_caps.h"
#include "esp_memory_utils.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_io_interface.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_co5300.h"
#include <cstring>
#include <new>
#include <type_traits>
#include <algorithm>

namespace motonav::drivers {
namespace {
constexpr const char *Tag = "qspi_panel";
struct QspiIo {
    esp_lcd_panel_io_t base{}; // Public ESP-IDF extension interface.
    spi_device_handle_t device = nullptr;
    spi_transaction_ext_t color[2]{};
    esp_lcd_panel_io_callbacks_t callbacks{};
    void *callback_context = nullptr;
    size_t maximum = 0;
    size_t chunk_bytes = 0;
    unsigned queued = 0, recycled = 0;
    bool faulted = false;
};
static_assert(std::is_standard_layout_v<QspiIo> && offsetof(QspiIo, base) == 0);

esp_err_t IRAM_ATTR drain(QspiIo &io) {
    while (io.recycled < io.queued) {
        spi_transaction_t *completed = nullptr;
        esp_err_t result = spi_device_get_trans_result(io.device, &completed, portMAX_DELAY);
        if (result != ESP_OK) return result;
        auto *expected = &io.color[io.recycled++].base;
        if (completed != expected) { io.faulted = true; return ESP_ERR_INVALID_STATE; }
    }
    io.queued = io.recycled = 0;
    return ESP_OK;
}
void IRAM_ATTR command(spi_transaction_ext_t &transaction, int encoded) {
    if (encoded < 0) {
        transaction.base.flags |= SPI_TRANS_VARIABLE_CMD | SPI_TRANS_VARIABLE_ADDR;
        transaction.command_bits = transaction.address_bits = 0;
    } else {
        // CO5300 QSPI: one-lane opcode + one-lane 24-bit address, then data.
        transaction.base.cmd = static_cast<uint32_t>(encoded) >> 24;
        transaction.base.addr = static_cast<uint32_t>(encoded) & 0x00ffffff;
    }
}
void IRAM_ATTR completed(spi_transaction_t *transaction) {
    auto *io = static_cast<QspiIo *>(transaction->user);
    if (!io || !io->callbacks.on_color_trans_done) return; // Only the final color chunk notifies.
    esp_lcd_panel_io_event_data_t event{};
    if (io->callbacks.on_color_trans_done(&io->base, &event, io->callback_context)) portYIELD_FROM_ISR();
}
esp_err_t IRAM_ATTR write_parameter(esp_lcd_panel_io_t *base, int encoded, const void *data, size_t bytes) {
    auto &io = *reinterpret_cast<QspiIo *>(base);
    if (io.faulted) return ESP_ERR_INVALID_STATE;
    if ((bytes && !data) || bytes > io.maximum) return ESP_ERR_INVALID_ARG;
    ESP_RETURN_ON_ERROR(drain(io), Tag, "drain before control");
    spi_transaction_ext_t transaction{};
    command(transaction, encoded);
    transaction.base.length = bytes * 8;
    if (bytes && bytes <= sizeof(transaction.base.tx_data)) {
        transaction.base.flags |= SPI_TRANS_USE_TXDATA;
        if (bytes) std::memcpy(transaction.base.tx_data, data, bytes);
    } else if (bytes) {
        transaction.base.tx_buffer = data;
    }
    return spi_device_polling_transmit(io.device, &transaction.base);
}
esp_err_t IRAM_ATTR write_color(esp_lcd_panel_io_t *base, int encoded, const void *pixels, size_t bytes) {
    auto &io = *reinterpret_cast<QspiIo *>(base);
    if (io.faulted) return ESP_ERR_INVALID_STATE;
    if (!pixels || !bytes || bytes > io.maximum || bytes % 4 ||
        (reinterpret_cast<uintptr_t>(pixels) & 3U) || !esp_ptr_dma_capable(pixels)) return ESP_ERR_INVALID_ARG;
    ESP_RETURN_ON_ERROR(drain(io), Tag, "recycle color descriptor");
    ESP_RETURN_ON_ERROR(spi_device_acquire_bus(io.device, portMAX_DELAY), Tag, "acquire color bus");
    esp_err_t result = ESP_OK;
    size_t offset = 0;
    // ESP32-S3 limits each DMA transaction to 32 KiB. Preserve CS across
    // chunks, put the command only on the first, and notify only on the last.
    while (offset < bytes) {
        auto &transfer = io.color[io.queued];
        transfer = {};
        command(transfer, offset ? -1 : encoded);
        size_t count = std::min(bytes - offset, io.chunk_bytes);
        bool last = offset + count == bytes;
        transfer.base.flags |= SPI_TRANS_MODE_QIO | SPI_TRANS_DMA_BUFFER_ALIGN_MANUAL;
        if (!last) transfer.base.flags |= SPI_TRANS_CS_KEEP_ACTIVE;
        transfer.base.length = count * 8;
        transfer.base.tx_buffer = static_cast<const uint8_t *>(pixels) + offset;
        transfer.base.user = last ? &io : nullptr;
        result = spi_device_queue_trans(io.device, &transfer.base, portMAX_DELAY);
        if (result != ESP_OK) {
            // A partly submitted CS-held sequence cannot be safely resumed.
            // Drain accepted jobs before returning buffer ownership; no fake
            // completion. Latch the fault to prevent later panel commands.
            if (io.queued) { io.faulted = true; ESP_ERROR_CHECK(drain(io)); }
            break;
        }
        ++io.queued;
        offset += count;
    }
    spi_device_release_bus(io.device);
    return result;
}
esp_err_t destroy(esp_lcd_panel_io_t *base) {
    auto &io = *reinterpret_cast<QspiIo *>(base);
    ESP_RETURN_ON_ERROR(drain(io), Tag, "drain before delete");
    ESP_RETURN_ON_ERROR(spi_bus_remove_device(io.device), Tag, "remove device");
    io.~QspiIo();
    heap_caps_free(&io);
    return ESP_OK;
}
esp_err_t set_callbacks(esp_lcd_panel_io_t *base, const esp_lcd_panel_io_callbacks_t *callbacks, void *context) {
    if (!callbacks) return ESP_ERR_INVALID_ARG;
    auto &io = *reinterpret_cast<QspiIo *>(base);
    ESP_RETURN_ON_ERROR(drain(io), Tag, "drain before callback registration");
    io.callbacks = *callbacks;
    io.callback_context = context;
    return ESP_OK;
}
esp_err_t new_io(size_t max_bytes, esp_lcd_panel_io_handle_t *out) {
    void *memory = heap_caps_malloc(sizeof(QspiIo), MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
    if (!memory) return ESP_ERR_NO_MEM;
    auto *io = new(memory) QspiIo{};
    io->maximum = max_bytes;
    io->base.rx_param = [](esp_lcd_panel_io_t *, int, void *, size_t) { return ESP_ERR_NOT_SUPPORTED; };
    io->base.tx_param = write_parameter; io->base.tx_color = write_color;
    io->base.del = destroy; io->base.register_event_callbacks = set_callbacks;
    spi_device_interface_config_t device{};
    device.command_bits = 8; device.address_bits = 24;
    device.clock_speed_hz = 40 * 1000 * 1000;
    device.spics_io_num = BSP_LCD_CS;
    device.flags = SPI_DEVICE_HALFDUPLEX;
    device.queue_size = 2;
    device.post_cb = completed;
    esp_err_t result = spi_bus_add_device(BSP_LCD_SPI_NUM, &device, &io->device);
    if (result != ESP_OK) { io->~QspiIo(); heap_caps_free(io); return result; }
    // IDF creates the master driver context when the first device is added;
    // querying its transaction limit before this point is invalid.
    result = spi_bus_get_max_transaction_len(BSP_LCD_SPI_NUM, &io->chunk_bytes);
    io->chunk_bytes &= ~size_t(3);
    if (result != ESP_OK || !io->chunk_bytes || max_bytes > 2 * io->chunk_bytes) {
        spi_bus_remove_device(io->device); io->~QspiIo(); heap_caps_free(io);
        return result != ESP_OK ? result : ESP_ERR_INVALID_ARG;
    }
    *out = &io->base;
    int actual_khz = 0;
    ESP_ERROR_CHECK(spi_device_get_actual_freq(io->device, &actual_khz));
    ESP_LOGI(Tag, "clock_khz=%d queue=2 command_bits=8 address_bits=24 max_bytes=%u chunk_bytes=%u",
             actual_khz, static_cast<unsigned>(max_bytes), static_cast<unsigned>(io->chunk_bytes));
    return ESP_OK;
}

// Same panel setup data as Waveshare BSP 3.0.0 (Apache-2.0),
// esp32_s3_touch_amoled_1_75c.c lcd_init_cmds. Keep pin/reset/timing unchanged.
constexpr uint8_t settings[]{0x20,0x10,0xa0,0x00,0x80,0x55,0x00,0x20,0xff,0xff};
constexpr uint8_t columns[]{0x00,0x06,0x01,0xd7}, rows[]{0x00,0x00,0x01,0xd1};
const co5300_lcd_init_cmd_t initialization[]{
    {0xfe,&settings[0],1,0},{0x19,&settings[1],1,0},{0x1c,&settings[2],1,0},
    {0xfe,&settings[3],1,0},{0xc4,&settings[4],1,0},{0x3a,&settings[5],1,0},
    {0x35,&settings[6],1,0},{0x53,&settings[7],1,0},{0x51,&settings[8],1,0},
    {0x63,&settings[9],1,0},{0x2a,columns,4,0},{0x2b,rows,4,600},
    {0x11,nullptr,0,600},{0x29,nullptr,0,0}
};
}
esp_err_t create_qspi_panel(size_t max_bytes, esp_lcd_panel_handle_t *panel, esp_lcd_panel_io_handle_t *io) {
    if (!panel || !io || !max_bytes || max_bytes > 65536 || max_bytes % 4) return ESP_ERR_INVALID_ARG;
    *panel = nullptr; *io = nullptr;
    spi_bus_config_t bus{};
    bus.sclk_io_num = BSP_LCD_PCLK;
    bus.data0_io_num = BSP_LCD_DATA0; bus.data1_io_num = BSP_LCD_DATA1;
    bus.data2_io_num = BSP_LCD_DATA2; bus.data3_io_num = BSP_LCD_DATA3;
    bus.data4_io_num = bus.data5_io_num = bus.data6_io_num = bus.data7_io_num = -1;
    bus.max_transfer_sz = static_cast<int>(std::min<size_t>(max_bytes, 32768));
    ESP_RETURN_ON_ERROR(spi_bus_initialize(BSP_LCD_SPI_NUM, &bus, SPI_DMA_CH_AUTO), Tag, "bus init");
    esp_err_t result = new_io(max_bytes, io);
    if (result != ESP_OK) { spi_bus_free(BSP_LCD_SPI_NUM); return result; }
    co5300_vendor_config_t vendor{};
    vendor.init_cmds = initialization; vendor.init_cmds_size = sizeof(initialization)/sizeof(initialization[0]);
    vendor.flags.use_qspi_interface = 1;
    esp_lcd_panel_dev_config_t config{};
    config.reset_gpio_num = BSP_LCD_RST;
    config.rgb_ele_order = LCD_RGB_ELEMENT_ORDER_RGB;
    config.bits_per_pixel = BSP_LCD_BITS_PER_PIXEL;
    config.vendor_config = &vendor;
    result = esp_lcd_new_panel_co5300(*io, &config, panel);
    if (result == ESP_OK) result = esp_lcd_panel_set_gap(*panel, 6, 0);
    if (result == ESP_OK) result = esp_lcd_panel_reset(*panel);
    if (result == ESP_OK) result = esp_lcd_panel_init(*panel);
    if (result == ESP_OK) result = esp_lcd_panel_disp_on_off(*panel, true);
    if (result != ESP_OK) {
        if (*panel) esp_lcd_panel_del(*panel);
        esp_lcd_panel_io_del(*io); spi_bus_free(BSP_LCD_SPI_NUM);
        *panel = nullptr; *io = nullptr;
    }
    return result;
}
} // namespace motonav::drivers
