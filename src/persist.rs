//! Persistent on-disk state: TLS identity, pairing PIN, and session token.
//!
//! Everything lives in a single JSON document under the platform's per-user
//! application-data directory (macOS convention, mirrored on the other OSes):
//!
//! - macOS:  `~/Library/Application Support/QuicMic/state.json`
//! - Windows: `%APPDATA%\QuicMic\state.json`
//! - Linux/others: `$XDG_DATA_HOME/QuicMic/state.json`, else `~/.local/share/QuicMic`
//!
//! The file holds the TLS private key and the pairing PIN, so it is written with
//! owner-only permissions: mode `0700` for the directory and `0600` for the file
//! (Unix). On Windows no explicit ACL is set — the per-user application-data
//! directory already carries default ACLs that exclude other users. Writes go
//! through a temp file + rename so a crash mid-write can never leave a torn
//! document behind.

use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use tracing::warn;

/// The three persisted values (plus the IP the certificate was issued for).
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct PersistedState {
    /// LAN IP the stored certificate's SANs were generated for. If the machine's
    /// current LAN IP differs, the stored certificate no longer matches any
    /// address phones would connect to and must be regenerated (see `main`).
    pub lan_ip: String,
    /// Pairing PIN. Persists across restarts until explicitly changed via `--pin`.
    pub pin: String,
    /// Last issued session token, if any. Seeding the server with it lets an
    /// already-paired phone survive a server restart without re-entering the PIN.
    #[serde(default)]
    pub token: Option<String>,
    /// Leaf certificate, PEM-encoded.
    pub cert_pem: String,
    /// Private key, PEM-encoded PKCS#8 ("PRIVATE KEY").
    pub key_pem: String,
}

/// A clonable handle the HTTP layer uses to persist session-token rotations
/// (`/api/pair`, `/api/renew`) without coupling the handlers to storage details.
///
/// `TokenStore::disabled()` never touches disk — that is what the router tests
/// use, so the fixtures stay hermetic and never read or write the real store.
#[derive(Clone, Default, Debug)]
pub struct TokenStore {
    path: Option<PathBuf>,
}

impl TokenStore {
    /// A handle bound to the real state file (best-effort: if the platform has no
    /// usable app-data location the handle is disabled and rotations are only
    /// logged).
    pub fn for_app() -> Self {
        match state_file_path() {
            Ok(path) => Self { path: Some(path) },
            Err(e) => {
                warn!("No persistent-state location available: {e:#}");
                Self { path: None }
            }
        }
    }

    /// A no-op handle for tests: reads nothing, writes nothing.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn disabled() -> Self {
        Self { path: None }
    }

    /// Persist a new session token (read-modify-write of just the token field, so
    /// a rotation racing nothing still preserves cert / PIN / IP). Best-effort:
    /// failures are logged, never fatal — the in-memory token stays authoritative
    /// for this run.
    pub fn save_token(&self, token: &str) {
        let Some(path) = &self.path else { return };
        if let Err(e) = update_token_in(path, Some(token)) {
            warn!(error = %e, "Could not persist session token to disk");
        }
    }
}

/// Resolve the platform app-data directory for QuicMic (`.../<app-dir>/`).
fn app_data_dir() -> anyhow::Result<PathBuf> {
    #[cfg(target_os = "macos")]
    let base = std::env::var_os("HOME")
        .map(|h| PathBuf::from(h).join("Library").join("Application Support"));

    #[cfg(windows)]
    let base = std::env::var_os("APPDATA").map(PathBuf::from);

    #[cfg(all(unix, not(target_os = "macos")))]
    let base = std::env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute())
        .or_else(|| {
            std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local").join("share"))
        });

    base.map(|b| b.join("QuicMic"))
        .ok_or_else(|| anyhow::anyhow!("neither HOME nor the platform app-data variable is set"))
}

