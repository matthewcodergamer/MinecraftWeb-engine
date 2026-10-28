package mcport.gpu.webgpu;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.Supplier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/** One GPURenderPassEncoder (begun by WebGpuCommandEncoder.createRenderPass). */
class WebGpuRenderPass implements RenderPassBackend {
    private WebGpuPipeline pipeline;
    private IndexType indexType = IndexType.INT;
    private long drawScratch;
    private int drawScratchSize;

    @Override
    public void pushDebugGroup(Supplier<String> label) {
    }

    @Override
    public void popDebugGroup() {
    }

    @Override
    public void setPipeline(BackendRenderPipeline p) {
        WebGpuPipeline next = (WebGpuPipeline) p;
        if (next != pipeline) {
            WG.setPipeline(next.id);
            WG.clearUniforms(next.uniformCount);
            pipeline = next;
        }
    }

    @Override
    public void setUniform(int index, Object value) {
        if (value instanceof GpuBufferSlice) {
            GpuBufferSlice s = (GpuBufferSlice) value;
            WG.setUniformBuffer(index, ((WebGpuBuffer) s.buffer()).id, s.offset(), s.length());
        } else if (value instanceof TextureViewAndSampler) {
            TextureViewAndSampler t = (TextureViewAndSampler) value;
            WG.setUniformTexture(index, ((WebGpuTextureView) t.view()).id, ((WebGpuSampler) t.sampler()).id);
        }
    }

    @Override
    public void pushConstants(ByteBuffer data) {
        // renderpearl's GLSL 330 shaders declare no push-constant blocks
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        WG.setScissor(true, x, y, width, height);
    }

    @Override
    public void disableScissor() {
        WG.setScissor(false, 0, 0, 0, 0);
    }

    @Override
    public void setVertexBuffer(int slot, GpuBufferSlice slice) {
        if (slice == null) {
            WG.setVertexBuffer(slot, 0, 0);
        } else {
            WG.setVertexBuffer(slot, ((WebGpuBuffer) slice.buffer()).id, slice.offset());
        }
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
        indexType = type;
        WG.setIndexBuffer(buffer == null ? 0 : ((WebGpuBuffer) buffer).id, type == IndexType.INT);
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        WG.drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException("WebGPU backend does not report multiDrawDirectInterleaved");
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
        int bytes = drawCount * 12;
        if (bytes > drawScratchSize) {
            if (drawScratch != 0) {
                MemoryUtil.nmemFree(drawScratch);
            }
            drawScratchSize = Math.max(bytes, 4096);
            drawScratch = MemoryUtil.nmemAlloc(drawScratchSize);
        }
        int indexSize = indexType == IndexType.INT ? 4 : 2;
        int base = firstIndexOffsets.position();
        int ib = indexCounts.position();
        int vb = vertexOffsets.position();
        for (int i = 0; i < drawCount; i++) {
            long p = drawScratch + i * 12L;
            MemoryUtil.memPutInt(p, (int) (firstIndexOffsets.get(base + i) / indexSize));
            MemoryUtil.memPutInt(p + 4, indexCounts.get(ib + i));
            MemoryUtil.memPutInt(p + 8, vertexOffsets.get(vb + i));
        }
        WG.multiDrawIndexed((int) drawScratch, drawCount);
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        WG.drawIndexedIndirect(((WebGpuBuffer) commands.buffer()).id, commands.offset(), drawCount);
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        WG.draw(vertexCount, instanceCount, firstVertex, firstInstance);
    }

    @Override
    public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException("WebGPU backend does not report multiDrawDirectInterleaved");
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        for (int i = 0; i < drawCount; i++) {
            WG.draw(vertexCounts.get(vertexCounts.position() + i), 1, firstVertices.get(firstVertices.position() + i), 0);
        }
    }

    @Override
    public void drawIndirect(GpuBufferSlice commands, int drawCount) {
        WG.drawIndirect(((WebGpuBuffer) commands.buffer()).id, commands.offset(), drawCount);
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }
}
