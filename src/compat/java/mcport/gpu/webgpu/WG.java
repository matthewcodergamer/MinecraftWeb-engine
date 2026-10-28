package mcport.gpu.webgpu;

import org.teavm.jso.JSBody;

/** Bindings to web/gpu/webgpu.js (__mcWebGPU). Handles are ints; addresses are native-heap offsets. */
final class WG {
    private WG() {
    }

    @JSBody(script = "return !!(window.__mcGPU && __mcGPU.ready && __mcGPU.api === 'WebGPU');")
    static native boolean available();

    @JSBody(script = "return __mcGPU.info();")
    static native String info();

    @JSBody(params = {"size", "usage", "label"}, script = "return __mcGPU.createBuffer(size, usage, label);")
    static native int createBuffer(double size, int usage, String label);

    @JSBody(params = "id", script = "__mcGPU.destroyBuffer(id);")
    static native void destroyBuffer(int id);

    @JSBody(params = "id", script = "return __mcGPU.mapBuffer(id);")
    static native int mapBuffer(int id);

    @JSBody(params = {"id", "offset", "length", "written"}, script = "__mcGPU.unmapBuffer(id, offset, length, written);")
    static native void unmapBuffer(int id, double offset, double length, boolean written);

    @JSBody(params = {"dst", "dstOffset", "src", "size"}, script = "__mcGPU.writeBufferStaged(dst, dstOffset, src, size);")
    static native void writeBuffer(int dst, double dstOffset, int src, int size);

    @JSBody(params = {"src", "srcOff", "dst", "dstOff", "size"}, script = "__mcGPU.copyBuffer(src, srcOff, dst, dstOff, size);")
    static native void copyBuffer(int src, double srcOff, int dst, double dstOff, double size);

    @JSBody(params = {"size", "alignment"}, script = "return __mcGPU.transientAlloc(size, alignment);")
    static native int transientAlloc(double size, double alignment);

    @JSBody(script = "return __mcGPU.lastTransientId();")
    static native int lastTransientId();

    @JSBody(script = "return __mcGPU.lastTransientAddress();")
    static native int lastTransientAddress();

    @JSBody(params = {"label", "usage", "format", "w", "h", "layers", "mips"},
            script = "return __mcGPU.createTexture(label, usage, format, w, h, layers, mips);")
    static native int createTexture(String label, int usage, int format, int w, int h, int layers, int mips);

    @JSBody(params = "id", script = "__mcGPU.destroyTexture(id);")
    static native void destroyTexture(int id);

    @JSBody(params = {"tex", "baseMip", "mips"}, script = "return __mcGPU.createView(tex, baseMip, mips);")
    static native int createView(int tex, int baseMip, int mips);

    @JSBody(params = "id", script = "__mcGPU.destroyView(id);")
    static native void destroyView(int id);

    @JSBody(params = {"u", "v", "min", "mag", "aniso", "maxLod"},
            script = "return __mcGPU.createSampler(u, v, min, mag, aniso, maxLod);")
    static native int createSampler(int u, int v, int min, int mag, int aniso, double maxLod);

    @JSBody(params = "id", script = "__mcGPU.destroySampler(id);")
    static native void destroySampler(int id);

    @JSBody(params = {"tex", "src", "bytes", "mip", "x", "y", "w", "h", "layer"},
            script = "__mcGPU.writeTexture(tex, src, bytes, mip, x, y, w, h, layer, 0);")
    static native void writeTexture(int tex, int src, int bytes, int mip, int x, int y, int w, int h, int layer);

    @JSBody(params = {"buf", "off", "rowLen", "rows", "tex", "mip", "x", "y", "w", "h", "layer"},
            script = "__mcGPU.copyBufferToTexture(buf, off, rowLen, rows, tex, mip, x, y, w, h, layer);")
    static native void copyBufferToTexture(int buf, double off, int rowLen, int rows, int tex, int mip, int x, int y, int w,
            int h, int layer);

    @JSBody(params = {"tex", "buf", "off", "mip", "x", "y", "w", "h", "cb"},
            script = "__mcGPU.copyTextureToBuffer(tex, buf, off, mip, x, y, w, h, cb);")
    static native void copyTextureToBuffer(int tex, int buf, double off, int mip, int x, int y, int w, int h, int cb);

    @JSBody(params = {"src", "dst", "mip", "dx", "dy", "sx", "sy", "w", "h"},
            script = "__mcGPU.copyTextureToTexture(src, dst, mip, dx, dy, sx, sy, w, h);")
    static native void copyTextureToTexture(int src, int dst, int mip, int dx, int dy, int sx, int sy, int w, int h);

