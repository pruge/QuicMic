use base64::Engine;
use ring::digest::{digest, SHA256};
use std::fs;
use std::net::IpAddr;
use std::path::Path;
use tracing::info;
use wtransport::Identity;

/// Directory where generated certificates are stored (only used with --dump-certs).
const CERTS_DIR: &str = "certs";

/// Holds the generated TLS identity material and its SHA-256 fingerprint.
#[derive(Clone)]
pub struct TlsIdentity {
    pub cert_pem: String,
    pub key_pem: String,
    pub cert_der: Vec<u8>,
    /// Base64-encoded SHA-256 hash (used by the JS client for serverCertificateHashes).
    pub cert_hash_base64: String,
}

/// Generate a fresh ECDSA P-256 self-signed certificate for the given LAN IP.
///
/// The certificate is generated completely in memory using wtransport's
/// built-in self-signed builder. It includes both the IP address and
/// "localhost" as Subject Alternative Names.
///
/// If `dump_certs` is true, the PEM files are saved to the `certs/` directory
/// for inspection (debug use only).
pub fn generate_identity(
    lan_ip: IpAddr,
    dump_certs: bool,
) -> anyhow::Result<(Identity, TlsIdentity)> {
    let san_ip = lan_ip.to_string();
    let wt_identity = Identity::self_signed(["localhost", &san_ip])
        .map_err(|e| anyhow::anyhow!("Failed to generate self-signed identity: {:?}", e))?;

    // Extract the leaf certificate from the chain
    let cert_chain = wt_identity.certificate_chain();
    let cert = cert_chain.as_slice().first().ok_or_else(|| {
        anyhow::anyhow!("Self-signed identity generated an empty certificate chain")
    })?;

    let cert_pem = cert.to_pem();
    let cert_der = cert.der().to_vec();
    let key_pem = wt_identity.private_key().to_secret_pem();

    // Compute SHA-256 fingerprint of the DER-encoded certificate
    let hash_result = digest(&SHA256, &cert_der);
    let cert_hash_base64 = base64::engine::general_purpose::STANDARD.encode(hash_result.as_ref());

    let tls_identity = TlsIdentity {
        cert_pem,
        key_pem,
        cert_der,
        cert_hash_base64,
    };

    if dump_certs {
        let dir = Path::new(CERTS_DIR);
        fs::create_dir_all(dir)?;
        fs::write(dir.join("cert.pem"), &tls_identity.cert_pem)?;
        fs::write(dir.join("key.pem"), &tls_identity.key_pem)?;
        fs::write(dir.join("cert.der"), &tls_identity.cert_der)?;
        info!("Certificate files dumped to certs/ directory");
    }

    info!(
        hash = %tls_identity.cert_hash_base64,
        "Generated ECDSA P-256 in-memory certificate (14-day lifetime)"
    );

    Ok((wt_identity, tls_identity))
}

/// Build RustlsConfig for axum-server from the TLS identity.
pub async fn build_rustls_config_async(
    identity: &TlsIdentity,
) -> anyhow::Result<axum_server::tls_rustls::RustlsConfig> {
    let config = axum_server::tls_rustls::RustlsConfig::from_pem(
        identity.cert_pem.as_bytes().to_vec(),
        identity.key_pem.as_bytes().to_vec(),
    )
    .await?;
    Ok(config)
}
