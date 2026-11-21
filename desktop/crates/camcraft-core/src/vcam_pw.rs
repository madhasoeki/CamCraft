//! Runtime-dynamically-loaded PipeWire 0.3 native virtual camera sink.
//!
//! Loads `libpipewire-0.3.so.0` (with `libpipewire-0.3.so` fallback) at runtime via
//! `libc::dlopen` / `libc::dlsym`, requiring zero compile-time `pipewire-devel`
//! headers and zero `DT_NEEDED` link dependency on `libpipewire`.
//!
//! Exposes a `Video/Source` camera node (`"CamCraft Virtual Camera"`) outputting
//! contiguous `I420` (`YUV420P`) frames compatible with OBS, Firefox, Chromium/WebRTC,
//! PipeWire camera portal (`xdg-desktop-portal`), and Discord/Vesktop.

use std::ffi::{CStr, c_char, c_int, c_void};
use std::path::{Path, PathBuf};
use std::ptr::{self, NonNull};
use std::sync::atomic::{AtomicI32, AtomicPtr, AtomicU32, Ordering};
use std::sync::{Arc, Mutex, Once, OnceLock};
use std::time::Duration;

use tracing::{debug, info, warn};

use super::{StandbyKind, VcamError, VirtualCameraSink, fill_standby_frame};
use crate::{CoreError, VideoOutputSink};

/// Synthetic device path identifier returned by [`PipeWireCameraSink::device_path`].
pub const PIPEWIRE_DEVICE_PATH: &str = "pipewire:camcraft_virtual_camera";

/// PipeWire output direction (`PW_DIRECTION_OUTPUT`).
pub const PW_DIRECTION_OUTPUT: u32 = 1;
/// Wildcard PipeWire node/port ID (`PW_ID_ANY`).
pub const PW_ID_ANY: u32 = u32::MAX;
/// PipeWire `pw_stream_events` struct ABI version (`PW_VERSION_STREAM_EVENTS`).
pub const PW_VERSION_STREAM_EVENTS: u32 = 2;

/// Stream flag requesting automatic mmap of shared buffers (`PW_STREAM_FLAG_MAP_BUFFERS`).
pub const PW_STREAM_FLAG_MAP_BUFFERS: u32 = 1 << 2;
/// Stream flag marking this source as a PipeWire graph driver (`PW_STREAM_FLAG_DRIVER`).
pub const PW_STREAM_FLAG_DRIVER: u32 = 1 << 3;

/// `enum pw_stream_state`: error state (`PW_STREAM_STATE_ERROR`).
pub const PW_STREAM_STATE_ERROR: i32 = -1;
/// `enum pw_stream_state`: disconnected (`PW_STREAM_STATE_UNCONNECTED`).
pub const PW_STREAM_STATE_UNCONNECTED: i32 = 0;
/// `enum pw_stream_state`: connecting (`PW_STREAM_STATE_CONNECTING`).
pub const PW_STREAM_STATE_CONNECTING: i32 = 1;
/// `enum pw_stream_state`: connected and paused (`PW_STREAM_STATE_PAUSED`).
pub const PW_STREAM_STATE_PAUSED: i32 = 2;
/// `enum pw_stream_state`: actively streaming (`PW_STREAM_STATE_STREAMING`).
pub const PW_STREAM_STATE_STREAMING: i32 = 3;

/// SPA primitive/compound type IDs (`<spa/utils/type.h>`).
pub const SPA_TYPE_ID: u32 = 3;
pub const SPA_TYPE_INT: u32 = 4;
pub const SPA_TYPE_RECTANGLE: u32 = 10;
pub const SPA_TYPE_FRACTION: u32 = 11;
pub const SPA_TYPE_OBJECT: u32 = 15;
pub const SPA_TYPE_CHOICE: u32 = 19;

/// SPA object type IDs (`<spa/utils/type.h>`).
pub const SPA_TYPE_OBJECT_FORMAT: u32 = 0x40002;
pub const SPA_TYPE_OBJECT_PARAM_BUFFERS: u32 = 0x40003;

/// SPA parameter IDs (`<spa/param/param.h>`).
pub const SPA_PARAM_ENUM_FORMAT: u32 = 3;
pub const SPA_PARAM_FORMAT: u32 = 4;
pub const SPA_PARAM_BUFFERS: u32 = 5;

/// SPA format keys & media values (`<spa/param/format.h>`).
pub const SPA_FORMAT_MEDIA_TYPE: u32 = 1;
pub const SPA_FORMAT_MEDIA_SUBTYPE: u32 = 2;
pub const SPA_MEDIA_TYPE_VIDEO: u32 = 2;
pub const SPA_MEDIA_SUBTYPE_RAW: u32 = 1;
pub const SPA_FORMAT_VIDEO_FORMAT: u32 = 0x20001;
pub const SPA_FORMAT_VIDEO_SIZE: u32 = 0x20003;
pub const SPA_FORMAT_VIDEO_FRAMERATE: u32 = 0x20004;

/// Planar YUV 4:2:0 (`YU12` / `I420`) SPA video format (`SPA_VIDEO_FORMAT_I420`).
pub const SPA_VIDEO_FORMAT_I420: u32 = 2;
/// SPA choice range (`SPA_CHOICE_Range`).
pub const SPA_CHOICE_RANGE: u32 = 1;

/// SPA buffer parameter property keys (`<spa/param/buffers.h>`).
pub const SPA_PARAM_BUFFERS_BUFFERS: u32 = 1;
pub const SPA_PARAM_BUFFERS_BLOCKS: u32 = 2;
pub const SPA_PARAM_BUFFERS_SIZE: u32 = 3;
pub const SPA_PARAM_BUFFERS_STRIDE: u32 = 4;
pub const SPA_PARAM_BUFFERS_DATA_TYPE: u32 = 6;

/// SPA data memory flags (`<spa/buffer/buffer.h>`).
pub const SPA_DATA_MEM_PTR: u32 = 1;
pub const SPA_DATA_MEM_FD: u32 = 2;

const DEFAULT_WIDTH: u32 = 1920;
const DEFAULT_HEIGHT: u32 = 1080;
const DEFAULT_FPS: u32 = 30;
const MAX_FPS: u32 = 60;
const CONNECT_POLL_STEPS: usize = 15;
const CONNECT_POLL_STEP_MS: u64 = 10;

/// Opaque PipeWire threaded loop handle (`struct pw_thread_loop`).
#[repr(C)]
pub struct PwThreadLoop {
    _private: [u8; 0],
}

/// Opaque PipeWire event loop handle (`struct pw_loop`).
#[repr(C)]
pub struct PwLoop {
    _private: [u8; 0],
}

/// Opaque PipeWire properties dictionary (`struct pw_properties`).
#[repr(C)]
pub struct PwProperties {
    _private: [u8; 0],
}

/// Opaque PipeWire stream handle (`struct pw_stream`).
#[repr(C)]
pub struct PwStream {
    _private: [u8; 0],
}

/// SPA Pod 8-byte header (`struct spa_pod`).
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SpaPod {
    pub size: u32,
    pub type_: u32,
}

/// SPA buffer chunk metadata (`struct spa_chunk`).
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SpaChunk {
    pub offset: u32,
    pub size: u32,
    pub stride: i32,
    pub flags: i32,
}

/// SPA buffer data plane descriptor (`struct spa_data`).
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct SpaData {
    pub type_: u32,
    pub flags: u32,
    pub fd: i64,
    pub mapoffset: u32,
    pub maxsize: u32,
    pub data: *mut c_void,
    pub chunk: *mut SpaChunk,
}

/// SPA buffer descriptor (`struct spa_buffer`).
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct SpaBuffer {
    pub n_metas: u32,
    pub n_datas: u32,
    pub metas: *mut c_void,
    pub datas: *mut SpaData,
}

/// PipeWire dequeued stream buffer (`struct pw_buffer`).
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct PwBuffer {
    pub buffer: *mut SpaBuffer,
    pub user_data: *mut c_void,
    pub size: u64,
    pub requested: u64,
    pub time: u64,
}

/// PipeWire `pw_stream_events` v2 callback table (`struct pw_stream_events`).
#[repr(C)]
#[derive(Clone, Copy)]
pub struct PwStreamEvents {
    pub version: u32,
    pub destroy: Option<unsafe extern "C" fn(data: *mut c_void)>,
    pub state_changed:
        Option<unsafe extern "C" fn(data: *mut c_void, old: i32, state: i32, error: *const c_char)>,
    pub control_info:
        Option<unsafe extern "C" fn(data: *mut c_void, id: u32, control: *const c_void)>,
    pub io_changed:
        Option<unsafe extern "C" fn(data: *mut c_void, id: u32, area: *mut c_void, size: u32)>,
    pub param_changed:
        Option<unsafe extern "C" fn(data: *mut c_void, id: u32, param: *const SpaPod)>,
    pub add_buffer: Option<unsafe extern "C" fn(data: *mut c_void, buffer: *mut PwBuffer)>,
    pub remove_buffer: Option<unsafe extern "C" fn(data: *mut c_void, buffer: *mut PwBuffer)>,
    pub process: Option<unsafe extern "C" fn(data: *mut c_void)>,
    pub drained: Option<unsafe extern "C" fn(data: *mut c_void)>,
    pub command: Option<unsafe extern "C" fn(data: *mut c_void, command: *const c_void)>,
    pub trigger_done: Option<unsafe extern "C" fn(data: *mut c_void)>,
}

