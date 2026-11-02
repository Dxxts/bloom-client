//! JNI entry points (`com.bloomclient.engine.bridge.NativeBridge`).
//!
//! Only primitive values cross the boundary: addresses (`jlong`) of off-heap
//! buffers owned by Java, lengths and status codes (`jint`). No Java object is
//! touched, so no `JNIEnv` call is ever made. Every entry point catches
//! panics and turns them into [`status::PANIC`]; Java then disables the
//! engine and goes back to the vanilla renderer.
//!
//! # Safety contract with Java
//!
//! - Every `addr`/`len` pair describes a readable (or, for outputs, writable)
//!   region that stays valid for the whole call.
//! - A context is used by one thread at a time and is destroyed exactly once.
//! - Pointers written into a mesh result stay valid until the next
//!   `meshSection` or `destroyContext` on the same context.

use std::cell::RefCell;
use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::Ordering;
use std::sync::{Arc, OnceLock, RwLock};
use std::time::Instant;

use bloom_core::model::{Model, ModelError};
use bloom_core::protocol::{
    status, Writer, LAYER_COUNT, PLAN_FLUIDS_OFFSET, PLAN_FLUID_COUNT_OFFSET, PLAN_SIZE, PROTOCOL_VERSION, RESULT_SIZE,
};
use bloom_core::state::StateTable;
use chunk_mesher::{MeshError, Mesher, LIGHT_MASK_BYTES};
use profiler::{EngineProfiler, EXPORT_SLOTS};

type JEnv = *mut c_void;
type JClass = *mut c_void;

struct Engine {
    states: StateTable,
    models: RwLock<Vec<Model>>,
}

static ENGINE: RwLock<Option<Arc<Engine>>> = RwLock::new(None);
static PROFILER: OnceLock<EngineProfiler> = OnceLock::new();

thread_local! {
    static LAST_ERROR: RefCell<String> = const { RefCell::new(String::new()) };
}

fn profiler() -> &'static EngineProfiler {
    PROFILER.get_or_init(EngineProfiler::default)
}

fn set_error(message: impl Into<String>) {
    LAST_ERROR.with(|e| *e.borrow_mut() = message.into());
}

fn engine() -> Option<Arc<Engine>> {
    ENGINE.read().unwrap_or_else(|e| e.into_inner()).clone()
}

/// Runs `f`, converting a panic into `on_panic` and recording its message.
fn guarded<T>(on_panic: T, f: impl FnOnce() -> T) -> T {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(payload) => {
            let msg = payload
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".into());
            set_error(format!("native panic: {msg}"));
            on_panic
        }
    }
}

/// # Safety
/// `addr..addr + len` must be readable for the duration of the borrow.
unsafe fn input<'a>(addr: i64, len: i32) -> Option<&'a [u8]> {
    if len == 0 {
        return Some(&[]);
    }
    if addr == 0 || len < 0 {
        return None;
    }
    Some(std::slice::from_raw_parts(addr as usize as *const u8, len as usize))
}

pub struct Context {
    mesher: Mesher,
}

#[no_mangle]
pub extern "system" fn Java_com_bloomclient_engine_bridge_NativeBridge_protocolVersion(_: JEnv, _: JClass) -> i32 {
    PROTOCOL_VERSION as i32
}

/// Replaces the state table (and drops every registered model).
#[no_mangle]
pub extern "system" fn Java_com_bloomclient_engine_bridge_NativeBridge_installTables(
    _: JEnv,
    _: JClass,
    states_addr: i64,
    states_len: i32,
    pairs_addr: i64,
    pairs_len: i32,
) -> i32 {
    guarded(status::PANIC, || {
        // SAFETY: Java passes live off-heap buffers (see module docs).
        let (Some(states), Some(pairs)) = (unsafe { input(states_addr, states_len) }, unsafe { input(pairs_addr, pairs_len) }) else {
            set_error("installTables: invalid buffer");
            return status::INVALID_INPUT;
        };
        match StateTable::parse(states, pairs) {
            Ok(table) => {
                let engine = Arc::new(Engine { states: table, models: RwLock::new(Vec::new()) });
                *ENGINE.write().unwrap_or_else(|e| e.into_inner()) = Some(engine);
                profiler().models_registered.store(0, Ordering::Relaxed);
                status::OK
            }
            Err(e) => {
                set_error(format!("installTables: {e:?}"));
                status::INVALID_INPUT
            }
        }
    })
}

