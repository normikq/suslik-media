# Ядро без интерфейса: перенос shared/ и сетевой части desktop/ с Kotlin. Только Qt Core и Qt Network — его гоняют тесты на Linux.
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
  src/Strings.cpp
  src/Store.cpp
  src/ApiClient.cpp
  src/CloudSync.cpp
  src/MarkersDetector.cpp
  src/CustomAudio.cpp
  src/TorrentInspector.cpp
  src/PlaybackSupervisor.cpp
  src/BurstProxy.cpp
  src/HttpUpstream.cpp
  src/CatchUp.cpp
  src/JsonOpt.h
  include/suslik/core/ApiClient.h
  include/suslik/core/CloudSync.h
)
target_include_directories(suslik_core PUBLIC include)
target_link_libraries(suslik_core PUBLIC Qt6::Core Qt6::Network)
if(MSVC)
  target_compile_options(suslik_core PRIVATE /W4 /utf-8)
else()
  target_compile_options(suslik_core PRIVATE -Wall -Wextra)
endif()
