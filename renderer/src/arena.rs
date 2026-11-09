//! Per-section GPU vertex/index buffers.
//!
//! Each 16³ section owns its own vertex/index buffer. When the Brain uploads a
//! new section, only that section (and its six neighbours, for border faces) is
//! re-meshed; the render loop does that work time-sliced, a couple of sections
//! per frame, so a block edit never rebuilds the whole world.

use std::collections::HashMap;

use crate::bridge::SectionMeshData;
use crate::mesh::Vertex;

pub struct SectionSlot {
    pub key: (i32, i32, i32),
    pub vertex_buffer: wgpu::Buffer,
    pub index_buffer: wgpu::Buffer,
    pub index_count: u32,
    pub vertex_count: u32,
    pub min: [f32; 3],
    pub max: [f32; 3],
    vertex_capacity: u64,
    index_capacity: u64,
}

pub struct ChunkArena {
    pub slots: Vec<SectionSlot>,
    pub total_indices: u32,
    pub total_vertices: u32,
}

const MIN_BUFFER: u64 = 4;

impl ChunkArena {
    pub fn upload(
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        sections: &[SectionMeshData],
    ) -> Self {
        let mut arena = Self {
            slots: Vec::with_capacity(sections.len()),
            total_indices: 0,
            total_vertices: 0,
        };
        for section in sections {
            if section.indices.is_empty() {
                continue;
            }
            let slot = create_slot(device, queue, section.key, section);
            arena.total_indices += slot.index_count;
            arena.total_vertices += slot.vertex_count;
            arena.slots.push(slot);
        }
        arena
    }

    /// Applies a remeshed section. Existing buffers are overwritten in place
    /// when they are large enough; otherwise the slot is replaced (rare: only
    /// when a section gains geometry).
    pub fn apply(
        &mut self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        key: (i32, i32, i32),
        mesh: &SectionMeshData,
    ) {
        if let Some(index) = self.slots.iter().position(|slot| slot.key == key) {
            let fits = {
                let slot = &self.slots[index];
                bytes_of(&mesh.vertices).len() as u64 <= slot.vertex_capacity
                    && bytes_of(&mesh.indices).len() as u64 <= slot.index_capacity
            };
            if fits {
                let slot = &mut self.slots[index];
                self.total_indices -= slot.index_count;
                self.total_vertices -= slot.vertex_count;
                if mesh.indices.is_empty() {
                    slot.index_count = 0;
                    slot.vertex_count = 0;
                } else {
                    queue.write_buffer(&slot.vertex_buffer, 0, bytes_of(&mesh.vertices));
                    queue.write_buffer(&slot.index_buffer, 0, bytes_of(&mesh.indices));
                    slot.index_count = mesh.indices.len() as u32;
                    slot.vertex_count = mesh.vertices.len() as u32;
                    self.total_indices += slot.index_count;
                    self.total_vertices += slot.vertex_count;
                }
                slot.min = mesh.min;
                slot.max = mesh.max;
                return;
            }
            self.total_indices -= self.slots[index].index_count;
            self.total_vertices -= self.slots[index].vertex_count;
            self.slots.remove(index);
        }

        if mesh.indices.is_empty() {
            return;
        }
        let slot = create_slot(device, queue, key, mesh);
        self.total_indices += slot.index_count;
        self.total_vertices += slot.vertex_count;
        self.slots.push(slot);
    }

    pub fn len(&self) -> usize {
        self.slots.len()
    }

    /// Drops every draw range. Used when the Java Brain replaces the synthetic
    /// demo world; the dirty queue then rebuilds the real sections.
    pub fn clear(&mut self) {
        self.slots.clear();
        self.total_indices = 0;
        self.total_vertices = 0;
    }
}

fn create_slot(
    device: &wgpu::Device,
    queue: &wgpu::Queue,
    key: (i32, i32, i32),
    mesh: &SectionMeshData,
) -> SectionSlot {
    let vertex_bytes = bytes_of(&mesh.vertices);
    let index_bytes = bytes_of(&mesh.indices);
    let vertex_capacity = capacity(vertex_bytes.len() as u64);
    let index_capacity = capacity(index_bytes.len() as u64);

    let vertex_buffer = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("mc-body/section.vertices"),
        size: vertex_capacity.max(MIN_BUFFER),
        usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    let index_buffer = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("mc-body/section.indices"),
        size: index_capacity.max(MIN_BUFFER),
        usage: wgpu::BufferUsages::INDEX | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    if !vertex_bytes.is_empty() {
        queue.write_buffer(&vertex_buffer, 0, vertex_bytes);
        queue.write_buffer(&index_buffer, 0, index_bytes);
    }

    SectionSlot {
        key,
        vertex_buffer,
        index_buffer,
        index_count: mesh.indices.len() as u32,
        vertex_count: mesh.vertices.len() as u32,
        min: mesh.min,
        max: mesh.max,
        vertex_capacity,
        index_capacity,
    }
}

fn capacity(bytes: u64) -> u64 {
    // Round up to 256 bytes so small edits do not keep reallocating.
    let rounded = ((bytes + 255) / 256) * 256;
    rounded.max(256)
}

fn bytes_of<T>(slice: &[T]) -> &[u8] {
    unsafe { std::slice::from_raw_parts(slice.as_ptr().cast::<u8>(), std::mem::size_of_val(slice)) }
}

#[allow(dead_code)]
pub fn vertex_size() -> usize {
    std::mem::size_of::<Vertex>()
}
