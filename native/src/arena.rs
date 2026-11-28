//! Per-section GPU vertex/index buffers for the native client.
//!
//! The native world is generated once at startup, so this is the simple upload
//! path: every visible section gets its own buffers and the draw loop binds
//! them directly. The browser body's incremental editor path can be added later
//! without changing the vertex format.

use crate::mesh::Vertex;

pub struct SectionMeshData {
    pub key: (i32, i32, i32),
    pub vertices: Vec<Vertex>,
    pub indices: Vec<u32>,
    pub opaque_count: u32,
    pub min: [f32; 3],
    pub max: [f32; 3],
}

pub struct SectionSlot {
    pub key: (i32, i32, i32),
    pub vertex_buffer: wgpu::Buffer,
    pub index_buffer: wgpu::Buffer,
    pub index_count: u32,
    pub opaque_index_count: u32,
    pub vertex_count: u32,
    pub min: [f32; 3],
    pub max: [f32; 3],
}

pub struct ChunkArena {
    pub slots: Vec<SectionSlot>,
    pub total_indices: u32,
    pub total_vertices: u32,
}

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
            let vertex_bytes = bytes_of(&section.vertices);
            let index_bytes = bytes_of(&section.indices);
            let vertex_buffer = device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("mc-native/section.vertices"),
                size: (vertex_bytes.len() as u64).max(4),
                usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
                mapped_at_creation: false,
            });
            let index_buffer = device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("mc-native/section.indices"),
                size: (index_bytes.len() as u64).max(4),
                usage: wgpu::BufferUsages::INDEX | wgpu::BufferUsages::COPY_DST,
                mapped_at_creation: false,
            });
            queue.write_buffer(&vertex_buffer, 0, vertex_bytes);
            queue.write_buffer(&index_buffer, 0, index_bytes);
            arena.total_indices += section.indices.len() as u32;
            arena.total_vertices += section.vertices.len() as u32;
            arena.slots.push(SectionSlot {
                key: section.key,
                vertex_buffer,
                index_buffer,
                index_count: section.indices.len() as u32,
                opaque_index_count: section.opaque_count,
                vertex_count: section.vertices.len() as u32,
                min: section.min,
                max: section.max,
            });
        }
        arena
    }

    pub fn len(&self) -> usize {
        self.slots.len()
    }
}

fn bytes_of<T>(slice: &[T]) -> &[u8] {
    unsafe { std::slice::from_raw_parts(slice.as_ptr().cast::<u8>(), std::mem::size_of_val(slice)) }
}
