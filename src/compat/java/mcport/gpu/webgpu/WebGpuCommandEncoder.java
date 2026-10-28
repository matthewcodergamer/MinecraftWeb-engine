package mcport.gpu.webgpu;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

/** The device's command encoder: records into one GPUCommandEncoder, submitted by submit()/present. */
class WebGpuCommandEncoder implements CommandEncoderBackend {
    private final WebGpuTransientMemory transientMemory = new WebGpuTransientMemory();
    private final Int2ObjectOpenHashMap<Runnable> readbackCallbacks = new Int2ObjectOpenHashMap<>();
    private int nextCallback = 1;

    @Override
    public void submit() {
        WG.submit();
        transientMemory.reset();
        runCompletedReadbacks();
    }

    void runCompletedReadbacks() {
        int id;
        while ((id = WG.pollReadback()) != 0) {
            Runnable r = readbackCallbacks.remove(id);
            if (r != null) {
                r.run();
            }
        }
    }

    @Override
    public TransientMemory transientMemory() {
        return transientMemory;
    }

    @Override
    public RenderPassBackend createRenderPass(RenderPassDescriptor d) {
        Json j = new Json().obj().key("colors").arr();
        for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> a : d.colorAttachments()) {
            if (a == null) {
                j.nul();
                continue;
            }
            j.arr().val(((WebGpuTextureView) a.textureView()).id).val(0);
            if (a.clearValue().isPresent()) {
                Vector4fc c = a.clearValue().get();
                j.val(true).val(c.x()).val(c.y()).val(c.z()).val(c.w());
            } else {
                j.val(false);
            }
            j.endArr();
        }
        j.endArr().key("depth");
        RenderPassDescriptor.Attachment<OptionalDouble> depth = d.depthAttachment();
        if (depth == null) {
            j.nul();
        } else {
            j.arr().val(((WebGpuTextureView) depth.textureView()).id).val(depth.clearValue().isPresent())
                    .val(depth.clearValue().orElse(0)).endArr();
        }
        j.key("area").arr().val(d.renderArea().x()).val(d.renderArea().y()).val(d.renderArea().width())
                .val(d.renderArea().height()).endArr().endObj();
        WG.beginPass(j.toString());
        return new WebGpuRenderPass();
    }

    @Override
    public void submitRenderPass() {
        WG.endPass();
    }

    @Override
    public void clearColorTexture(GpuTexture texture, Vector4fc color) {
        WG.clearColor(((WebGpuTexture) texture).id, color.x(), color.y(), color.z(), color.w());
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture color, Vector4fc value, GpuTexture depth, double depthValue) {
        clearColorTexture(color, value);
        clearDepthTexture(depth, depthValue);
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture color, Vector4fc value, GpuTexture depth, double depthValue, int x, int y,
            int width, int height, int mipLevel) {
        WG.clearColorDepthRegion(((WebGpuTexture) color).id, value.x(), value.y(), value.z(), value.w(),
                ((WebGpuTexture) depth).id, depthValue, x, y, width, height);
    }

    @Override
    public void clearDepthTexture(GpuTexture texture, double depth) {
        WG.clearDepth(((WebGpuTexture) texture).id, depth);
    }

    /** Address of the data in the native heap (copied there first if it is a Java-heap buffer). */
    private static long heapAddress(ByteBuffer data, long[] temp) {
        if (data.isDirect()) {
            return MemoryUtil.memAddress(data);
        }
        long p = MemoryUtil.nmemAlloc(Math.max(1, data.remaining()));
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), p, data.remaining());
        temp[0] = p;
        return p;
    }

    @Override
    public void writeToBuffer(GpuBufferSlice slice, ByteBuffer data) {
        long[] temp = new long[1];
        long src = heapAddress(data, temp);
        WG.writeBuffer(((WebGpuBuffer) slice.buffer()).id, slice.offset(), (int) src, Math.min(data.remaining(), (int) slice.length()));
        if (temp[0] != 0) {
            MemoryUtil.nmemFree(temp[0]);
        }
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        WG.copyBuffer(((WebGpuBuffer) source.buffer()).id, source.offset(), ((WebGpuBuffer) target.buffer()).id, target.offset(),
                Math.min(source.length(), target.length()));
    }

    @Override
    public void writeToTexture(GpuTexture texture, ByteBuffer data, int mipLevel, int depthOrLayer, int x, int y, int width, int height) {
        long[] temp = new long[1];
        long src = heapAddress(data, temp);
        WG.writeTexture(((WebGpuTexture) texture).id, (int) src, data.remaining(), mipLevel, x, y, width, height, depthOrLayer);
        if (temp[0] != 0) {
            MemoryUtil.nmemFree(temp[0]);
        }
    }

    @Override
    public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight,
            GpuTexture destination, int destX, int destY, int copyWidth, int copyHeight, int mipLevel, int arrayLayer) {
        long skip = ((long) sourceX + (long) sourceY * sourceWidth) * destination.getFormat().blockSize();
        WG.copyBufferToTexture(((WebGpuBuffer) source.buffer()).id, source.offset() + skip, sourceWidth, sourceHeight,
                ((WebGpuTexture) destination).id, mipLevel, destX, destY, copyWidth, copyHeight, arrayLayer);
    }

    private int callback(Runnable r) {
        int id = nextCallback++;
        if (nextCallback == Integer.MAX_VALUE) {
            nextCallback = 1;
        }
        readbackCallbacks.put(id, r);
        return id;
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
        copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int x,
            int y, int width, int height) {
        WG.copyTextureToBuffer(((WebGpuTexture) source).id, ((WebGpuBuffer) destination).id, offset, mipLevel, x, y, width, height,
                callback(callback));
    }

    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY, int sourceX,
            int sourceY, int width, int height) {
        WG.copyTextureToTexture(((WebGpuTexture) source).id, ((WebGpuTexture) destination).id, mipLevel, destX, destY, sourceX,
                sourceY, width, height);
    }

    @Override
    public GpuFence createFence() {
        return new WebGpuFence();
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }
}