static STREAM_EVENTS: PwStreamEvents = PwStreamEvents {
    version: PW_VERSION_STREAM_EVENTS,
    destroy: None,
    state_changed: Some(on_state_changed),
    control_info: None,
    io_changed: None,
    param_changed: Some(on_param_changed),
    add_buffer: None,
    remove_buffer: None,
    process: Some(on_process),
    drained: None,
    command: None,
    trigger_done: None,
};

type PwInitFn = unsafe extern "C" fn(argc: *mut c_int, argv: *mut *mut *mut c_char);
type PwGetLibraryVersionFn = unsafe extern "C" fn() -> *const c_char;
type PwThreadLoopNewFn =
    unsafe extern "C" fn(name: *const c_char, props: *const c_void) -> *mut PwThreadLoop;
type PwThreadLoopDestroyFn = unsafe extern "C" fn(loop_: *mut PwThreadLoop);
type PwThreadLoopGetLoopFn = unsafe extern "C" fn(loop_: *mut PwThreadLoop) -> *mut PwLoop;
type PwThreadLoopStartFn = unsafe extern "C" fn(loop_: *mut PwThreadLoop) -> c_int;
type PwThreadLoopStopFn = unsafe extern "C" fn(loop_: *mut PwThreadLoop);
type PwThreadLoopLockFn = unsafe extern "C" fn(loop_: *mut PwThreadLoop);
type PwThreadLoopUnlockFn = unsafe extern "C" fn(loop_: *mut PwThreadLoop);
type PwPropertiesNewFn = unsafe extern "C" fn(key: *const c_char) -> *mut PwProperties;
type PwPropertiesSetFn = unsafe extern "C" fn(
    props: *mut PwProperties,
    key: *const c_char,
    value: *const c_char,
) -> c_int;
type PwPropertiesFreeFn = unsafe extern "C" fn(props: *mut PwProperties);
type PwStreamNewSimpleFn = unsafe extern "C" fn(
    loop_: *mut PwLoop,
    name: *const c_char,
    props: *mut PwProperties,
    events: *const PwStreamEvents,
    data: *mut c_void,
) -> *mut PwStream;
type PwStreamDestroyFn = unsafe extern "C" fn(stream: *mut PwStream);
type PwStreamConnectFn = unsafe extern "C" fn(
    stream: *mut PwStream,
    direction: u32,
    target_id: u32,
    flags: u32,
    params: *mut *const SpaPod,
    n_params: u32,
) -> c_int;
type PwStreamDisconnectFn = unsafe extern "C" fn(stream: *mut PwStream) -> c_int;
type PwStreamUpdateParamsFn =
    unsafe extern "C" fn(stream: *mut PwStream, params: *mut *const SpaPod, n_params: u32) -> c_int;
type PwStreamDequeueBufferFn = unsafe extern "C" fn(stream: *mut PwStream) -> *mut PwBuffer;
type PwStreamQueueBufferFn =
    unsafe extern "C" fn(stream: *mut PwStream, buffer: *mut PwBuffer) -> c_int;
type PwStreamTriggerProcessFn = unsafe extern "C" fn(stream: *mut PwStream) -> c_int;
type PwStreamGetStateFn =
    unsafe extern "C" fn(stream: *mut PwStream, error: *mut *const c_char) -> i32;

struct PipeWireApi {
    _lib_handle: *mut c_void,
    pw_get_library_version: PwGetLibraryVersionFn,
    pw_thread_loop_new: PwThreadLoopNewFn,
    pw_thread_loop_destroy: PwThreadLoopDestroyFn,
    pw_thread_loop_get_loop: PwThreadLoopGetLoopFn,
    pw_thread_loop_start: PwThreadLoopStartFn,
    pw_thread_loop_stop: PwThreadLoopStopFn,
    pw_thread_loop_lock: PwThreadLoopLockFn,
    pw_thread_loop_unlock: PwThreadLoopUnlockFn,
    pw_properties_new: PwPropertiesNewFn,
    pw_properties_set: PwPropertiesSetFn,
    pw_properties_free: PwPropertiesFreeFn,
    pw_stream_new_simple: PwStreamNewSimpleFn,
    pw_stream_destroy: PwStreamDestroyFn,
    pw_stream_connect: PwStreamConnectFn,
    pw_stream_disconnect: PwStreamDisconnectFn,
    pw_stream_update_params: PwStreamUpdateParamsFn,
    pw_stream_dequeue_buffer: PwStreamDequeueBufferFn,
    pw_stream_queue_buffer: PwStreamQueueBufferFn,
    pw_stream_trigger_process: PwStreamTriggerProcessFn,
    pw_stream_get_state: PwStreamGetStateFn,
}

// SAFETY: `PipeWireApi` holds an immutable `dlopen` handle kept resident for the process
// lifetime inside `PW_API` and plain C function pointers; PipeWire calls are serialized
// through `pw_thread_loop_lock` or `PW_INIT_ONCE`.
unsafe impl Send for PipeWireApi {}
// SAFETY: Same invariants as `Send` above; all fields are read-only after initialization.
unsafe impl Sync for PipeWireApi {}

static PW_INIT_ONCE: Once = Once::new();
static PW_API: OnceLock<Result<Arc<PipeWireApi>, String>> = OnceLock::new();

fn last_dlerror() -> String {
    // SAFETY: `dlerror()` returns either null or a TLS/static NUL-terminated error string.
    let err_ptr = unsafe { libc::dlerror() };
    if err_ptr.is_null() {
        "unknown dynamic linker error".to_owned()
    } else {
        // SAFETY: `err_ptr` was checked non-null and points to a valid C string from `dlerror()`.
        unsafe { CStr::from_ptr(err_ptr) }
            .to_string_lossy()
            .into_owned()
    }
}

/// Resolves symbol `name` from `handle` and reinterprets it as C function pointer `T`.
///
/// # Safety
/// `handle` must be a valid non-null handle returned by `libc::dlopen`, and `T` must be a
/// `Copy` C ABI function pointer whose signature matches `name` in `libpipewire-0.3`.
unsafe fn load_sym<T: Copy>(handle: *mut c_void, name: &CStr) -> Result<T, String> {
    const {
        assert!(size_of::<T>() == size_of::<*mut c_void>());
    }
    // SAFETY: Clearing prior TLS `dlerror` state before `dlsym` requires no invariants.
    unsafe {
        libc::dlerror();
    }
    // SAFETY: `handle` is valid and `name` is a NUL-terminated C string literal.
    let sym = unsafe { libc::dlsym(handle, name.as_ptr()) };
    if sym.is_null() {
        return Err(format!(
            "missing PipeWire symbol '{}': {}",
            name.to_string_lossy(),
            last_dlerror()
        ));
    }
    // SAFETY: `sym` is non-null, `size_of::<T>() == size_of::<*mut c_void>()` is statically
    // asserted above, and the caller guarantees `T` matches the exported C function signature.
    Ok(unsafe { std::mem::transmute_copy::<*mut c_void, T>(&sym) })
}

impl PipeWireApi {
    fn get() -> Result<Arc<Self>, VcamError> {
        let cached = PW_API.get_or_init(Self::load_uncached);
        cached.clone().map_err(|details| VcamError::DeviceOpen {
            path: PathBuf::from(PIPEWIRE_DEVICE_PATH),
            details,
        })
    }

    fn load_uncached() -> Result<Arc<Self>, String> {
        let candidates = [c"libpipewire-0.3.so.0", c"libpipewire-0.3.so"];
        let mut handle: *mut c_void = ptr::null_mut();
        let mut last_err = String::new();

        for soname in candidates {
            // SAFETY: Clearing prior TLS `dlerror` state before `dlopen`.
            unsafe {
                libc::dlerror();
            }
            // SAFETY: `soname` is a static NUL-terminated C string and flags are valid `dlopen` flags.
            let candidate =
                unsafe { libc::dlopen(soname.as_ptr(), libc::RTLD_LAZY | libc::RTLD_LOCAL) };
            if !candidate.is_null() {
                handle = candidate;
                break;
            }
            last_err = format!("{}: {}", soname.to_string_lossy(), last_dlerror());
        }

        if handle.is_null() {
            return Err(format!(
                "failed to load libpipewire-0.3 runtime library ({last_err})"
            ));
        }

        // SAFETY: `handle` is a non-null `dlopen` handle for `libpipewire-0.3`, and each function
        // pointer type matches the PipeWire 0.3 C ABI specification.
        let loaded = unsafe { Self::resolve_all(handle) };
        match loaded {
            Ok(api) => {
                // SAFETY: `api.pw_get_library_version` is valid and returns a static version string.
                let ver_ptr = unsafe { (api.pw_get_library_version)() };
                let version = if ver_ptr.is_null() {
                    "unknown".to_owned()
                } else {
                    // SAFETY: `ver_ptr` is non-null and points to a static NUL-terminated string.
                    unsafe { CStr::from_ptr(ver_ptr) }
                        .to_string_lossy()
                        .into_owned()
                };
                debug!(%version, "Loaded PipeWire 0.3 runtime library");
                Ok(Arc::new(api))
            }
            Err(err) => {
                // SAFETY: `handle` was opened above and `Self::resolve_all` failed before
                // `pw_init` or storing the handle anywhere.
                unsafe {
                    libc::dlclose(handle);
                }
                Err(err)
            }
        }
    }

