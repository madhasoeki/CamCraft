//! In-app GitHub Releases update checker, SHA-256 verifier, and self-updater.

use std::cmp::Ordering;
use std::fmt;
use std::fs::{self, File};
use std::io::{ErrorKind, Read, Write};
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use flate2::read::GzDecoder;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use tar::Archive;
use thiserror::Error;
use tracing::{debug, info, warn};

/// Default GitHub `owner/repo` slug used to query releases.
pub const DEFAULT_GITHUB_REPO: &str = "madhasoeki/CamCraft";

/// Default GitHub REST API base URL.
pub const DEFAULT_API_BASE_URL: &str = "https://api.github.com";

/// Compile-time version of `camcraft-core` / desktop workspace.
pub const CURRENT_VERSION: &str = env!("CARGO_PKG_VERSION");

/// Maximum permitted download / extraction payload size (256 MiB).
pub const MAX_DOWNLOAD_BYTES: u64 = 256 * 1024 * 1024;

const DOWNLOAD_CHUNK_BYTES: usize = 32 * 1024;
const CHECKSUM_FILE_NAME: &str = "SHA256SUMS.txt";

/// Errors produced while checking for, downloading, verifying, or installing updates.
#[derive(Debug, Error)]
pub enum UpdateError {
    #[error("invalid semantic version `{0}`")]
    InvalidVersion(String),

    #[error("HTTP request failed: {0}")]
    Http(String),

    #[error("GitHub API returned unexpected HTTP {status}: {message}")]
    HttpStatus { status: u16, message: String },

    #[error("failed to parse GitHub release metadata: {0}")]
    InvalidJson(String),

    #[error("no compatible Linux desktop archive found in release {version}")]
    NoMatchingAsset { version: String },

    #[error("download size {size} bytes exceeds safety limit of {limit} bytes")]
    PayloadTooLarge { size: u64, limit: u64 },

    #[error("checksum error: {0}")]
    InvalidChecksumFile(String),

    #[error("SHA-256 verification failed: expected {expected}, got {actual}")]
    ChecksumMismatch { expected: String, actual: String },

    #[error("archive did not contain expected binary `{binary}`")]
    MissingBinaryInArchive { binary: String },

    #[error(
        "installation directory `{path}` is not writable ({source}); update manually or reinstall with appropriate permissions"
    )]
    PermissionDenied {
        path: PathBuf,
        #[source]
        source: std::io::Error,
    },

    #[error("self-replacement failed: {0}")]
    SelfReplace(String),

    #[error("I/O error during update: {0}")]
    Io(#[from] std::io::Error),
}

/// Configuration for GitHub release update checks.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpdateConfig {
    pub repo: String,
    pub current_version: String,
    pub api_base_url: String,
}

impl Default for UpdateConfig {
    fn default() -> Self {
        let repo = std::env::var("CAMCRAFT_UPDATE_REPO")
            .ok()
            .map(|s| s.trim().to_owned())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| DEFAULT_GITHUB_REPO.to_owned());

        Self {
            repo,
            current_version: CURRENT_VERSION.to_owned(),
            api_base_url: DEFAULT_API_BASE_URL.to_owned(),
        }
    }
}

/// Parsed Semantic Version (`major.minor.patch[-prerelease]`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SemVer {
    pub major: u64,
    pub minor: u64,
    pub patch: u64,
    pub pre: Option<String>,
}

