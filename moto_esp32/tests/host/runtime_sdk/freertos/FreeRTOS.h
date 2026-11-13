#pragma once
#include <cstdint>
using BaseType_t = int;
using TickType_t = uint32_t;
constexpr BaseType_t pdTRUE = 1, pdFALSE = 0, pdPASS = 1;
constexpr TickType_t portMAX_DELAY = ~TickType_t{};
#define pdMS_TO_TICKS(ms) (ms)
#define portYIELD_FROM_ISR() ((void)0)
using portMUX_TYPE = unsigned;
#define portMUX_INITIALIZER_UNLOCKED 0
#define portENTER_CRITICAL(p) (++*(p))
#define portEXIT_CRITICAL(p) (--*(p))
