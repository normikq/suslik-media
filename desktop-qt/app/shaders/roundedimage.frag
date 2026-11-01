#version 440
// Картинка со скруглёнными углами без промежуточного слоя: берёт текстуру Image напрямую, сама кадрирует
// (как PreserveAspectCrop, uvRect = смещение и масштаб) и срезает углы. Один такой элемент — ни одного FBO.
layout(location = 0) in vec2 qt_TexCoord0;
layout(location = 0) out vec4 fragColor;
layout(std140, binding = 0) uniform buf {
    mat4 qt_Matrix;
    float qt_Opacity;
    vec2 size;
    float radius;
    vec4 uvRect;
};
layout(binding = 1) uniform sampler2D source;

void main() {
    vec2 p = qt_TexCoord0 * size;
    vec2 q = abs(p - size * 0.5) - (size * 0.5 - vec2(radius));
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    float a = clamp(0.5 - d, 0.0, 1.0);
    fragColor = texture(source, uvRect.xy + qt_TexCoord0 * uvRect.zw) * (a * qt_Opacity);
}
