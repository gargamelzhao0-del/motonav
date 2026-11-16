#include "ble_test_sdk.h"
#include "motonav/drivers/ble_transport.h"
#include <cassert>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>
using namespace motonav::drivers;
static const ble_gatt_svc_def *diagnostic_services;
static int diagnostic_resets=0,diagnostic_receives=0;
static BleReceiveResult diagnostic_result=BleReceiveResult::Accepted;
ble_hs_config ble_hs_cfg{};
static int (*gap)(ble_gap_event *,void *);
static const ble_gatt_svc_def *services;
static std::vector<ble_npl_callout *> timers;
static std::vector<std::string> sent;
static std::vector<uint16_t> sent_handles;
static std::vector<BleEvent> events;
static int allocation_failures, send_failures, send_error=BLE_HS_EBUSY, live_mbufs, terminations, terminate_failures;
static bool schedule_failure, authenticated=true, advertising;
static uint16_t mtu=23;
static int resets;
static unsigned service_changes;
static unsigned stack_remaining=7000;
unsigned uxTaskGetStackHighWaterMark(void *) { return stack_remaining; }
uint32_t esp_random() { return 123456; }
int os_mbuf_append(os_mbuf *p,const void *bytes,size_t size) { std::memcpy(p->bytes+p->length,bytes,size); p->length+=size; return 0; }
int ble_hs_mbuf_to_flat(os_mbuf *p,void *bytes,uint16_t,uint16_t *) { std::memcpy(bytes,p->bytes,p->length); return 0; }
os_mbuf *ble_hs_mbuf_from_flat(const void *bytes,uint16_t size) {
    if(allocation_failures) { --allocation_failures; return nullptr; }
    auto *p=new os_mbuf{}; ++live_mbufs; os_mbuf_append(p,bytes,size); return p;
}
int ble_gatts_notify_custom(uint16_t,uint16_t handle,os_mbuf *p) {
    int result=0;
    if(send_failures) { --send_failures; result=send_error; }
    else { sent.emplace_back(reinterpret_cast<char *>(p->bytes),p->length); sent_handles.push_back(handle); }
    delete p; --live_mbufs; return result;
}
uint16_t ble_att_mtu(uint16_t) { return mtu; }
int ble_gap_terminate(uint16_t,uint8_t) { ++terminations; if(terminate_failures) { --terminate_failures; return BLE_HS_EBUSY; } return 0; }
int ble_sm_inject_io(uint16_t,ble_sm_io *) { return 0; }
int ble_gap_conn_find(uint16_t,ble_gap_conn_desc *peer) { *peer={{true,authenticated,true},1}; return 0; }
int ble_store_util_delete_peer(const int *) { return 0; }
void ble_store_util_status_rr() {}
int ble_gap_adv_active() { return advertising; }
int ble_gap_adv_set_fields(const ble_gap_adv_fields *) { return 0; }
int ble_gap_adv_rsp_set_fields(const ble_gap_adv_fields *) { return 0; }
int ble_gap_adv_start(uint8_t,const void *,int,const ble_gap_adv_params *,int (*cb)(ble_gap_event *,void *),void *) { gap=cb; advertising=true; return 0; }
int ble_hs_id_infer_auto(int,uint8_t *type) { *type=0; return 0; }
void ble_hs_sched_reset(int reason) { ++resets; ble_hs_cfg.reset_cb(reason); }
int ble_npl_callout_init(ble_npl_callout *c,void *,void (*cb)(ble_npl_event *),void *) { *c={cb,false,0}; timers.push_back(c); return 0; }
int ble_npl_callout_reset(ble_npl_callout *c,uint32_t ticks) {
    if(schedule_failure) { schedule_failure=false; return 1; }
    c->active=true; c->ticks=ticks; return 0;
}
void ble_npl_callout_stop(ble_npl_callout *c) { c->active=false; }
uint32_t ble_npl_time_ms_to_ticks32(uint32_t ms) { return ms; }
void *nimble_port_get_dflt_eventq() { return nullptr; }
int nimble_port_init() { return 0; }
void nimble_port_run() {}
void nimble_port_freertos_init(void (*)(void *)) {}
void nimble_port_freertos_deinit() {}
void ble_svc_gap_init() {}
void ble_svc_gatt_init() {}
void ble_svc_gatt_changed(uint16_t first, uint16_t last) { assert(first==1 && last==0xffff); ++service_changes; }
int ble_svc_gap_device_name_set(const char *) { return 0; }
int ble_gatts_count_cfg(const ble_gatt_svc_def *) { return 0; }
int ble_gatts_add_svcs(const ble_gatt_svc_def *value) {
    if(value[1].type==0){diagnostic_services=value;*value[0].characteristics[0].val_handle=44;return 0;}
    services=value; *services[0].characteristics[1].val_handle=42;
    *services[2].characteristics[1].val_handle=43; return 0;
}
void ble_store_config_init() {}
static void fire() { auto *timer=timers[1]; assert(timer->active); timer->active=false; timer->cb(nullptr); assert(live_mbufs==0); }
static void connect() {
    ble_gap_event event{};
    event.type=BLE_GAP_EVENT_CONNECT; event.connect={0,7}; advertising=false; gap(&event,nullptr);
    event.type=BLE_GAP_EVENT_SUBSCRIBE; event.subscribe={42,true}; gap(&event,nullptr);
    event.type=BLE_GAP_EVENT_ENC_CHANGE; event.enc_change={0,7}; gap(&event,nullptr);
}
static int write(const std::string &bytes, unsigned channel=3) {
    os_mbuf p{}; os_mbuf_append(&p,bytes.data(),bytes.size());
    ble_gatt_access_ctxt ctx{BLE_GATT_ACCESS_OP_WRITE_CHR,&p};
    const auto &characteristic=services[channel==4?2:0].characteristics[0];
    return characteristic.access_cb(7,1,&ctx,characteristic.arg);
}
static int write_diagnostic(const std::string &bytes,uint16_t connection=7) {
    os_mbuf p{};os_mbuf_append(&p,bytes.data(),bytes.size());
    ble_gatt_access_ctxt ctx{BLE_GATT_ACCESS_OP_WRITE_CHR,&p};
    const auto &c=diagnostic_services[0].characteristics[0];
    return c.access_cb(connection,1,&ctx,c.arg);
}
int main() {
    const BleCallbacks cb{nullptr,[](void *,const BleEvent &e){events.push_back(e);},
        [](void *,const uint8_t *p,size_t size) {
            const auto result=ble_notify(p,size);
            return result==ESP_OK?BleReceiveResult::Accepted:result==ESP_ERR_NO_MEM?BleReceiveResult::NoMemory:BleReceiveResult::Rejected;
        }};
    const BleDiagnosticCallbacks diag{nullptr,[](void *,const uint8_t *,size_t){++diagnostic_receives;return diagnostic_result;},
        [](void *){++diagnostic_resets;}};
    assert(ble_start("MotoNav-test","test",cb,diag)==ESP_OK);
    assert(diagnostic_services && diagnostic_services[0].characteristics[0].flags & BLE_GATT_CHR_F_WRITE_AUTHEN);
    ble_hs_cfg.sync_cb();
    assert(ble_runtime_status().diagnostic_rx_handle==44 && ble_runtime_status().host_stack_min_bytes==7000);
    assert(write_diagnostic("x")==BLE_ATT_ERR_UNLIKELY && diagnostic_receives==0);
    connect();
    assert(diagnostic_resets==1);
    const auto before_diagnostic=events.size();
    assert(write_diagnostic("x")==0 && diagnostic_receives==1 && events.size()==before_diagnostic);
    assert(write_diagnostic("x",99)==BLE_ATT_ERR_UNLIKELY && diagnostic_receives==1);
    assert(write_diagnostic(std::string(21,'x'))==BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN);
    diagnostic_result=BleReceiveResult::NoMemory;assert(write_diagnostic("x")==BLE_ATT_ERR_INSUFFICIENT_RES);
    diagnostic_result=BleReceiveResult::Rejected;assert(write_diagnostic("x")==BLE_ATT_ERR_UNLIKELY);
    assert(ble_runtime_status().diagnostic_writes==3 && ble_runtime_status().diagnostic_rejected==2);
    diagnostic_result=BleReceiveResult::Accepted;
    assert(service_changes==1);
    // Restored CCCDs do not choose the protocol; authenticated RX does.
    const uint8_t first='x';
    assert(ble_notify(&first,1)==ESP_ERR_INVALID_STATE);
    assert(write("A")==0 && sent.empty());
    assert(events[events.size()-2].type==BleEventType::Protocol && events[events.size()-2].value==3);
    allocation_failures=2;
    fire(); fire(); assert(sent.empty()); fire(); assert(sent.back()=="A" && sent_handles.back()==42);
    assert(write("B")==0 && write("C")==0);
    send_failures=1; fire(); assert(sent.back()=="A"); fire();
    assert(sent[sent.size()-2]=="B" && sent.back()=="C");
    const uint8_t a='a';
    BleNotification batch[32]; for(auto &p:batch) p={&a,1};
    assert(ble_notify_batch(batch,32)==ESP_OK);
    assert(write("D")==BLE_ATT_ERR_INSUFFICIENT_RES);
    for(int i=0;i<4;++i) fire();
    assert(write("D")==0); fire(); assert(sent.back()=="D");
    schedule_failure=true;
    assert(write("E")==BLE_ATT_ERR_UNLIKELY && !timers[1]->active);
    assert(write("F")==0); fire(); assert(sent.back()=="F");
    const uint8_t long_data[21]{};
    const BleNotification bad_batch[]{{&a,1},{long_data,21}};
    assert(ble_notify_batch(bad_batch,2)==ESP_ERR_INVALID_SIZE && !timers[1]->active);
    for(int cause:{BLE_GAP_EVENT_DISCONNECT,BLE_GAP_EVENT_SUBSCRIBE,BLE_GAP_EVENT_ENC_CHANGE}) {
        assert(write("OLD")==0);
        const auto before=sent.size();
        ble_gap_event event{}; event.type=cause; event.subscribe={42,false}; event.enc_change={1,7};
        gap(&event,nullptr);
        if(cause!=BLE_GAP_EVENT_SUBSCRIBE)assert(write_diagnostic("no security")==BLE_ATT_ERR_UNLIKELY);
        assert(!timers[1]->active);
        timers[1]->cb(nullptr); assert(sent.size()==before);
        connect(); assert(write("NEW")==0); fire(); assert(sent.back()=="NEW");
    }
    ble_gap_event subscription{}; subscription.type=BLE_GAP_EVENT_SUBSCRIBE;
    subscription.subscribe={43,true}; gap(&subscription,nullptr);
    assert(write("NAV3")==0);
    assert(write("WRONG CHANNEL",4)==BLE_ATT_ERR_UNLIKELY);
    subscription.subscribe={43,false}; gap(&subscription,nullptr);
    assert(timers[1]->active); fire(); assert(sent.back()=="NAV3" && sent_handles.back()==42);
    // Removing the active CCCD discards its pending ACK before NAV4 can be selected.
    assert(write("OLD NAV3")==0);
    subscription.subscribe={42,false}; gap(&subscription,nullptr);
    assert(!timers[1]->active);
    subscription.subscribe={43,true}; gap(&subscription,nullptr);
    const auto sent_before_switch=sent.size();
    assert(write("NAV4",4)==0); fire();
    assert(sent.size()==sent_before_switch+1 && sent.back()=="NAV4" && sent_handles.back()==43);
    assert(events[events.size()-2].type==BleEventType::Protocol && events[events.size()-2].value==4);
    subscription.subscribe={42,true}; gap(&subscription,nullptr);
    assert(write("OLD NAV4",4)==0);
    subscription.subscribe={43,false}; gap(&subscription,nullptr);
    assert(!timers[1]->active);
    assert(write("NAV3 AGAIN")==0); fire();
    assert(sent.back()=="NAV3 AGAIN" && sent_handles.back()==42);
    assert(write("TIMEOUT")==0);
    allocation_failures=50; terminate_failures=1;
    for(int i=0;i<50;++i) fire();
    assert(terminations==1 && events.back().type==BleEventType::Security && !events.back().value);
    assert(write("BLOCKED")==BLE_ATT_ERR_UNLIKELY);
    ble_gap_event security{}; security.type=BLE_GAP_EVENT_ENC_CHANGE; security.enc_change={1,7};
    gap(&security,nullptr); assert(timers[1]->active); // Security events cannot cancel termination retry.
    fire(); assert(terminations==2);
    ble_gap_event event{}; event.type=BLE_GAP_EVENT_DISCONNECT; gap(&event,nullptr);
    connect(); assert(write("RECOVERED")==0); fire(); assert(sent.back()=="RECOVERED");
    assert(write("RESET")==0);
    send_error=BLE_HS_ENOTSUP; send_failures=1; terminate_failures=10;
    fire();
    for(int i=0;i<9;++i) fire();
    assert(resets==1 && !timers[1]->active && events.back().type==BleEventType::Disconnected);
    std::cout << "PASS: real BLE transport deferred ACKs, allocation/HCI retry, bounded bursts, atomic enqueue and session cleanup\n";
}