/// Registers a model. Returns its id (>= 1) or a negated status code.
#[no_mangle]
pub extern "system" fn Java_com_bloomclient_engine_bridge_NativeBridge_registerModel(_: JEnv, _: JClass, addr: i64, len: i32) -> i32 {
    guarded(-status::PANIC, || {
        // SAFETY: see module docs.
        let Some(bytes) = (unsafe { input(addr, len) }) else {
            set_error("registerModel: invalid buffer");
            return -status::INVALID_INPUT;
        };
        let Some(engine) = engine() else {
            set_error("registerModel: no tables installed");
            return -status::STALE_TABLES;
        };
        match Model::parse(bytes) {
            Ok((epoch, _)) if epoch != engine.states.epoch => -status::STALE_TABLES,
            Ok((_, model)) => {
                let mut models = engine.models.write().unwrap_or_else(|e| e.into_inner());
                if models.len() >= i32::MAX as usize - 1 {
                    set_error("registerModel: too many models");
                    return -status::INTERNAL;
                }
                models.push(model);
                profiler().models_registered.fetch_add(1, Ordering::Relaxed);
                models.len() as i32
            }
            Err(ModelError::StaleEpoch) => -status::STALE_TABLES,
            Err(e) => {
                set_error(format!("registerModel: {e:?}"));
                -status::INVALID_INPUT
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_bloomclient_engine_bridge_NativeBridge_createContext(_: JEnv, _: JClass) -> i64 {
    guarded(0, || Box::into_raw(Box::new(Context { mesher: Mesher::new() })) as usize as i64)
}

#[no_mangle]
pub extern "system" fn Java_com_bloomclient_engine_bridge_NativeBridge_destroyContext(_: JEnv, _: JClass, ctx: i64) {
    guarded((), || {
        if ctx != 0 {
            // SAFETY: `ctx` came from createContext and is destroyed once.
            drop(unsafe { Box::from_raw(ctx as usize as *mut Context) });
        }
    })
}

/// First pass over a snapshot whose light array is not filled yet: writes
/// the plan (`PLAN_SIZE` bytes): the bitmask of light positions Java must
/// read, then the fluid blocks Java must describe.
#[no_mangle]
pub extern "system" fn Java_com_bloomclient_engine_bridge_NativeBridge_planLight(
    _: JEnv,
    _: JClass,
    ctx: i64,
    snapshot_addr: i64,
    snapshot_len: i32,
    mask_addr: i64,
) -> i32 {
    if ctx == 0 || mask_addr == 0 {
        set_error("planLight: null context or mask");
        return status::INVALID_INPUT;
    }
    // SAFETY: `ctx` is a live context used only by the calling thread.
    let context = unsafe { &mut *(ctx as usize as *mut Context) };
    let code = guarded(status::PANIC, || {
        // SAFETY: see module docs.
        let Some(bytes) = (unsafe { input(snapshot_addr, snapshot_len) }) else {
            set_error("planLight: invalid snapshot buffer");
            return status::INVALID_INPUT;
        };
        let Some(engine) = engine() else {
            return status::STALE_TABLES;
        };
        let models = engine.models.read().unwrap_or_else(|e| e.into_inner());
        match context.mesher.plan_light(bytes, &engine.states, models.as_slice()) {
            Ok(mask) => {
                let out = mask_addr as usize as *mut u8;
                // SAFETY: Java provides PLAN_SIZE writable bytes.
                unsafe { std::ptr::copy_nonoverlapping(mask.as_ptr(), out, LIGHT_MASK_BYTES) };
                let requests = context.mesher.fluid_requests();
                debug_assert!(PLAN_FLUIDS_OFFSET + requests.len() * 2 <= PLAN_SIZE);
                // SAFETY: as above; at most SECTION_VOLUME requests fit by construction.
                unsafe {
                    (out.add(PLAN_FLUID_COUNT_OFFSET) as *mut u32).write_unaligned(requests.len() as u32);
                    let dst = out.add(PLAN_FLUIDS_OFFSET) as *mut u16;
                    for (i, r) in requests.iter().enumerate() {
                        dst.add(i).write_unaligned(*r);
                    }
                }
                status::OK
            }
            Err(MeshError::StaleEpoch) => status::STALE_TABLES,
            Err(MeshError::Snapshot(e)) => {
                set_error(format!("planLight: {e:?}"));
                status::INVALID_INPUT
            }
        }
    });
    if code == status::PANIC {
        context.mesher = Mesher::new();
    }
    code
}

/// Meshes one section snapshot and writes the result header (`RESULT_SIZE`
/// bytes) to `result_addr`.
#[no_mangle]
pub extern "system" fn Java_com_bloomclient_engine_bridge_NativeBridge_meshSection(
    _: JEnv,
    _: JClass,
    ctx: i64,
    snapshot_addr: i64,
    snapshot_len: i32,
    result_addr: i64,
) -> i32 {
    if ctx == 0 || result_addr == 0 {
        set_error("meshSection: null context or result");
        return status::INVALID_INPUT;
    }
    // SAFETY: `ctx` is a live context used only by the calling thread.
    let context = unsafe { &mut *(ctx as usize as *mut Context) };
    let outcome = guarded(Err(status::PANIC), || mesh_into(context, snapshot_addr, snapshot_len, result_addr));
    match outcome {
        Ok(()) => status::OK,
        Err(code) => {
            if code == status::PANIC {
                // A panic may leave scratch state half-written: start clean.
                context.mesher = Mesher::new();
            }
            profiler().sections_rejected.fetch_add(1, Ordering::Relaxed);
            // SAFETY: Java provides RESULT_SIZE writable bytes.
            unsafe { std::ptr::write_bytes(result_addr as usize as *mut u8, 0, RESULT_SIZE) };
            // SAFETY: as above; the status is the first field.
            unsafe { (result_addr as usize as *mut i32).write_unaligned(code) };
            code
        }
    }
}

fn mesh_into(context: &mut Context, snapshot_addr: i64, snapshot_len: i32, result_addr: i64) -> Result<(), i32> {
    let started = Instant::now();
    // SAFETY: see module docs.
    let Some(bytes) = (unsafe { input(snapshot_addr, snapshot_len) }) else {
        set_error("meshSection: invalid snapshot buffer");
        return Err(status::INVALID_INPUT);
    };
    let Some(engine) = engine() else {
        return Err(status::STALE_TABLES);
    };
    {
        let models = engine.models.read().unwrap_or_else(|e| e.into_inner());
        match context.mesher.mesh(bytes, &engine.states, models.as_slice()) {
            Ok(()) => {}
            Err(MeshError::StaleEpoch) => return Err(status::STALE_TABLES),
            Err(MeshError::Snapshot(e)) => {
                set_error(format!("meshSection: {e:?}"));
                return Err(status::INVALID_INPUT);
            }
        }
    }
    let m = &context.mesher;
    let elapsed = started.elapsed().as_nanos() as u64;

    let mut w = Writer::with_len(RESULT_SIZE);
    w.put_i32(0, status::OK);
    w.put_u32(4, m.demoted.len() as u32);
    w.put_u64(8, m.demoted.as_ptr() as usize as u64);
    let mut bytes_out = 0;
    for layer in 0..LAYER_COUNT {
        w.put_u64(16 + layer * 16, m.layers[layer].as_ptr() as usize as u64);
        w.put_u32(24 + layer * 16, m.vertex_count(layer) as u32);
        bytes_out += m.layers[layer].len() as u64;
    }
    w.put_u64(64, m.visibility);
    w.put_u32(72, m.stats.native_blocks);
    w.put_u32(76, m.stats.quads);
    w.put_u32(80, m.stats.culled_faces);
    w.put_u32(84, m.stats.demoted_blocks);
    w.put_u64(88, elapsed);
    w.put_u32(96, m.stats.native_fluids);
    w.put_u32(100, m.stats.demoted_fluids);
    // SAFETY: Java provides RESULT_SIZE writable bytes.
    unsafe { std::ptr::copy_nonoverlapping(w.bytes.as_ptr(), result_addr as usize as *mut u8, RESULT_SIZE) };

    let p = profiler();
    p.sections_meshed.fetch_add(1, Ordering::Relaxed);
    p.native_blocks.fetch_add(m.stats.native_blocks as u64, Ordering::Relaxed);
    p.native_fluids.fetch_add(m.stats.native_fluids as u64, Ordering::Relaxed);
    p.demoted_fluids.fetch_add(m.stats.demoted_fluids as u64, Ordering::Relaxed);
    p.demoted_blocks.fetch_add(m.stats.demoted_blocks as u64, Ordering::Relaxed);
    p.quads.fetch_add(m.stats.quads as u64, Ordering::Relaxed);
    p.culled_faces.fetch_add(m.stats.culled_faces as u64, Ordering::Relaxed);
    p.vertex_bytes.fetch_add(bytes_out, Ordering::Relaxed);
    p.mesh_ns.record(elapsed);
    Ok(())
}

/// Writes up to `slots` u64 counters (see `profiler::EngineProfiler::export`).
#[no_mangle]
pub extern "system" fn Java_com_bloomclient_engine_bridge_NativeBridge_readStats(_: JEnv, _: JClass, addr: i64, slots: i32) -> i32 {
    guarded(0, || {
        if addr == 0 || slots <= 0 {
            return 0;
        }
        let values = profiler().export();
        let n = (slots as usize).min(EXPORT_SLOTS);
        // SAFETY: Java provides `slots * 8` writable bytes.
        unsafe { std::ptr::copy_nonoverlapping(values.as_ptr() as *const u8, addr as usize as *mut u8, n * 8) };
        n as i32
    })
}

#[no_mangle]
pub extern "system" fn Java_com_bloomclient_engine_bridge_NativeBridge_resetStats(_: JEnv, _: JClass) {
    guarded((), || profiler().reset())
}

/// Copies the calling thread's last error message (UTF-8) and returns its
/// full length in bytes.
#[no_mangle]
pub extern "system" fn Java_com_bloomclient_engine_bridge_NativeBridge_lastError(_: JEnv, _: JClass, addr: i64, capacity: i32) -> i32 {
    guarded(0, || {
        LAST_ERROR.with(|e| {
            let e = e.borrow();
            let n = e.len().min(capacity.max(0) as usize);
            if addr != 0 && n > 0 {
                // SAFETY: Java provides `capacity` writable bytes.
                unsafe { std::ptr::copy_nonoverlapping(e.as_ptr(), addr as usize as *mut u8, n) };
            }
            e.len() as i32
        })
    })
}

/// Drops the tables and models. Contexts are destroyed separately.
#[no_mangle]
pub extern "system" fn Java_com_bloomclient_engine_bridge_NativeBridge_shutdown(_: JEnv, _: JClass) {
    guarded((), || *ENGINE.write().unwrap_or_else(|e| e.into_inner()) = None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use bloom_core::model::{cube_face, encode_model};
    use bloom_core::protocol::{face_class, state_flags, NO_SHAPE};
    use bloom_core::state::{encode_state_table, StateInfo};
    use bloom_core::Direction;
    use chunk_mesher::SnapshotBuilder;

    fn call_mesh(ctx: i64, snap: &[u8], result: &mut [u8; RESULT_SIZE]) -> i32 {
        Java_com_bloomclient_engine_bridge_NativeBridge_meshSection(
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            ctx,
            snap.as_ptr() as i64,
            snap.len() as i32,
            result.as_mut_ptr() as i64,
        )
    }

    #[test]
    fn end_to_end_through_the_jni_surface() {
        let null = std::ptr::null_mut();
        let air = StateInfo { flags: state_flags::AIR | state_flags::LIGHT_PERMEABLE, ..StateInfo::UNKNOWN };
        let air = StateInfo { face_classes: 0, face_shapes: [NO_SHAPE; 6], block_id: 0, fluid_group: 0, ..air };
        let stone = StateInfo {
            flags: state_flags::SOLID_RENDER | state_flags::COLLISION_FULL_BLOCK,
            block_id: 1,
            shade_brightness: 0.2,
            light_emission: 0,
            skip_rule: 0,
            face_classes: (0..6).fold(0, |a, d| a | ((face_class::BLOCK as u16) << (d * 2))),
            face_shapes: [NO_SHAPE; 6],
            fluid_group: 0,
            fluid_model: 0,
            fluid_amount: 0,
        };
        let table = encode_state_table(9, &[air, stone]);
        let st = Java_com_bloomclient_engine_bridge_NativeBridge_installTables(null, null, table.as_ptr() as i64, table.len() as i32, 0, 0);
        assert_eq!(st, status::OK);

        let quads: Vec<_> = Direction::ALL.iter().map(|&d| cube_face(d, [0.0, 0.0, 1.0, 1.0], -1, 0)).collect();
        let model = encode_model(9, true, &quads);
        let id = Java_com_bloomclient_engine_bridge_NativeBridge_registerModel(null, null, model.as_ptr() as i64, model.len() as i32);
        assert_eq!(id, 1);
        let stale = encode_model(8, true, &quads);
        assert_eq!(
            Java_com_bloomclient_engine_bridge_NativeBridge_registerModel(null, null, stale.as_ptr() as i64, stale.len() as i32),
            -status::STALE_TABLES
        );

        let mut b = SnapshotBuilder::new(9, 1, [0, 0, 0], [0.5, 1.0, 0.8, 0.8, 0.6, 0.6]);
        b.set_state(1, 1, 1, 1);
        b.set_model(1 | (1 << 4) | (1 << 8), id as u32);
        let snap = b.finish();

        let ctx = Java_com_bloomclient_engine_bridge_NativeBridge_createContext(null, null);
        assert_ne!(ctx, 0);
        let mut result = [0u8; RESULT_SIZE];
        assert_eq!(call_mesh(ctx, &snap, &mut result), status::OK);
        let solid_vertices = u32::from_le_bytes(result[24..28].try_into().unwrap());
        assert_eq!(solid_vertices, 24);
        assert_eq!(u32::from_le_bytes(result[72..76].try_into().unwrap()), 1);

        // Truncated input is rejected without touching anything else.
        assert_eq!(call_mesh(ctx, &snap[..100], &mut result), status::INVALID_INPUT);
        assert_eq!(i32::from_le_bytes(result[0..4].try_into().unwrap()), status::INVALID_INPUT);

        let mut msg = [0u8; 256];
        let n = Java_com_bloomclient_engine_bridge_NativeBridge_lastError(null, null, msg.as_mut_ptr() as i64, 256);
        assert!(std::str::from_utf8(&msg[..n as usize]).unwrap().contains("meshSection"));

        let mut stats = [0u64; EXPORT_SLOTS];
        assert_eq!(
            Java_com_bloomclient_engine_bridge_NativeBridge_readStats(null, null, stats.as_mut_ptr() as i64, EXPORT_SLOTS as i32),
            EXPORT_SLOTS as i32
        );
        assert!(stats[0] >= 1);

        // New tables invalidate snapshots built for the old epoch.
        let table2 = encode_state_table(10, &[air, stone]);
        Java_com_bloomclient_engine_bridge_NativeBridge_installTables(null, null, table2.as_ptr() as i64, table2.len() as i32, 0, 0);
        assert_eq!(call_mesh(ctx, &snap, &mut result), status::STALE_TABLES);

        Java_com_bloomclient_engine_bridge_NativeBridge_destroyContext(null, null, ctx);
        Java_com_bloomclient_engine_bridge_NativeBridge_shutdown(null, null);
    }
}
