#include "qspi_panel.h"
#include "qspi_test_sdk.h"
#include <cstring>
#include <iostream>
#include <vector>

namespace {
spi_device_interface_config_t device;
struct Pending { spi_transaction_t *pointer; spi_transaction_t copy; bool completed=false; };
std::vector<Pending> queued;
bool dma_capable=true, fail_drain=false, fail_init=false, acquired=false;
int fail_queue_after=-1;
int allocated=0, buses=0, devices=0, controls=0, callbacks=0, yields=0, drains=0;
struct Parameter { unsigned opcode; uint64_t address; std::vector<uint8_t> data; };
std::vector<Parameter> commands;
void check_pending() {
    for (const auto &job : queued) {
        assert(job.pointer->cmd==job.copy.cmd && job.pointer->addr==job.copy.addr);
        assert(job.pointer->tx_buffer==job.copy.tx_buffer && job.pointer->length==job.copy.length);
    }
}
void complete_dma() {
    check_pending();
    for (auto &job : queued) if (!job.completed) {
        job.completed=true;
        device.post_cb(job.pointer); // ISR completion does not recycle the descriptor.
        return;
    }
    assert(false);
}
}
void fake_yield() { ++yields; }
void *heap_caps_malloc(size_t size, int) { ++allocated; return std::malloc(size); }
void heap_caps_free(void *pointer) { --allocated; std::free(pointer); }
bool esp_ptr_dma_capable(const void *) { return dma_capable; }
esp_err_t spi_bus_initialize(int, const spi_bus_config_t *bus, int) {
    assert(!buses && bus->max_transfer_sz==64);
    assert(bus->data4_io_num==-1 && bus->data7_io_num==-1);
    ++buses; return ESP_OK;
}
esp_err_t spi_bus_free(int) { assert(buses && !devices); --buses; return ESP_OK; }
esp_err_t spi_bus_add_device(int, const spi_device_interface_config_t *config, spi_device_handle_t *out) {
    assert(buses && !devices && config->command_bits==8 && config->address_bits==24);
    assert(config->clock_speed_hz==40000000 && config->queue_size==2);
    assert(config->flags==SPI_DEVICE_HALFDUPLEX);
    device=*config; *out=&device; ++devices; return ESP_OK;
}
esp_err_t spi_bus_remove_device(spi_device_handle_t) {
    assert(queued.empty() && devices); --devices; return ESP_OK;
}
esp_err_t spi_device_get_actual_freq(spi_device_handle_t, int *out) { *out=40000; return ESP_OK; }
// Smaller hardware limit deliberately exercises chunking with small fixtures.
esp_err_t spi_bus_get_max_transaction_len(int, size_t *out) {
    if (!devices) return ESP_ERR_INVALID_ARG;
    *out=32; return ESP_OK;
}
esp_err_t spi_device_acquire_bus(spi_device_handle_t, int) { assert(!acquired); acquired=true; return ESP_OK; }
void spi_device_release_bus(spi_device_handle_t) { assert(acquired); acquired=false; }
esp_err_t spi_device_polling_transmit(spi_device_handle_t, spi_transaction_t *transaction) {
    assert(queued.empty()); // Drain before mixing queued DMA and polling commands.
    if (!transaction->length && (transaction->tx_buffer || (transaction->flags & SPI_TRANS_USE_TXDATA)))
        return ESP_ERR_INVALID_ARG; // Same zero-data rule as the real ESP-IDF driver.
    assert(!(transaction->flags & SPI_TRANS_MODE_QIO) && !transaction->user);
    auto *bytes=(transaction->flags & SPI_TRANS_USE_TXDATA) ? transaction->tx_data :
        static_cast<const uint8_t *>(transaction->tx_buffer);
    Parameter item{transaction->cmd,transaction->addr,{}};
    if (transaction->length) item.data.assign(bytes, bytes+transaction->length/8);
    commands.push_back(item); ++controls;
    device.post_cb(transaction);
    return ESP_OK;
}
esp_err_t spi_device_queue_trans(spi_device_handle_t, spi_transaction_t *transaction, int) {
    assert(acquired && queued.size()<2 && transaction->length<=32*8);
    assert((transaction->flags & (SPI_TRANS_MODE_QIO | SPI_TRANS_DMA_BUFFER_ALIGN_MANUAL))==
           (SPI_TRANS_MODE_QIO | SPI_TRANS_DMA_BUFFER_ALIGN_MANUAL));
    if (queued.empty()) assert(transaction->cmd==0x32);
    else {
        auto *extended=reinterpret_cast<spi_transaction_ext_t *>(transaction);
        assert((transaction->flags & (SPI_TRANS_VARIABLE_CMD | SPI_TRANS_VARIABLE_ADDR))==
               (SPI_TRANS_VARIABLE_CMD | SPI_TRANS_VARIABLE_ADDR));
        assert(!extended->command_bits && !extended->address_bits);
    }
    assert(bool(transaction->user)==!(transaction->flags & SPI_TRANS_CS_KEEP_ACTIVE));
    if (fail_queue_after==0) { fail_queue_after=-1; return ESP_FAIL; }
    if (fail_queue_after>0) --fail_queue_after;
    check_pending(); queued.push_back({transaction,*transaction,false}); return ESP_OK;
}
esp_err_t spi_device_get_trans_result(spi_device_handle_t, spi_transaction_t **out, int) {
    assert(!queued.empty()); check_pending(); ++drains;
    if (fail_drain) { fail_drain=false; return ESP_FAIL; }
    if (!queued.front().completed) complete_dma(); // Emulate blocking get-result.
    *out=queued.front().pointer; queued.erase(queued.begin()); return ESP_OK;
}
struct FakePanel { esp_lcd_panel_io_handle_t io; co5300_vendor_config_t vendor; };
esp_err_t esp_lcd_new_panel_co5300(esp_lcd_panel_io_handle_t io, const esp_lcd_panel_dev_config_t *config, esp_lcd_panel_handle_t *out) {
    *out=new FakePanel{io,*static_cast<co5300_vendor_config_t *>(config->vendor_config)};
    return ESP_OK;
}
esp_err_t esp_lcd_panel_set_gap(esp_lcd_panel_handle_t, int x, int y) { assert(x==6 && y==0); return ESP_OK; }
esp_err_t esp_lcd_panel_reset(esp_lcd_panel_handle_t) { return ESP_OK; }
esp_err_t esp_lcd_panel_init(esp_lcd_panel_handle_t panel) {
    if (fail_init) return ESP_FAIL;
    for (size_t i=0; i<panel->vendor.init_cmds_size; ++i) {
        const auto &cmd=panel->vendor.init_cmds[i];
        auto result=panel->io->tx_param(panel->io,0x02000000|(cmd.cmd<<8),cmd.data,cmd.data_bytes);
        if (result!=ESP_OK) return result;
    }
    return ESP_OK;
}
esp_err_t esp_lcd_panel_disp_on_off(esp_lcd_panel_handle_t, bool) { return ESP_OK; }
esp_err_t esp_lcd_panel_del(esp_lcd_panel_handle_t panel) { delete panel; return ESP_OK; }

