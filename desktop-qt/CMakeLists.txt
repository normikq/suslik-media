cmake_minimum_required(VERSION 3.21)

# Suslik Media для Windows на C++/Qt (с 3.0.0 заменила Kotlin-версию). Устройство — docs/DESKTOP.md.
project(SuslikMediaQt VERSION 3.1.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

option(SM_BUILD_APP "Собирать приложение (нужен Qt Quick)" ON)
option(SM_BUILD_TESTS "Собирать тесты ядра" ON)

# Адрес сервера в репозиторий не пишем: SITE_URL из окружения или -DSM_SITE_URL=... (разбор — core/SiteAddress).
if(NOT DEFINED SM_SITE_URL)
  set(SM_SITE_URL "$ENV{SITE_URL}")
endif()

find_package(Qt6 6.4 REQUIRED COMPONENTS Core Network)

add_subdirectory(core)
if(SM_BUILD_APP)
  add_subdirectory(app)
endif()
if(SM_BUILD_TESTS)
  enable_testing()
  add_subdirectory(tests)
endif()