    @JSBody(script = "return __mcGPU.pollReadback();")
    static native int pollReadback();

    @JSBody(params = {"tex", "r", "g", "b", "a"}, script = "__mcGPU.clearColor(tex, r, g, b, a);")
    static native void clearColor(int tex, float r, float g, float b, float a);

    @JSBody(params = {"tex", "d"}, script = "__mcGPU.clearDepth(tex, d);")
    static native void clearDepth(int tex, double d);

    @JSBody(params = {"c", "r", "g", "b", "a", "d", "depth", "x", "y", "w", "h"},
            script = "__mcGPU.clearColorDepthRegion(c, r, g, b, a, d, depth, x, y, w, h);")
    static native void clearColorDepthRegion(int c, float r, float g, float b, float a, int d, double depth, int x, int y,
            int w, int h);

    @JSBody(params = "json", script = "return __mcGPU.createPipeline(json);")
    static native int createPipeline(String json);

    @JSBody(params = "id", script = "return __mcGPU.pipelineError(id);")
    static native String pipelineError(int id);

    @JSBody(params = "id", script = "__mcGPU.destroyPipeline(id);")
    static native void destroyPipeline(int id);

    @JSBody(params = "json", script = "__mcGPU.beginPass(json);")
    static native void beginPass(String json);

    @JSBody(script = "__mcGPU.endPass();")
    static native void endPass();

    @JSBody(params = "id", script = "__mcGPU.setPipeline(id);")
    static native void setPipeline(int id);

    @JSBody(params = "count", script = "__mcGPU.clearUniforms(count);")
    static native void clearUniforms(int count);

    @JSBody(params = {"i", "buf", "off", "size"}, script = "__mcGPU.setUniformBuffer(i, buf, off, size);")
    static native void setUniformBuffer(int i, int buf, double off, double size);

    @JSBody(params = {"i", "view", "sampler"}, script = "__mcGPU.setUniformTexture(i, view, sampler);")
    static native void setUniformTexture(int i, int view, int sampler);

    @JSBody(params = {"slot", "buf", "off"}, script = "__mcGPU.setVertexBuffer(slot, buf, off);")
    static native void setVertexBuffer(int slot, int buf, double off);

    @JSBody(params = {"buf", "is32"}, script = "__mcGPU.setIndexBuffer(buf, is32);")
    static native void setIndexBuffer(int buf, boolean is32);

    @JSBody(params = {"on", "x", "y", "w", "h"}, script = "__mcGPU.setScissor(on, x, y, w, h);")
    static native void setScissor(boolean on, int x, int y, int w, int h);

    @JSBody(params = {"n", "inst", "first", "firstInst"}, script = "__mcGPU.draw(n, inst, first, firstInst);")
    static native void draw(int n, int inst, int first, int firstInst);

    @JSBody(params = {"n", "inst", "first", "base", "firstInst"},
            script = "__mcGPU.drawIndexed(n, inst, first, base, firstInst);")
    static native void drawIndexed(int n, int inst, int first, int base, int firstInst);

    @JSBody(params = {"addr", "count"}, script = "__mcGPU.multiDrawIndexed(addr, count);")
    static native void multiDrawIndexed(int addr, int count);

    @JSBody(params = {"buf", "off", "count"}, script = "__mcGPU.drawIndexedIndirect(buf, off, count);")
    static native void drawIndexedIndirect(int buf, double off, int count);

    @JSBody(params = {"buf", "off", "count"}, script = "__mcGPU.drawIndirect(buf, off, count);")
    static native void drawIndirect(int buf, double off, int count);

    @JSBody(script = "__mcGPU.submit();")
    static native void submit();

    @JSBody(script = "return __mcGPU.createFence();")
    static native int createFence();

    @JSBody(params = "id", script = "return __mcGPU.fenceDone(id);")
    static native boolean fenceDone(int id);

    @JSBody(params = "id", script = "__mcGPU.destroyFence(id);")
    static native void destroyFence(int id);

    @JSBody(params = {"w", "h", "vsync"}, script = "return __mcGPU.configureSurface(w, h, vsync);")
    static native boolean configureSurface(int w, int h, boolean vsync);

    @JSBody(params = "view", script = "__mcGPU.blitToSurface(view);")
    static native void blitToSurface(int view);

    @JSBody(script = "__mcGPU.present();")
    static native void present();

    @JSBody(script = "var e = __mcGPU.errors.splice(0); return e.join('\\n');")
    static native String drainErrors();
}