    /// Resolves all required PipeWire 0.3 symbols and runs `pw_init` once.
    ///
    /// # Safety
    /// `handle` must be a valid non-null handle to `libpipewire-0.3.so*`.
    unsafe fn resolve_all(handle: *mut c_void) -> Result<Self, String> {
        // SAFETY: Each `load_sym` call uses the matching PipeWire 0.3 C ABI signature.
        unsafe {
            let pw_init: PwInitFn = load_sym(handle, c"pw_init")?;
            let pw_get_library_version: PwGetLibraryVersionFn =
                load_sym(handle, c"pw_get_library_version")?;
            let pw_thread_loop_new: PwThreadLoopNewFn = load_sym(handle, c"pw_thread_loop_new")?;
            let pw_thread_loop_destroy: PwThreadLoopDestroyFn =
                load_sym(handle, c"pw_thread_loop_destroy")?;
            let pw_thread_loop_get_loop: PwThreadLoopGetLoopFn =
                load_sym(handle, c"pw_thread_loop_get_loop")?;
            let pw_thread_loop_start: PwThreadLoopStartFn =
                load_sym(handle, c"pw_thread_loop_start")?;
            let pw_thread_loop_stop: PwThreadLoopStopFn = load_sym(handle, c"pw_thread_loop_stop")?;
            let pw_thread_loop_lock: PwThreadLoopLockFn = load_sym(handle, c"pw_thread_loop_lock")?;
            let pw_thread_loop_unlock: PwThreadLoopUnlockFn =
                load_sym(handle, c"pw_thread_loop_unlock")?;
            let pw_properties_new: PwPropertiesNewFn = load_sym(handle, c"pw_properties_new")?;
            let pw_properties_set: PwPropertiesSetFn = load_sym(handle, c"pw_properties_set")?;
            let pw_properties_free: PwPropertiesFreeFn = load_sym(handle, c"pw_properties_free")?;
            let pw_stream_new_simple: PwStreamNewSimpleFn =
                load_sym(handle, c"pw_stream_new_simple")?;
            let pw_stream_destroy: PwStreamDestroyFn = load_sym(handle, c"pw_stream_destroy")?;
            let pw_stream_connect: PwStreamConnectFn = load_sym(handle, c"pw_stream_connect")?;
            let pw_stream_disconnect: PwStreamDisconnectFn =
                load_sym(handle, c"pw_stream_disconnect")?;
            let pw_stream_update_params: PwStreamUpdateParamsFn =
                load_sym(handle, c"pw_stream_update_params")?;
            let pw_stream_dequeue_buffer: PwStreamDequeueBufferFn =
                load_sym(handle, c"pw_stream_dequeue_buffer")?;
            let pw_stream_queue_buffer: PwStreamQueueBufferFn =
                load_sym(handle, c"pw_stream_queue_buffer")?;
            let pw_stream_trigger_process: PwStreamTriggerProcessFn =
                load_sym(handle, c"pw_stream_trigger_process")?;
            let pw_stream_get_state: PwStreamGetStateFn = load_sym(handle, c"pw_stream_get_state")?;

            PW_INIT_ONCE.call_once(|| {
                pw_init(ptr::null_mut(), ptr::null_mut());
            });

            Ok(Self {
                _lib_handle: handle,
                pw_get_library_version,
                pw_thread_loop_new,
                pw_thread_loop_destroy,
                pw_thread_loop_get_loop,
                pw_thread_loop_start,
                pw_thread_loop_stop,
                pw_thread_loop_lock,
                pw_thread_loop_unlock,
                pw_properties_new,
                pw_properties_set,
                pw_properties_free,
                pw_stream_new_simple,
                pw_stream_destroy,
                pw_stream_connect,
                pw_stream_disconnect,
                pw_stream_update_params,
                pw_stream_dequeue_buffer,
                pw_stream_queue_buffer,
                pw_stream_trigger_process,
                pw_stream_get_state,
            })
        }
    }
}

/// Pure-Rust SPA 0.2 pod builder backed by `Vec<u64>` for guaranteed 8-byte alignment.
#[derive(Debug, Default)]
pub struct SpaPodBuilder {
    words: Vec<u64>,
    pending_lo: Option<u32>,
}

impl SpaPodBuilder {
    /// Creates an empty builder with pre-reserved 8-byte word capacity.
    #[must_use]
    pub fn with_capacity_words(words: usize) -> Self {
        Self {
            words: Vec::with_capacity(words),
            pending_lo: None,
        }
    }

    /// Resets the builder while retaining its allocated capacity.
    pub fn clear(&mut self) {
        self.words.clear();
        self.pending_lo = None;
    }

    /// Current unpadded byte length written so far.
    #[must_use]
    pub fn byte_len(&self) -> usize {
        self.words.len() * 8 + usize::from(self.pending_lo.is_some()) * 4
    }

    /// Returns the underlying `u64` words (8-byte aligned).
    #[must_use]
    pub fn as_words(&self) -> &[u64] {
        &self.words
    }

    /// Returns an 8-byte aligned pointer to the root [`SpaPod`].
    #[must_use]
    pub fn as_pod_ptr(&self) -> *const SpaPod {
        self.words.as_ptr().cast::<SpaPod>()
    }

    /// Copies the serialized pod words into host-endian bytes for inspection/testing.
    #[must_use]
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(self.byte_len());
        for word in &self.words {
            out.extend_from_slice(&word.to_ne_bytes());
        }
        if let Some(lo) = self.pending_lo {
            out.extend_from_slice(&lo.to_ne_bytes());
        }
        out
    }

    fn push_u32(&mut self, value: u32) {
        if let Some(first) = self.pending_lo.take() {
            let b0 = first.to_ne_bytes();
            let b1 = value.to_ne_bytes();
            self.words.push(u64::from_ne_bytes([
                b0[0], b0[1], b0[2], b0[3], b1[0], b1[1], b1[2], b1[3],
            ]));
        } else {
            self.pending_lo = Some(value);
        }
    }

    fn pad_to_8(&mut self) {
        if self.pending_lo.is_some() {
            self.push_u32(0);
        }
    }

    fn set_u32_at_byte(&mut self, byte_offset: usize, value: u32) {
        let word_idx = byte_offset / 8;
        let is_high = (byte_offset % 8) == 4;
        if let Some(word) = self.words.get_mut(word_idx) {
            let mut bytes = word.to_ne_bytes();
            let val_bytes = value.to_ne_bytes();
            if is_high {
                bytes[4..8].copy_from_slice(&val_bytes);
            } else {
                bytes[0..4].copy_from_slice(&val_bytes);
            }
            *word = u64::from_ne_bytes(bytes);
        }
    }

    fn start_object(&mut self, object_type: u32, param_id: u32) -> usize {
        self.pad_to_8();
        let header_pos = self.byte_len();
        self.push_u32(0);
        self.push_u32(SPA_TYPE_OBJECT);
        self.push_u32(object_type);
        self.push_u32(param_id);
        header_pos
    }

    fn finish_object(&mut self, header_pos: usize) {
        self.pad_to_8();
        let body_size = (self.byte_len() - header_pos - 8) as u32;
        self.set_u32_at_byte(header_pos, body_size);
    }

    fn add_prop_id(&mut self, key: u32, id_value: u32) {
        self.push_u32(key);
        self.push_u32(0);
        self.push_u32(4);
        self.push_u32(SPA_TYPE_ID);
        self.push_u32(id_value);
        self.pad_to_8();
    }

    fn add_prop_int(&mut self, key: u32, value: i32) {
        self.push_u32(key);
        self.push_u32(0);
        self.push_u32(4);
        self.push_u32(SPA_TYPE_INT);
        self.push_u32(value as u32);
        self.pad_to_8();
    }

    fn add_prop_rectangle(&mut self, key: u32, width: u32, height: u32) {
        self.push_u32(key);
        self.push_u32(0);
        self.push_u32(8);
        self.push_u32(SPA_TYPE_RECTANGLE);
        self.push_u32(width);
        self.push_u32(height);
        self.pad_to_8();
    }

    fn add_prop_choice_fraction_range(
        &mut self,
        key: u32,
        default: (u32, u32),
        min: (u32, u32),
        max: (u32, u32),
    ) {
        self.push_u32(key);
        self.push_u32(0);
        let choice_pos = self.byte_len();
        self.push_u32(0);
        self.push_u32(SPA_TYPE_CHOICE);
        self.push_u32(SPA_CHOICE_RANGE);
        self.push_u32(0);
        self.push_u32(8);
        self.push_u32(SPA_TYPE_FRACTION);
        for (num, denom) in [default, min, max] {
            self.push_u32(num);
            self.push_u32(denom);
        }
        let body_size = (self.byte_len() - choice_pos - 8) as u32;
        self.set_u32_at_byte(choice_pos, body_size);
        self.pad_to_8();
    }

    fn add_prop_choice_int_range(&mut self, key: u32, default: i32, min: i32, max: i32) {
        self.push_u32(key);
        self.push_u32(0);
        let choice_pos = self.byte_len();
        self.push_u32(0);
        self.push_u32(SPA_TYPE_CHOICE);
        self.push_u32(SPA_CHOICE_RANGE);
        self.push_u32(0);
        self.push_u32(4);
        self.push_u32(SPA_TYPE_INT);
        for val in [default, min, max] {
            self.push_u32(val as u32);
        }
        let body_size = (self.byte_len() - choice_pos - 8) as u32;
        self.set_u32_at_byte(choice_pos, body_size);
        self.pad_to_8();
    }
}

