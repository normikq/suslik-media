//! Запас на диске (`suslik_core::burst_proxy`), подключённый к приложению: папка во временных файлах, HTTPS через
//! rustls, журнал плеера и новая подпись зеркала. Как `Reserve.kt`.

use crate::api::Api;
use crate::player_log;
use std::io;
use std::net::TcpStream;
use std::path::PathBuf;
use std::sync::{Arc, OnceLock};
use std::time::Duration;
use suslik_core::burst_proxy::{self, HttpUpstream};
use suslik_core::http1::{self, Connector, Stream};
use suslik_core::mirror_link;

/// Папка запаса (и для «Очистить кэш» до первого фильма). Своя у отладочной сборки: init() её очищает, а обычная
/// может в это же время играть из своей.
pub fn dir() -> PathBuf {
    std::env::temp_dir().join(if cfg!(feature = "diag") { "SuslikDebug-stream" } else { "SuslikMedia-stream" })
}

/// TCP, затем TLS (rustls, корни webpki). Сокет отдаётся до рукопожатия: сторож запаса обрывает и повисшее.
struct TlsConnector {
    config: Arc<rustls::ClientConfig>,
}

impl TlsConnector {
    fn new() -> io::Result<Self> {
        let roots = rustls::RootCertStore { roots: webpki_roots::TLS_SERVER_ROOTS.to_vec() };
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let config = rustls::ClientConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()
            .map_err(io::Error::other)?
            .with_root_certificates(roots)
            .with_no_client_auth();
        Ok(Self { config: Arc::new(config) })
    }
}

impl Connector for TlsConnector {
    fn connect(
        &self,
        host: &str,
        port: u16,
        tls: bool,
        on_tcp: &mut dyn FnMut(&TcpStream),
    ) -> io::Result<Box<dyn Stream>> {
        let tcp = http1::tcp(host, port, Duration::from_secs(15))?;
        on_tcp(&tcp);
        if !tls {
            return Ok(Box::new(tcp));
        }
        let name = rustls::pki_types::ServerName::try_from(host.to_string())
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?;
        let conn = rustls::ClientConnection::new(self.config.clone(), name).map_err(io::Error::other)?;
        Ok(Box::new(rustls::StreamOwned::new(conn, tcp)))
    }
}

/// Один раз за процесс, перед первым фильмом.
pub fn init(api: &Api) {
    static DONE: OnceLock<()> = OnceLock::new();
    if DONE.get().is_some() {
        return;
    }
    let connector = match TlsConnector::new() {
        Ok(c) => c,
        Err(e) => {
            player_log::write(&format!("reserve: TLS is not available ({e}): playing directly"));
            return;
        }
    };
    let _ = DONE.set(());
    burst_proxy::set_log(player_log::write);
    let up = HttpUpstream::new(Arc::new(connector));
    let reset = up.clone();
    burst_proxy::set_reset_connections(move || reset.reset());
    burst_proxy::init(&dir(), up);
    // Подпись зеркала живёт 12 часов: фильм на паузе на ночь получает новый адрес, а не 401 навсегда.
    let api = api.clone();
    burst_proxy::set_refresh_url(move |old| {
        let path = mirror_link::renew_path(old)?;
        let v = api.get(&path).ok()?;
        v.get("url").and_then(|u| u.as_str()).filter(|u| !u.is_empty()).map(str::to_string)
    });
}