impl SemVer {
    /// Parses a SemVer string, stripping an optional leading `v`/`V` and `+build` metadata.
    pub fn parse(raw: &str) -> Result<Self, UpdateError> {
        let trimmed = raw.trim();
        let no_prefix = trimmed
            .strip_prefix('v')
            .or_else(|| trimmed.strip_prefix('V'))
            .unwrap_or(trimmed);
        let without_build = no_prefix.split('+').next().unwrap_or(no_prefix);

        let (core, pre) = match without_build.split_once('-') {
            Some((c, p)) => {
                if p.is_empty() || p.split('.').any(str::is_empty) {
                    return Err(UpdateError::InvalidVersion(raw.to_owned()));
                }
                (c, Some(p.to_owned()))
            }
            None => (without_build, None),
        };

        let mut parts = core.split('.');
        let major_str = parts
            .next()
            .ok_or_else(|| UpdateError::InvalidVersion(raw.to_owned()))?;
        let minor_str = parts
            .next()
            .ok_or_else(|| UpdateError::InvalidVersion(raw.to_owned()))?;
        let patch_str = parts
            .next()
            .ok_or_else(|| UpdateError::InvalidVersion(raw.to_owned()))?;
        if parts.next().is_some() {
            return Err(UpdateError::InvalidVersion(raw.to_owned()));
        }

        let parse_num = |s: &str| -> Result<u64, UpdateError> {
            if s.is_empty() || !s.bytes().all(|b| b.is_ascii_digit()) {
                return Err(UpdateError::InvalidVersion(raw.to_owned()));
            }
            s.parse::<u64>()
                .map_err(|_| UpdateError::InvalidVersion(raw.to_owned()))
        };

        Ok(Self {
            major: parse_num(major_str)?,
            minor: parse_num(minor_str)?,
            patch: parse_num(patch_str)?,
            pre,
        })
    }
}

impl fmt::Display for SemVer {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match &self.pre {
            Some(pre) => write!(f, "{}.{}.{}-{pre}", self.major, self.minor, self.patch),
            None => write!(f, "{}.{}.{}", self.major, self.minor, self.patch),
        }
    }
}

impl Ord for SemVer {
    fn cmp(&self, other: &Self) -> Ordering {
        self.major
            .cmp(&other.major)
            .then_with(|| self.minor.cmp(&other.minor))
            .then_with(|| self.patch.cmp(&other.patch))
            .then_with(|| compare_prerelease(self.pre.as_deref(), other.pre.as_deref()))
    }
}

impl PartialOrd for SemVer {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

fn compare_prerelease(a: Option<&str>, b: Option<&str>) -> Ordering {
    match (a, b) {
        (None, None) => Ordering::Equal,
        (None, Some(_)) => Ordering::Greater,
        (Some(_), None) => Ordering::Less,
        (Some(pa), Some(pb)) => {
            let mut ia = pa.split('.');
            let mut ib = pb.split('.');
            loop {
                match (ia.next(), ib.next()) {
                    (None, None) => return Ordering::Equal,
                    (None, Some(_)) => return Ordering::Less,
                    (Some(_), None) => return Ordering::Greater,
                    (Some(seg_a), Some(seg_b)) => {
                        let ord = compare_pre_segment(seg_a, seg_b);
                        if ord != Ordering::Equal {
                            return ord;
                        }
                    }
                }
            }
        }
    }
}

fn compare_pre_segment(a: &str, b: &str) -> Ordering {
    let a_num = a.parse::<u64>();
    let b_num = b.parse::<u64>();
    match (a_num, b_num) {
        (Ok(na), Ok(nb)) => na.cmp(&nb),
        (Ok(_), Err(_)) => Ordering::Less,
        (Err(_), Ok(_)) => Ordering::Greater,
        (Err(_), Err(_)) => a.cmp(b),
    }
}

/// Metadata describing an available desktop release update.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReleaseInfo {
    pub tag_name: String,
    pub version: String,
    pub release_notes: String,
    pub html_url: String,
    pub asset_name: String,
    pub download_url: String,
    pub asset_size: u64,
    pub checksums_url: Option<String>,
}

/// Outcome returned by [`check_for_update`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UpdateCheckOutcome {
    UpToDate {
        current_version: String,
        latest_version: String,
    },
    UpdateAvailable(ReleaseInfo),
}

/// Streaming download progress notification.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DownloadProgress {
    pub downloaded_bytes: u64,
    pub total_bytes: u64,
}

/// Result returned by [`download_and_install_update`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstallOutcome {
    pub version: String,
    pub binary_path: PathBuf,
    pub sibling_updated: bool,
}

#[derive(Debug, Deserialize)]
struct GitHubRelease {
    tag_name: String,
    #[serde(default)]
    body: Option<String>,
    #[serde(default)]
    html_url: String,
    #[serde(default)]
    draft: bool,
    #[serde(default)]
    assets: Vec<GitHubAsset>,
}

