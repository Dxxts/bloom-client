//! Command buffers, synchronization and resource lifetime.
//!
//! [`Frames`] keeps N frames in flight. Each frame owns a command pool (reset
//! as a whole instead of freeing buffers one by one), a fence signalled when
//! its submission completes, and the two semaphores of presentation.
//! [`DeletionQueue`] destroys resources only after the frame that last used
//! them has completed on the GPU.

use ash::vk;

use crate::memory::Allocator;
use crate::resources::{Buffer, Image};
use crate::Result;

/// Records with `record`, submits, waits on a fence. For uploads and setup.
pub fn one_time_submit(device: &ash::Device, queue: vk::Queue, queue_family: u32, record: impl FnOnce(vk::CommandBuffer)) -> Result<()> {
    let pool_info = vk::CommandPoolCreateInfo::default().queue_family_index(queue_family).flags(vk::CommandPoolCreateFlags::TRANSIENT);
    // SAFETY: every object is created, used and destroyed inside this function.
    unsafe {
        let pool = device.create_command_pool(&pool_info, None)?;
        let result = (|| -> Result<()> {
            let alloc = vk::CommandBufferAllocateInfo::default().command_pool(pool).level(vk::CommandBufferLevel::PRIMARY).command_buffer_count(1);
            let cmd = device.allocate_command_buffers(&alloc)?[0];
            device.begin_command_buffer(cmd, &vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT))?;
            record(cmd);
            device.end_command_buffer(cmd)?;
            let fence = device.create_fence(&vk::FenceCreateInfo::default(), None)?;
            let cmds = [cmd];
            let submit = [vk::SubmitInfo::default().command_buffers(&cmds)];
            let submitted = device.queue_submit(queue, &submit, fence);
            let waited = submitted.and_then(|()| device.wait_for_fences(&[fence], true, u64::MAX));
            device.destroy_fence(fence, None);
            waited?;
            Ok(())
        })();
        device.destroy_command_pool(pool, None);
        result
    }
}

pub struct Frame {
    pub pool: vk::CommandPool,
    pub cmd: vk::CommandBuffer,
    pub fence: vk::Fence,
    pub image_available: vk::Semaphore,
    pub render_finished: vk::Semaphore,
    /// Frame number of the last submission from this slot.
    pub submitted: u64,
}

pub struct Frames {
    pub frames: Vec<Frame>,
    /// Number of the frame being recorded (starts at 1).
    pub number: u64,
    /// Highest frame number known to be complete on the GPU.
    pub completed: u64,
}

impl Frames {
    pub fn new(device: &ash::Device, queue_family: u32, in_flight: usize) -> Result<Self> {
        let mut frames = Vec::with_capacity(in_flight);
        for _ in 0..in_flight {
            // SAFETY: plain object creation on a live device.
            unsafe {
                let pool = device.create_command_pool(&vk::CommandPoolCreateInfo::default().queue_family_index(queue_family), None)?;
                let alloc = vk::CommandBufferAllocateInfo::default().command_pool(pool).level(vk::CommandBufferLevel::PRIMARY).command_buffer_count(1);
                let cmd = device.allocate_command_buffers(&alloc)?[0];
                // Created signalled so the first wait returns at once.
                let fence = device.create_fence(&vk::FenceCreateInfo::default().flags(vk::FenceCreateFlags::SIGNALED), None)?;
                let image_available = device.create_semaphore(&vk::SemaphoreCreateInfo::default(), None)?;
                let render_finished = device.create_semaphore(&vk::SemaphoreCreateInfo::default(), None)?;
                frames.push(Frame { pool, cmd, fence, image_available, render_finished, submitted: 0 });
            }
        }
        Ok(Self { frames, number: 1, completed: 0 })
    }

    pub fn slot(&self) -> usize {
        (self.number % self.frames.len() as u64) as usize
    }

