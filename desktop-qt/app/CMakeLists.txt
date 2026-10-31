# Приложение: пока только окно-заглушка, чтобы CI проверял сборку с Qt Quick. Интерфейс — шаг 2 (docs/QT-PORT.md).
find_package(Qt6 6.4 REQUIRED COMPONENTS Gui Quick)

qt_add_executable(suslik_media WIN32 main.cpp)
set_target_properties(suslik_media PROPERTIES OUTPUT_NAME "SuslikMediaQt")
qt_add_qml_module(suslik_media
  URI Suslik
  VERSION 1.0
  QML_FILES qml/Main.qml
)
target_compile_definitions(suslik_media PRIVATE
  SM_VERSION="${PROJECT_VERSION}"
  SM_SITE_URL="${SM_SITE_URL}"
)
target_link_libraries(suslik_media PRIVATE suslik_core Qt6::Gui Qt6::Quick)
if(MSVC)
  target_compile_options(suslik_media PRIVATE /utf-8)
endif()
