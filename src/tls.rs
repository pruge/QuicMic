use base64::Engine;
use ring::digest::{digest, SHA256};
use std::fs;
use std::net::IpAddr;
use std::path::Path;
use tracing::info;
use wtransport::tls::{Certificate, CertificateChain, PrivateKey};
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

/// Rebuild an identity from previously persisted PEM material.
///
/// The mirror image of `generate_identity`: parses the leaf certificate and the
/// PKCS#8 private key out of their PEM encodings and re-derives `TlsIdentity`
/// (DER + SHA-256 hash) so a restarted server presents byte-identical TLS
/// material. Callers must already have checked that the stored certificate's
/// SANs match the current LAN IP — this function does not inspect them.
pub fn restore_identity(cert_pem: &str, key_pem: &str) -> anyhow::Result<(Identity, TlsIdentity)> {
    let cert_der = pem_section(cert_pem, "CERTIFICATE")?;
    let key_der = pem_section(key_pem, "PRIVATE KEY")?;

    let cert = Certificate::from_der(cert_der.clone())
        .map_err(|e| anyhow::anyhow!("Stored certificate is not valid DER: {e:?}"))?;
    let wt_identity = Identity::new(
        CertificateChain::single(cert),
        // `to_secret_pem` writes PKCS#8 ("PRIVATE KEY"), so the round trip lands
        // back in `from_der_pkcs8`.
        PrivateKey::from_der_pkcs8(key_der),
    );

    let hash_result = digest(&SHA256, &cert_der);
    let cert_hash_base64 = base64::engine::general_purpose::STANDARD.encode(hash_result.as_ref());

    let tls_identity = TlsIdentity {
        cert_pem: cert_pem.to_string(),
        key_pem: key_pem.to_string(),
        cert_der,
        cert_hash_base64,
    };

    info!(
        hash = %tls_identity.cert_hash_base64,
        "Restored persisted ECDSA P-256 certificate"
    );

    Ok((wt_identity, tls_identity))
}

/// Extract and base64-decode the first `-----BEGIN <label>-----` section of a
/// PEM document into raw DER bytes.
fn pem_section(pem: &str, label: &str) -> anyhow::Result<Vec<u8>> {
    let begin = format!("-----BEGIN {label}-----");
    let end = format!("-----END {label}-----");

    let start = pem
        .find(&begin)
        .ok_or_else(|| anyhow::anyhow!("PEM is missing a {begin} section"))?
        + begin.len();
    let stop = pem[start..]
        .find(&end)
        .ok_or_else(|| anyhow::anyhow!("PEM has {begin} but no matching {end}"))?
        + start;

    // Tolerate arbitrary whitespace inside the body (line wrapping is the norm).
    let body: String = pem[start..stop]
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect();
    base64::engine::general_purpose::STANDARD
        .decode(body.as_bytes())
        .map_err(|e| anyhow::anyhow!("Invalid base64 in PEM {label} section: {e}"))
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

#[cfg(test)]
mod tests {
    use super::{generate_identity, restore_identity};
    use std::net::IpAddr;

    #[test]
    fn restored_identity_matches_original_fingerprint() {
        let lan_ip: IpAddr = "192.168.1.42".parse().unwrap();
        let (_, original) = generate_identity(lan_ip, false).unwrap();

        let (wt_restored, restored) =
            restore_identity(&original.cert_pem, &original.key_pem).unwrap();

        // The hash the phone pins (via /api/info -> serverCertificateHashes) must
        // be byte-identical after a restart, or every client would have to re-pair.
        assert_eq!(restored.cert_hash_base64, original.cert_hash_base64);
        assert_eq!(restored.cert_der, original.cert_der);
        assert_eq!(restored.cert_pem, original.cert_pem);
        assert_eq!(restored.key_pem, original.key_pem);

        // The rebuilt wtransport Identity must carry the same leaf certificate.
        let restored_der = wt_restored.certificate_chain().as_slice()[0].der().to_vec();
        assert_eq!(restored_der, original.cert_der);
    }

    #[test]
    fn restore_rejects_malformed_pem() {
        assert!(restore_identity("not a pem", "also not").is_err());
        // A certificate without its key is just as unusable.
        assert!(restore_identity(
            "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----",
            "",
        )
        .is_err());
    }
}
