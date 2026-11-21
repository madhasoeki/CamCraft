//! In-app GitHub Releases update checker, SHA-256 verifier, and self-updater.

use std::cmp::Ordering;
use std::ffi::OsStr;
use std::fmt;
use std::fs::{self, File};
use std::io::{ErrorKind, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
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

/// How the currently running CamCraft desktop binary was installed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InstallKind {
    AppImage(PathBuf),
    RpmPackage,
    DebPackage,
    PortableArchive,
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

/// Concrete installation action taken by [`download_and_install_update`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InstallAction {
    ReplacedInPlace,
    DownloadedPackage {
        package_path: PathBuf,
        install_command: String,
    },
}

/// Result returned by [`download_and_install_update`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstallOutcome {
    pub version: String,
    pub binary_path: PathBuf,
    pub sibling_updated: bool,
    pub action: InstallAction,
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

fn os_release_tokens(content: &str, key: &str) -> Vec<String> {
    let prefix = format!("{key}=");
    for raw_line in content.lines() {
        let line = raw_line.trim();
        if let Some(val) = line.strip_prefix(&prefix) {
            let clean = val
                .trim_matches('"')
                .trim_matches('\'')
                .to_ascii_lowercase();
            return clean.split_whitespace().map(str::to_owned).collect();
        }
    }
    Vec::new()
}

fn os_release_matches_any(content: &str, ids: &[&str], id_likes: &[&str]) -> bool {
    let id_tokens = os_release_tokens(content, "ID");
    if id_tokens.iter().any(|tok| ids.contains(&tok.as_str())) {
        return true;
    }
    let like_tokens = os_release_tokens(content, "ID_LIKE");
    like_tokens
        .iter()
        .any(|tok| id_likes.contains(&tok.as_str()))
}

fn is_fedora_os_release(content: &str) -> bool {
    os_release_matches_any(content, &["fedora"], &["fedora", "rhel"])
}

fn is_rpm_os_release(content: &str) -> bool {
    is_fedora_os_release(content)
        || os_release_matches_any(
            content,
            &[
                "rhel",
                "centos",
                "rocky",
                "almalinux",
                "ol",
                "nobara",
                "bazzite",
                "opensuse-tumbleweed",
                "opensuse-leap",
                "sles",
            ],
            &["fedora", "rhel", "centos", "suse", "opensuse"],
        )
}

fn is_deb_os_release(content: &str) -> bool {
    os_release_matches_any(
        content,
        &[
            "debian",
            "ubuntu",
            "linuxmint",
            "pop",
            "elementary",
            "zorin",
            "kali",
            "raspbian",
            "mx",
        ],
        &["debian", "ubuntu"],
    )
}

/// Detects the current installation method using `$APPIMAGE`, `current_exe()`, and `/etc/os-release`.
#[must_use]
pub fn detect_install_kind() -> InstallKind {
    let appimage_env = std::env::var_os("APPIMAGE").map(PathBuf::from);
    let exe_path = std::env::current_exe().unwrap_or_default();
    let os_release = fs::read_to_string("/etc/os-release").unwrap_or_default();
    detect_install_kind_from(appimage_env.as_deref(), &exe_path, &os_release)
}

