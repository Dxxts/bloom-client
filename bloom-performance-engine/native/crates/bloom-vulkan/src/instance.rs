//! Vulkan loader and instance.

use std::ffi::{c_void, CStr};
use std::sync::{Arc, Mutex};

use ash::{ext, khr, vk};

use crate::{Error, Result};

const VALIDATION_LAYER: &CStr = c"VK_LAYER_KHRONOS_validation";

/// Messages from the validation layer and the driver (`VK_EXT_debug_utils`).
#[derive(Default)]
pub struct DebugLog {
    pub warnings: u32,
    pub errors: u32,
    pub last: Vec<String>,
}

pub struct Instance {
    pub entry: ash::Entry,
    pub raw: ash::Instance,
    pub validation: bool,
    pub surface_support: bool,
    debug: Option<(ext::debug_utils::Instance, vk::DebugUtilsMessengerEXT)>,
    pub debug_log: Arc<Mutex<DebugLog>>,
    // Keeps the pointer handed to the messenger alive at a fixed address.
    #[allow(clippy::redundant_allocation)]
    _debug_user_data: Option<Box<Arc<Mutex<DebugLog>>>>,
}

unsafe extern "system" fn debug_callback(
    severity: vk::DebugUtilsMessageSeverityFlagsEXT,
    _types: vk::DebugUtilsMessageTypeFlagsEXT,
    data: *const vk::DebugUtilsMessengerCallbackDataEXT<'_>,
    user: *mut c_void,
) -> vk::Bool32 {
    // SAFETY: `user` is the boxed Arc owned by the Instance; `data` is valid for the call.
    let log = unsafe { &*(user as *const Arc<Mutex<DebugLog>>) };
    let message = unsafe { data.as_ref() }
        .and_then(|d| unsafe { d.message_as_c_str() })
        .map(|m| m.to_string_lossy().into_owned())
        .unwrap_or_default();
    if let Ok(mut log) = log.lock() {
        if severity.contains(vk::DebugUtilsMessageSeverityFlagsEXT::ERROR) {
            log.errors += 1;
        } else {
            log.warnings += 1;
        }
        if log.last.len() == 16 {
            log.last.remove(0);
        }
        log.last.push(message);
    }
    vk::FALSE
}

impl Instance {
    /// Loads `vulkan-1.dll` and creates an instance. The validation layer is
    /// enabled when `validation` is set and the layer is installed.
    pub fn new(app_name: &CStr, validation: bool) -> Result<Self> {
        // SAFETY: loading the system Vulkan loader.
        let entry = unsafe { ash::Entry::load() }.map_err(|e| Error::Loader(e.to_string()))?;
        // SAFETY: plain queries on a loaded entry.
        let layers = unsafe { entry.enumerate_instance_layer_properties() }.unwrap_or_default();
        let extensions = unsafe { entry.enumerate_instance_extension_properties(None) }.unwrap_or_default();
        let has_ext = |name: &CStr| extensions.iter().any(|e| e.extension_name_as_c_str() == Ok(name));
        let validation = validation && layers.iter().any(|l| l.layer_name_as_c_str() == Ok(VALIDATION_LAYER));
        let debug_utils = has_ext(ext::debug_utils::NAME);
        let surface_support = has_ext(khr::surface::NAME) && has_ext(khr::win32_surface::NAME);

        let mut ext_names = Vec::new();
        if debug_utils {
            ext_names.push(ext::debug_utils::NAME.as_ptr());
        }
        if surface_support {
            ext_names.push(khr::surface::NAME.as_ptr());
            ext_names.push(khr::win32_surface::NAME.as_ptr());
        }
        if has_ext(khr::get_physical_device_properties2::NAME) {
            ext_names.push(khr::get_physical_device_properties2::NAME.as_ptr());
        }
        let layer_names = if validation { vec![VALIDATION_LAYER.as_ptr()] } else { vec![] };
        let app = vk::ApplicationInfo::default()
            .application_name(app_name)
            .engine_name(c"Bloom Native World Engine")
            .api_version(vk::API_VERSION_1_2);
        let info = vk::InstanceCreateInfo::default().application_info(&app).enabled_extension_names(&ext_names).enabled_layer_names(&layer_names);
        // SAFETY: every pointer in `info` outlives the call.
        let raw = unsafe { entry.create_instance(&info, None) }?;

        let debug_log = Arc::new(Mutex::new(DebugLog::default()));
        let mut user_data = None;
        let mut debug = None;
        if debug_utils {
            let boxed = Box::new(Arc::clone(&debug_log));
            let loader = ext::debug_utils::Instance::new(&entry, &raw);
            let info = vk::DebugUtilsMessengerCreateInfoEXT::default()
                .message_severity(vk::DebugUtilsMessageSeverityFlagsEXT::WARNING | vk::DebugUtilsMessageSeverityFlagsEXT::ERROR)
                .message_type(
                    vk::DebugUtilsMessageTypeFlagsEXT::GENERAL | vk::DebugUtilsMessageTypeFlagsEXT::VALIDATION | vk::DebugUtilsMessageTypeFlagsEXT::PERFORMANCE,
                )
                .pfn_user_callback(Some(debug_callback))
                .user_data(&*boxed as *const Arc<Mutex<DebugLog>> as *mut c_void);
            // SAFETY: the user data box lives as long as the Instance.
            if let Ok(messenger) = unsafe { loader.create_debug_utils_messenger(&info, None) } {
                debug = Some((loader, messenger));
                user_data = Some(boxed);
            }
        }
        Ok(Self { entry, raw, validation, surface_support, debug, debug_log, _debug_user_data: user_data })
    }
}

impl Drop for Instance {
    fn drop(&mut self) {
        // SAFETY: every child object was destroyed by its owner first.
        unsafe {
            if let Some((loader, messenger)) = self.debug.take() {
                loader.destroy_debug_utils_messenger(messenger, None);
            }
            self.raw.destroy_instance(None);
        }
    }
}
