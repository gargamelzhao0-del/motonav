#include "display_port.h"
#include "strip_stream.h"
#include "bsp/esp-bsp.h"
#include "bsp/touch.h"
#include "esp_check.h"
#include "esp_timer.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_panel_io.h"
#include "freertos/semphr.h"

namespace motonav {
namespace {
struct PanelStream {
    esp_lcd_panel_io_handle_t io;
    StripStream strips;
    SemaphoreHandle_t done;
    bool pending = false; // Accessed by the LVGL task only.
};
PanelStream panel_stream{};
#if MOTONAV_AUTORUN_BENCH || MOTONAV_AUTORUN_MAP_BENCH
volatile unsigned io_stage;
#endif
void io_progress([[maybe_unused]] unsigned stage) {
#if MOTONAV_AUTORUN_BENCH || MOTONAV_AUTORUN_MAP_BENCH
    io_stage=stage;
#endif
}
esp_err_t create_panel_on_ui_core(const bsp_display_config_t &config,
                                 esp_lcd_panel_handle_t *panel, esp_lcd_panel_io_handle_t *io) {
    struct Request {
        const bsp_display_config_t *config;
        esp_lcd_panel_handle_t *panel;
        esp_lcd_panel_io_handle_t *io;
        SemaphoreHandle_t done;
        esp_err_t result;
    } request{&config, panel, io, xSemaphoreCreateBinary(), ESP_FAIL};
    if (!request.done) return ESP_ERR_NO_MEM;
    // SPI allocates its ISR on the calling core. Keep queued/polling SPI work
    // and its completion interrupt on the same core as the display task.
    BaseType_t created = xTaskCreatePinnedToCore([](void *context) {
        auto &request = *static_cast<Request *>(context);
        request.result = bsp_display_new(request.config, request.panel, request.io);
        xSemaphoreGive(request.done);
        vTaskDelete(nullptr);
    }, "panel-init", 4096, &request, 6, nullptr, 1);
    if (created == pdPASS) xSemaphoreTake(request.done, portMAX_DELAY);
    vSemaphoreDelete(request.done);
    return created == pdPASS ? request.result : ESP_ERR_NO_MEM;
}
void wait_transfer(lv_display_t *) {
    if (!panel_stream.pending) return;
    // Keep ownership until the actual DMA callback. A timeout reports a fault;
    // it must never pretend completion or reuse a buffer still owned by SPI.
    while (xSemaphoreTake(panel_stream.done, pdMS_TO_TICKS(1000)) != pdTRUE)
        ESP_LOGE("display", "waiting for SPI DMA completion");
    panel_stream.pending = false;
}
bool transfer_done(esp_lcd_panel_io_handle_t, esp_lcd_panel_io_event_data_t *, void *context) {
    auto *display = static_cast<lv_display_t *>(context);
    bool yield = esp_lv_adapter_display_notify_color_trans_done_from_isr(display);
    BaseType_t wake = pdFALSE;
    xSemaphoreGiveFromISR(panel_stream.done, &wake);
    // IDF 5.5's SPI LCD post-callback ignores the boolean return value. Request
    // the switch explicitly, after ISR bookkeeping completes on this core,
    // instead of adding up to one tick of idle bus time to every strip.
    if (yield || wake == pdTRUE) portYIELD_FROM_ISR();
    return yield || wake == pdTRUE;
}

esp_err_t draw_strip(lv_display_t *, esp_lcd_panel_handle_t panel, int x1, int y1, int x2, int y2,
                     const void *pixels, void *context) {
    auto &stream = *static_cast<PanelStream *>(context);
    io_progress(1);
    // Also consume completion when LVGL saw flush_ready before entering its
    // wait callback. No completion token may carry over to the next transfer.
    wait_transfer(nullptr);
    io_progress(2);
    auto plan = stream.strips.begin(x1, y1, x2, y2);
    if (plan == StripStream::Partial) {
        stream.pending = true;
        esp_err_t result = esp_lcd_panel_draw_bitmap(panel, x1, y1, x2, y2, pixels);
        if (result != ESP_OK) stream.pending = false;
        return result;
    }
    if (plan == StripStream::Start) {
        // Same 6-pixel column offset as the BSP. The window uses the actual
        // dirty rectangle's columns and extends downward for following bands.
        // This also pipelines partial map updates instead of draining SPI for
        // two address commands before every band.
        const uint16_t left = static_cast<uint16_t>(x1 + 6), right = static_cast<uint16_t>(x2 + 5);
        const uint8_t columns[]{static_cast<uint8_t>(left >> 8), static_cast<uint8_t>(left),
                                static_cast<uint8_t>(right >> 8), static_cast<uint8_t>(right)};
        const uint8_t rows[]{static_cast<uint8_t>(y1 >> 8), static_cast<uint8_t>(y1), 0x01, 0xd1};
        ESP_RETURN_ON_ERROR(esp_lcd_panel_io_tx_param(stream.io, 0x02002a00, columns, sizeof(columns)), "display", "columns");
        io_progress(3);
        ESP_RETURN_ON_ERROR(esp_lcd_panel_io_tx_param(stream.io, 0x02002b00, rows, sizeof(rows)), "display", "rows");
    }
    io_progress(4);
    // RAMWRC is also used by the board vendor's Arduino QSPI driver. Keep
    // the adapter's actual DMA completion callback and FPS counter intact.
    int command = plan == StripStream::Continue ? 0x32003c00 : 0x32002c00;
    stream.pending = true;
    esp_err_t result = esp_lcd_panel_io_tx_color(stream.io, command, pixels, (y2 - y1) * (x2 - x1) * 2);
    if (result != ESP_OK) stream.pending = false;
    ESP_RETURN_ON_ERROR(result, "display", "pixels");
    io_progress(5);
    stream.strips.submitted(y2);
    return ESP_OK;
}
#if MOTONAV_AUTORUN_BENCH || MOTONAV_AUTORUN_MAP_BENCH
volatile unsigned profile_stage, profile_at_ms;
void profile_frame(lv_event_t *event) {
    static int64_t frame_start, flush_start, wait_start;
    static int64_t render_us, flush_us, wait_us;
    static unsigned frames;
    int64_t now = esp_timer_get_time();
    switch (lv_event_get_code(event)) {
        case LV_EVENT_RENDER_START: profile_stage=1; frame_start = now; break;
        case LV_EVENT_FLUSH_START: profile_stage=2; flush_start = now; break;
        case LV_EVENT_FLUSH_FINISH: profile_stage=3; flush_us += now - flush_start; break;
        case LV_EVENT_FLUSH_WAIT_START: profile_stage=4; wait_start = now; break;
        case LV_EVENT_FLUSH_WAIT_FINISH: profile_stage=5; wait_us += now - wait_start; break;
        case LV_EVENT_RENDER_READY:
            profile_stage=6;
            render_us += now - frame_start;
            if (++frames == 120) {
                ESP_LOGI("display_profile", "render_us=%lld flush_call_us=%lld flush_wait_us=%lld",
                    static_cast<long long>(render_us / frames), static_cast<long long>(flush_us / frames),
                    static_cast<long long>(wait_us / frames));
                frames = 0; render_us = flush_us = wait_us = 0;
            }
            break;
        default: return;
    }
    profile_at_ms = static_cast<unsigned>(now/1000);
}
#endif
void round_area(lv_event_t *event) {
    auto *area = static_cast<lv_area_t *>(lv_event_get_param(event));
    // CO5300 requires even starts and odd ends, including for partial updates.
    area->x1 &= ~1; area->y1 &= ~1;
    area->x2 |= 1; area->y2 |= 1;
}
}

void log_display_diagnostics() {
#if MOTONAV_AUTORUN_BENCH || MOTONAV_AUTORUN_MAP_BENCH
    ESP_LOGI("display_profile", "stage=%u io=%u age_ms=%u", profile_stage, io_stage,
             static_cast<unsigned>(esp_timer_get_time()/1000)-profile_at_ms);
#endif
}
lv_display_t *start_display() {
    esp_lv_adapter_config_t adapter = ESP_LV_ADAPTER_DEFAULT_CONFIG();
    adapter.task_core_id = 1;
    ESP_ERROR_CHECK(esp_lv_adapter_init(&adapter));
    ESP_ERROR_CHECK(esp_lv_adapter_set_default_display_idf_callback_registration_enabled(false));
    panel_stream.done = xSemaphoreCreateBinary();
    ESP_ERROR_CHECK(panel_stream.done ? ESP_OK : ESP_ERR_NO_MEM);

    esp_lcd_panel_handle_t panel = nullptr;
    esp_lcd_panel_io_handle_t io = nullptr;
    bsp_display_config_t hardware{};
    hardware.max_transfer_sz = BSP_LCD_H_RES * BSP_LCD_V_RES * BSP_LCD_BITS_PER_PIXEL / 8;
    // Keep the vendor's reset sequence, panel commands and 40 MHz QSPI clock.
    ESP_ERROR_CHECK(create_panel_on_ui_core(hardware, &panel, &io));
    esp_lv_adapter_display_config_t config{};
    config.panel = panel; config.panel_io = io;
    config.profile.interface = ESP_LV_ADAPTER_PANEL_IF_OTHER;
    config.profile.rotation = ESP_LV_ADAPTER_ROTATE_0;
    config.profile.hor_res = BSP_LCD_H_RES; config.profile.ver_res = BSP_LCD_V_RES;
    config.profile.buffer_height = 64;
    // Two 59,648-byte internal buffers avoid rendering into external PSRAM
    // and the SPI driver's temporary DMA bounce-buffer copies.
    config.profile.use_psram = false;
    config.profile.require_double_buffer = true;
    config.tear_avoid_mode = ESP_LV_ADAPTER_TEAR_AVOID_MODE_NONE;
    auto *display = esp_lv_adapter_register_display(&config);
    ESP_ERROR_CHECK(display ? ESP_OK : ESP_ERR_NO_MEM);
    // Render in the panel's byte order. The adapter can submit these pixels
    // directly, keeping conversion out of the interval after DMA completion.
    lv_display_set_color_format(display, LV_COLOR_FORMAT_RGB565_SWAPPED);
    ESP_LOGI("display", "double_buffer=%d color_format=%d", lv_display_is_double_buffered(display), lv_display_get_color_format(display));
    panel_stream.io = io;
    esp_lv_adapter_draw_bitmap_callbacks_t callbacks{};
    callbacks.custom_draw_bitmap = draw_strip;
    ESP_ERROR_CHECK(esp_lv_adapter_set_draw_bitmap_callbacks(display, &callbacks, &panel_stream));
    esp_lcd_panel_io_callbacks_t io_callbacks{};
    io_callbacks.on_color_trans_done = transfer_done;
    ESP_ERROR_CHECK(esp_lcd_panel_io_register_event_callbacks(io, &io_callbacks, display));
    lv_display_set_flush_wait_cb(display, wait_transfer);
    lv_display_add_event_cb(display, round_area, LV_EVENT_INVALIDATE_AREA, nullptr);
#if MOTONAV_AUTORUN_BENCH || MOTONAV_AUTORUN_MAP_BENCH
    lv_display_add_event_cb(display, profile_frame, LV_EVENT_ALL, nullptr);
#endif

    bsp_display_cfg_t touch_hardware{};
    touch_hardware.touch_flags.mirror_x = touch_hardware.touch_flags.mirror_y = 1;
    esp_lcd_touch_handle_t touch = nullptr;
    ESP_ERROR_CHECK(bsp_touch_new(&touch_hardware, &touch));
    esp_lv_adapter_touch_config_t touch_config = ESP_LV_ADAPTER_TOUCH_DEFAULT_CONFIG(display, touch);
    ESP_ERROR_CHECK(esp_lv_adapter_register_touch(&touch_config) ? ESP_OK : ESP_FAIL);
    ESP_ERROR_CHECK(bsp_display_brightness_init());
    // Panel commands must finish before the UI task starts submitting SPI DMA.
    ESP_ERROR_CHECK(bsp_display_brightness_set(70));
    ESP_ERROR_CHECK(esp_lv_adapter_start());
    return display;
}
}