/// Populates `builder` with an `SPA_TYPE_OBJECT_Format` / `SPA_PARAM_EnumFormat` pod for
/// `I420` raw video at `width x height` and framerate range `[0/1, 60/1]` defaulting to `fps/1`.
pub fn build_video_enum_format_pod_into(
    builder: &mut SpaPodBuilder,
    width: u32,
    height: u32,
    fps: u32,
) {
    builder.clear();
    let obj = builder.start_object(SPA_TYPE_OBJECT_FORMAT, SPA_PARAM_ENUM_FORMAT);
    builder.add_prop_id(SPA_FORMAT_MEDIA_TYPE, SPA_MEDIA_TYPE_VIDEO);
    builder.add_prop_id(SPA_FORMAT_MEDIA_SUBTYPE, SPA_MEDIA_SUBTYPE_RAW);
    builder.add_prop_id(SPA_FORMAT_VIDEO_FORMAT, SPA_VIDEO_FORMAT_I420);
    builder.add_prop_rectangle(SPA_FORMAT_VIDEO_SIZE, width, height);
    builder.add_prop_choice_fraction_range(
        SPA_FORMAT_VIDEO_FRAMERATE,
        (fps, 1),
        (0, 1),
        (MAX_FPS, 1),
    );
    builder.finish_object(obj);
}

/// Builds a standalone `SPA_TYPE_OBJECT_Format` / `SPA_PARAM_EnumFormat` pod.
#[must_use]
pub fn build_video_enum_format_pod(width: u32, height: u32, fps: u32) -> SpaPodBuilder {
    let mut builder = SpaPodBuilder::with_capacity_words(24);
    build_video_enum_format_pod_into(&mut builder, width, height, fps);
    builder
}

/// Populates `builder` with an `SPA_TYPE_OBJECT_ParamBuffers` / `SPA_PARAM_Buffers` pod
/// sized for contiguous `I420` (`width * height * 3 / 2`) frames.
pub fn build_video_buffers_pod_into(builder: &mut SpaPodBuilder, width: u32, height: u32) {
    builder.clear();
    let frame_size = i32::try_from((width as u64) * (height as u64) * 3 / 2).unwrap_or(i32::MAX);
    let stride = i32::try_from(width).unwrap_or(i32::MAX);
    let data_types = ((1_u32 << SPA_DATA_MEM_PTR) | (1_u32 << SPA_DATA_MEM_FD)) as i32;

    let obj = builder.start_object(SPA_TYPE_OBJECT_PARAM_BUFFERS, SPA_PARAM_BUFFERS);
    builder.add_prop_choice_int_range(SPA_PARAM_BUFFERS_BUFFERS, 4, 2, 8);
    builder.add_prop_int(SPA_PARAM_BUFFERS_BLOCKS, 1);
    builder.add_prop_int(SPA_PARAM_BUFFERS_SIZE, frame_size);
    builder.add_prop_int(SPA_PARAM_BUFFERS_STRIDE, stride);
    builder.add_prop_int(SPA_PARAM_BUFFERS_DATA_TYPE, data_types);
    builder.finish_object(obj);
}

/// Builds a standalone `SPA_TYPE_OBJECT_ParamBuffers` / `SPA_PARAM_Buffers` pod.
#[must_use]
pub fn build_video_buffers_pod(width: u32, height: u32) -> SpaPodBuilder {
    let mut builder = SpaPodBuilder::with_capacity_words(24);
    build_video_buffers_pod_into(&mut builder, width, height);
    builder
}

struct SharedState {
    api: Arc<PipeWireApi>,
    stream: AtomicPtr<PwStream>,
    state: AtomicI32,
    width: AtomicU32,
    height: AtomicU32,
    latest_frame: Mutex<Vec<u8>>,
}

struct ThreadLoopLockGuard<'a> {
    api: &'a PipeWireApi,
    thread_loop: *mut PwThreadLoop,
}

impl<'a> ThreadLoopLockGuard<'a> {
    fn lock(api: &'a PipeWireApi, thread_loop: *mut PwThreadLoop) -> Self {
        // SAFETY: `thread_loop` is a live `PwThreadLoop` owned by `PipeWireCameraSink`.
        unsafe {
            (api.pw_thread_loop_lock)(thread_loop);
        }
        Self { api, thread_loop }
    }
}

impl Drop for ThreadLoopLockGuard<'_> {
    fn drop(&mut self) {
        // SAFETY: Matches the `pw_thread_loop_lock` call in `ThreadLoopLockGuard::lock`.
        unsafe {
            (self.api.pw_thread_loop_unlock)(self.thread_loop);
        }
    }
}

/// Copies `frame` into `pw_buf` if pointers and buffer capacity are valid, then queues `pw_buf`.
///
/// # Safety
/// Must be called while holding the PipeWire thread loop lock (or inside a thread-loop
/// callback). `stream` and `pw_buf` must be valid non-null pointers from PipeWire.
unsafe fn fill_and_queue_pw_buffer(
    api: &PipeWireApi,
    stream: *mut PwStream,
    pw_buf: *mut PwBuffer,
    frame: &[u8],
    width: u32,
) {
    // SAFETY: `pw_buf` is a non-null buffer returned by `pw_stream_dequeue_buffer`.
    let spa_buf = unsafe { (*pw_buf).buffer };
    if !spa_buf.is_null() {
        // SAFETY: `spa_buf` points to a valid `SpaBuffer` owned by `pw_buf` until queued.
        let (n_datas, datas_ptr) = unsafe { ((*spa_buf).n_datas, (*spa_buf).datas) };
        if n_datas > 0 && !datas_ptr.is_null() {
            // SAFETY: `n_datas >= 1` guarantees `datas_ptr[0]` is a valid `SpaData`.
            let d0 = unsafe { &mut *datas_ptr };
            let dst_ptr = d0.data.cast::<u8>();
            let maxsize = d0.maxsize as usize;
            let chunk_ptr = d0.chunk;

            if !dst_ptr.is_null() && !chunk_ptr.is_null() {
                if !frame.is_empty() && maxsize >= frame.len() {
                    // SAFETY: `dst_ptr` points to a mapped PipeWire buffer of at least `maxsize`
                    // bytes (`>= frame.len()`), and does not overlap with Rust-owned `frame`.
                    unsafe {
                        ptr::copy_nonoverlapping(frame.as_ptr(), dst_ptr, frame.len());
                    }
                    // SAFETY: `chunk_ptr` is non-null and points to `pw_buf`'s `SpaChunk`.
                    unsafe {
                        (*chunk_ptr).offset = 0;
                        (*chunk_ptr).size = frame.len() as u32;
                        (*chunk_ptr).stride = i32::try_from(width).unwrap_or(i32::MAX);
                        (*chunk_ptr).flags = 0;
                    }
                } else {
                    // SAFETY: `chunk_ptr` is non-null; mark chunk empty rather than overflowing.
                    unsafe {
                        (*chunk_ptr).offset = 0;
                        (*chunk_ptr).size = 0;
                        (*chunk_ptr).stride = 0;
                        (*chunk_ptr).flags = 0;
                    }
                }
            }
        }
    }

    // SAFETY: `stream` is valid and `pw_buf` was dequeued from it and must be queued back.
    unsafe {
        (api.pw_stream_queue_buffer)(stream, pw_buf);
    }
}

