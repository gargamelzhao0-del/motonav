file(GLOB images "${ROOT}/preview-normal/*.ppm")
foreach(image IN LISTS images)
    get_filename_component(name "${image}" NAME)
    execute_process(COMMAND "${CMAKE_COMMAND}" -E compare_files
        "${image}" "${ROOT}/preview-uncached/${name}" RESULT_VARIABLE result)
    if(NOT result EQUAL 0)
        message(FATAL_ERROR "Cached label changed pixels: ${name}")
    endif()
endforeach()
