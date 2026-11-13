#pragma once
#include "esp_err.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include <cstddef>
#include <cstdint>
#define IRAM_ATTR
inline void test_log(const char *,const char *,...) {}
#define ESP_LOGI(...) test_log(__VA_ARGS__)
#define ESP_LOGE(...) test_log(__VA_ARGS__)
#define ESP_RETURN_ON_ERROR(call, ...) do { const auto error=(call); if(error!=ESP_OK) return error; } while(0)
using esp_lcd_panel_handle_t = void *;
using esp_lcd_panel_io_handle_t = void *;
struct esp_lcd_panel_io_event_data_t {};
struct lv_display_t { void (*wait)(lv_display_t *); int format; };
struct lv_area_t { int x1,y1,x2,y2; };
struct lv_event_t { void *param; };
struct bsp_display_config_t { size_t max_transfer_sz; };
struct esp_lv_adapter_config_t { int task_core_id; };
#define ESP_LV_ADAPTER_DEFAULT_CONFIG() esp_lv_adapter_config_t{}
struct esp_lv_adapter_display_config_t {
    esp_lcd_panel_handle_t panel;
    esp_lcd_panel_io_handle_t panel_io;
    struct { int interface,rotation,hor_res,ver_res,buffer_height; bool use_psram,require_double_buffer; } profile;
    int tear_avoid_mode;
};
struct esp_lv_adapter_draw_bitmap_callbacks_t {
    esp_err_t (*custom_draw_bitmap)(lv_display_t *,esp_lcd_panel_handle_t,int,int,int,int,const void *,void *);
};
struct esp_lcd_panel_io_callbacks_t {
    bool (*on_color_trans_done)(esp_lcd_panel_io_handle_t,esp_lcd_panel_io_event_data_t *,void *);
};
constexpr int BSP_LCD_H_RES=466, BSP_LCD_V_RES=466, BSP_LCD_BITS_PER_PIXEL=16,
    ESP_LV_ADAPTER_PANEL_IF_OTHER=0, ESP_LV_ADAPTER_ROTATE_0=0, ESP_LV_ADAPTER_TEAR_AVOID_MODE_NONE=0,
    LV_COLOR_FORMAT_RGB565_SWAPPED=1, LV_EVENT_INVALIDATE_AREA=1;
esp_err_t bsp_display_new(const bsp_display_config_t *,esp_lcd_panel_handle_t *,esp_lcd_panel_io_handle_t *);
int64_t esp_timer_get_time();
esp_err_t esp_lcd_panel_io_tx_param(esp_lcd_panel_io_handle_t,int,const void *,size_t);
esp_err_t esp_lcd_panel_io_tx_color(esp_lcd_panel_io_handle_t,int,const void *,size_t);
esp_err_t esp_lcd_panel_io_register_event_callbacks(esp_lcd_panel_io_handle_t,const esp_lcd_panel_io_callbacks_t *,void *);
esp_err_t esp_lcd_panel_co5300_set_brightness(esp_lcd_panel_handle_t,uint8_t);
esp_err_t esp_lv_adapter_init(const esp_lv_adapter_config_t *);
esp_err_t esp_lv_adapter_set_default_display_idf_callback_registration_enabled(bool);
lv_display_t *esp_lv_adapter_register_display(const esp_lv_adapter_display_config_t *);
esp_err_t esp_lv_adapter_set_draw_bitmap_callbacks(lv_display_t *,const esp_lv_adapter_draw_bitmap_callbacks_t *,void *);
bool esp_lv_adapter_display_notify_color_trans_done_from_isr(lv_display_t *);
esp_err_t esp_lv_adapter_start();
esp_err_t esp_lv_adapter_lock(int32_t);
void esp_lv_adapter_unlock();
esp_err_t esp_lv_adapter_fps_stats_enable(lv_display_t *,bool);
esp_err_t esp_lv_adapter_fps_stats_reset(lv_display_t *);
esp_err_t esp_lv_adapter_get_fps(lv_display_t *,uint32_t *);
void lv_display_set_color_format(lv_display_t *,int);
int lv_display_is_double_buffered(lv_display_t *);
int lv_display_get_color_format(lv_display_t *);
void lv_display_set_flush_wait_cb(lv_display_t *,void (*)(lv_display_t *));
void lv_display_add_event_cb(lv_display_t *,void (*)(lv_event_t *),int,void *);
void *lv_event_get_param(lv_event_t *);