unsafe extern "C" fn on_state_changed(
    data: *mut c_void,
    _old: i32,
    state: i32,
    error: *const c_char,
) {
    if data.is_null() {
        return;
    }
    // SAFETY: `data` is the boxed `SharedState` pointer which outlives `pw_stream_destroy`
    // and `pw_thread_loop_stop`.
    let shared = unsafe { &*data.cast::<SharedState>() };
    shared.state.store(state, Ordering::Release);

    if state == PW_STREAM_STATE_ERROR {
        let msg = if error.is_null() {
            "unknown PipeWire stream error".to_owned()
        } else {
            // SAFETY: `error` was checked non-null and points to PipeWire's error C string.
            unsafe { CStr::from_ptr(error) }
                .to_string_lossy()
                .into_owned()
        };
        warn!(error = %msg, "PipeWire virtual camera stream entered ERROR state");
    } else {
        debug!(state, "PipeWire virtual camera stream state changed");
    }
}

unsafe extern "C" fn on_param_changed(data: *mut c_void, id: u32, param: *const SpaPod) {
    if data.is_null() || param.is_null() || id != SPA_PARAM_FORMAT {
        return;
    }
    // SAFETY: `data` is the boxed `SharedState` pointer kept alive until after loop stop.
    let shared = unsafe { &*data.cast::<SharedState>() };
    let stream = shared.stream.load(Ordering::Acquire);
    if stream.is_null() {
        return;
    }

    let width = shared.width.load(Ordering::Acquire);
    let height = shared.height.load(Ordering::Acquire);
    if width == 0 || height == 0 {
        return;
    }

    let buffers_pod = build_video_buffers_pod(width, height);
    let mut param_ptr: *const SpaPod = buffers_pod.as_pod_ptr();
    // SAFETY: `stream` is valid, callback runs inside PipeWire's thread loop with its lock held,
    // and `param_ptr` points to an 8-byte aligned `ParamBuffers` SPA pod alive for this call.
    let rc = unsafe { (shared.api.pw_stream_update_params)(stream, &raw mut param_ptr, 1) };
    if rc < 0 {
        warn!(
            rc,
            width, height, "pw_stream_update_params(SPA_PARAM_Buffers) failed"
        );
    }
}

unsafe extern "C" fn on_process(data: *mut c_void) {
    if data.is_null() {
        return;
    }
    // SAFETY: `data` is the boxed `SharedState` pointer kept alive until after loop stop.
    let shared = unsafe { &*data.cast::<SharedState>() };
    let stream = shared.stream.load(Ordering::Acquire);
    if stream.is_null() {
        return;
    }

    // SAFETY: `stream` is non-null and callback executes on the PipeWire thread loop.
    let pw_buf = unsafe { (shared.api.pw_stream_dequeue_buffer)(stream) };
    if pw_buf.is_null() {
        return;
    }

    let width = shared.width.load(Ordering::Acquire);
    let latest = shared
        .latest_frame
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());

    // SAFETY: `stream` and `pw_buf` are valid and we are inside PipeWire's process callback.
    unsafe {
        fill_and_queue_pw_buffer(&shared.api, stream, pw_buf, &latest, width);
    }
}

/// Native PipeWire `Video/Source` virtual camera sink loaded dynamically at runtime.
pub struct PipeWireCameraSink {
    thread_loop: Option<NonNull<PwThreadLoop>>,
    shared_ptr: *mut SharedState,
    configured_width: u32,
    configured_height: u32,
    pod_builder: SpaPodBuilder,
    standby_scratch: Vec<u8>,
}

// SAFETY: `PipeWireCameraSink` has exclusive ownership of `thread_loop` and the owner-side
// lifecycle of `shared_ptr`. PipeWire's background thread only accesses `Sync` fields inside
// `SharedState` and is joined via `pw_thread_loop_stop` before `shared_ptr` is freed.
unsafe impl Send for PipeWireCameraSink {}

impl PipeWireCameraSink {
    /// Creates an unopened PipeWire virtual camera sink.
    #[must_use]
    pub fn new() -> Self {
        Self {
            thread_loop: None,
            shared_ptr: ptr::null_mut(),
            configured_width: 0,
            configured_height: 0,
            pod_builder: SpaPodBuilder::with_capacity_words(24),
            standby_scratch: Vec::new(),
        }
    }

    fn write_frame_validated(
        &mut self,
        width: u32,
        height: u32,
        data: &[u8],
    ) -> Result<(), VcamError> {
        let dev_path = Path::new(PIPEWIRE_DEVICE_PATH);
        if width == 0 || height == 0 || !width.is_multiple_of(2) || !height.is_multiple_of(2) {
            return Err(VcamError::FormatError {
                path: dev_path.to_path_buf(),
                width,
                height,
                details: "YUV420P dimensions must be positive and multiples of 2".to_owned(),
            });
        }

        let Some(expected_len) = (width as usize)
            .checked_mul(height as usize)
            .and_then(|px| px.checked_mul(3))
            .map(|total| total / 2)
        else {
            return Err(VcamError::FormatError {
                path: dev_path.to_path_buf(),
                width,
                height,
                details: "frame dimensions overflow buffer size".to_owned(),
            });
        };

        if data.len() != expected_len {
            return Err(VcamError::WriteError {
                path: dev_path.to_path_buf(),
                bytes: data.len(),
                details: format!(
                    "invalid YUV420P frame buffer size: expected {expected_len} bytes for {width}x{height}, got {}",
                    data.len()
                ),
            });
        }

        let Some(tl) = self.thread_loop else {
            return Err(VcamError::NotOpen);
        };
        if self.shared_ptr.is_null() {
            return Err(VcamError::NotOpen);
        }

        // SAFETY: `self.shared_ptr` is non-null while `self.thread_loop` is open and is only
        // freed inside `close()` after `pw_thread_loop_stop`.
        let shared = unsafe { &*self.shared_ptr };

        // Lock `latest_frame` strictly outside `pw_thread_loop_lock` to avoid lock-order
        // inversion with `on_process` (which runs under PipeWire's thread loop lock).
        {
            let mut latest = shared
                .latest_frame
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            latest.clear();
            latest.extend_from_slice(data);
        }

        let dims_changed = self.configured_width != width || self.configured_height != height;
        let state = shared.state.load(Ordering::Acquire);

        if state == PW_STREAM_STATE_ERROR {
            return Err(VcamError::WriteError {
                path: dev_path.to_path_buf(),
                bytes: data.len(),
                details: "PipeWire virtual camera stream is in ERROR state".to_owned(),
            });
        }

        if !dims_changed && state != PW_STREAM_STATE_STREAMING {
            return Ok(());
        }

        let _lock = ThreadLoopLockGuard::lock(&shared.api, tl.as_ptr());
        let stream = shared.stream.load(Ordering::Acquire);
        if stream.is_null() {
            return Err(VcamError::NotOpen);
        }

        if dims_changed {
            shared.width.store(width, Ordering::Release);
            shared.height.store(height, Ordering::Release);
            build_video_enum_format_pod_into(&mut self.pod_builder, width, height, DEFAULT_FPS);
            let mut param_ptr: *const SpaPod = self.pod_builder.as_pod_ptr();
            // SAFETY: `stream` is valid, loop lock is held by `_lock`, and `param_ptr` points to
            // an 8-byte aligned `EnumFormat` SPA pod alive for the duration of this call.
            let rc = unsafe { (shared.api.pw_stream_update_params)(stream, &raw mut param_ptr, 1) };
            if rc < 0 {
                return Err(VcamError::FormatError {
                    path: dev_path.to_path_buf(),
                    width,
                    height,
                    details: format!("pw_stream_update_params failed with code {rc}"),
                });
            }
            self.configured_width = width;
            self.configured_height = height;
        }

        if shared.state.load(Ordering::Acquire) == PW_STREAM_STATE_STREAMING {
            // SAFETY: `stream` is valid and the thread loop lock is held by `_lock`.
            let pw_buf = unsafe { (shared.api.pw_stream_dequeue_buffer)(stream) };
            if !pw_buf.is_null() {
                // SAFETY: `stream` and `pw_buf` are valid and loop lock is held.
                unsafe {
                    fill_and_queue_pw_buffer(&shared.api, stream, pw_buf, data, width);
                }
            }
            // SAFETY: `stream` is valid and loop lock is held; trigger graph processing.
            let rc = unsafe { (shared.api.pw_stream_trigger_process)(stream) };
            if rc < 0 {
                debug!(rc, "pw_stream_trigger_process returned non-zero");
            }
        }

        Ok(())
    }

    fn build_stream_properties(api: &PipeWireApi) -> Result<*mut PwProperties, VcamError> {
        let dev_path = PathBuf::from(PIPEWIRE_DEVICE_PATH);
        // SAFETY: Passing `ptr::null()` invokes `pw_properties_new` with no trailing varargs.
        let props = unsafe { (api.pw_properties_new)(ptr::null()) };
        if props.is_null() {
            return Err(VcamError::DeviceOpen {
                path: dev_path,
                details: "pw_properties_new returned null".to_owned(),
            });
        }

        let entries: [(&CStr, &CStr); 6] = [
            (c"media.type", c"Video"),
            (c"media.category", c"Source"),
            (c"media.role", c"Camera"),
            (c"media.class", c"Video/Source"),
            (c"node.name", c"camcraft_virtual_camera"),
            (c"node.description", c"CamCraft Virtual Camera"),
        ];

        for (key, val) in entries {
            // SAFETY: `props` is a valid `PwProperties`, and `key`/`val` are static C strings.
            let rc = unsafe { (api.pw_properties_set)(props, key.as_ptr(), val.as_ptr()) };
            if rc < 0 {
                // SAFETY: `props` has not yet been transferred to `pw_stream_new_simple`.
                unsafe {
                    (api.pw_properties_free)(props);
                }
                return Err(VcamError::DeviceOpen {
                    path: dev_path,
                    details: format!(
                        "pw_properties_set('{}') failed with code {rc}",
                        key.to_string_lossy()
                    ),
                });
            }
        }

        Ok(props)
    }