/// Pure, testable installation-kind detector given explicit environment inputs.
#[must_use]
pub fn detect_install_kind_from(
    appimage_env: Option<&Path>,
    exe_path: &Path,
    os_release: &str,
) -> InstallKind {
    if let Some(appimage_path) = appimage_env
        && !appimage_path.as_os_str().is_empty()
    {
        return InstallKind::AppImage(appimage_path.to_path_buf());
    }

    let is_system_bin = exe_path.starts_with("/usr/bin") || exe_path.starts_with("/bin");
    if is_system_bin {
        if is_rpm_os_release(os_release) {
            return InstallKind::RpmPackage;
        }
        if is_deb_os_release(os_release) {
            return InstallKind::DebPackage;
        }
    }

    InstallKind::PortableArchive
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

/// Selects the best matching GitHub Release asset name for `install_kind`, falling back to `.tar.gz`.
#[must_use]
pub fn select_asset_for_install_kind<'a, S: AsRef<str>>(
    asset_names: &'a [S],
    install_kind: &InstallKind,
    is_fedora: bool,
    arch: &str,
) -> Option<&'a str> {
    let find_matching = |pred: &dyn Fn(&str) -> bool| -> Option<&'a str> {
        asset_names
            .iter()
            .map(AsRef::as_ref)
            .find(|name| pred(name))
    };

    match install_kind {
        InstallKind::AppImage(_) => {
            if let Some(matched) = find_matching(&|n| {
                n.ends_with(".AppImage") && (arch.is_empty() || n.contains(arch))
            })
            .or_else(|| find_matching(&|n| n.ends_with(".AppImage")))
            {
                return Some(matched);
            }
        }
        InstallKind::RpmPackage => {
            if let Some(matched) = find_matching(&|n| {
                n.ends_with(".rpm")
                    && !n.ends_with(".src.rpm")
                    && (arch.is_empty() || n.contains(arch))
            })
            .or_else(|| find_matching(&|n| n.ends_with(".rpm") && !n.ends_with(".src.rpm")))
            {
                return Some(matched);
            }
        }
        InstallKind::DebPackage => {
            let deb_arch = match arch {
                "x86_64" => "amd64",
                "aarch64" => "arm64",
                other => other,
            };
            let arch_suffix = format!("_{deb_arch}.deb");
            if let Some(matched) =
                find_matching(&|n| !deb_arch.is_empty() && n.ends_with(&arch_suffix))
                    .or_else(|| find_matching(&|n| n.ends_with(".deb")))
            {
                return Some(matched);
            }
        }
        InstallKind::PortableArchive => {}
    }

    let candidates = preferred_desktop_asset_names(is_fedora, arch);
    candidates
        .iter()
        .find_map(|cand| find_matching(&|name| name == cand))
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
    check_for_update_with_kind(config, &detect_install_kind(), is_fedora_like_os())
}

fn check_for_update_with_kind(
    config: &UpdateConfig,
    install_kind: &InstallKind,
    is_fedora: bool,
) -> Result<UpdateCheckOutcome, UpdateError> {
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

    let asset_names: Vec<&str> = release.assets.iter().map(|a| a.name.as_str()).collect();
    let selected_name = select_asset_for_install_kind(
        &asset_names,
        install_kind,
        is_fedora,
        std::env::consts::ARCH,
    )
    .ok_or_else(|| UpdateError::NoMatchingAsset {
        version: release.tag_name.clone(),
    })?;
    let selected_asset = release
        .assets
        .iter()
        .find(|a| a.name == selected_name)
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
        fs::create_dir_all(&path).map_err(|err| map_dir_write_error(parent, err))?;
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

fn package_update_cache_dir_from(xdg_cache_home: Option<&OsStr>, home: Option<&OsStr>) -> PathBuf {
    if let Some(xdg) = xdg_cache_home
        && !xdg.is_empty()
    {
        return PathBuf::from(xdg).join("camcraft").join("updates");
    }
    if let Some(home_dir) = home
        && !home_dir.is_empty()
    {
        return PathBuf::from(home_dir)
            .join(".cache")
            .join("camcraft")
            .join("updates");
    }
    PathBuf::from("/tmp/camcraft-updates")
}

fn sanitize_asset_filename(asset_name: &str) -> String {
    Path::new(asset_name)
        .file_name()
        .and_then(|s| s.to_str())
        .filter(|s| !s.is_empty() && *s != "." && *s != "..")
        .unwrap_or("camcraft-update")
        .to_owned()
}

fn set_executable_permissions(path: &Path) -> Result<(), UpdateError> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = fs::metadata(path)?.permissions();
        perms.set_mode(0o755);
        fs::set_permissions(path, perms)?;
    }
    #[cfg(not(unix))]
    let _ = path;
    Ok(())
}

