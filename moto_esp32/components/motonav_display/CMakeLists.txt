idf_component_register(
    SRCS "display.cpp"
    INCLUDE_DIRS "include"
    REQUIRES lvgl esp_common
    PRIV_REQUIRES waveshare__esp32_s3_touch_amoled_1_75c espressif__esp_lvgl_adapter esp_lcd freertos
)
