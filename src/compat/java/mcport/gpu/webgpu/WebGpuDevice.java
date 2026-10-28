package mcport.gpu.webgpu;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WebGpuDevice implements GpuDeviceBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebGpuDevice.class);
    private final WebGpuCommandEncoder encoder = new WebGpuCommandEncoder();
    private final DeviceInfo info;

    WebGpuDevice() {
        String json = WG.info();
        String name = field(json, "name");
        String vendor = field(json, "vendor");
        int maxTex = Integer.parseInt(field(json, "maxTextureSize"));
        int uboAlign = Integer.parseInt(field(json, "minUniformOffsetAlignment"));
        long maxBuffer = (long) Double.parseDouble(field(json, "maxBufferSize"));
        int maxColor = Integer.parseInt(field(json, "maxColorAttachments"));
        boolean fallback = "true".equals(field(json, "fallback"));
        Set<String> features = new LinkedHashSet<>(Arrays.asList(field(json, "features").replace("[", "").replace("]", "")
                .replace("\"", "").split(",")));
        features.remove("");
        info = new DeviceInfo(name, vendor, "WebGPU (" + browser() + ")", true, "WebGPU", 1.0f,
                new DeviceLimits(16, uboAlign, maxTex, maxBuffer, 0, maxColor, 0),
                new DeviceFeatures(false, false, false, true, false, false, true, false),
                features,
                new HintsAndWorkarounds(false, false, false, true),
                fallback ? DeviceType.CPU : DeviceType.OTHER);
        LOGGER.info("WebGPU device: {} ({}), max texture {}, features {}", name, vendor, maxTex, features);
    }

    @org.teavm.jso.JSBody(script = "return navigator.userAgent;")
    private static native String browser();

    /** Tiny extractor for the flat JSON object returned by __mcWebGPU.info(). */
    private static String field(String json, String key) {
        int i = json.indexOf("\"" + key + "\":");
        if (i < 0) {
            return "";
        }
        i += key.length() + 3;
        if (json.charAt(i) == '"') {
            int e = json.indexOf('"', i + 1);
            return json.substring(i + 1, e);
        }
        if (json.charAt(i) == '[') {
            return json.substring(i, json.indexOf(']', i) + 1);
        }
        int e = i;
        while (e < json.length() && ",}".indexOf(json.charAt(e)) < 0) {
            e++;
        }
        return json.substring(i, e);
    }

    @Override
    public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
        return new WebGpuSurface(encoder);
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return encoder;
    }

    @Override
    public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy,
            OptionalDouble maxLod) {
        return new WebGpuSampler(u, v, min, mag, maxAnisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(String label, int usage, GpuFormat format, int width, int height, int depthOrLayers,
            int mipLevels) {
        return new WebGpuTexture(usage, label == null ? "texture" : label, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        return new WebGpuTextureView((WebGpuTexture) texture, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
        return new WebGpuBuffer(usage, size, label == null ? null : label.get());
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer data) {
        WebGpuBuffer buffer = new WebGpuBuffer(usage, data.remaining(), label == null ? null : label.get());
        encoder.writeToBuffer(buffer.slice(), data);
        return buffer;
    }

    @Override
    public List<String> getLastDebugMessages() {
        String errors = WG.drainErrors();
        return errors.isEmpty() ? List.of() : new ArrayList<>(Arrays.asList(errors.split("\n")));
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    @Override
    public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo createInfo) {
        WebGpuPipeline pipeline = new WebGpuPipeline(createInfo);
        String error = pipeline.error();
        if (!error.isEmpty()) {
            LOGGER.warn("Pipeline {} could not be translated to WebGPU: {}", createInfo.name(), error);
        }
        return () -> pipeline;
    }

    @Override
    public void close() {
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(int size) {
        return new WebGpuQueryPool(size);
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return info;
    }
}
