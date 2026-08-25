//! Hybrid static asset serving: a local `web/` directory overrides the assets
//! embedded into the binary at compile time.

use std::fmt::Write as _;
use std::hash::{Hash, Hasher};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use axum::http::{header, HeaderMap, StatusCode, Uri};
use axum::response::{IntoResponse, Response};

#[derive(rust_embed::RustEmbed)]
#[folder = "web/"]
struct Asset;

/// Local `web/` override directories, in lookup precedence order:
///   1. next to the executable — the documented customization location, and
///   2. the current working directory — convenient for `cargo run` during
///      development, where the binary lives under `target/`.
///
/// Only the directory *paths* are cached (the executable's location is fixed for
/// the lifetime of the process). File existence and contents are still checked on
/// every request, so a `web/` directory — or an individual file — added or edited
/// while the server is running is picked up immediately, with no restart.
fn override_dirs() -> &'static [PathBuf] {
    static DIRS: OnceLock<Vec<PathBuf>> = OnceLock::new();
    DIRS.get_or_init(|| {
        let mut dirs = Vec::new();
        if let Ok(exe) = std::env::current_exe() {
            if let Some(parent) = exe.parent() {
                dirs.push(parent.join("web"));
            }
        }
        // CWD fallback (also covers `cargo run`, where the exe is under target/).
        let cwd_web = PathBuf::from("web");
        if !dirs.contains(&cwd_web) {
            dirs.push(cwd_web);
        }
        dirs
    })
}

/// Reject any request path that isn't a plain relative path under `web/`.
/// Blocks directory traversal (`..`) and absolute paths from escaping the asset
/// directory on the local-disk override path — a raw client could otherwise
/// request e.g. `/../Cargo.toml` and read files outside `web/`.
fn is_safe_asset_path(path: &str) -> bool {
    use std::path::Component;
    !path.is_empty()
        && Path::new(path)
            .components()
            .all(|c| matches!(c, Component::Normal(_)))
}

/// Fallback handler: serve from a local `web/` directory if present, otherwise
/// from the embedded binary assets.
pub(super) async fn handle_static_assets(uri: Uri, headers: HeaderMap) -> Response {
    let path = uri.path().trim_start_matches('/');
    let path = if path.is_empty() { "index.html" } else { path };

    // Refuse traversal/absolute paths before touching the filesystem.
    if !is_safe_asset_path(path) {
        return (StatusCode::NOT_FOUND, "File not found").into_response();
    }

    let if_none_match = headers
        .get(header::IF_NONE_MATCH)
        .and_then(|v| v.to_str().ok());

    // 1. Local disk override. Checked per request so newly added or freshly edited
    //    files are served immediately (no restart). The ETag is content-derived,
    //    so any edit — even one that leaves the file size unchanged — is reliably
    //    detected on the browser's next revalidation.
    for dir in override_dirs() {
        let disk_path = dir.join(path);
        if disk_path.is_file() {
            if let Ok(content) = tokio::fs::read(&disk_path).await {
                let etag = content_etag(&content);
                if if_none_match == Some(etag.as_str()) {
                    return not_modified(&etag);
                }
                let mime = mime_guess::from_path(&disk_path).first_or_octet_stream();
                return ok_response(&etag, mime.as_ref(), content);
            }
        }
    }

    // 2. Fall back to embedded assets. Their content is immutable, so the ETag is
    //    rust-embed's compile-time SHA-256 — no per-request hashing, and the body
    //    is never materialized on a 304.
    if let Some(embedded) = Asset::get(path) {
        let etag = embedded_etag(&embedded.metadata.sha256_hash());
        if if_none_match == Some(etag.as_str()) {
            return not_modified(&etag);
        }
        let mime = mime_guess::from_path(path).first_or_octet_stream();
        return ok_response(&etag, mime.as_ref(), embedded.data.into_owned());
    }

    // 3. Not found
    (StatusCode::NOT_FOUND, "File not found").into_response()
}

/// Content-derived ETag for live-editable disk files. A weak content hash is
/// enough to disambiguate a handful of small assets, and recomputing it on each
/// request is what makes an edit show up on the very next reload.
fn content_etag(content: &[u8]) -> String {
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    content.hash(&mut hasher);
    format!("\"{:016x}\"", hasher.finish())
}

/// ETag for an embedded file, derived from rust-embed's compile-time SHA-256.
/// The first 16 bytes of the digest are ample to distinguish the bundled assets.
fn embedded_etag(sha256: &[u8; 32]) -> String {
    let mut s = String::with_capacity(34);
    s.push('"');
    for b in &sha256[..16] {
        let _ = write!(s, "{:02x}", b);
    }
    s.push('"');
    s
}

/// Content-Security-Policy for the served web UI. The app is fully self-contained
/// — its own JS/CSS, an inline `data:` favicon, and same-origin WebTransport /
/// WebSocket connections — so everything locks to `'self'` except the `data:`
/// favicon image. There are no inline `<style>` blocks or `style="..."` attributes
/// (all styling lives in `style.css`), so `style-src` stays `'self'` with no
/// `'unsafe-inline'`. Any injected external resource (script, frame, connection) is
/// blocked.
const CSP: &str = "default-src 'self'; \
script-src 'self'; \
style-src 'self'; \
img-src 'self' data:; \
connect-src 'self'; \
worker-src 'self'; \
object-src 'none'; \
base-uri 'none'; \
frame-ancestors 'none'; \
form-action 'none'";

/// `200 OK` with the asset body, its `ETag`, and the standard security/caching
/// headers. `Cache-Control: no-cache` makes the browser revalidate on every load,
/// so a rebuilt or edited asset is picked up immediately instead of being served
/// stale; the `ETag` keeps that cheap (an unchanged asset costs only a conditional
/// round-trip that returns `304`).
fn ok_response(etag: &str, mime: &str, content: Vec<u8>) -> Response {
    (
        [
            (header::CONTENT_TYPE, mime),
            (header::CACHE_CONTROL, "no-cache"),
            (header::ETAG, etag),
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
            (header::REFERRER_POLICY, "no-referrer"),
            (header::CONTENT_SECURITY_POLICY, CSP),
            (header::X_FRAME_OPTIONS, "DENY"),
        ],
        content,
    )
        .into_response()
}

/// `304 Not Modified` (no body) for a client whose `If-None-Match` already
/// matches the current ETag.
fn not_modified(etag: &str) -> Response {
    (
        StatusCode::NOT_MODIFIED,
        [
            (header::ETAG, etag),
            (header::CACHE_CONTROL, "no-cache"),
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
            (header::REFERRER_POLICY, "no-referrer"),
            (header::CONTENT_SECURITY_POLICY, CSP),
            (header::X_FRAME_OPTIONS, "DENY"),
        ],
    )
        .into_response()
}

#[cfg(test)]
mod embed_tests {
    use super::Asset;

    #[test]
    fn vendored_tabler_assets_are_embedded() {
        assert!(
            Asset::get("vendor/tabler/microphone.svg").is_some(),
            "vendored Tabler microphone SVG must be embedded in the binary"
        );
        assert!(
            Asset::get("vendor/tabler/LICENSE").is_some(),
            "vendored Tabler LICENSE must ship inside the binary"
        );
    }
}
