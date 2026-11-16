#include "motonav/diagnostics/serial_bridge.h"
#include "motonav/drivers/platform.h"
#include <cassert>
#include <deque>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>
using namespace motonav::diagnostics;
using motonav::drivers::BleReceiveResult;
static std::deque<std::vector<uint8_t>> queue;
static size_t item_size;
static bool fail_create=false,fail_task=false;
static void (*worker)(void *);
static void *worker_context;
static uint64_t now_ms;
static std::vector<std::string> output;
struct Empty {};
QueueHandle_t xQueueCreate(unsigned count,size_t size){assert(count==4);item_size=size;return fail_create?nullptr:&queue;}
BaseType_t xQueueSend(QueueHandle_t,const void *value,TickType_t wait){assert(wait==0);if(queue.size()==4)return pdFALSE;const auto *p=static_cast<const uint8_t *>(value);queue.emplace_back(p,p+item_size);return pdTRUE;}
BaseType_t xQueueReceive(QueueHandle_t,void *value,TickType_t wait){assert(wait==portMAX_DELAY);if(queue.empty())throw Empty{};std::memcpy(value,queue.front().data(),item_size);queue.pop_front();return pdTRUE;}
void vQueueDelete(QueueHandle_t){queue.clear();}
BaseType_t xTaskCreate(void (*f)(void *),const char *,uint32_t stack,void *context,unsigned priority,void *){assert(stack>=4096&&priority==1);worker=f;worker_context=context;return fail_task?pdFALSE:pdPASS;}
uint64_t motonav::drivers::monotonic_us(){return now_ms*1000;}
void diagnostic_test_log(const char *tag,const char *format,const char *line){assert(std::string(tag)=="phone_diag"&&std::string(format)=="%s");output.emplace_back(line);}
static void drain(){try{worker(worker_context);}catch(const Empty &){} }
static void set32(std::vector<uint8_t> &frame,size_t offset,uint32_t value){for(unsigned i=0;i<4;++i)frame[offset+i]=uint8_t(value>>(8*i));}
static BleReceiveResult send(motonav::drivers::BleDiagnosticCallbacks cb,std::vector<uint8_t> frame,uint32_t seq) {
    set32(frame,8,seq);set32(frame,84,crc32(frame.data(),84));
    BleReceiveResult result=BleReceiveResult::Rejected;
    for(size_t offset=0;offset<frame.size();offset+=16){size_t count=std::min(size_t(16),frame.size()-offset);uint8_t p[20]{0xd1,uint8_t(seq),uint8_t(offset),uint8_t(count)};std::memcpy(p+4,frame.data()+offset,count);result=cb.receive(cb.context,p,count+4);}
    return result;
}
int main(int argc,char **argv){
    assert(argc==2);std::ifstream in(argv[1]);std::string text;std::getline(in,text);assert(!text.empty());
    std::vector<uint8_t> frame;for(size_t i=0;i<text.size();i+=2)frame.push_back(uint8_t(std::stoul(text.substr(i,2),nullptr,16)));
    SerialBridge bridge;fail_create=true;assert(bridge.init()==ESP_ERR_NO_MEM);fail_create=false;
    fail_task=true;assert(bridge.init()==ESP_ERR_NO_MEM);fail_task=false;assert(bridge.init()==ESP_OK);
    auto cb=bridge.callbacks();assert(bridge.init()==ESP_ERR_INVALID_STATE);
    for(unsigned i=1;i<=4;++i){now_ms=1000*i;assert(send(cb,frame,i)==BleReceiveResult::Accepted);}
    assert(output.empty()&&queue.size()==4); // No formatting/serial I/O on BLE callback.
    now_ms=5000;assert(send(cb,frame,5)==BleReceiveResult::NoMemory);assert(queue.size()==4);
    drain();assert(output.size()==4);assert(output.back().find("\"receiver_dropped\":1")!=std::string::npos);
    assert(send(cb,frame,5)==BleReceiveResult::Accepted); // Rejected admission did not advance replay state.
    now_ms=6000;assert(send(cb,frame,5)==BleReceiveResult::Rejected);
    assert(send(cb,frame,6)==BleReceiveResult::Accepted);
    now_ms=6001;assert(send(cb,frame,7)==BleReceiveResult::NoMemory); // Firmware independently limits ingress.
    cb.reset(cb.context);drain();assert(output.size()==4); // Disconnect drops queued previous-epoch snapshots.
    now_ms=7000;assert(send(cb,frame,1)==BleReceiveResult::Accepted);drain();assert(output.size()==5);
    std::cout<<"PASS: actual diagnostic bridge bounded worker queue, overflow, replay, rate limit, disconnect epoch and allocation failures\n";
}