fn download_and_verify_to_file<F>(
    release: &ReleaseInfo,
    dest_path: &Path,
    permission_dir: &Path,
    on_progress: &mut F,
) -> Result<(), UpdateError>
where
    F: FnMut(DownloadProgress),
{
    if release.asset_size > MAX_DOWNLOAD_BYTES {
        return Err(UpdateError::PayloadTooLarge {
            size: release.asset_size,
            limit: MAX_DOWNLOAD_BYTES,
        });
    }

    let agent = build_http_agent();
    let user_agent = format!("CamCraft/{CURRENT_VERSION}");

    info!(url = %release.download_url, asset = %release.asset_name, "Downloading CamCraft update asset");
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
        File::create(dest_path).map_err(|err| map_dir_write_error(permission_dir, err))?;
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
        info!(sha256 = %actual_sha256, "Verified release asset SHA-256 checksum");
    } else {
        warn!("Release does not provide SHA256SUMS.txt; skipping checksum verification");
    }

    Ok(())
}

/// Downloads, verifies, and applies (or stages for system install) a desktop update according
/// to the detected [`InstallKind`].
pub fn download_and_install_update<F>(
    release: &ReleaseInfo,
    primary_binary_name: &str,
    on_progress: F,
) -> Result<InstallOutcome, UpdateError>
where
    F: FnMut(DownloadProgress),
{
    download_and_install_update_with_options(
        release,
        primary_binary_name,
        &detect_install_kind(),
        None,
        true,
        on_progress,
    )
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SystemPackageKind {
    Rpm,
    Deb,
}

fn download_and_install_update_with_options<F>(
    release: &ReleaseInfo,
    primary_binary_name: &str,
    install_kind: &InstallKind,
    cache_dir_override: Option<&Path>,
    open_with_xdg: bool,
    mut on_progress: F,
) -> Result<InstallOutcome, UpdateError>
where
    F: FnMut(DownloadProgress),
{
    if release.asset_name.ends_with(".AppImage")
        && let InstallKind::AppImage(appimage_path) = install_kind
    {
        return install_appimage_update(appimage_path, release, &mut on_progress);
    }

    if release.asset_name.ends_with(".rpm") {
        return install_system_package_update(
            SystemPackageKind::Rpm,
            release,
            cache_dir_override,
            open_with_xdg,
            &mut on_progress,
        );
    }

    if release.asset_name.ends_with(".deb") {
        return install_system_package_update(
            SystemPackageKind::Deb,
            release,
            cache_dir_override,
            open_with_xdg,
            &mut on_progress,
        );
    }

    install_portable_archive_update(release, primary_binary_name, &mut on_progress)
}

fn install_appimage_update<F>(
    appimage_path: &Path,
    release: &ReleaseInfo,
    on_progress: &mut F,
) -> Result<InstallOutcome, UpdateError>
where
    F: FnMut(DownloadProgress),
{
    let parent = appimage_path.parent().ok_or_else(|| {
        UpdateError::Io(std::io::Error::new(
            ErrorKind::NotFound,
            "AppImage path has no parent directory",
        ))
    })?;

    // Stage in the same directory so `fs::rename` is an atomic same-filesystem replacement.
    let staging = StagingDirGuard::create_in(parent)?;
    let staged_appimage = staging
        .path
        .join(sanitize_asset_filename(&release.asset_name));
    download_and_verify_to_file(release, &staged_appimage, parent, on_progress)?;
    set_executable_permissions(&staged_appimage)?;

    fs::rename(&staged_appimage, appimage_path).map_err(|err| map_dir_write_error(parent, err))?;

    info!(
        version = %release.version,
        appimage = %appimage_path.display(),
        "Replaced AppImage in place"
    );
    Ok(InstallOutcome {
        version: release.version.clone(),
        binary_path: appimage_path.to_path_buf(),
        sibling_updated: false,
        action: InstallAction::ReplacedInPlace,
    })
}

fn install_system_package_update<F>(
    pkg_kind: SystemPackageKind,
    release: &ReleaseInfo,
    cache_dir_override: Option<&Path>,
    open_with_xdg: bool,
    on_progress: &mut F,
) -> Result<InstallOutcome, UpdateError>
where
    F: FnMut(DownloadProgress),
{
    let cache_dir = match cache_dir_override {
        Some(dir) => dir.to_path_buf(),
        None => package_update_cache_dir_from(
            std::env::var_os("XDG_CACHE_HOME").as_deref(),
            std::env::var_os("HOME").as_deref(),
        ),
    };
    fs::create_dir_all(&cache_dir)?;

    let safe_name = sanitize_asset_filename(&release.asset_name);
    let staging = StagingDirGuard::create_in(&cache_dir)?;
    let staged_pkg = staging.path.join(&safe_name);
    download_and_verify_to_file(release, &staged_pkg, &cache_dir, on_progress)?;

    let package_path = cache_dir.join(&safe_name);
    fs::rename(&staged_pkg, &package_path)?;

    let install_command = match pkg_kind {
        SystemPackageKind::Rpm => {
            format!("sudo dnf install -y {}", shell_quote_path(&package_path))
        }
        SystemPackageKind::Deb => {
            format!("sudo apt install -y {}", shell_quote_path(&package_path))
        }
    };

    if open_with_xdg
        && let Err(err) = Command::new("xdg-open")
            .arg(&package_path)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
    {
        debug!(
            error = %err,
            path = %package_path.display(),
            "xdg-open launch failed or unavailable; manual install command provided"
        );
    }

    info!(
        version = %release.version,
        package = %package_path.display(),
        install_command = %install_command,
        "Downloaded verified system package update"
    );
    Ok(InstallOutcome {
        version: release.version.clone(),
        binary_path: package_path.clone(),
        sibling_updated: false,
        action: InstallAction::DownloadedPackage {
            package_path,
            install_command,
        },
    })
}

fn shell_quote_path(path: &Path) -> String {
    let raw = path.display().to_string();
    if raw
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'/' | b'.' | b'_' | b'-' | b'~'))
    {
        raw
    } else {
        let escaped = raw.replace('\'', "'\\''");
        format!("'{escaped}'")
    }
}