/// Full path of the persistent-state JSON document.
pub fn state_file_path() -> anyhow::Result<PathBuf> {
    Ok(app_data_dir()?.join("state.json"))
}

/// Load the persisted state from the real location. Returns `None` when no state
/// exists yet (first run — completely normal) or when the file is unreadable or
/// corrupt (logged; treated as first run so a bad file can never wedge startup).
pub fn load() -> Option<PersistedState> {
    let path = state_file_path().ok()?;
    match load_from(&path) {
        Ok(Some(state)) => Some(state),
        Ok(None) => None,
        Err(e) => {
            warn!(error = %e, "Ignoring unreadable persistent state — regenerating");
            None
        }
    }
}

/// Load from an explicit path. `Ok(None)` means "no file" (first run); a corrupt
/// document is an error so callers can log it distinctly.
fn load_from(path: &Path) -> anyhow::Result<Option<PersistedState>> {
    let bytes = match fs::read(path) {
        Ok(b) => b,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e.into()),
    };
    serde_json::from_slice(&bytes)
        .map(Some)
        .map_err(|e| anyhow::anyhow!("corrupt state file {}: {e}", path.display()))
}

/// Persist the full state to the real location. Best-effort by design: a failure
/// is logged (by the caller) but must not take the server down.
pub fn save(state: &PersistedState) -> anyhow::Result<()> {
    save_to(&state_file_path()?, state)
}

/// Write the state atomically (temp file + rename), creating the directory with
/// owner-only permissions on Unix.
fn save_to(path: &Path, state: &PersistedState) -> anyhow::Result<()> {
    let dir = path
        .parent()
        .ok_or_else(|| anyhow::anyhow!("state path has no parent directory"))?;
    create_private_dir(dir)?;

    let json = serde_json::to_vec_pretty(state)?;
    let tmp = path.with_extension("json.tmp");
    fs::write(&tmp, &json)?;
    restrict_permissions(&tmp);
    // Rename is atomic within a filesystem: readers see either the old or the
    // new complete document, never a partial one.
    fs::rename(&tmp, path)?;
    Ok(())
}

/// Patch only the token field of an existing state file, keeping every other
/// field byte-for-byte as stored. `None` clears it.
fn update_token_in(path: &Path, token: Option<&str>) -> anyhow::Result<()> {
    let mut value: serde_json::Value = serde_json::from_slice(&fs::read(path)?)?;
    value["token"] = match token {
        Some(t) => serde_json::Value::String(t.to_string()),
        None => serde_json::Value::Null,
    };
    let tmp = path.with_extension("json.tmp");
    fs::write(&tmp, serde_json::to_vec_pretty(&value)?)?;
    restrict_permissions(&tmp);
    fs::rename(&tmp, path)?;
    Ok(())
}

/// Create `dir` (and parents). On Unix the directory is created with mode 0700 so
/// only the owner can list or enter it.
fn create_private_dir(dir: &Path) -> anyhow::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        fs::DirBuilder::new()
            .recursive(true)
            .mode(0o700)
            .create(dir)?;
    }
    #[cfg(not(unix))]
    fs::create_dir_all(dir)?;
    Ok(())
}