    fn stream_error_details(api: &PipeWireApi, stream: *mut PwStream) -> String {
        let mut err_ptr: *const c_char = ptr::null();
        // SAFETY: `stream` is valid and `err_ptr` is a valid stack pointer.
        let state = unsafe { (api.pw_stream_get_state)(stream, &raw mut err_ptr) };
        if !err_ptr.is_null() {
            // SAFETY: `err_ptr` was populated by `pw_stream_get_state` and is NUL-terminated.
            let msg = unsafe { CStr::from_ptr(err_ptr) }.to_string_lossy();
            format!("PipeWire stream error (state {state}): {msg}")
        } else {
            format!("PipeWire daemon unreachable or stream entered error state ({state})")
        }
    }
}

impl Default for PipeWireCameraSink {
    fn default() -> Self {
        Self::new()
    }
}

impl VirtualCameraSink for PipeWireCameraSink {
    fn open(&mut self) -> Result<(), VcamError> {
        if self.is_open() {
            return Ok(());
        }

        let dev_path = PathBuf::from(PIPEWIRE_DEVICE_PATH);
        let api = PipeWireApi::get()?;

        // SAFETY: `c"camcraft-pw"` is a static C string and `props` may be null.
        let raw_loop = unsafe { (api.pw_thread_loop_new)(c"camcraft-pw".as_ptr(), ptr::null()) };
        let Some(thread_loop) = NonNull::new(raw_loop) else {
            return Err(VcamError::DeviceOpen {
                path: dev_path,
                details: "pw_thread_loop_new returned null".to_owned(),
            });
        };

        // SAFETY: `thread_loop` is a valid newly created `PwThreadLoop`.
        let pw_loop = unsafe { (api.pw_thread_loop_get_loop)(thread_loop.as_ptr()) };
        if pw_loop.is_null() {
            // SAFETY: Loop was not started; destroy it cleanly before returning.
            unsafe {
                (api.pw_thread_loop_destroy)(thread_loop.as_ptr());
            }
            return Err(VcamError::DeviceOpen {
                path: dev_path,
                details: "pw_thread_loop_get_loop returned null".to_owned(),
            });
        }

        let props = match Self::build_stream_properties(&api) {
            Ok(p) => p,
            Err(err) => {
                // SAFETY: `thread_loop` has not been started and owns no stream yet.
                unsafe {
                    (api.pw_thread_loop_destroy)(thread_loop.as_ptr());
                }
                return Err(err);
            }
        };

        let shared_ptr = Box::into_raw(Box::new(SharedState {
            api: Arc::clone(&api),
            stream: AtomicPtr::new(ptr::null_mut()),
            state: AtomicI32::new(PW_STREAM_STATE_UNCONNECTED),
            width: AtomicU32::new(DEFAULT_WIDTH),
            height: AtomicU32::new(DEFAULT_HEIGHT),
            latest_frame: Mutex::new(Vec::new()),
        }));

        // SAFETY: `pw_loop` is valid, `props` ownership transfers to `pw_stream_new_simple`
        // (freed by PipeWire even on failure), `STREAM_EVENTS` is `'static`, and `shared_ptr`
        // remains alive until after `pw_stream_destroy` and `pw_thread_loop_stop`.
        let stream = unsafe {
            (api.pw_stream_new_simple)(
                pw_loop,
                c"CamCraft Virtual Camera".as_ptr(),
                props,
                &raw const STREAM_EVENTS,
                shared_ptr.cast::<c_void>(),
            )
        };

        if stream.is_null() {
            // SAFETY: `pw_stream_new_simple` failed before spawning callbacks and already freed `props`.
            unsafe {
                (api.pw_thread_loop_destroy)(thread_loop.as_ptr());
                drop(Box::from_raw(shared_ptr));
            }
            return Err(VcamError::DeviceOpen {
                path: dev_path,
                details: "pw_stream_new_simple failed".to_owned(),
            });
        }

        // SAFETY: `shared_ptr` was just allocated and is exclusively owned here before loop start.
        unsafe {
            (*shared_ptr).stream.store(stream, Ordering::Release);
        }

        build_video_enum_format_pod_into(
            &mut self.pod_builder,
            DEFAULT_WIDTH,
            DEFAULT_HEIGHT,
            DEFAULT_FPS,
        );
        let mut param_ptr: *const SpaPod = self.pod_builder.as_pod_ptr();

        // SAFETY: `stream` is valid, thread loop is not yet running, and `param_ptr` points to
        // an 8-byte aligned `EnumFormat` SPA pod alive for the duration of `pw_stream_connect`.
        let connect_rc = unsafe {
            (api.pw_stream_connect)(
                stream,
                PW_DIRECTION_OUTPUT,
                PW_ID_ANY,
                PW_STREAM_FLAG_DRIVER | PW_STREAM_FLAG_MAP_BUFFERS,
                &raw mut param_ptr,
                1,
            )
        };

        if connect_rc < 0 {
            // SAFETY: Loop has not started yet, so we destroy stream, then loop, then `SharedState`.
            unsafe {
                (*shared_ptr)
                    .stream
                    .store(ptr::null_mut(), Ordering::Release);
                (api.pw_stream_destroy)(stream);
                (api.pw_thread_loop_destroy)(thread_loop.as_ptr());
                drop(Box::from_raw(shared_ptr));
            }
            return Err(VcamError::DeviceOpen {
                path: dev_path,
                details: format!("pw_stream_connect failed with code {connect_rc}"),
            });
        }

        // SAFETY: `thread_loop` is valid and configured with the connected stream.
        let start_rc = unsafe { (api.pw_thread_loop_start)(thread_loop.as_ptr()) };
        if start_rc < 0 {
            // SAFETY: `pw_thread_loop_start` failed so no background thread is running.
            unsafe {
                (*shared_ptr)
                    .stream
                    .store(ptr::null_mut(), Ordering::Release);
                (api.pw_stream_disconnect)(stream);
                (api.pw_stream_destroy)(stream);
                (api.pw_thread_loop_destroy)(thread_loop.as_ptr());
                drop(Box::from_raw(shared_ptr));
            }
            return Err(VcamError::DeviceOpen {
                path: dev_path,
                details: format!("pw_thread_loop_start failed with code {start_rc}"),
            });
        }

        self.thread_loop = Some(thread_loop);
        self.shared_ptr = shared_ptr;
        self.configured_width = DEFAULT_WIDTH;
        self.configured_height = DEFAULT_HEIGHT;

        for _ in 0..CONNECT_POLL_STEPS {
            // SAFETY: `self.shared_ptr` is alive while `self.thread_loop` is running.
            let state = unsafe { (*shared_ptr).state.load(Ordering::Acquire) };
            match state {
                PW_STREAM_STATE_ERROR => {
                    let details = {
                        let _lock = ThreadLoopLockGuard::lock(&api, thread_loop.as_ptr());
                        Self::stream_error_details(&api, stream)
                    };
                    let _ = self.close();
                    return Err(VcamError::DeviceOpen {
                        path: dev_path,
                        details,
                    });
                }
                PW_STREAM_STATE_PAUSED | PW_STREAM_STATE_STREAMING => {
                    info!(
                        device = PIPEWIRE_DEVICE_PATH,
                        state, "PipeWire virtual camera stream opened"
                    );
                    return Ok(());
                }
                _ => std::thread::sleep(Duration::from_millis(CONNECT_POLL_STEP_MS)),
            }
        }

        // Verify final stream state directly under lock in case `state_changed` raced or erred.
        let final_state = {
            let _lock = ThreadLoopLockGuard::lock(&api, thread_loop.as_ptr());
            // SAFETY: `stream` is live under `ThreadLoopLockGuard`.
            let st = unsafe { (api.pw_stream_get_state)(stream, ptr::null_mut()) };
            if st == PW_STREAM_STATE_ERROR {
                Err(Self::stream_error_details(&api, stream))
            } else {
                Ok(st)
            }
        };

        match final_state {
            Ok(state) => {
                info!(
                    device = PIPEWIRE_DEVICE_PATH,
                    state, "PipeWire virtual camera stream opened"
                );
                Ok(())
            }
            Err(details) => {
                let _ = self.close();
                Err(VcamError::DeviceOpen {
                    path: dev_path,
                    details,
                })
            }
        }
    }

    fn write_frame(&mut self, width: u32, height: u32, data: &[u8]) -> Result<(), VcamError> {
        self.write_frame_validated(width, height, data)
    }

