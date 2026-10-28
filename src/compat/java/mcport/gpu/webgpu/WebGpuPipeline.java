package mcport.gpu.webgpu;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendEquation;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Render pipeline: SPIR-V from renderpearl's frontend (bindings already relocated by PipelineBuilder) is turned into
 * WGSL by web/native/shaders.js; GPURenderPipeline variants are created lazily per pass/texture configuration.
 */
class WebGpuPipeline implements BackendRenderPipeline {
    final int id;
    final int uniformCount;
    private boolean closed;

    WebGpuPipeline(CreateInfo info) {
        Json j = new Json().obj();
        j.key("name").val(info.name());
        j.key("shaders").arr();
        for (CreateInfo.Shader s : info.shaders()) {
            ByteBuffer spv = s.module().spv();
            j.obj().key("stage").val(s.module().type() == ShaderType.FRAGMENT ? "fragment" : "vertex");
            j.key("spv").arr().val(MemoryUtil.memAddress(spv)).val(spv.remaining()).endArr().endObj();
        }
        j.endArr();
        j.key("vertexBuffers").arr();
        for (CreateInfo.VertexBuffer vb : info.vertexBuffers()) {
            j.obj().key("slot").val(vb.bufferSlot()).key("stride").val(vb.stride()).key("step").val(vb.stepRate()).endObj();
        }
        j.endArr();
        j.key("attribs").arr();
        for (CreateInfo.AttribBinding a : info.attribBindings()) {
            j.obj().key("slot").val(a.bufferSlot()).key("location").val(a.location()).key("offset").val(a.offset())
                    .key("format").val(a.format().name()).endObj();
        }
        j.endArr();
        j.key("uniforms").arr();
        for (BindGroupLayout.UniformDescription u : info.uniforms()) {
            j.obj().key("name").val(u.name()).key("type").val(u.type().name())
                    .key("format").val(u.gpuFormat() == null ? null : u.gpuFormat().name()).endObj();
        }
        j.endArr();
        DepthStencilState d = info.depthStencilState();
        j.key("depth");
        if (d == null) {
            j.nul();
        } else {
            j.obj().key("compare").val(d.depthTest().ordinal()).key("write").val(d.writeDepth())
                    .key("biasScale").val(d.depthBiasScaleFactor()).key("biasConst").val(d.depthBiasConstant()).endObj();
        }
        j.key("cull").val(info.cull());
        j.key("targets").arr();
        for (ColorTargetState t : info.colorTargetStates()) {
            if (t == null) {
                j.nul();
                continue;
            }
            j.obj().key("format").val(t.format().ordinal()).key("mask").val(t.writeMask());
            j.key("blend");
            if (t.blendFunction().isPresent()) {
                BlendFunction b = t.blendFunction().get();
                j.obj().key("color");
                eq(j, b.color());
                j.key("alpha");
                eq(j, b.alpha());
                j.endObj();
            } else {
                j.nul();
            }
            j.endObj();
        }
        j.endArr();
        j.key("topology").val(info.primitiveTopology().name());
        j.endObj();
        this.id = WG.createPipeline(j.toString());
        this.uniformCount = info.uniforms().size();
    }

    private static void eq(Json j, BlendEquation e) {
        j.obj().key("src").val(e.sourceFactor().name()).key("dst").val(e.destFactor().name()).key("op").val(e.op().name()).endObj();
    }

    String error() {
        return WG.pipelineError(id);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            WG.destroyPipeline(id);
        }
    }
}
