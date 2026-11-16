#include "navigation_service.h"
#include <cassert>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

using namespace motonav;
static unsigned takes, gives, clocks;
static bool locked;
static bool allocation_fails;
static unsigned allocations,releases;
static esp_err_t enqueue_error;
static std::vector<std::string> queued;
SemaphoreHandle_t xSemaphoreCreateMutex() { return &locked; }
BaseType_t xSemaphoreTake(SemaphoreHandle_t, TickType_t) {
    assert(!locked); locked = true; ++takes; return pdTRUE;
}
BaseType_t xSemaphoreGive(SemaphoreHandle_t) {
    assert(locked); locked = false; ++gives; return pdTRUE;
}
namespace motonav::drivers {
void *allocate_external(size_t bytes) { if(allocation_fails)return nullptr;++allocations;return std::malloc(bytes); }
void free_external(void *memory) { ++releases;std::free(memory); }
uint64_t monotonic_us() { ++clocks; return 100000; }
esp_err_t ble_notify_batch(const BleNotification *messages, size_t count) {
    assert(!locked); // Enqueue never holds the business-state mutex.
    if (enqueue_error) return enqueue_error;
    for (size_t i = 0; i < count; ++i)
        queued.emplace_back(reinterpret_cast<const char *>(messages[i].data), messages[i].length);
    return ESP_OK;
}
}
static std::string frame(const std::string &body) {
    char tail[12];
    std::snprintf(tail, sizeof(tail), ",%08X\n", NavigationReceiver::crc32(body.data(), body.size()));
    return body + tail;
}
int main(int argc, char **argv) {
    allocation_fails=true;
    {app::NavigationService unavailable;assert(unavailable.init()==ESP_ERR_NO_MEM);}
    assert(allocations==0&&releases==0);allocation_fails=false;
    app::NavigationService service;
    assert(service.init() == ESP_OK);
    const auto cb = service.callbacks();
    auto send = [&](const std::string &bytes) {
        return cb.receive(cb.context, reinterpret_cast<const uint8_t *>(bytes.data()), bytes.size());
    };
    auto event = [&](drivers::BleEventType type, uint32_t value = 1) { cb.event(cb.context, {type, value}); };
    const auto hello = frame("H,3,1,123");
    const auto live = frame("N,3,2,123,2,3,250,12000,1000,50,0,0");
    using Result = drivers::BleReceiveResult;
    assert(send(hello) == Result::Rejected && queued.empty());
    event(drivers::BleEventType::Connected);
    event(drivers::BleEventType::Subscription);
    event(drivers::BleEventType::Security);
    for (size_t split = 1; split < live.size(); ++split) {
        event(drivers::BleEventType::Subscription, 0);
        event(drivers::BleEventType::Subscription);
        assert(send(hello) == Result::Accepted);
        assert(send(live.substr(0, split)) == Result::Accepted);
        const auto before = service.snapshot();
        const auto replies = queued.size();
        enqueue_error = ESP_ERR_NO_MEM;
        assert(send(live.substr(split)) == Result::NoMemory);
        const auto failed = service.snapshot();
        assert(failed.packets.accepted == before.packets.accepted && !failed.has_guidance);
        assert(queued.size() == replies);
        enqueue_error = ESP_OK;
        takes = gives = clocks = 0;
        assert(send(live.substr(split)) == Result::Accepted);
        assert(takes == 2 && gives == 2 && clocks == 1); // Per-write, not per-byte.
        assert(queued.back() == "A,2,OK\n" && service.snapshot().guidance.mode == Live);
    }
    event(drivers::BleEventType::Disconnected);
    event(drivers::BleEventType::Connected);
    event(drivers::BleEventType::Subscription);
    event(drivers::BleEventType::Security);
    enqueue_error = ESP_FAIL;
    const auto before = service.snapshot();
    assert(send(hello + live) == Result::Rejected);
    assert(!service.snapshot().handshaken && service.snapshot().packets.accepted == before.packets.accepted);
    enqueue_error = ESP_OK;
    assert(send(hello + live) == Result::Accepted);
    assert(queued[queued.size()-2] == "A,1,NAV3\n" && queued.back() == "A,2,OK\n");
    const auto accepted = service.snapshot().packets.accepted;
    assert(send(std::string(33, '\n')) == Result::NoMemory);
    assert(service.snapshot().packets.accepted == accepted && service.snapshot().packets.rejected == 0);
    assert(send(std::string(32, '\n')) == Result::Accepted && queued.back() == "E,NAV3\n");
    event(drivers::BleEventType::Security, 0);
    assert(send(live) == Result::Rejected);
    assert(argc == 2);
    std::ifstream input(argv[1]);
    std::vector<std::string> frames;
    std::string hex;
    while (std::getline(input, hex)) {
        if (hex.empty()) continue;
        std::string bytes;
        for (size_t i=0; i<hex.size(); i+=2) bytes += static_cast<char>(std::stoul(hex.substr(i,2), nullptr,16));
        frames.push_back(bytes);
    }
    assert(frames.size()==6 && frames[5].size()==304);
    for (size_t index : {size_t(1), size_t(5)}) {
        const auto &route=frames[index];
        for (size_t split=1; split<route.size(); ++split) {
            event(drivers::BleEventType::Disconnected);
            event(drivers::BleEventType::Connected);
            event(drivers::BleEventType::Protocol,4);
            event(drivers::BleEventType::Security);
            event(drivers::BleEventType::Subscription);
            assert(send(frames[0])==Result::Accepted && queued.back()=="A,1,NAV4\n");
            assert(send(route.substr(0,split))==Result::Accepted);
            const auto before_route=service.snapshot();
            const auto replies=queued.size();
            enqueue_error=ESP_ERR_NO_MEM;
            assert(send(route.substr(split))==Result::NoMemory);
            const auto failed=service.snapshot();
            assert(failed.protocol==4 && failed.route.count==0 && !failed.has_guidance);
            assert(failed.packets.accepted==before_route.packets.accepted && queued.size()==replies);
            enqueue_error=ESP_OK;
            takes=gives=clocks=0;
            assert(send(route.substr(split))==Result::Accepted);
            assert(takes==2 && gives==2 && clocks==1);
            const auto committed=service.snapshot();
            assert(committed.route.count==(index==1?4:64) && committed.has_guidance);
            assert(queued.back()==(index==1?"A,2,OK\n":"A,6,OK\n"));
        }
    }
    // A valid route followed by an overfull error batch must publish neither.
    event(drivers::BleEventType::Subscription,0);
    event(drivers::BleEventType::Subscription);
    assert(send(frames[0])==Result::Accepted);
    const auto replies=queued.size();
    assert(send(frames[1]+std::string(32,'!'))==Result::NoMemory);
    assert(!service.snapshot().has_guidance && service.snapshot().route.count==0 && queued.size()==replies);
    assert(send(frames[1])==Result::Accepted && service.snapshot().route.count==4);
    std::cout << "PASS: real NAV3/NAV4 service, every split retry including 304-byte route, atomic rollback and bounded lock work\n";
}
