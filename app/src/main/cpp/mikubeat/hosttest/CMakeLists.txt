# Host build of the same analysis code, for the accuracy test (bpm_eval.py).
#   cmake -S app/src/main/cpp/mikubeat/hosttest -B /tmp/mikubeat-host && cmake --build /tmp/mikubeat-host
cmake_minimum_required(VERSION 3.16)
project(mikubeat_hosttest C CXX)
set(CMAKE_CXX_STANDARD 14)
include(${CMAKE_CURRENT_LIST_DIR}/../sources.cmake)
add_executable(bpm_hosttest ${MIKUBEAT_SOURCES} ${CMAKE_CURRENT_LIST_DIR}/bpm_hosttest.cpp)
target_include_directories(bpm_hosttest PRIVATE ${MIKUBEAT_INCLUDES})
target_compile_definitions(bpm_hosttest PRIVATE ${MIKUBEAT_DEFINES})
target_compile_options(bpm_hosttest PRIVATE -O2 -Wno-vla -Wno-unused-parameter)
target_link_libraries(bpm_hosttest m)