int main() {
    esp_lcd_panel_handle_t panel=nullptr; esp_lcd_panel_io_handle_t io=nullptr;
    assert(motonav::drivers::create_qspi_panel(64,nullptr,&io)==ESP_ERR_INVALID_ARG);
    fail_init=true;
    assert(motonav::drivers::create_qspi_panel(64,&panel,&io)==ESP_FAIL);
    assert(!panel && !io && !allocated && !devices && !buses);
    fail_init=false;
    assert(motonav::drivers::create_qspi_panel(64,&panel,&io)==ESP_OK);
    assert(commands.size()==14 && controls==14 && callbacks==0);
    assert(commands[10].opcode==2 && commands[10].address==0x2a00);
    assert(commands[10].data==std::vector<uint8_t>({0,6,1,0xd7}));
    assert(commands[12].address==0x1100 && commands[12].data.empty());
    int context=42;
    esp_lcd_panel_io_callbacks_t events{[](esp_lcd_panel_io_handle_t, esp_lcd_panel_io_event_data_t *, void *value) {
        assert(*static_cast<int *>(value)==42); ++callbacks; return true;
    }};
    assert(io->register_event_callbacks(io,&events,&context)==ESP_OK);
    alignas(4) uint8_t pixels[68]{};
    assert(io->tx_color(io,0x32002c00,pixels,64)==ESP_OK);
    assert(queued.size()==2 && queued[0].pointer->cmd==0x32 && queued[0].pointer->addr==0x002c00);
    assert(queued[0].pointer->tx_buffer==pixels && queued[1].pointer->tx_buffer==pixels+32 && !callbacks);
    complete_dma();
    assert(callbacks==0 && yields==0); // Never finish a strip after only its first chunk.
    complete_dma();
    assert(callbacks==1 && yields==1 && queued.size()==2);
    assert(io->tx_color(io,0x32003c00,pixels+4,60)==ESP_OK);
    assert(drains==2 && queued[0].pointer->addr==0x003c00 && queued[0].pointer->tx_buffer==pixels+4);
    uint8_t brightness=178;
    assert(io->tx_param(io,0x02005100,&brightness,1)==ESP_OK);
    assert(queued.empty() && callbacks==2 && commands.back().data[0]==178);
    assert(io->tx_param(io,0x02001100,nullptr,0)==ESP_OK);
    assert(callbacks==2 && commands.back().data.empty());
    assert(io->tx_color(io,0x32002c00,pixels,68)==ESP_ERR_INVALID_ARG);
    assert(io->tx_color(io,0x32002c00,pixels+2,60)==ESP_ERR_INVALID_ARG);
    assert(io->tx_color(io,0x32002c00,pixels,62)==ESP_ERR_INVALID_ARG);
    assert(io->tx_color(io,0x32002c00,nullptr,64)==ESP_ERR_INVALID_ARG);
    dma_capable=false;
    assert(io->tx_color(io,0x32002c00,pixels,64)==ESP_ERR_INVALID_ARG);
    dma_capable=true; fail_queue_after=0;
    assert(io->tx_color(io,0x32002c00,pixels,64)==ESP_FAIL && queued.empty());
    assert(io->tx_color(io,0x32002c00,pixels,64)==ESP_OK);
    fail_drain=true;
    assert(io->tx_color(io,0x32003c00,pixels+4,60)==ESP_FAIL);
    assert(queued[0].pointer->addr==0x002c00 && queued[0].pointer->tx_buffer==pixels); // Failure cannot reuse DMA memory.
    assert(io->tx_color(io,0x32003c00,pixels+4,60)==ESP_OK);
    assert(esp_lcd_panel_del(panel)==ESP_OK);
    assert(io->del(io)==ESP_OK && queued.empty() && !devices && !allocated);
    assert(spi_bus_free(BSP_LCD_SPI_NUM)==ESP_OK && !buses);
    assert(motonav::drivers::create_qspi_panel(64,&panel,&io)==ESP_OK);
    assert(io->register_event_callbacks(io,&events,&context)==ESP_OK);
    int before=callbacks;
    fail_queue_after=1; // Accept first CS-held chunk, reject the final chunk.
    assert(io->tx_color(io,0x32002c00,pixels,64)==ESP_FAIL);
    assert(queued.empty() && callbacks==before && !acquired);
    assert(io->tx_color(io,0x32002c00,pixels,64)==ESP_ERR_INVALID_STATE);
    assert(io->tx_param(io,0x02005100,&brightness,1)==ESP_ERR_INVALID_STATE);
    assert(esp_lcd_panel_del(panel)==ESP_OK && io->del(io)==ESP_OK);
    assert(spi_bus_free(BSP_LCD_SPI_NUM)==ESP_OK && !allocated && !buses);
    std::cout << "PASS: real QSPI implementation, command framing, DMA ownership, callbacks, errors and cleanup\n";
}
