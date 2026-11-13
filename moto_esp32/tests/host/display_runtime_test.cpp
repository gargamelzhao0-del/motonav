#include "display_test_sdk.h"
#include "motonav/drivers/display.h"
#include "motonav/drivers/platform.h"
#include "qspi_panel.h"
#include "device_supervisor.h"
#include <cassert>
#include <cstring>
#include <iostream>
#include <string>
using namespace motonav::drivers;
struct Parked {};
struct Restarted {};
static lv_display_t screen{};
static esp_lv_adapter_draw_bitmap_callbacks_t draw;
static esp_lcd_panel_io_callbacks_t io_callbacks;
static void *draw_context, *io_context;
static int locks, lock_calls, color_calls, controls, completions, restarts;
static bool color_failure, control_failure, brightness_failure, fault_during_start;
static bool fault_during_lock, lock_succeeds_after_fault;
static const void *dma_buffer;
static size_t dma_bytes;
static TickType_t waited;
SemaphoreHandle_t xSemaphoreCreateMutex() { return new int(1); }
SemaphoreHandle_t xSemaphoreCreateBinary() { return new int(0); }
BaseType_t xSemaphoreTake(SemaphoreHandle_t handle,TickType_t ticks) {
    auto &token=*static_cast<int *>(handle);
    if(!token) { waited=ticks; return pdFALSE; }
    --token; return pdTRUE;
}
BaseType_t xSemaphoreGive(SemaphoreHandle_t handle) { *static_cast<int *>(handle)=1; return pdTRUE; }
BaseType_t xSemaphoreGiveFromISR(SemaphoreHandle_t handle,BaseType_t *wake) { *wake=pdFALSE; return xSemaphoreGive(handle); }
void vSemaphoreDelete(SemaphoreHandle_t handle) { delete static_cast<int *>(handle); }
BaseType_t xTaskCreatePinnedToCore(void (*fn)(void *),const char *,uint32_t,void *context,unsigned,void *,int core) {
    assert(core==1); fn(context); return pdPASS;
}
void vTaskDelete(void *) {}
void vTaskDelay(TickType_t) { throw Parked{}; } // Stop the intentional parking loop in host tests only.
int64_t esp_timer_get_time() { static int64_t time; return time+=10; }
esp_err_t bsp_display_new(const bsp_display_config_t *,esp_lcd_panel_handle_t *panel,esp_lcd_panel_io_handle_t *io) {
    *panel=&screen; *io=&screen; return ESP_OK;
}
namespace motonav::drivers {
esp_err_t create_qspi_panel(size_t,esp_lcd_panel_handle_t *panel,esp_lcd_panel_io_handle_t *io) {
    *panel=&screen; *io=&screen; return ESP_OK;
}
esp_err_t qspi_panel_set_color(esp_lcd_panel_io_handle_t,DisplayColor) { return ESP_OK; }
[[noreturn]] void restart_device() { ++restarts; throw Restarted{}; }
}
esp_err_t esp_lcd_panel_io_tx_param(esp_lcd_panel_io_handle_t,int,const void *,size_t) {
    ++controls; return control_failure?ESP_FAIL:ESP_OK;
}
esp_err_t esp_lcd_panel_io_tx_color(esp_lcd_panel_io_handle_t,int,const void *buffer,size_t bytes) {
    ++color_calls; dma_buffer=buffer; dma_bytes=bytes;
    // Failure may still leave a legacy backend owning this buffer.
    return color_failure?ESP_FAIL:ESP_OK;
}
esp_err_t esp_lcd_panel_io_register_event_callbacks(esp_lcd_panel_io_handle_t,const esp_lcd_panel_io_callbacks_t *c,void *ctx) {
    io_callbacks=*c; io_context=ctx; return ESP_OK;
}
esp_err_t esp_lcd_panel_co5300_set_brightness(esp_lcd_panel_handle_t,uint8_t) { return brightness_failure?ESP_FAIL:ESP_OK; }
esp_err_t esp_lv_adapter_init(const esp_lv_adapter_config_t *) { return ESP_OK; }
esp_err_t esp_lv_adapter_set_default_display_idf_callback_registration_enabled(bool enabled) { assert(!enabled); return ESP_OK; }
lv_display_t *esp_lv_adapter_register_display(const esp_lv_adapter_display_config_t *) { return &screen; }
esp_err_t esp_lv_adapter_set_draw_bitmap_callbacks(lv_display_t *,const esp_lv_adapter_draw_bitmap_callbacks_t *c,void *ctx) {
    draw=*c; draw_context=ctx; return ESP_OK;
}
bool esp_lv_adapter_display_notify_color_trans_done_from_isr(lv_display_t *) { ++completions; return false; }
esp_err_t esp_lv_adapter_start() {
    if(fault_during_start) {
        try { draw.custom_draw_bitmap(&screen,nullptr,1,0,2,2,nullptr,draw_context); }
        catch(const Parked &) {}
    }
    return ESP_OK;
}
esp_err_t esp_lv_adapter_lock(int32_t timeout) {
    ++lock_calls;
    if(fault_during_lock) {
        assert(timeout==100); fault_during_lock=false;
        try { draw.custom_draw_bitmap(&screen,nullptr,1,0,2,2,nullptr,draw_context); }
        catch(const Parked &) {}
        if(!lock_succeeds_after_fault) return ESP_ERR_TIMEOUT;
    }
    ++locks; return ESP_OK;
}
void esp_lv_adapter_unlock() { assert(locks); --locks; }
esp_err_t esp_lv_adapter_fps_stats_enable(lv_display_t *,bool) { assert(locks); return ESP_OK; }
esp_err_t esp_lv_adapter_fps_stats_reset(lv_display_t *) { assert(locks); return ESP_OK; }
esp_err_t esp_lv_adapter_get_fps(lv_display_t *,uint32_t *fps) { assert(locks); *fps=0; return ESP_OK; }
void lv_display_set_color_format(lv_display_t *d,int format) { d->format=format; }
int lv_display_is_double_buffered(lv_display_t *) { return 1; }
int lv_display_get_color_format(lv_display_t *d) { return d->format; }
void lv_display_set_flush_wait_cb(lv_display_t *d,void (*fn)(lv_display_t *)) { d->wait=fn; }
void lv_display_add_event_cb(lv_display_t *,void (*)(lv_event_t *),int,void *) {}
void *lv_event_get_param(lv_event_t *event) { return event->param; }
static void complete() { io_callbacks.on_color_trans_done(nullptr,nullptr,io_context); }
template<class F> static void parked(F fn) {
    bool blocked=false;
    try { fn(); } catch(const Parked &) { blocked=true; }
    assert(blocked);
}
int main(int argc,char **argv) {
    assert(argc==2);
    const std::string mode=argv[1];
    lv_display_t *display=nullptr;
    assert(display_status().state==DisplayState::NotInitialized);
    motonav::app::supervise_display(); assert(!restarts && !lock_calls);
    DisplayConfig config;
    if(mode=="submit_legacy") { config.direct_qspi=false; config.color=DisplayColor::Rgb565; }
    assert(display_init(&display,config)==ESP_OK && display_status().state==DisplayState::Ready);
    fault_during_start=mode=="start_fault";
    assert(display_start()==ESP_OK);
    if(!fault_during_start) assert(display_status().state==DisplayState::Running);
    alignas(16) uint16_t pixels[4]{0x00f8,0x00f8,0x00f8,0x00f8};
    auto flush=[&]() { return draw.custom_draw_bitmap(display,nullptr,0,0,2,2,pixels,draw_context); };
    if(mode=="normal") {
        uint32_t fps;
        assert(display_enable_fps(display,true)==ESP_OK && !locks);
        assert(display_reset_fps(display)==ESP_OK && !locks);
        assert(display_read_fps(display,&fps)==ESP_OK && !locks);
        assert(display_enable_metrics(true)==ESP_OK);
        assert(flush()==ESP_OK && color_calls==1 && completions==0 && dma_bytes==4);
        complete(); display->wait(display);
        DisplayMetrics metrics;
        assert(display_read_metrics(&metrics)==ESP_OK && metrics.bytes==4 && metrics.transfers==1);
        assert(display_set_brightness(70)==ESP_OK && !locks);
        motonav::app::supervise_display(); assert(!restarts);
    } else {
        DisplayFault reason=DisplayFault::None;
        if(mode=="timeout" || mode=="brightness_timeout" || mode=="metrics_timeout") {
            if(mode=="metrics_timeout") assert(display_enable_metrics(true)==ESP_OK);
            assert(flush()==ESP_OK && completions==0);
            if(mode=="timeout") parked([&] { display->wait(display); });
            else if(mode=="brightness_timeout") assert(display_set_brightness(70)==ESP_ERR_TIMEOUT && !locks);
            else { DisplayMetrics metrics; assert(display_read_metrics(&metrics)==ESP_ERR_TIMEOUT && !locks); }
            assert(waited==250 && completions==0 && dma_buffer==pixels);
            reason=DisplayFault::DmaTimeout;
        } else if(mode=="submit" || mode=="submit_legacy") {
            color_failure=true; parked(flush);
            assert(color_calls==1 && dma_buffer==pixels && !completions);
            reason=DisplayFault::ColorTransfer;
        } else if(mode=="control") {
            control_failure=true; parked(flush); assert(!color_calls);
            reason=DisplayFault::ControlTransfer;
        } else if(mode=="brightness") {
            brightness_failure=true; assert(display_set_brightness(70)==ESP_FAIL && !locks);
            reason=DisplayFault::ControlTransfer;
        } else if(mode=="fps_lock_fault" || mode=="fps_lock_race") {
            fault_during_lock=true;
            lock_succeeds_after_fault=mode=="fps_lock_race";
            uint32_t fps;
            assert(display_read_fps(display,&fps)==ESP_ERR_INVALID_STATE && !locks);
            reason=DisplayFault::InvalidBuffer;
        } else if(mode=="start_fault") reason=DisplayFault::InvalidBuffer;
        else assert(false);
        assert(display_status().state==DisplayState::Faulted && display_status().fault==reason);
        const auto error=display_status().error;
        assert(error!=ESP_OK);
        complete(); // A late real ISR must never revive a faulted renderer.
        const auto writes=color_calls;
        const auto commands=controls;
        std::memset(pixels,0x5a,sizeof(pixels));
        parked(flush);
        for(auto p:pixels) assert(p==0x5a5a);
        assert(color_calls==writes && controls==commands && display_status().fault==reason);
        assert(display_lock()==ESP_ERR_INVALID_STATE && display_set_brightness(70)==error);
        uint32_t fps;
        assert(display_enable_fps(display,true)==ESP_ERR_INVALID_STATE);
        assert(display_reset_fps(display)==ESP_ERR_INVALID_STATE);
        assert(display_read_fps(display,&fps)==ESP_ERR_INVALID_STATE);
        const auto before=lock_calls;
        try { motonav::app::supervise_display(); } catch(const Restarted &) {}
        assert(restarts==1 && lock_calls==before); // Recovery does not need the stalled LVGL lock.
    }
    std::cout << "PASS: real display runtime / " << mode << " / ownership and application supervision\n";
}