fn install_portable_archive_update<F>(
    release: &ReleaseInfo,
    primary_binary_name: &str,
    on_progress: &mut F,
) -> Result<InstallOutcome, UpdateError>
where
    F: FnMut(DownloadProgress),
{
    let current_exe = std::env::current_exe()?;
    let exe_dir = current_exe.parent().ok_or_else(|| {
        UpdateError::Io(std::io::Error::new(
            ErrorKind::NotFound,
            "current executable has no parent directory",
        ))
    })?;

    let staging = StagingDirGuard::create_in(exe_dir)?;
    let archive_path = staging
        .path
        .join(sanitize_asset_filename(&release.asset_name));
    download_and_verify_to_file(release, &archive_path, exe_dir, on_progress)?;

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
        action: InstallAction::ReplacedInPlace,
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
        set_executable_permissions(&dest)?;
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
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

    #[test]
    fn detect_install_kind_handles_appimage_rpm_deb_and_portable() {
        assert_eq!(
            detect_install_kind_from(
                Some(Path::new("/home/user/Applications/CamCraft.AppImage")),
                Path::new("/tmp/.mount_CamCraXYZ/usr/bin/camcraft-gui"),
                "ID=ubuntu\nID_LIKE=debian\n",
            ),
            InstallKind::AppImage(PathBuf::from("/home/user/Applications/CamCraft.AppImage"))
        );

        assert_eq!(
            detect_install_kind_from(
                Some(Path::new("")),
                Path::new("/usr/bin/camcraft-gui"),
                "ID=fedora\n",
            ),
            InstallKind::RpmPackage
        );

        assert_eq!(
            detect_install_kind_from(
                None,
                Path::new("/bin/camcraft-cli"),
                "ID=nobara\nID_LIKE=\"fedora\"\n",
            ),
            InstallKind::RpmPackage
        );

        assert_eq!(
            detect_install_kind_from(
                None,
                Path::new("/usr/bin/camcraft-gui"),
                "ID=ubuntu\nID_LIKE=debian\n",
            ),
            InstallKind::DebPackage
        );

        assert_eq!(
            detect_install_kind_from(None, Path::new("/usr/bin/camcraft-gui"), "ID=debian\n",),
            InstallKind::DebPackage
        );

        assert_eq!(
            detect_install_kind_from(
                None,
                Path::new("/home/user/.local/bin/camcraft-gui"),
                "ID=fedora\n",
            ),
            InstallKind::PortableArchive
        );

        assert_eq!(
            detect_install_kind_from(None, Path::new("/usr/bin/camcraft-gui"), "ID=arch\n",),
            InstallKind::PortableArchive
        );
    }

    #[test]
    fn select_asset_for_install_kind_matches_packages_and_falls_back_to_tarball() {
        let full_assets = [
            "SHA256SUMS.txt",
            "camcraft-0.2.0-1.src.rpm",
            "camcraft-0.2.0-1.x86_64.rpm",
            "camcraft_0.2.0_amd64.deb",
            "CamCraft-v0.2.0-x86_64.AppImage",
            "camcraft-desktop-fedora-x86_64.tar.gz",
            "camcraft-desktop-linux-x86_64.tar.gz",
        ];

        assert_eq!(
            select_asset_for_install_kind(
                &full_assets,
                &InstallKind::AppImage(PathBuf::from("/tmp/CamCraft.AppImage")),
                false,
                "x86_64"
            ),
            Some("CamCraft-v0.2.0-x86_64.AppImage")
        );
        assert_eq!(
            select_asset_for_install_kind(&full_assets, &InstallKind::RpmPackage, true, "x86_64"),
            Some("camcraft-0.2.0-1.x86_64.rpm")
        );
        assert_eq!(
            select_asset_for_install_kind(&full_assets, &InstallKind::DebPackage, false, "x86_64"),
            Some("camcraft_0.2.0_amd64.deb")
        );
        assert_eq!(
            select_asset_for_install_kind(
                &full_assets,
                &InstallKind::PortableArchive,
                true,
                "x86_64"
            ),
            Some("camcraft-desktop-fedora-x86_64.tar.gz")
        );

        let legacy_tarball_only = [
            "camcraft-desktop-fedora-x86_64.tar.gz",
            "camcraft-desktop-linux-x86_64.tar.gz",
        ];
        assert_eq!(
            select_asset_for_install_kind(
                &legacy_tarball_only,
                &InstallKind::AppImage(PathBuf::from("/tmp/CamCraft.AppImage")),
                false,
                "x86_64"
            ),
            Some("camcraft-desktop-linux-x86_64.tar.gz")
        );
        assert_eq!(
            select_asset_for_install_kind(
                &legacy_tarball_only,
                &InstallKind::RpmPackage,
                true,
                "x86_64"
            ),
            Some("camcraft-desktop-fedora-x86_64.tar.gz")
        );
    }

    #[test]
    fn package_cache_dir_respects_xdg_and_home_fallbacks() {
        assert_eq!(
            package_update_cache_dir_from(
                Some(OsStr::new("/var/cache/custom")),
                Some(OsStr::new("/home/alice")),
            ),
            PathBuf::from("/var/cache/custom/camcraft/updates")
        );
        assert_eq!(
            package_update_cache_dir_from(Some(OsStr::new("")), Some(OsStr::new("/home/alice"))),
            PathBuf::from("/home/alice/.cache/camcraft/updates")
        );
        assert_eq!(
            package_update_cache_dir_from(None, None),
            PathBuf::from("/tmp/camcraft-updates")
        );
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

    fn spawn_route_http_server(
        routes: HashMap<String, Vec<u8>>,
        expected_requests: usize,
    ) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        thread::spawn(move || {
            for _ in 0..expected_requests {
                let Ok((mut stream, _)) = listener.accept() else {
                    break;
                };
                let mut req_buf = [0_u8; 4096];
                let n = stream.read(&mut req_buf).unwrap_or(0);
                let req = String::from_utf8_lossy(&req_buf[..n]);
                let path = req
                    .lines()
                    .next()
                    .and_then(|line| line.split_whitespace().nth(1))
                    .unwrap_or("/");
                if let Some(body) = routes.get(path) {
                    let header = format!(
                        "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                        body.len()
                    );
                    let _ = stream.write_all(header.as_bytes());
                    let _ = stream.write_all(body);
                } else {
                    let msg = b"Not Found";
                    let header = format!(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                        msg.len()
                    );
                    let _ = stream.write_all(header.as_bytes());
                    let _ = stream.write_all(msg);
                }
                let _ = stream.flush();
            }
        });
        format!("http://{addr}")
    }

    fn sha256_hex(bytes: &[u8]) -> String {
        let mut hasher = Sha256::new();
        hasher.update(bytes);
        hasher
            .finalize()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect()
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

    #[test]
    fn check_for_update_with_kind_picks_package_or_appimage_asset() {
        let payload = r#"{
            "tag_name": "v0.3.0",
            "body": "Packaging release.",
            "html_url": "https://github.com/test/repo/releases/tag/v0.3.0",
            "draft": false,
            "assets": [
                {
                    "name": "CamCraft-v0.3.0-x86_64.AppImage",
                    "browser_download_url": "https://example.invalid/CamCraft.AppImage",
                    "size": 9000
                },
                {
                    "name": "camcraft-0.3.0-1.x86_64.rpm",
                    "browser_download_url": "https://example.invalid/camcraft.rpm",
                    "size": 8000
                },
                {
                    "name": "camcraft_0.3.0_amd64.deb",
                    "browser_download_url": "https://example.invalid/camcraft.deb",
                    "size": 7000
                },
                {
                    "name": "camcraft-desktop-fedora-x86_64.tar.gz",
                    "browser_download_url": "https://example.invalid/fedora.tar.gz",
                    "size": 6000
                },
                {
                    "name": "SHA256SUMS.txt",
                    "browser_download_url": "https://example.invalid/SHA256SUMS.txt",
                    "size": 256
                }
            ]
        }"#
        .to_owned();

        let base_app = spawn_single_http_server("200 OK", payload.clone());
        let cfg_app = UpdateConfig {
            repo: "test/repo".into(),
            current_version: "0.0.1".into(),
            api_base_url: base_app,
        };
        let app_outcome = check_for_update_with_kind(
            &cfg_app,
            &InstallKind::AppImage(PathBuf::from("/tmp/CamCraft.AppImage")),
            false,
        )
        .unwrap();
        let UpdateCheckOutcome::UpdateAvailable(app_info) = app_outcome else {
            panic!("expected UpdateAvailable");
        };
        assert_eq!(app_info.asset_name, "CamCraft-v0.3.0-x86_64.AppImage");

        let base_rpm = spawn_single_http_server("200 OK", payload.clone());
        let cfg_rpm = UpdateConfig {
            repo: "test/repo".into(),
            current_version: "0.0.1".into(),
            api_base_url: base_rpm,
        };
        let rpm_outcome =
            check_for_update_with_kind(&cfg_rpm, &InstallKind::RpmPackage, true).unwrap();
        let UpdateCheckOutcome::UpdateAvailable(rpm_info) = rpm_outcome else {
            panic!("expected UpdateAvailable");
        };
        assert_eq!(rpm_info.asset_name, "camcraft-0.3.0-1.x86_64.rpm");

        let base_deb = spawn_single_http_server("200 OK", payload);
        let cfg_deb = UpdateConfig {
            repo: "test/repo".into(),
            current_version: "0.0.1".into(),
            api_base_url: base_deb,
        };
        let deb_outcome =
            check_for_update_with_kind(&cfg_deb, &InstallKind::DebPackage, false).unwrap();
        let UpdateCheckOutcome::UpdateAvailable(deb_info) = deb_outcome else {
            panic!("expected UpdateAvailable");
        };
        assert_eq!(deb_info.asset_name, "camcraft_0.3.0_amd64.deb");
    }

    struct TempDirCleanup(PathBuf);

    impl TempDirCleanup {
        fn new(prefix: &str) -> Self {
            let nanos = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_or(0, |d| d.as_nanos());
            let dir = std::env::temp_dir().join(format!("{prefix}-{}-{nanos}", std::process::id()));
            fs::create_dir_all(&dir).unwrap();
            Self(dir)
        }
    }

    impl Drop for TempDirCleanup {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn appimage_update_verifies_checksum_and_replaces_in_place() {
        let tmp = TempDirCleanup::new("camcraft-appimage-test");
        let target_appimage = tmp.0.join("CamCraft.AppImage");
        fs::write(&target_appimage, b"old-appimage-bytes").unwrap();

        let new_bytes = b"new-verified-appimage-payload".to_vec();
        let asset_name = "CamCraft-v0.2.0-x86_64.AppImage";
        let sums = format!("{}  {asset_name}\n", sha256_hex(&new_bytes));

        let mut routes = HashMap::new();
        routes.insert("/CamCraft.AppImage".to_owned(), new_bytes.clone());
        routes.insert("/SHA256SUMS.txt".to_owned(), sums.into_bytes());
        let base_url = spawn_route_http_server(routes, 2);

        let release = ReleaseInfo {
            tag_name: "v0.2.0".into(),
            version: "0.2.0".into(),
            release_notes: String::new(),
            html_url: String::new(),
            asset_name: asset_name.into(),
            download_url: format!("{base_url}/CamCraft.AppImage"),
            asset_size: new_bytes.len() as u64,
            checksums_url: Some(format!("{base_url}/SHA256SUMS.txt")),
        };

        let mut progress_seen = Vec::new();
        let outcome = download_and_install_update_with_options(
            &release,
            "camcraft-gui",
            &InstallKind::AppImage(target_appimage.clone()),
            None,
            false,
            |p| progress_seen.push(p.downloaded_bytes),
        )
        .unwrap();

        assert_eq!(outcome.action, InstallAction::ReplacedInPlace);
        assert_eq!(outcome.binary_path, target_appimage);
        assert!(!outcome.sibling_updated);
        assert_eq!(fs::read(&target_appimage).unwrap(), new_bytes);
        assert_eq!(progress_seen.last().copied(), Some(new_bytes.len() as u64));

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(&target_appimage).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode, 0o755);
        }
    }

    #[test]
    fn appimage_update_rejects_bad_checksum_without_overwriting() {
        let tmp = TempDirCleanup::new("camcraft-appimage-badsum");
        let target_appimage = tmp.0.join("CamCraft.AppImage");
        fs::write(&target_appimage, b"original-good-appimage").unwrap();

        let new_bytes = b"corrupted-appimage".to_vec();
        let asset_name = "CamCraft-v0.2.0-x86_64.AppImage";
        let bad_sums = format!("{}  {asset_name}\n", "0".repeat(64));

        let mut routes = HashMap::new();
        routes.insert("/CamCraft.AppImage".to_owned(), new_bytes.clone());
        routes.insert("/SHA256SUMS.txt".to_owned(), bad_sums.into_bytes());
        let base_url = spawn_route_http_server(routes, 2);

        let release = ReleaseInfo {
            tag_name: "v0.2.0".into(),
            version: "0.2.0".into(),
            release_notes: String::new(),
            html_url: String::new(),
            asset_name: asset_name.into(),
            download_url: format!("{base_url}/CamCraft.AppImage"),
            asset_size: new_bytes.len() as u64,
            checksums_url: Some(format!("{base_url}/SHA256SUMS.txt")),
        };

        let err = download_and_install_update_with_options(
            &release,
            "camcraft-gui",
            &InstallKind::AppImage(target_appimage.clone()),
            None,
            false,
            |_| {},
        )
        .unwrap_err();
        assert!(matches!(err, UpdateError::ChecksumMismatch { .. }));
        assert_eq!(
            fs::read(&target_appimage).unwrap(),
            b"original-good-appimage"
        );
    }

    #[test]
    fn rpm_and_deb_updates_download_to_cache_dir_without_permission_denied() {
        let tmp = TempDirCleanup::new("camcraft-pkg-test");

        let rpm_bytes = b"fake-rpm-payload".to_vec();
        let rpm_name = "camcraft-0.2.0-1.x86_64.rpm";
        let deb_bytes = b"fake-deb-payload".to_vec();
        let deb_name = "camcraft_0.2.0_amd64.deb";
        let sums = format!(
            "{}  {rpm_name}\n{}  {deb_name}\n",
            sha256_hex(&rpm_bytes),
            sha256_hex(&deb_bytes)
        );

        let mut routes = HashMap::new();
        routes.insert(format!("/{rpm_name}"), rpm_bytes.clone());
        routes.insert(format!("/{deb_name}"), deb_bytes.clone());
        routes.insert("/SHA256SUMS.txt".to_owned(), sums.into_bytes());
        let base_url = spawn_route_http_server(routes, 4);

        let rpm_release = ReleaseInfo {
            tag_name: "v0.2.0".into(),
            version: "0.2.0".into(),
            release_notes: String::new(),
            html_url: String::new(),
            asset_name: rpm_name.into(),
            download_url: format!("{base_url}/{rpm_name}"),
            asset_size: rpm_bytes.len() as u64,
            checksums_url: Some(format!("{base_url}/SHA256SUMS.txt")),
        };

        let rpm_outcome = download_and_install_update_with_options(
            &rpm_release,
            "camcraft-gui",
            &InstallKind::RpmPackage,
            Some(&tmp.0),
            false,
            |_| {},
        )
        .unwrap();
        let expected_rpm_path = tmp.0.join(rpm_name);
        assert_eq!(
            rpm_outcome.action,
            InstallAction::DownloadedPackage {
                package_path: expected_rpm_path.clone(),
                install_command: format!("sudo dnf install -y {}", expected_rpm_path.display()),
            }
        );
        assert_eq!(fs::read(&expected_rpm_path).unwrap(), rpm_bytes);

        let deb_release = ReleaseInfo {
            tag_name: "v0.2.0".into(),
            version: "0.2.0".into(),
            release_notes: String::new(),
            html_url: String::new(),
            asset_name: deb_name.into(),
            download_url: format!("{base_url}/{deb_name}"),
            asset_size: deb_bytes.len() as u64,
            checksums_url: Some(format!("{base_url}/SHA256SUMS.txt")),
        };

        let deb_outcome = download_and_install_update_with_options(
            &deb_release,
            "camcraft-cli",
            &InstallKind::DebPackage,
            Some(&tmp.0),
            false,
            |_| {},
        )
        .unwrap();
        let expected_deb_path = tmp.0.join(deb_name);
        assert_eq!(
            deb_outcome.action,
            InstallAction::DownloadedPackage {
                package_path: expected_deb_path.clone(),
                install_command: format!("sudo apt install -y {}", expected_deb_path.display()),
            }
        );
        assert_eq!(fs::read(&expected_deb_path).unwrap(), deb_bytes);
    }
}
