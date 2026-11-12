idf_build_get_property(device_tests MOTONAV_DEVICE_TESTS)
set(app_dependencies motonav_core motonav_ui motonav_display motonav_touch
    motonav_ble motonav_platform freertos log)
if(device_tests)
    list(APPEND app_dependencies motonav_bench)
endif()
idf_component_register(SRCS "app_main.cpp" "app/navigation_app.cpp" "app/navigation_service.cpp"
    INCLUDE_DIRS "." PRIV_REQUIRES ${app_dependencies})
target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTONAV_DEVICE_TESTS=$<BOOL:${device_tests}>)
target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTONAV_DISPLAY_ROWS=${MOTONAV_DISPLAY_ROWS})
target_compile_definitions(${COMPONENT_LIB} PRIVATE MOTONAV_DIRECT_QSPI=$<BOOL:${MOTONAV_DIRECT_QSPI}>)