#[derive(Debug, Deserialize)]
struct GitHubAsset {
    name: String,
    browser_download_url: String,
    #[serde(default)]
    size: u64,
}

/// Returns `true` if `/etc/os-release` identifies Fedora or a Fedora/RHEL derivative.
#[must_use]
pub fn is_fedora_like_os() -> bool {
    let Ok(content) = fs::read_to_string("/etc/os-release") else {
        return false;
    };
    is_fedora_os_release(&content)
}

fn is_fedora_os_release(content: &str) -> bool {
    for raw_line in content.lines() {
        let line = raw_line.trim();
        if let Some(val) = line.strip_prefix("ID=") {
            let clean = val
                .trim_matches('"')
                .trim_matches('\'')
                .to_ascii_lowercase();
            if clean == "fedora" {
                return true;
            }
        } else if let Some(val) = line.strip_prefix("ID_LIKE=") {
            let clean = val
                .trim_matches('"')
                .trim_matches('\'')
                .to_ascii_lowercase();
            if clean
                .split_whitespace()
                .any(|tok| tok == "fedora" || tok == "rhel")
            {
                return true;
            }
        }
    }
    false
}

/// Returns ordered candidate archive filenames for the given Linux variant and architecture.
#[must_use]
pub fn preferred_desktop_asset_names(is_fedora: bool, arch: &str) -> Vec<String> {
    if arch == "x86_64" {
        if is_fedora {
            vec![
                "camcraft-desktop-fedora-x86_64.tar.gz".to_owned(),
                "camcraft-desktop-linux-x86_64.tar.gz".to_owned(),
            ]
        } else {
            vec![
                "camcraft-desktop-linux-x86_64.tar.gz".to_owned(),
                "camcraft-desktop-fedora-x86_64.tar.gz".to_owned(),
            ]
        }
    } else {
        vec![format!("camcraft-desktop-linux-{arch}.tar.gz")]
    }
}

fn build_http_agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .timeout_connect(Some(Duration::from_secs(10)))
        .timeout_recv_response(Some(Duration::from_secs(15)))
        .timeout_global(Some(Duration::from_secs(300)))
        .http_status_as_error(false)
        .build()
        .into()
}

/// Queries GitHub Releases (`releases/latest`) and compares versions using SemVer.
pub fn check_for_update(config: &UpdateConfig) -> Result<UpdateCheckOutcome, UpdateError> {
    let current_sv = SemVer::parse(&config.current_version)?;
    let base = config.api_base_url.trim_end_matches('/');
    let url = format!("{base}/repos/{}/releases/latest", config.repo);
    let user_agent = format!("CamCraft/{}", config.current_version);

    debug!(%url, "Checking GitHub Releases for updates");
    let agent = build_http_agent();
    let mut response = agent
        .get(&url)
        .header("User-Agent", &user_agent)
        .header("Accept", "application/vnd.github+json")
        .call()
        .map_err(|e| UpdateError::Http(e.to_string()))?;

    let status = response.status().as_u16();
    if status == 404 {
        return Ok(UpdateCheckOutcome::UpToDate {
            current_version: config.current_version.clone(),
            latest_version: config.current_version.clone(),
        });
    }

    let body = response
        .body_mut()
        .read_to_string()
        .map_err(|e| UpdateError::Http(e.to_string()))?;

    if status != 200 {
        return Err(UpdateError::HttpStatus {
            status,
            message: summarize_http_error(&body),
        });
    }

    let release: GitHubRelease =
        serde_json::from_str(&body).map_err(|e| UpdateError::InvalidJson(e.to_string()))?;

    if release.draft {
        return Ok(UpdateCheckOutcome::UpToDate {
            current_version: config.current_version.clone(),
            latest_version: config.current_version.clone(),
        });
    }

    let latest_sv = SemVer::parse(&release.tag_name)?;
    if latest_sv <= current_sv {
        return Ok(UpdateCheckOutcome::UpToDate {
            current_version: config.current_version.clone(),
            latest_version: release.tag_name,
        });
    }

    let candidates = preferred_desktop_asset_names(is_fedora_like_os(), std::env::consts::ARCH);
    let selected_asset = candidates
        .iter()
        .find_map(|cand| release.assets.iter().find(|a| a.name == *cand))
        .ok_or_else(|| UpdateError::NoMatchingAsset {
            version: release.tag_name.clone(),
        })?;

    let checksums_url = release
        .assets
        .iter()
        .find(|a| a.name == CHECKSUM_FILE_NAME)
        .map(|a| a.browser_download_url.clone());

    Ok(UpdateCheckOutcome::UpdateAvailable(ReleaseInfo {
        tag_name: release.tag_name,
        version: latest_sv.to_string(),
        release_notes: release.body.unwrap_or_default(),
        html_url: release.html_url,
        asset_name: selected_asset.name.clone(),
        download_url: selected_asset.browser_download_url.clone(),
        asset_size: selected_asset.size,
        checksums_url,
    }))
}