    /// Waits until the current slot's previous submission is done, then resets
    /// its fence and command pool and begins its command buffer.
    pub fn begin(&mut self, device: &ash::Device) -> Result<vk::CommandBuffer> {
        let slot = self.slot();
        let frame = &mut self.frames[slot];
        // SAFETY: the fence and pool belong to this slot; waiting makes the reset safe.
        unsafe {
            device.wait_for_fences(&[frame.fence], true, u64::MAX)?;
            self.completed = self.completed.max(frame.submitted);
            device.reset_fences(&[frame.fence])?;
            device.reset_command_pool(frame.pool, vk::CommandPoolResetFlags::empty())?;
            device.begin_command_buffer(frame.cmd, &vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT))?;
        }
        Ok(frame.cmd)
    }

    /// Ends and submits the current frame. With `present`, waits on
    /// `image_available` and signals `render_finished`.
    pub fn submit(&mut self, device: &ash::Device, queue: vk::Queue, present: bool) -> Result<()> {
        let slot = self.slot();
        let frame = &mut self.frames[slot];
        let cmds = [frame.cmd];
        let wait = [frame.image_available];
        let stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
        let signal = [frame.render_finished];
        let mut submit = vk::SubmitInfo::default().command_buffers(&cmds);
        if present {
            submit = submit.wait_semaphores(&wait).wait_dst_stage_mask(&stages).signal_semaphores(&signal);
        }
        // SAFETY: the command buffer was recorded in `begin` and is ended here.
        unsafe {
            device.end_command_buffer(frame.cmd)?;
            device.queue_submit(queue, &[submit], frame.fence)?;
        }
        frame.submitted = self.number;
        self.number += 1;
        Ok(())
    }

    pub fn current(&self) -> &Frame {
        &self.frames[self.slot()]
    }

    pub fn destroy(&mut self, device: &ash::Device) {
        for f in self.frames.drain(..) {
            // SAFETY: the caller waited for the device to be idle.
            unsafe {
                device.destroy_semaphore(f.image_available, None);
                device.destroy_semaphore(f.render_finished, None);
                device.destroy_fence(f.fence, None);
                device.destroy_command_pool(f.pool, None);
            }
        }
    }
}

/// A resource waiting for the GPU to stop using it.
pub enum Garbage {
    Buffer(Buffer),
    Image(Image),
    Pipeline(vk::Pipeline),
    Framebuffer(vk::Framebuffer),
}

#[derive(Default)]
pub struct DeletionQueue {
    pending: Vec<(u64, Garbage)>,
    pub destroyed: u64,
}

impl DeletionQueue {
    /// `last_used` is the number of the last frame that may use the resource.
    pub fn push(&mut self, last_used: u64, garbage: Garbage) {
        self.pending.push((last_used, garbage));
    }

    pub fn len(&self) -> usize {
        self.pending.len()
    }

    pub fn is_empty(&self) -> bool {
        self.pending.is_empty()
    }

    /// Destroys everything whose last frame is complete.
    pub fn collect(&mut self, completed: u64, device: &ash::Device, allocator: &mut Allocator) {
        let mut i = 0;
        while i < self.pending.len() {
            if self.pending[i].0 <= completed {
                let (_, garbage) = self.pending.swap_remove(i);
                destroy(garbage, device, allocator);
                self.destroyed += 1;
            } else {
                i += 1;
            }
        }
    }

    /// Destroys everything. Only after `device_wait_idle`.
    pub fn flush(&mut self, device: &ash::Device, allocator: &mut Allocator) {
        self.collect(u64::MAX, device, allocator);
    }
}

fn destroy(garbage: Garbage, device: &ash::Device, allocator: &mut Allocator) {
    match garbage {
        Garbage::Buffer(b) => b.destroy(device, allocator),
        Garbage::Image(i) => i.destroy(device, allocator),
        // SAFETY: the frames that used them are complete.
        Garbage::Pipeline(p) => unsafe { device.destroy_pipeline(p, None) },
        Garbage::Framebuffer(f) => unsafe { device.destroy_framebuffer(f, None) },
    }
}
