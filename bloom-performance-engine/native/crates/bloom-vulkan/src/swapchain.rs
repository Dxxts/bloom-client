//! Presentation: a plain Win32 window, its Vulkan surface and the swapchain.

use ash::{khr, vk};

use crate::device::Device;
use crate::instance::Instance;
use crate::{Error, Result};

#[cfg(windows)]
pub struct Window {
    pub hwnd: windows_sys::Win32::Foundation::HWND,
    pub hinstance: windows_sys::Win32::Foundation::HINSTANCE,
}

#[cfg(windows)]
impl Window {
    pub fn new(title: &str, width: i32, height: i32) -> Result<Self> {
        use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
        use windows_sys::Win32::UI::WindowsAndMessaging::*;

        unsafe extern "system" fn proc(hwnd: windows_sys::Win32::Foundation::HWND, msg: u32, w: usize, l: isize) -> isize {
            // SAFETY: standard default window procedure.
            unsafe {
                if msg == WM_DESTROY {
                    PostQuitMessage(0);
                    return 0;
                }
                DefWindowProcW(hwnd, msg, w, l)
            }
        }

        let class: Vec<u16> = "BloomVulkanWindow\0".encode_utf16().collect();
        let title: Vec<u16> = title.encode_utf16().chain(std::iter::once(0)).collect();
        // SAFETY: Win32 window creation with valid, NUL-terminated strings.
        unsafe {
            let hinstance = GetModuleHandleW(std::ptr::null());
            let wc = WNDCLASSW {
                style: CS_HREDRAW | CS_VREDRAW,
                lpfnWndProc: Some(proc),
                cbClsExtra: 0,
                cbWndExtra: 0,
                hInstance: hinstance,
                hIcon: std::ptr::null_mut(),
                hCursor: LoadCursorW(std::ptr::null_mut(), IDC_ARROW),
                hbrBackground: std::ptr::null_mut(),
                lpszMenuName: std::ptr::null(),
                lpszClassName: class.as_ptr(),
            };
            RegisterClassW(&wc);
            let hwnd = CreateWindowExW(
                0,
                class.as_ptr(),
                title.as_ptr(),
                WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                CW_USEDEFAULT,
                CW_USEDEFAULT,
                width,
                height,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                hinstance,
                std::ptr::null(),
            );
            if hwnd.is_null() {
                return Err(Error::Window("CreateWindowExW failed".into()));
            }
            Ok(Self { hwnd, hinstance })
        }
    }

