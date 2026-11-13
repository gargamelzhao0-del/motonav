idf_component_register(SRCS "display_benchmark.cpp" "map_fixture.cpp" "driver_probe.cpp"
    INCLUDE_DIRS "include" REQUIRES motonav_ui motonav_graphics
    PRIV_REQUIRES log motonav_display motonav_platform)
target_compile_definitions(${COMPONENT_LIB} PRIVATE
    MOTONAV_BENCH_SECONDS=${MOTONAV_BENCH_SECONDS}
    MOTONAV_AUTORUN_BENCH=$<BOOL:${MOTONAV_AUTORUN_BENCH}>
    MOTONAV_AUTORUN_MAP_BENCH=$<BOOL:${MOTONAV_AUTORUN_MAP_BENCH}>)
