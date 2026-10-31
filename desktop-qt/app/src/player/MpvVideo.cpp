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
/// ядро держится ссылкой, пока render context не освобождён (иначе mpv роняет процесс).
class MpvRenderer : public QQuickFramebufferObject::Renderer {
public:
    MpvRenderer(mpv::Handle h, MpvVideo *item) : m_item(item) { start(std::move(h)); }

    void start(mpv::Handle h) {
        m_h = std::move(h);
        if (!m_h) return;
        const mpv::Api *a = mpv::api();
        mpv_opengl_init_params gl{procAddress, nullptr};
        // Без ADVANCED_CONTROL: тогда вывод mpv ждал бы поток отрисовки, а тот — окно, занятое синхронным вызовом mpv
        // (взаимная блокировка). Свой поток вывода mpv держит сам.
        mpv_render_param params[] = {{MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
                                     {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl},
                                     {MPV_RENDER_PARAM_INVALID, nullptr}};
        if (a->render_context_create(&m_ctx, m_h.get(), params) < 0) { m_ctx = nullptr; return; }
        // «Есть что рисовать» приходит из потоков mpv: только просим перерисовку элемента.
        a->render_context_set_update_callback(m_ctx, [](void *p) {
            auto *self = static_cast<MpvRenderer *>(p);
            QPointer<MpvVideo> item = self->m_item;
            if (item) QMetaObject::invokeMethod(item, [item] { if (item) item->update(); }, Qt::QueuedConnection);
        }, this);
    }

    ~MpvRenderer() override {
        if (m_ctx) {
            const mpv::Api *a = mpv::api();
            a->render_context_set_update_callback(m_ctx, nullptr, nullptr);
            a->render_context_free(m_ctx);
        }
        // m_h отпускается после — ядро закроется, если движок его уже отпустил
    }

    // GUI-поток стоит: ядро можно взять у движка (libmpv могли докачать после открытия экрана)
    void synchronize(QQuickFramebufferObject *item) override {
        if (m_ctx) return;
        auto *v = static_cast<MpvVideo *>(item);
        if (v->engine() && v->engine()->available()) start(v->engine()->handle());
    }

    QOpenGLFramebufferObject *createFramebufferObject(const QSize &size) override {
        QOpenGLFramebufferObjectFormat f;
        f.setAttachment(QOpenGLFramebufferObject::NoAttachment);
        return new QOpenGLFramebufferObject(size, f);
    }

    void render() override {
        if (!m_ctx) {
            QOpenGLContext::currentContext()->functions()->glClearColor(0, 0, 0, 1);
            QOpenGLContext::currentContext()->functions()->glClear(GL_COLOR_BUFFER_BIT);
            return;
        }
        const mpv::Api *a = mpv::api();
        a->render_context_update(m_ctx);
        QOpenGLFramebufferObject *fbo = framebufferObject();
        mpv_opengl_fbo target{static_cast<int>(fbo->handle()), fbo->width(), fbo->height(), 0};
        int flip = 0;
        int block = 0; // кадр по готовности, без ожидания времени показа: окно не ждёт mpv
        mpv_render_param params[] = {{MPV_RENDER_PARAM_OPENGL_FBO, &target},
                                     {MPV_RENDER_PARAM_FLIP_Y, &flip},
                                     {MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME, &block},
                                     {MPV_RENDER_PARAM_INVALID, nullptr}};
        a->render_context_render(m_ctx, params);
        // mpv меняет состояние GL: вернуть Qt Quick то, что он ждёт
        QQuickOpenGLUtils::resetOpenGLState();
    }

private:
    mpv::Handle m_h;
    QPointer<MpvVideo> m_item;
    mpv_render_context *m_ctx = nullptr;
};

} // namespace

MpvVideo::MpvVideo(QQuickItem *parent) : QQuickFramebufferObject(parent) {
    setMirrorVertically(false); // mpv рисует кадр так, как его ждёт текстура Qt Quick (FLIP_Y=0)
}

void MpvVideo::setEngine(MpvEngine *e) {
    if (m_engine == e) return;
    m_engine = e;
    emit engineChanged();
    update();
}

QQuickFramebufferObject::Renderer *MpvVideo::createRenderer() const {
    // без libmpv — пустой вывод (Qt Quick не принимает nullptr); ядро он возьмёт, когда оно появится
    mpv::Handle h = m_engine && m_engine->available() ? m_engine->handle() : mpv::Handle();
    return new MpvRenderer(std::move(h), const_cast<MpvVideo *>(this));
}

} // namespace suslik::app
