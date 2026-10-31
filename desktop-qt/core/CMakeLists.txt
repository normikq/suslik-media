# Ядро без интерфейса: перенос shared/ с Kotlin. Только Qt Core (и позже Qt Network) — его гоняют тесты на Linux.
add_library(suslik_core STATIC
  src/SiteAddress.cpp
  src/MirrorLink.cpp
  src/Versions.cpp
  src/TimeText.cpp
  src/Changelog.cpp
  src/UrlCoding.cpp
  src/Model.cpp
  src/Auth.cpp
  src/WatchSync.cpp
)
target_include_directories(suslik_core PUBLIC include)
target_link_libraries(suslik_core PUBLIC Qt6::Core)
if(MSVC)
  target_compile_options(suslik_core PRIVATE /W4 /utf-8)
else()
  target_compile_options(suslik_core PRIVATE -Wall -Wextra)
endif()