    /// Processes pending messages. `false` once the window was closed.
    pub fn pump(&self) -> bool {
        use windows_sys::Win32::UI::WindowsAndMessaging::*;
        // SAFETY: standard message loop on the creating thread.
        unsafe {
            let mut msg: MSG = std::mem::zeroed();
            while PeekMessageW(&mut msg, std::ptr::null_mut(), 0, 0, PM_REMOVE) != 0 {
                if msg.message == WM_QUIT {
                    return false;
                }
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
        true
    }

    pub fn client_size(&self) -> (u32, u32) {
        use windows_sys::Win32::UI::WindowsAndMessaging::GetClientRect;
        let mut rect = windows_sys::Win32::Foundation::RECT { left: 0, top: 0, right: 0, bottom: 0 };
        // SAFETY: valid window handle.
        unsafe { GetClientRect(self.hwnd, &mut rect) };
        ((rect.right - rect.left).max(1) as u32, (rect.bottom - rect.top).max(1) as u32)
    }
}

#[cfg(windows)]
impl Drop for Window {
    fn drop(&mut self) {
        // SAFETY: the window belongs to this object.
        unsafe { windows_sys::Win32::UI::WindowsAndMessaging::DestroyWindow(self.hwnd) };
    }
}

pub struct Swapchain {
    surface_loader: khr::surface::Instance,
    loader: khr::swapchain::Device,
    pub surface: vk::SurfaceKHR,
    pub raw: vk::SwapchainKHR,
    pub images: Vec<vk::Image>,
    pub views: Vec<vk::ImageView>,
    pub format: vk::Format,
    pub extent: vk::Extent2D,
    pub present_mode: vk::PresentModeKHR,
    pub recreated: u32,
    vsync: bool,
}

impl Swapchain {
    #[cfg(windows)]
    pub fn new(instance: &Instance, device: &Device, window: &Window, vsync: bool) -> Result<Self> {
        if !instance.surface_support || !device.swapchain_enabled {
            return Err(Error::Window("surface or swapchain extension unavailable".into()));
        }
        let win32 = khr::win32_surface::Instance::new(&instance.entry, &instance.raw);
        let info = vk::Win32SurfaceCreateInfoKHR::default().hinstance(window.hinstance as isize).hwnd(window.hwnd as isize);
        // SAFETY: valid window handles.
        let surface = unsafe { win32.create_win32_surface(&info, None) }?;
        let surface_loader = khr::surface::Instance::new(&instance.entry, &instance.raw);
        // SAFETY: valid physical device and surface.
        let supported = unsafe { surface_loader.get_physical_device_surface_support(device.physical, device.queue_family, surface) }?;
        if !supported {
            unsafe { surface_loader.destroy_surface(surface, None) };
            return Err(Error::Window("the graphics queue cannot present to this window".into()));
        }
        let loader = khr::swapchain::Device::new(&instance.raw, &device.raw);
        let mut sc = Self {
            surface_loader,
            loader,
            surface,
            raw: vk::SwapchainKHR::null(),
            images: Vec::new(),
            views: Vec::new(),
            format: vk::Format::UNDEFINED,
            extent: vk::Extent2D::default(),
            present_mode: vk::PresentModeKHR::FIFO,
            recreated: 0,
            vsync,
        };
        sc.create(device, window.client_size())?;
        Ok(sc)
    }

    /// (Re)creates the swapchain for `size`; the old one is retired.
    pub fn create(&mut self, device: &Device, size: (u32, u32)) -> Result<()> {
        // SAFETY: queries and creation on live objects; old views destroyed after idle.
        unsafe {
            let caps = self.surface_loader.get_physical_device_surface_capabilities(device.physical, self.surface)?;
            let formats = self.surface_loader.get_physical_device_surface_formats(device.physical, self.surface)?;
            let modes = self.surface_loader.get_physical_device_surface_present_modes(device.physical, self.surface)?;
            let format = formats
                .iter()
                .find(|f| f.format == vk::Format::B8G8R8A8_UNORM && f.color_space == vk::ColorSpaceKHR::SRGB_NONLINEAR)
                .copied()
                .unwrap_or(formats[0]);
            // FIFO is always available; without vsync prefer IMMEDIATE, then MAILBOX.
            self.present_mode = if self.vsync {
                vk::PresentModeKHR::FIFO
            } else {
                [vk::PresentModeKHR::IMMEDIATE, vk::PresentModeKHR::MAILBOX].into_iter().find(|m| modes.contains(m)).unwrap_or(vk::PresentModeKHR::FIFO)
            };
            self.extent = if caps.current_extent.width != u32::MAX {
                caps.current_extent
            } else {
                vk::Extent2D {
                    width: size.0.clamp(caps.min_image_extent.width, caps.max_image_extent.width),
                    height: size.1.clamp(caps.min_image_extent.height, caps.max_image_extent.height),
                }
            };
            let mut count = caps.min_image_count + 1;
            if caps.max_image_count > 0 {
                count = count.min(caps.max_image_count);
            }
            let old = self.raw;
            let info = vk::SwapchainCreateInfoKHR::default()
                .surface(self.surface)
                .min_image_count(count)
                .image_format(format.format)
                .image_color_space(format.color_space)
                .image_extent(self.extent)
                .image_array_layers(1)
                .image_usage(vk::ImageUsageFlags::COLOR_ATTACHMENT)
                .image_sharing_mode(vk::SharingMode::EXCLUSIVE)
                .pre_transform(caps.current_transform)
                .composite_alpha(vk::CompositeAlphaFlagsKHR::OPAQUE)
                .present_mode(self.present_mode)
                .clipped(true)
                .old_swapchain(old);
            self.raw = self.loader.create_swapchain(&info, None)?;
            device.wait_idle();
            for v in self.views.drain(..) {
                device.raw.destroy_image_view(v, None);
            }
            if old != vk::SwapchainKHR::null() {
                self.loader.destroy_swapchain(old, None);
                self.recreated += 1;
            }
            self.format = format.format;
            self.images = self.loader.get_swapchain_images(self.raw)?;
            for &image in &self.images {
                let view_info = vk::ImageViewCreateInfo::default().image(image).view_type(vk::ImageViewType::TYPE_2D).format(self.format).subresource_range(
                    vk::ImageSubresourceRange { aspect_mask: vk::ImageAspectFlags::COLOR, base_mip_level: 0, level_count: 1, base_array_layer: 0, layer_count: 1 },
                );
                self.views.push(device.raw.create_image_view(&view_info, None)?);
            }
        }
        Ok(())
    }

    /// Next image index, or `None` when the swapchain must be recreated.
    pub fn acquire(&self, signal: vk::Semaphore) -> Result<Option<u32>> {
        // SAFETY: valid swapchain and semaphore.
        match unsafe { self.loader.acquire_next_image(self.raw, u64::MAX, signal, vk::Fence::null()) } {
            Ok((index, _suboptimal)) => Ok(Some(index)),
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => Ok(None),
            Err(e) => Err(e.into()),
        }
    }

    /// Presents; `false` when the swapchain must be recreated.
    pub fn present(&self, queue: vk::Queue, index: u32, wait: vk::Semaphore) -> Result<bool> {
        let swapchains = [self.raw];
        let indices = [index];
        let waits = [wait];
        let info = vk::PresentInfoKHR::default().wait_semaphores(&waits).swapchains(&swapchains).image_indices(&indices);
        // SAFETY: valid queue and swapchain.
        match unsafe { self.loader.queue_present(queue, &info) } {
            Ok(suboptimal) => Ok(!suboptimal),
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => Ok(false),
            Err(e) => Err(e.into()),
        }
    }

    pub fn destroy(&mut self, device: &Device) {
        // SAFETY: the caller waited for the device to be idle.
        unsafe {
            for v in self.views.drain(..) {
                device.raw.destroy_image_view(v, None);
            }
            self.loader.destroy_swapchain(self.raw, None);
            self.surface_loader.destroy_surface(self.surface, None);
        }
    }
}