    fn write_standby_frame(&mut self, width: u32, height: u32) -> Result<(), VcamError> {
        let mut buf = std::mem::take(&mut self.standby_scratch);
        fill_standby_frame(&mut buf, width, height, StandbyKind::Standby);
        let res = self.write_frame_validated(width, height, &buf);
        self.standby_scratch = buf;
        res
    }

    fn write_reconnecting_frame(&mut self, width: u32, height: u32) -> Result<(), VcamError> {
        let mut buf = std::mem::take(&mut self.standby_scratch);
        fill_standby_frame(&mut buf, width, height, StandbyKind::Reconnecting);
        let res = self.write_frame_validated(width, height, &buf);
        self.standby_scratch = buf;
        res
    }

    fn current_dimensions(&self) -> Option<(u32, u32)> {
        if self.is_open() && self.configured_width > 0 && self.configured_height > 0 {
            Some((self.configured_width, self.configured_height))
        } else {
            None
        }
    }

    fn close(&mut self) -> Result<(), VcamError> {
        let Some(thread_loop) = self.thread_loop.take() else {
            self.shared_ptr = ptr::null_mut();
            self.configured_width = 0;
            self.configured_height = 0;
            return Ok(());
        };

        let shared_ptr = self.shared_ptr;
        self.shared_ptr = ptr::null_mut();
        self.configured_width = 0;
        self.configured_height = 0;

        if shared_ptr.is_null() {
            return Ok(());
        }

        // SAFETY: `shared_ptr` remains valid until after `pw_stream_destroy` and
        // `pw_thread_loop_stop` complete below.
        let api = unsafe { Arc::clone(&(*shared_ptr).api) };

        {
            let _lock = ThreadLoopLockGuard::lock(&api, thread_loop.as_ptr());
            // SAFETY: `shared_ptr` is valid and loop lock is held.
            let stream = unsafe { (*shared_ptr).stream.swap(ptr::null_mut(), Ordering::AcqRel) };
            if !stream.is_null() {
                // SAFETY: `stream` is valid and loop lock is held while disconnecting and destroying.
                unsafe {
                    (api.pw_stream_disconnect)(stream);
                    (api.pw_stream_destroy)(stream);
                }
            }
        }

        // SAFETY: Thread loop lock has been released; `pw_thread_loop_stop` joins the thread
        // so no PipeWire callbacks can reference `shared_ptr` afterwards.
        unsafe {
            (api.pw_thread_loop_stop)(thread_loop.as_ptr());
            (api.pw_thread_loop_destroy)(thread_loop.as_ptr());
            drop(Box::from_raw(shared_ptr));
        }

        info!(
            device = PIPEWIRE_DEVICE_PATH,
            "PipeWire virtual camera sink closed"
        );
        Ok(())
    }

    fn is_open(&self) -> bool {
        self.thread_loop.is_some() && !self.shared_ptr.is_null()
    }

    fn device_path(&self) -> &Path {
        Path::new(PIPEWIRE_DEVICE_PATH)
    }
}

impl Drop for PipeWireCameraSink {
    fn drop(&mut self) {
        let _ = self.close();
    }
}

impl VideoOutputSink for PipeWireCameraSink {
    fn write_frame(&mut self, width: u32, height: u32, data: &[u8]) -> Result<(), CoreError> {
        VirtualCameraSink::write_frame(self, width, height, data).map_err(CoreError::Vcam)
    }
}

#[cfg(test)]
mod tests {
    use std::mem::{offset_of, size_of};

    use super::*;

    fn read_u32_ne(bytes: &[u8], offset: usize) -> u32 {
        let arr: [u8; 4] = bytes[offset..offset + 4]
            .try_into()
            .expect("4-byte aligned u32 slice");
        u32::from_ne_bytes(arr)
    }

    fn read_i32_ne(bytes: &[u8], offset: usize) -> i32 {
        let arr: [u8; 4] = bytes[offset..offset + 4]
            .try_into()
            .expect("4-byte aligned i32 slice");
        i32::from_ne_bytes(arr)
    }

    #[test]
    fn test_abi_struct_sizes_and_offsets() {
        assert_eq!(size_of::<SpaPod>(), 8);
        assert_eq!(offset_of!(SpaPod, size), 0);
        assert_eq!(offset_of!(SpaPod, type_), 4);

        assert_eq!(size_of::<SpaChunk>(), 16);
        assert_eq!(offset_of!(SpaChunk, offset), 0);
        assert_eq!(offset_of!(SpaChunk, size), 4);
        assert_eq!(offset_of!(SpaChunk, stride), 8);
        assert_eq!(offset_of!(SpaChunk, flags), 12);

        let ptr_sz = size_of::<*mut c_void>();
        assert_eq!(offset_of!(SpaData, type_), 0);
        assert_eq!(offset_of!(SpaData, flags), 4);
        assert_eq!(offset_of!(SpaData, fd), 8);
        assert_eq!(offset_of!(SpaData, mapoffset), 16);
        assert_eq!(offset_of!(SpaData, maxsize), 20);
        assert_eq!(offset_of!(SpaData, data), 24);
        assert_eq!(offset_of!(SpaData, chunk), 24 + ptr_sz);
        assert_eq!(size_of::<SpaData>(), 24 + 2 * ptr_sz);

        assert_eq!(offset_of!(SpaBuffer, n_metas), 0);
        assert_eq!(offset_of!(SpaBuffer, n_datas), 4);
        assert_eq!(offset_of!(SpaBuffer, metas), 8);
        assert_eq!(offset_of!(SpaBuffer, datas), 8 + ptr_sz);
        assert_eq!(size_of::<SpaBuffer>(), 8 + 2 * ptr_sz);

        assert_eq!(offset_of!(PwBuffer, buffer), 0);
        assert_eq!(offset_of!(PwBuffer, user_data), ptr_sz);
        assert_eq!(offset_of!(PwBuffer, size), 2 * ptr_sz);
        assert_eq!(offset_of!(PwBuffer, requested), 2 * ptr_sz + 8);
        assert_eq!(offset_of!(PwBuffer, time), 2 * ptr_sz + 16);
        assert_eq!(size_of::<PwBuffer>(), 2 * ptr_sz + 24);

        assert_eq!(offset_of!(PwStreamEvents, version), 0);
        assert_eq!(offset_of!(PwStreamEvents, destroy), ptr_sz);
        assert_eq!(offset_of!(PwStreamEvents, state_changed), 2 * ptr_sz);
        assert_eq!(offset_of!(PwStreamEvents, control_info), 3 * ptr_sz);
        assert_eq!(offset_of!(PwStreamEvents, io_changed), 4 * ptr_sz);
        assert_eq!(offset_of!(PwStreamEvents, param_changed), 5 * ptr_sz);
        assert_eq!(offset_of!(PwStreamEvents, add_buffer), 6 * ptr_sz);
        assert_eq!(offset_of!(PwStreamEvents, remove_buffer), 7 * ptr_sz);
        assert_eq!(offset_of!(PwStreamEvents, process), 8 * ptr_sz);
        assert_eq!(offset_of!(PwStreamEvents, drained), 9 * ptr_sz);
        assert_eq!(offset_of!(PwStreamEvents, command), 10 * ptr_sz);
        assert_eq!(offset_of!(PwStreamEvents, trigger_done), 11 * ptr_sz);
        assert_eq!(size_of::<PwStreamEvents>(), 12 * ptr_sz);
    }