/// Restrict a freshly written file to its owner (`0600`) on Unix. No-op on other
/// platforms, where the user-profile directory already provides the isolation.
fn restrict_permissions(path: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let perms = fs::Permissions::from_mode(0o600);
        if let Err(e) = fs::set_permissions(path, perms) {
            warn!(error = %e, path = %path.display(), "Could not set owner-only permissions");
        }
    }
    #[allow(unused_variables)]
    let _ = path;
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::env;

    /// Fresh unique temp directory per test (no tempfile dependency needed).
    fn temp_dir(name: &str) -> PathBuf {
        let dir = env::temp_dir().join(format!(
            "quicmic-persist-test-{name}-{}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&dir);
        create_private_dir(&dir).unwrap();
        dir
    }

    fn sample_state(pin: &str, ip: &str) -> PersistedState {
        PersistedState {
            lan_ip: ip.to_string(),
            pin: pin.to_string(),
            token: None,
            cert_pem: "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----".into(),
            key_pem: "-----BEGIN PRIVATE KEY-----\nBBBB\n-----END PRIVATE KEY-----".into(),
        }
    }

    #[test]
    fn roundtrip_preserves_all_fields() {
        let dir = temp_dir("roundtrip");
        let path = dir.join("state.json");
        let mut state = sample_state("123456", "192.168.1.42");
        state.token = Some("tok-abc".to_string());

        save_to(&path, &state).unwrap();
        let loaded = load_from(&path).unwrap().expect("state should exist");

        assert_eq!(loaded.lan_ip, "192.168.1.42");
        assert_eq!(loaded.pin, "123456");
        assert_eq!(loaded.token.as_deref(), Some("tok-abc"));
        assert_eq!(loaded.cert_pem, state.cert_pem);
        assert_eq!(loaded.key_pem, state.key_pem);

        let _ = fs::remove_dir_all(&dir);
    }

    #[cfg(unix)]
    #[test]
    fn file_and_dir_are_owner_only() {
        use std::os::unix::fs::PermissionsExt;

        let dir = temp_dir("perms");
        let nested = dir.join("QuicMic");
        let path = nested.join("state.json");

        save_to(&path, &sample_state("654321", "10.0.0.2")).unwrap();

        let file_mode = fs::metadata(&path).unwrap().permissions().mode();
        assert_eq!(file_mode & 0o777, 0o600, "state file must be owner-only");

        let dir_mode = fs::metadata(&nested).unwrap().permissions().mode();
        assert_eq!(
            dir_mode & 0o777,
            0o700,
            "state directory must be owner-only"
        );

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn missing_file_is_first_run_not_error() {
        let dir = temp_dir("missing");
        let path = dir.join("nonexistent.json");
        assert!(load_from(&path).unwrap().is_none());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn corrupt_file_is_an_error_not_silent_none() {
        let dir = temp_dir("corrupt");
        let path = dir.join("state.json");
        fs::write(&path, "{ not valid json").unwrap();
        assert!(load_from(&path).is_err());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn update_token_patches_only_token_field() {
        let dir = temp_dir("token-patch");
        let path = dir.join("state.json");
        let mut state = sample_state("111222", "192.168.0.7");
        state.token = Some("old-token".to_string());
        save_to(&path, &state).unwrap();

        update_token_in(&path, Some("new-token")).unwrap();

        let loaded = load_from(&path).unwrap().unwrap();
        assert_eq!(loaded.token.as_deref(), Some("new-token"));
        // Everything else survived untouched.
        assert_eq!(loaded.pin, "111222");
        assert_eq!(loaded.lan_ip, "192.168.0.7");
        assert_eq!(loaded.cert_pem, state.cert_pem);
        assert_eq!(loaded.key_pem, state.key_pem);

        // Clearing works too.
        update_token_in(&path, None).unwrap();
        assert!(load_from(&path).unwrap().unwrap().token.is_none());

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn disabled_token_store_never_touches_disk() {
        // The whole point of `disabled()` is that tests never hit the real store;
        // calling save_token on it must simply do nothing (and not panic).
        TokenStore::disabled().save_token("whatever");
    }

    #[test]
    fn save_overwrites_previous_state_atomically() {
        let dir = temp_dir("overwrite");
        let path = dir.join("state.json");
        save_to(&path, &sample_state("111111", "10.0.0.1")).unwrap();
        save_to(&path, &sample_state("222222", "10.0.0.9")).unwrap();
        let loaded = load_from(&path).unwrap().unwrap();
        assert_eq!(loaded.pin, "222222");
        assert_eq!(loaded.lan_ip, "10.0.0.9");
        // The temp file was consumed by the rename.
        assert!(!path.with_extension("json.tmp").exists());
        let _ = fs::remove_dir_all(&dir);
    }
}
