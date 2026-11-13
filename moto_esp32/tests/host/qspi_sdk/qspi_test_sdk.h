#pragma once
// Minimal ESP-IDF public API fake for transport ownership/failure tests.
// Hardware validation still runs against the real SDK and COM21.
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
using esp_err_t = int;
constexpr int ESP_OK=0, ESP_FAIL=-1, ESP_ERR_INVALID_ARG=1, ESP_ERR_INVALID_STATE=2,
    ESP_ERR_NO_MEM=3, ESP_ERR_NOT_SUPPORTED=4;
#define ESP_RETURN_ON_ERROR(expr, ...) do { auto error=(expr); if(error!=ESP_OK) return error; } while(0)
#define ESP_ERROR_CHECK(expr) assert((expr)==ESP_OK)
#define ESP_LOGI(...) ((void)0)
#define IRAM_ATTR
#define portYIELD_FROM_ISR() fake_yield()
constexpr int portMAX_DELAY=-1, MALLOC_CAP_INTERNAL=1, MALLOC_CAP_8BIT=2;
void fake_yield();
void *heap_caps_malloc(size_t, int);
void heap_caps_free(void *);
bool esp_ptr_dma_capable(const void *);
struct spi_transaction_t {
    uint32_t flags=0; uint16_t cmd=0; uint64_t addr=0;
    size_t length=0; const void *tx_buffer=nullptr; uint8_t tx_data[4]{};
    void *user=nullptr;
};
struct spi_transaction_ext_t { spi_transaction_t base{}; uint8_t command_bits=0, address_bits=0; };
struct spi_device_interface_config_t {
    uint8_t command_bits=0, address_bits=0; int clock_speed_hz=0, spics_io_num=0;
    uint32_t flags=0; int queue_size=0; void (*post_cb)(spi_transaction_t *)=nullptr;
};
struct spi_bus_config_t {
    int sclk_io_num=0,data0_io_num=0,data1_io_num=0,data2_io_num=0,data3_io_num=0;
    int data4_io_num=0,data5_io_num=0,data6_io_num=0,data7_io_num=0,max_transfer_sz=0;
};
using spi_device_handle_t = void *;
constexpr uint32_t SPI_TRANS_VARIABLE_CMD=1, SPI_TRANS_VARIABLE_ADDR=2, SPI_TRANS_USE_TXDATA=4,
    SPI_TRANS_MODE_QIO=8, SPI_TRANS_DMA_BUFFER_ALIGN_MANUAL=16, SPI_DEVICE_HALFDUPLEX=32, SPI_TRANS_CS_KEEP_ACTIVE=64;
constexpr int SPI_DMA_CH_AUTO=1;
esp_err_t spi_device_get_trans_result(spi_device_handle_t, spi_transaction_t **, int);
esp_err_t spi_device_polling_transmit(spi_device_handle_t, spi_transaction_t *);
esp_err_t spi_device_queue_trans(spi_device_handle_t, spi_transaction_t *, int);
esp_err_t spi_bus_remove_device(spi_device_handle_t);
esp_err_t spi_bus_add_device(int, const spi_device_interface_config_t *, spi_device_handle_t *);
esp_err_t spi_device_get_actual_freq(spi_device_handle_t, int *);
esp_err_t spi_bus_initialize(int, const spi_bus_config_t *, int);
esp_err_t spi_bus_free(int);
esp_err_t spi_bus_get_max_transaction_len(int, size_t *);
esp_err_t spi_device_acquire_bus(spi_device_handle_t, int);
void spi_device_release_bus(spi_device_handle_t);
struct esp_lcd_panel_io_t;
using esp_lcd_panel_io_handle_t = esp_lcd_panel_io_t *;
struct esp_lcd_panel_io_event_data_t {};
struct esp_lcd_panel_io_callbacks_t {
    bool (*on_color_trans_done)(esp_lcd_panel_io_handle_t, esp_lcd_panel_io_event_data_t *, void *)=nullptr;
};
struct esp_lcd_panel_io_t {
    esp_err_t (*rx_param)(esp_lcd_panel_io_t *, int, void *, size_t)=nullptr;
    esp_err_t (*tx_param)(esp_lcd_panel_io_t *, int, const void *, size_t)=nullptr;
    esp_err_t (*tx_color)(esp_lcd_panel_io_t *, int, const void *, size_t)=nullptr;
    esp_err_t (*del)(esp_lcd_panel_io_t *)=nullptr;
    esp_err_t (*register_event_callbacks)(esp_lcd_panel_io_t *, const esp_lcd_panel_io_callbacks_t *, void *)=nullptr;
};
struct co5300_lcd_init_cmd_t { int cmd; const void *data; size_t data_bytes; unsigned delay_ms; };
struct co5300_vendor_config_t {
    const co5300_lcd_init_cmd_t *init_cmds=nullptr; size_t init_cmds_size=0;
    struct { unsigned use_qspi_interface=0; } flags;
};
struct esp_lcd_panel_dev_config_t {
    int reset_gpio_num=0, rgb_ele_order=0, bits_per_pixel=0;
    void *vendor_config=nullptr;
};
struct FakePanel;
using esp_lcd_panel_handle_t=FakePanel *;
constexpr int LCD_RGB_ELEMENT_ORDER_RGB=0;
esp_err_t esp_lcd_new_panel_co5300(esp_lcd_panel_io_handle_t, const esp_lcd_panel_dev_config_t *, esp_lcd_panel_handle_t *);
esp_err_t esp_lcd_panel_set_gap(esp_lcd_panel_handle_t, int, int);
esp_err_t esp_lcd_panel_reset(esp_lcd_panel_handle_t);
esp_err_t esp_lcd_panel_init(esp_lcd_panel_handle_t);
esp_err_t esp_lcd_panel_disp_on_off(esp_lcd_panel_handle_t, bool);
esp_err_t esp_lcd_panel_del(esp_lcd_panel_handle_t);
inline esp_err_t esp_lcd_panel_io_del(esp_lcd_panel_io_handle_t io) { return io->del(io); }
constexpr int BSP_LCD_PCLK=38, BSP_LCD_DATA0=4, BSP_LCD_DATA1=5, BSP_LCD_DATA2=6, BSP_LCD_DATA3=7,
    BSP_LCD_CS=12, BSP_LCD_RST=1, BSP_LCD_SPI_NUM=2, BSP_LCD_BITS_PER_PIXEL=16;