fn summarize_http_error(body: &str) -> String {
    #[derive(Deserialize)]
    struct ApiErr {
        message: Option<String>,
    }
    if let Ok(parsed) = serde_json::from_str::<ApiErr>(body)
        && let Some(msg) = parsed.message
    {
        return msg;
    }
    let trimmed = body.trim();
    if trimmed.is_empty() {
        "empty error response".to_owned()
    } else {
        trimmed.chars().take(160).collect()
    }
}

/// Parses a `SHA256SUMS.txt` file and returns the expected 64-character lowercase SHA-256 hex digest for `asset_name`.
pub fn parse_sha256sums(content: &str, asset_name: &str) -> Result<String, UpdateError> {
    for raw_line in content.lines() {
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut parts = line.split_whitespace();
        let Some(hash) = parts.next() else {
            continue;
        };
        let Some(file_token) = parts.next() else {
            continue;
        };
        let clean_name = file_token.trim_start_matches('*').trim_start_matches("./");
        if clean_name == asset_name {
            if hash.len() != 64 || !hash.bytes().all(|b| b.is_ascii_hexdigit()) {
                return Err(UpdateError::InvalidChecksumFile(format!(
                    "malformed SHA-256 digest for `{asset_name}`"
                )));
            }
            return Ok(hash.to_ascii_lowercase());
        }
    }

    Err(UpdateError::InvalidChecksumFile(format!(
        "entry `{asset_name}` not found in {CHECKSUM_FILE_NAME}"
    )))
}

struct StagingDirGuard {
    path: PathBuf,
}

impl StagingDirGuard {
    fn create_in(parent: &Path) -> Result<Self, UpdateError> {
        let pid = std::process::id();
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |d| d.as_nanos());
        let path = parent.join(format!(".camcraft-update-{pid}-{nanos}"));
        fs::create_dir_all(&path).map_err(|err| map_dir_write_error(&path, err))?;
        Ok(Self { path })
    }
}

