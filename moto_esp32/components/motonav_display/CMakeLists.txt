idf_component_register(
    SRCS "display.cpp" "qspi_panel.cpp" "pixel_pack.cpp"
    INCLUDE_DIRS "include"
    REQUIRES lvgl esp_common
    PRIV_REQUIRES waveshare__esp32_s3_touch_amoled_1_75c espressif__esp_lvgl_adapter espressif__esp_lcd_co5300 esp_lcd esp_driver_spi esp_hw_support heap freertos esp_timer
)
if(IDF_TARGET STREQUAL "esp32s3")
    target_sources(${COMPONENT_LIB} PRIVATE "pixel_pack_s3.S")
    target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTONAV_PACK_S3=1)
endif()