    #[test]
    fn test_enum_format_pod_layout_and_alignment() {
        let pod = build_video_enum_format_pod(1920, 1080, 30);
        assert_eq!((pod.as_pod_ptr() as usize) % 8, 0);
        let bytes = pod.to_bytes();
        assert_eq!(bytes.len(), 168);
        assert_eq!(bytes.len() % 8, 0);

        // Root SPA_TYPE_Object header
        assert_eq!(read_u32_ne(&bytes, 0), 160);
        assert_eq!(read_u32_ne(&bytes, 4), SPA_TYPE_OBJECT);
        assert_eq!(read_u32_ne(&bytes, 8), SPA_TYPE_OBJECT_FORMAT);
        assert_eq!(read_u32_ne(&bytes, 12), SPA_PARAM_ENUM_FORMAT);

        // Property 1: mediaType = video
        assert_eq!(read_u32_ne(&bytes, 16), SPA_FORMAT_MEDIA_TYPE);
        assert_eq!(read_u32_ne(&bytes, 24), 4);
        assert_eq!(read_u32_ne(&bytes, 28), SPA_TYPE_ID);
        assert_eq!(read_u32_ne(&bytes, 32), SPA_MEDIA_TYPE_VIDEO);

        // Property 2: mediaSubtype = raw
        assert_eq!(read_u32_ne(&bytes, 40), SPA_FORMAT_MEDIA_SUBTYPE);
        assert_eq!(read_u32_ne(&bytes, 48), 4);
        assert_eq!(read_u32_ne(&bytes, 52), SPA_TYPE_ID);
        assert_eq!(read_u32_ne(&bytes, 56), SPA_MEDIA_SUBTYPE_RAW);

        // Property 3: VIDEO_format = I420
        assert_eq!(read_u32_ne(&bytes, 64), SPA_FORMAT_VIDEO_FORMAT);
        assert_eq!(read_u32_ne(&bytes, 72), 4);
        assert_eq!(read_u32_ne(&bytes, 76), SPA_TYPE_ID);
        assert_eq!(read_u32_ne(&bytes, 80), SPA_VIDEO_FORMAT_I420);

        // Property 4: VIDEO_size = Rectangle(1920, 1080)
        assert_eq!(read_u32_ne(&bytes, 88), SPA_FORMAT_VIDEO_SIZE);
        assert_eq!(read_u32_ne(&bytes, 96), 8);
        assert_eq!(read_u32_ne(&bytes, 100), SPA_TYPE_RECTANGLE);
        assert_eq!(read_u32_ne(&bytes, 104), 1920);
        assert_eq!(read_u32_ne(&bytes, 108), 1080);

        // Property 5: VIDEO_framerate = Choice(Range, Fraction: 30/1, 0/1, 60/1)
        assert_eq!(read_u32_ne(&bytes, 112), SPA_FORMAT_VIDEO_FRAMERATE);
        assert_eq!(read_u32_ne(&bytes, 120), 40);
        assert_eq!(read_u32_ne(&bytes, 124), SPA_TYPE_CHOICE);
        assert_eq!(read_u32_ne(&bytes, 128), SPA_CHOICE_RANGE);
        assert_eq!(read_u32_ne(&bytes, 132), 0);
        assert_eq!(read_u32_ne(&bytes, 136), 8);
        assert_eq!(read_u32_ne(&bytes, 140), SPA_TYPE_FRACTION);
        assert_eq!(read_u32_ne(&bytes, 144), 30);
        assert_eq!(read_u32_ne(&bytes, 148), 1);
        assert_eq!(read_u32_ne(&bytes, 152), 0);
        assert_eq!(read_u32_ne(&bytes, 156), 1);
        assert_eq!(read_u32_ne(&bytes, 160), 60);
        assert_eq!(read_u32_ne(&bytes, 164), 1);
    }

    #[test]
    fn test_buffers_pod_layout_and_alignment() {
        let pod = build_video_buffers_pod(1920, 1080);
        assert_eq!((pod.as_pod_ptr() as usize) % 8, 0);
        let bytes = pod.to_bytes();
        assert_eq!(bytes.len(), 160);
        assert_eq!(bytes.len() % 8, 0);

        // Root SPA_TYPE_Object header
        assert_eq!(read_u32_ne(&bytes, 0), 152);
        assert_eq!(read_u32_ne(&bytes, 4), SPA_TYPE_OBJECT);
        assert_eq!(read_u32_ne(&bytes, 8), SPA_TYPE_OBJECT_PARAM_BUFFERS);
        assert_eq!(read_u32_ne(&bytes, 12), SPA_PARAM_BUFFERS);

        // Property 1: buffers = Choice(Range, Int: 4, 2, 8)
        assert_eq!(read_u32_ne(&bytes, 16), SPA_PARAM_BUFFERS_BUFFERS);
        assert_eq!(read_u32_ne(&bytes, 24), 28);
        assert_eq!(read_u32_ne(&bytes, 28), SPA_TYPE_CHOICE);
        assert_eq!(read_u32_ne(&bytes, 32), SPA_CHOICE_RANGE);
        assert_eq!(read_u32_ne(&bytes, 40), 4);
        assert_eq!(read_u32_ne(&bytes, 44), SPA_TYPE_INT);
        assert_eq!(read_i32_ne(&bytes, 48), 4);
        assert_eq!(read_i32_ne(&bytes, 52), 2);
        assert_eq!(read_i32_ne(&bytes, 56), 8);
        assert_eq!(read_u32_ne(&bytes, 60), 0);

        // Property 2: blocks = 1
        assert_eq!(read_u32_ne(&bytes, 64), SPA_PARAM_BUFFERS_BLOCKS);
        assert_eq!(read_u32_ne(&bytes, 72), 4);
        assert_eq!(read_u32_ne(&bytes, 76), SPA_TYPE_INT);
        assert_eq!(read_i32_ne(&bytes, 80), 1);

        // Property 3: size = 1920 * 1080 * 3 / 2
        assert_eq!(read_u32_ne(&bytes, 88), SPA_PARAM_BUFFERS_SIZE);
        assert_eq!(read_u32_ne(&bytes, 96), 4);
        assert_eq!(read_u32_ne(&bytes, 100), SPA_TYPE_INT);
        assert_eq!(read_i32_ne(&bytes, 104), 1920 * 1080 * 3 / 2);

        // Property 4: stride = 1920
        assert_eq!(read_u32_ne(&bytes, 112), SPA_PARAM_BUFFERS_STRIDE);
        assert_eq!(read_u32_ne(&bytes, 120), 4);
        assert_eq!(read_u32_ne(&bytes, 124), SPA_TYPE_INT);
        assert_eq!(read_i32_ne(&bytes, 128), 1920);

        // Property 5: dataType = (1 << MemPtr) | (1 << MemFd)
        assert_eq!(read_u32_ne(&bytes, 136), SPA_PARAM_BUFFERS_DATA_TYPE);
        assert_eq!(read_u32_ne(&bytes, 144), 4);
        assert_eq!(read_u32_ne(&bytes, 148), SPA_TYPE_INT);
        assert_eq!(read_i32_ne(&bytes, 152), 6);
    }

    #[test]
    fn test_pipewire_sink_unopened_validation_and_close() {
        let mut sink = PipeWireCameraSink::new();
        assert!(!sink.is_open());
        assert_eq!(sink.device_path(), Path::new(PIPEWIRE_DEVICE_PATH));
        assert_eq!(sink.current_dimensions(), None);

        let bad_dims = VirtualCameraSink::write_frame(&mut sink, 0, 1080, &[]);
        assert!(matches!(bad_dims, Err(VcamError::FormatError { .. })));

        let odd_dims = VirtualCameraSink::write_frame(&mut sink, 63, 64, &[0u8; 63 * 64 * 3 / 2]);
        assert!(matches!(odd_dims, Err(VcamError::FormatError { .. })));

        let bad_size = VirtualCameraSink::write_frame(&mut sink, 64, 64, &[0u8; 100]);
        assert!(matches!(bad_size, Err(VcamError::WriteError { .. })));

        let not_open = VirtualCameraSink::write_frame(&mut sink, 64, 64, &[0u8; 64 * 64 * 3 / 2]);
        assert!(matches!(not_open, Err(VcamError::NotOpen)));

        let video_sink_not_open =
            VideoOutputSink::write_frame(&mut sink, 64, 64, &[0u8; 64 * 64 * 3 / 2]);
        assert!(matches!(
            video_sink_not_open,
            Err(CoreError::Vcam(VcamError::NotOpen))
        ));

        assert!(sink.close().is_ok());
    }

    #[test]
    fn test_pod_builder_720p60_and_capacity_reuse() {
        let mut builder = SpaPodBuilder::with_capacity_words(32);
        let initial_cap = builder.words.capacity();
        let initial_ptr = builder.as_pod_ptr();

        build_video_enum_format_pod_into(&mut builder, 1280, 720, 60);
        let bytes = builder.to_bytes();
        assert_eq!(bytes.len(), 168);
        assert_eq!(read_u32_ne(&bytes, 104), 1280);
        assert_eq!(read_u32_ne(&bytes, 108), 720);
        assert_eq!(read_u32_ne(&bytes, 144), 60);
        assert_eq!(read_u32_ne(&bytes, 160), 60);

        build_video_buffers_pod_into(&mut builder, 1280, 720);
        let buf_bytes = builder.to_bytes();
        assert_eq!(buf_bytes.len(), 160);
        assert_eq!(read_i32_ne(&buf_bytes, 104), 1280 * 720 * 3 / 2);
        assert_eq!(read_i32_ne(&buf_bytes, 128), 1280);
        assert_eq!(builder.words.capacity(), initial_cap);
        assert_eq!(builder.as_pod_ptr(), initial_ptr);
    }

    #[test]
    fn test_pipewire_sink_open_and_close_lifecycle() {
        let mut sink = PipeWireCameraSink::new();
        match sink.open() {
            Ok(()) => {
                assert!(sink.is_open());
                assert_eq!(sink.current_dimensions(), Some((1920, 1080)));
                let standby_res = sink.write_standby_frame(1280, 720);
                assert!(standby_res.is_ok(), "standby write failed: {standby_res:?}");
                assert_eq!(sink.current_dimensions(), Some((1280, 720)));
                assert!(sink.close().is_ok());
                assert!(!sink.is_open());
            }
            Err(VcamError::DeviceOpen { path, .. }) => {
                assert_eq!(path, Path::new(PIPEWIRE_DEVICE_PATH));
                assert!(!sink.is_open());
            }
            Err(other) => panic!("unexpected PipeWire open error: {other:?}"),
        }
    }
}