impl Drop for StagingDirGuard {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

fn map_dir_write_error(path: &Path, err: std::io::Error) -> UpdateError {
    if err.kind() == ErrorKind::PermissionDenied || err.kind() == ErrorKind::ReadOnlyFilesystem {
        UpdateError::PermissionDenied {
            path: path.to_path_buf(),
            source: err,
        }
    } else {
        UpdateError::Io(err)
    }
}

/// Downloads the release archive, verifies `SHA256SUMS.txt` when present, extracts
/// desktop binaries into a staging folder, and atomically replaces the running executable.
pub fn download_and_install_update<F>(
    release: &ReleaseInfo,
    primary_binary_name: &str,
    mut on_progress: F,
) -> Result<InstallOutcome, UpdateError>
where
    F: FnMut(DownloadProgress),
{
    if release.asset_size > MAX_DOWNLOAD_BYTES {
        return Err(UpdateError::PayloadTooLarge {
            size: release.asset_size,
            limit: MAX_DOWNLOAD_BYTES,
        });
    }

    let current_exe = std::env::current_exe()?;
    let exe_dir = current_exe.parent().ok_or_else(|| {
        UpdateError::Io(std::io::Error::new(
            ErrorKind::NotFound,
            "current executable has no parent directory",
        ))
    })?;

    let staging = StagingDirGuard::create_in(exe_dir)?;
    let archive_path = staging.path.join(&release.asset_name);

    let agent = build_http_agent();
    let user_agent = format!("CamCraft/{CURRENT_VERSION}");

    info!(url = %release.download_url, asset = %release.asset_name, "Downloading CamCraft update archive");
    let mut response = agent
        .get(&release.download_url)
        .header("User-Agent", &user_agent)
        .call()
        .map_err(|e| UpdateError::Http(e.to_string()))?;

    let status = response.status().as_u16();
    if status != 200 {
        let msg = response.body_mut().read_to_string().unwrap_or_default();
        return Err(UpdateError::HttpStatus {
            status,
            message: summarize_http_error(&msg),
        });
    }

    let header_len = response
        .headers()
        .get("Content-Length")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or(0);
    let total_bytes = if release.asset_size > 0 {
        release.asset_size
    } else {
        header_len
    };
    if total_bytes > MAX_DOWNLOAD_BYTES {
        return Err(UpdateError::PayloadTooLarge {
            size: total_bytes,
            limit: MAX_DOWNLOAD_BYTES,
        });
    }

    let mut out_file =
        File::create(&archive_path).map_err(|err| map_dir_write_error(exe_dir, err))?;
    let mut reader = response.body_mut().as_reader();
    let mut buf = [0_u8; DOWNLOAD_CHUNK_BYTES];
    let mut hasher = Sha256::new();
    let mut downloaded_bytes: u64 = 0;

    loop {
        let n = reader.read(&mut buf)?;
        if n == 0 {
            break;
        }
        downloaded_bytes = downloaded_bytes.saturating_add(n as u64);
        if downloaded_bytes > MAX_DOWNLOAD_BYTES {
            return Err(UpdateError::PayloadTooLarge {
                size: downloaded_bytes,
                limit: MAX_DOWNLOAD_BYTES,
            });
        }
        hasher.update(&buf[..n]);
        out_file.write_all(&buf[..n])?;
        on_progress(DownloadProgress {
            downloaded_bytes,
            total_bytes: total_bytes.max(downloaded_bytes),
        });
    }
    out_file.flush()?;
    drop(out_file);

    let actual_sha256: String = hasher
        .finalize()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect();

    if let Some(ref checksums_url) = release.checksums_url {
        let mut cs_resp = agent
            .get(checksums_url)
            .header("User-Agent", &user_agent)
            .call()
            .map_err(|e| UpdateError::Http(e.to_string()))?;
        let cs_status = cs_resp.status().as_u16();
        let cs_body = cs_resp
            .body_mut()
            .read_to_string()
            .map_err(|e| UpdateError::Http(e.to_string()))?;
        if cs_status != 200 {
            return Err(UpdateError::HttpStatus {
                status: cs_status,
                message: summarize_http_error(&cs_body),
            });
        }
        let expected_sha256 = parse_sha256sums(&cs_body, &release.asset_name)?;
        if actual_sha256 != expected_sha256 {
            return Err(UpdateError::ChecksumMismatch {
                expected: expected_sha256,
                actual: actual_sha256,
            });
        }
        info!(sha256 = %actual_sha256, "Verified archive SHA-256 checksum");
    } else {
        warn!("Release does not provide SHA256SUMS.txt; skipping checksum verification");
    }

    extract_binaries_from_tar_gz(&archive_path, &staging.path)?;

    let staged_primary = staging.path.join(primary_binary_name);
    if !staged_primary.exists() {
        return Err(UpdateError::MissingBinaryInArchive {
            binary: primary_binary_name.to_owned(),
        });
    }

    self_replace::self_replace(&staged_primary)
        .map_err(|e| UpdateError::SelfReplace(e.to_string()))?;

    let sibling_name = match primary_binary_name {
        "camcraft-gui" => Some("camcraft-cli"),
        "camcraft-cli" => Some("camcraft-gui"),
        _ => None,
    };

    let mut sibling_updated = false;
    if let Some(sib) = sibling_name {
        let staged_sib = staging.path.join(sib);
        let target_sib = exe_dir.join(sib);
        if staged_sib.exists() && target_sib.exists() {
            match fs::rename(&staged_sib, &target_sib) {
                Ok(()) => {
                    sibling_updated = true;
                }
                Err(err) => {
                    warn!(error = %err, sibling = sib, "Failed to update sibling binary");
                }
            }
        }
    }

    info!(version = %release.version, sibling_updated, "Installed CamCraft desktop update");
    Ok(InstallOutcome {
        version: release.version.clone(),
        binary_path: current_exe,
        sibling_updated,
    })
}

fn extract_binaries_from_tar_gz(
    archive_path: &Path,
    staging_dir: &Path,
) -> Result<(), UpdateError> {
    let file = File::open(archive_path)?;
    let gz = GzDecoder::new(file);
    let mut archive = Archive::new(gz);

    let mut total_extracted: u64 = 0;
    for entry_res in archive.entries()? {
        let mut entry = entry_res?;
        if !entry.header().entry_type().is_file() {
            continue;
        }
        let file_name = {
            let entry_path = entry.path()?;
            entry_path
                .file_name()
                .and_then(|n| n.to_str())
                .map(str::to_owned)
        };
        let Some(name) = file_name else {
            continue;
        };
        if name != "camcraft-gui" && name != "camcraft-cli" {
            continue;
        }

        let dest = staging_dir.join(&name);
        let mut out = File::create(&dest)?;
        let mut buf = [0_u8; DOWNLOAD_CHUNK_BYTES];
        loop {
            let n = entry.read(&mut buf)?;
            if n == 0 {
                break;
            }
            total_extracted = total_extracted.saturating_add(n as u64);
            if total_extracted > MAX_DOWNLOAD_BYTES {
                return Err(UpdateError::PayloadTooLarge {
                    size: total_extracted,
                    limit: MAX_DOWNLOAD_BYTES,
                });
            }
            out.write_all(&buf[..n])?;
        }
        out.flush()?;

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = out.metadata()?.permissions();
            perms.set_mode(0o755);
            fs::set_permissions(&dest, perms)?;
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::thread;

    use super::*;

    #[test]
    fn semver_parsing_and_ordering() {
        let v001 = SemVer::parse("v0.0.1").unwrap();
        let v002 = SemVer::parse("0.0.2").unwrap();
        let v0010 = SemVer::parse("0.0.10").unwrap();
        let v009 = SemVer::parse("0.0.9").unwrap();
        let pre_alpha = SemVer::parse("v0.1.0-alpha.1").unwrap();
        let pre_beta = SemVer::parse("v0.1.0-beta.1").unwrap();
        let pre_num_2 = SemVer::parse("v0.1.0-rc.2").unwrap();
        let pre_num_10 = SemVer::parse("v0.1.0-rc.10").unwrap();
        let stable = SemVer::parse("0.1.0+build.42").unwrap();

        assert_eq!(v001, SemVer::parse("0.0.1").unwrap());
        assert!(v001 < v002);
        assert!(v009 < v0010);
        assert!(pre_alpha < pre_beta);
        assert!(pre_num_2 < pre_num_10);
        assert!(pre_beta < stable);
        assert!(pre_num_10 < stable);

        assert!(SemVer::parse("").is_err());
        assert!(SemVer::parse("1.2").is_err());
        assert!(SemVer::parse("1.2.3.4").is_err());
        assert!(SemVer::parse("1.2.x").is_err());
        assert!(SemVer::parse("1.2.3-").is_err());
    }

    #[test]
    fn parse_sha256sums_handles_standard_and_binary_prefixes() {
        let digest_a = "a".repeat(64);
        let digest_b = "B".repeat(64);
        let content = format!(
            "# comment\n{digest_a}  camcraft-desktop-fedora-x86_64.tar.gz\n{digest_b} *./camcraft-desktop-linux-x86_64.tar.gz\n"
        );

        assert_eq!(
            parse_sha256sums(&content, "camcraft-desktop-fedora-x86_64.tar.gz").unwrap(),
            digest_a
        );
        assert_eq!(
            parse_sha256sums(&content, "camcraft-desktop-linux-x86_64.tar.gz").unwrap(),
            "b".repeat(64)
        );
        assert!(parse_sha256sums(&content, "missing.tar.gz").is_err());

        let malformed = "1234  camcraft-desktop-fedora-x86_64.tar.gz\n";
        assert!(parse_sha256sums(malformed, "camcraft-desktop-fedora-x86_64.tar.gz").is_err());
    }

    #[test]
    fn asset_selection_orders_fedora_and_generic_linux() {
        assert_eq!(
            preferred_desktop_asset_names(true, "x86_64"),
            vec![
                "camcraft-desktop-fedora-x86_64.tar.gz",
                "camcraft-desktop-linux-x86_64.tar.gz",
            ]
        );
        assert_eq!(
            preferred_desktop_asset_names(false, "x86_64"),
            vec![
                "camcraft-desktop-linux-x86_64.tar.gz",
                "camcraft-desktop-fedora-x86_64.tar.gz",
            ]
        );
        assert!(is_fedora_os_release("NAME=\"Fedora Linux\"\nID=fedora\n"));
        assert!(is_fedora_os_release("ID=nobara\nID_LIKE=\"fedora\"\n"));
        assert!(!is_fedora_os_release("ID=ubuntu\nID_LIKE=debian\n"));
    }

    fn spawn_single_http_server(status_line: &str, body: String) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let status = status_line.to_owned();
        thread::spawn(move || {
            if let Ok((mut stream, _)) = listener.accept() {
                let mut req_buf = [0_u8; 2048];
                let _ = stream.read(&mut req_buf);
                let resp = format!(
                    "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = stream.write_all(resp.as_bytes());
                let _ = stream.flush();
            }
        });
        format!("http://{addr}")
    }

    #[test]
    fn check_for_update_treats_404_as_up_to_date() {
        let base_url =
            spawn_single_http_server("404 Not Found", "{\"message\":\"Not Found\"}".into());
        let cfg = UpdateConfig {
            repo: "test/repo".into(),
            current_version: "0.0.1".into(),
            api_base_url: base_url,
        };
        let outcome = check_for_update(&cfg).unwrap();
        assert_eq!(
            outcome,
            UpdateCheckOutcome::UpToDate {
                current_version: "0.0.1".into(),
                latest_version: "0.0.1".into(),
            }
        );
    }

    #[test]
    fn check_for_update_detects_newer_release_and_checksums() {
        let payload = r#"{
            "tag_name": "v0.2.0",
            "body": "Faster decoding and bug fixes.",
            "html_url": "https://github.com/test/repo/releases/tag/v0.2.0",
            "draft": false,
            "assets": [
                {
                    "name": "camcraft-desktop-fedora-x86_64.tar.gz",
                    "browser_download_url": "https://example.invalid/fedora.tar.gz",
                    "size": 123456
                },
                {
                    "name": "camcraft-desktop-linux-x86_64.tar.gz",
                    "browser_download_url": "https://example.invalid/linux.tar.gz",
                    "size": 123400
                },
                {
                    "name": "SHA256SUMS.txt",
                    "browser_download_url": "https://example.invalid/SHA256SUMS.txt",
                    "size": 256
                }
            ]
        }"#
        .to_owned();
        let base_url = spawn_single_http_server("200 OK", payload);
        let cfg = UpdateConfig {
            repo: "test/repo".into(),
            current_version: "0.0.1".into(),
            api_base_url: base_url,
        };
        let outcome = check_for_update(&cfg).unwrap();
        match outcome {
            UpdateCheckOutcome::UpdateAvailable(info) => {
                assert_eq!(info.tag_name, "v0.2.0");
                assert_eq!(info.version, "0.2.0");
                assert_eq!(info.release_notes, "Faster decoding and bug fixes.");
                assert_eq!(
                    info.checksums_url.as_deref(),
                    Some("https://example.invalid/SHA256SUMS.txt")
                );
                if is_fedora_like_os() {
                    assert_eq!(info.asset_name, "camcraft-desktop-fedora-x86_64.tar.gz");
                } else {
                    assert_eq!(info.asset_name, "camcraft-desktop-linux-x86_64.tar.gz");
                }
            }
            UpdateCheckOutcome::UpToDate { .. } => panic!("expected UpdateAvailable"),
        }
    }
}
