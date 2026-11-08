#version 440
// Свет экрана на стенах, сцене и потолке вокруг полотна (Main.qml, «Вписать в сцену»).
// Почти как прибавление света: прозрачность пятна — по яркости цвета, поэтому тёмный кадр вокруг ничего
// не затемняет, а сила отсвета сама идёт за яркостью фильма.
// Цвет — размытый край кадра (мипмапы уменьшенной копии) пополам со средним цветом всего кадра;
// спад — экспонента от расстояния до края полотна.
layout(location = 0) in vec2 qt_TexCoord0;
layout(location = 0) out vec4 fragColor;
layout(std140, binding = 0) uniform buf {
    mat4 qt_Matrix;
    float qt_Opacity;
    float strength;   // общая сила (от света зала, SceneProbe.glow)
    float margin;     // поле вокруг полотна в долях его размера (элемент шире полотна на 2·margin)
    float aspect;     // ширина/высота полотна — спад одинаковый по горизонтали и вертикали
    float falloff;    // расстояние спада в долях высоты полотна
};
layout(binding = 1) uniform sampler2D frame;

void main() {
    vec2 uv = qt_TexCoord0 * (1.0 + 2.0 * margin) - margin; // координаты полотна (0…1 — само полотно)
    vec2 edge = clamp(uv, 0.0, 1.0);
    vec2 out2 = (uv - edge) * vec2(aspect, 1.0);
    float d = length(out2);
    if (d <= 0.0) { fragColor = vec4(0.0); return; } // под полотном — его закрывает кадр
    vec3 near = texture(frame, edge, 3.0).rgb;
    vec3 avg = texture(frame, vec2(0.5), 8.0).rgb;
    vec3 c = mix(near, avg, 0.5);
    // у края элемента — плавно в ноль, чтобы не было видно его границы
    float rim = max(abs(uv.x - 0.5) - 0.5, abs(uv.y - 0.5) - 0.5);
    float k = exp(-d / falloff) * (1.0 - smoothstep(margin * 0.6, margin, rim));
    // Альфа по яркости света (08.11: с альфой 0 Windows отсвет не показывал вовсе): тёмный кадр — почти прозрачно,
    // игру не затемняет; яркий — светлое пятно поверх стены. Цвет премультиплицированный, как ждёт Qt Quick.
    float a = clamp(strength * k * qt_Opacity, 0.0, 1.0);
    float peak = max(max(c.r, c.g), max(c.b, 1e-4));
    fragColor = vec4(c / peak * (a * peak), a * peak);
}
