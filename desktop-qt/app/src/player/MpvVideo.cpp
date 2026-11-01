#include "MpvVideo.h"

#include <QOpenGLContext>
#include <QOpenGLFramebufferObject>
#include <QOpenGLFunctions>
#include <QQuickOpenGLUtils>
#include <QQuickWindow>

namespace suslik::app {

namespace {

void *procAddress(void *, const char *name) {
    QOpenGLContext *ctx = QOpenGLContext::currentContext();
    return ctx ? reinterpret_cast<void *>(ctx->getProcAddress(QByteArray(name))) : nullptr;
}

/// Живёт в потоке отрисовки Qt Quick. Render context создаётся и освобождается здесь же (в его GL-контексте);
/// ядро держится ссылкой, пока render context не освобождён.
class MpvRenderer : public QQuickFramebufferObject::Renderer {
public:
    MpvRenderer(mpv::Handle h, MpvVideo *item) : m_item(item) { start(std::move(h)); }

    void start(mpv::Handle h) {
        m_h = std::move(h);
        if (!m_h) return;
        const mpv::Api *a = mpv::api();
        mpv_opengl_init_params gl{procAddress, nullptr};
        // Без ADVANCED_CONTROL: вывод mpv не блокирует поток отрисовки Qt.
        mpv_render_param params[] = {
            {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
            {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl},
            {MPV_RENDER_PARAM_INVALID, nullptr}
        };
        if (a->render_context_create(&m_ctx, m_h.get(), params) < 0) {
            m_ctx = nullptr;
            return;
        }
        // Уведомление о готовности кадра из потока mpv — запрашиваем перерисовку в GUI-потоке.
        a->render_context_set_update_callback(m_ctx, [](void *p) {
            auto *self = static_cast<MpvRenderer *>(p);
            if (self && self->m_item) {
                QPointer<MpvVideo> item = self->m_item;
                QMetaObject::invokeMethod(item, [item] {
                    if (item) item->update();
                }, Qt::QueuedConnection);
            }
        }, this);
    }

    ~MpvRenderer() override {
        if (m_ctx) {
            const mpv::Api *a = mpv::api();
            a->render_context_set_update_callback(m_ctx, nullptr, nullptr);
            a->render_context_free(m_ctx);
            m_ctx = nullptr;
        }
    }

    // GUI-поток стоит: ядро можно взять у движка (libmpv могли докачать после открытия экрана, ядро — пересоздать).
    // Только готовое ядро: отпущенное после закрытия здесь не создаётся заново.
    void synchronize(QQuickFramebufferObject *item) override {
        auto *v = static_cast<MpvVideo *>(item);
        if (!v || !v->engine()) return;
        mpv::Handle h = v->engine()->current();
        if (!h || (m_ctx && m_h.get() == h.get())) return;
        if (m_ctx) {
            const mpv::Api *a = mpv::api();
            a->render_context_set_update_callback(m_ctx, nullptr, nullptr);
            a->render_context_free(m_ctx);
            m_ctx = nullptr;
        }
        start(std::move(h));
    }

    QOpenGLFramebufferObject *createFramebufferObject(const QSize &size) override {
        QOpenGLFramebufferObjectFormat f;
        f.setAttachment(QOpenGLFramebufferObject::NoAttachment);
        return new QOpenGLFramebufferObject(size, f);
    }

    void render() override {
        if (!m_ctx) {
            auto *funcs = QOpenGLContext::currentContext() ? QOpenGLContext::currentContext()->functions() : nullptr;
            if (funcs) {
                funcs->glClearColor(0, 0, 0, 1);
                funcs->glClear(GL_COLOR_BUFFER_BIT);
            }
            return;
        }
        const mpv::Api *a = mpv::api();
        a->render_context_update(m_ctx);
        QOpenGLFramebufferObject *fbo = framebufferObject();
        if (!fbo) return;

        mpv_opengl_fbo target{static_cast<int>(fbo->handle()), fbo->width(), fbo->height(), 0};
        int flip = 0; // В OpenGL FBO координата Y естественная для Qt Quick
        int block = 0; // Кадр по готовности, без блокировки потока сцены
        mpv_render_param params[] = {
            {MPV_RENDER_PARAM_OPENGL_FBO, &target},
            {MPV_RENDER_PARAM_FLIP_Y, &flip},
            {MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME, &block},
            {MPV_RENDER_PARAM_INVALID, nullptr}
        };
        a->render_context_render(m_ctx, params);
        // mpv меняет состояние GL — восстанавливаем состояние для сцены Qt Quick
        QQuickOpenGLUtils::resetOpenGLState();
    }

private:
    mpv::Handle m_h;
    QPointer<MpvVideo> m_item;
    mpv_render_context *m_ctx = nullptr;
};

} // namespace

MpvVideo::MpvVideo(QQuickItem *parent) : QQuickFramebufferObject(parent) {
    setMirrorVertically(false);
}

void MpvVideo::setEngine(MpvEngine *e) {
    if (m_engine == e) return;
    m_engine = e;
    emit engineChanged();
    update();
}

QQuickFramebufferObject::Renderer *MpvVideo::createRenderer() const {
    return new MpvRenderer(m_engine ? m_engine->current() : mpv::Handle{}, const_cast<MpvVideo *>(this));
}

} // namespace suslik::app
