idf_build_get_property(device_tests MOTONAV_DEVICE_TESTS)
idf_build_get_property(ble_diagnostics MOTONAV_BLE_DIAGNOSTICS)
set(app_dependencies motonav_core motonav_ui motonav_display motonav_touch
    motonav_ble motonav_platform freertos log)
if(device_tests)
    list(APPEND app_dependencies motonav_bench)
endif()
if(ble_diagnostics)
    list(APPEND app_dependencies motonav_diagnostics)
endif()
idf_component_register(SRCS "app_main.cpp" "app/navigation_app.cpp" "app/navigation_service.cpp" "app/device_supervisor.cpp"
    INCLUDE_DIRS "." PRIV_REQUIRES ${app_dependencies})
target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTONAV_DEVICE_TESTS=$<BOOL:${device_tests}>)
target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTONAV_BLE_DIAGNOSTICS=$<BOOL:${ble_diagnostics}>)
target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTONAV_DISPLAY_ROWS=${MOTONAV_DISPLAY_ROWS})
target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTONAV_DIRECT_QSPI=$<BOOL:${MOTONAV_DIRECT_QSPI}>)
target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTONAV_COLOR_RGB332=$<STREQUAL:${MOTONAV_COLOR_MODE},RGB332>)

target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTONAV_NAV_METRICS=$<BOOL:${MOTONAV_NAV_METRICS}>)
