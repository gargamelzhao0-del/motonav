#pragma once
#include "freertos/FreeRTOS.h"
BaseType_t xTaskCreatePinnedToCore(void (*)(void *),const char *,uint32_t,void *,unsigned,void *,int);
void vTaskDelete(void *);
void vTaskDelay(TickType_t);
unsigned uxTaskGetStackHighWaterMark(void *);
