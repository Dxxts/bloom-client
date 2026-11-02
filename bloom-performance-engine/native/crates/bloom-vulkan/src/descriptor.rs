//! Descriptor management: a cache of set layouts keyed by their bindings and
//! an allocator that grows by adding pools when one is exhausted.

use std::collections::HashMap;

use ash::vk;

use crate::Result;

/// `(binding, descriptor type, count, stage flags)`.
pub type BindingKey = (u32, i32, u32, u32);

#[derive(Default)]
pub struct LayoutCache {
    layouts: HashMap<Vec<BindingKey>, vk::DescriptorSetLayout>,
}

impl LayoutCache {
    pub fn get(&mut self, device: &ash::Device, bindings: &[vk::DescriptorSetLayoutBinding<'_>]) -> Result<vk::DescriptorSetLayout> {
        let mut key: Vec<BindingKey> =
            bindings.iter().map(|b| (b.binding, b.descriptor_type.as_raw(), b.descriptor_count, b.stage_flags.as_raw())).collect();
        key.sort_unstable();
        if let Some(&layout) = self.layouts.get(&key) {
            return Ok(layout);
        }
        let info = vk::DescriptorSetLayoutCreateInfo::default().bindings(bindings);
        // SAFETY: valid bindings.
        let layout = unsafe { device.create_descriptor_set_layout(&info, None) }?;
        self.layouts.insert(key, layout);
        Ok(layout)
    }

    pub fn len(&self) -> usize {
        self.layouts.len()
    }

    pub fn is_empty(&self) -> bool {
        self.layouts.is_empty()
    }

    pub fn destroy(&mut self, device: &ash::Device) {
        for (_, layout) in self.layouts.drain() {
            // SAFETY: no set using the layout is in use any more.
            unsafe { device.destroy_descriptor_set_layout(layout, None) };
        }
    }
}

pub struct DescriptorAllocator {
    pools: Vec<vk::DescriptorPool>,
    sets_per_pool: u32,
    pub sets_allocated: u64,
}

impl DescriptorAllocator {
    pub fn new(sets_per_pool: u32) -> Self {
        Self { pools: Vec::new(), sets_per_pool, sets_allocated: 0 }
    }

    fn new_pool(&mut self, device: &ash::Device) -> Result<vk::DescriptorPool> {
        let n = self.sets_per_pool;
        let sizes = [
            vk::DescriptorPoolSize { ty: vk::DescriptorType::UNIFORM_BUFFER, descriptor_count: n * 2 },
            vk::DescriptorPoolSize { ty: vk::DescriptorType::STORAGE_BUFFER, descriptor_count: n * 2 },
            vk::DescriptorPoolSize { ty: vk::DescriptorType::COMBINED_IMAGE_SAMPLER, descriptor_count: n * 2 },
        ];
        let info = vk::DescriptorPoolCreateInfo::default().max_sets(n).pool_sizes(&sizes);
        // SAFETY: valid create info.
        let pool = unsafe { device.create_descriptor_pool(&info, None) }?;
        self.pools.push(pool);
        Ok(pool)
    }

    pub fn allocate(&mut self, device: &ash::Device, layout: vk::DescriptorSetLayout) -> Result<vk::DescriptorSet> {
        let layouts = [layout];
        if let Some(&pool) = self.pools.last() {
            let info = vk::DescriptorSetAllocateInfo::default().descriptor_pool(pool).set_layouts(&layouts);
            // SAFETY: valid pool and layout.
            match unsafe { device.allocate_descriptor_sets(&info) } {
                Ok(sets) => {
                    self.sets_allocated += 1;
                    return Ok(sets[0]);
                }
                Err(vk::Result::ERROR_OUT_OF_POOL_MEMORY | vk::Result::ERROR_FRAGMENTED_POOL) => {}
                Err(e) => return Err(e.into()),
            }
        }
        let pool = self.new_pool(device)?;
        let info = vk::DescriptorSetAllocateInfo::default().descriptor_pool(pool).set_layouts(&layouts);
        // SAFETY: fresh pool sized for at least one set of every type.
        let sets = unsafe { device.allocate_descriptor_sets(&info) }?;
        self.sets_allocated += 1;
        Ok(sets[0])
    }

    pub fn pools(&self) -> usize {
        self.pools.len()
    }

    pub fn destroy(&mut self, device: &ash::Device) {
        for pool in self.pools.drain(..) {
            // SAFETY: the sets allocated from the pool are no longer in use.
            unsafe { device.destroy_descriptor_pool(pool, None) };
        }
    }
}
