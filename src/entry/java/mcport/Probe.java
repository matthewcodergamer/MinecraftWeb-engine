package mcport;

import com.google.common.collect.MapMaker;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentMap;
import mcport.rt.BrowserRuntime;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/** Small, fast-building experiments used to isolate runtime problems. */
public final class Probe {
    public static void main(String[] args) {
        try {
            Object o = new Object();
            WeakReference<Object> ref = new WeakReference<>(o);
            BrowserRuntime.report("weakref.get", String.valueOf(ref.get() == o));

            ConcurrentMap<String, String> weak = new MapMaker().weakValues().makeMap();
            String v = new String("value");
            weak.put("k", v);
            BrowserRuntime.report("mapmaker.weak.get", String.valueOf(weak.get("k")));
            BrowserRuntime.report("mapmaker.weak.computeIfAbsent",
                    String.valueOf(weak.computeIfAbsent("k2", k -> new String("computed"))));

            ConcurrentMap<String, String> strong = new MapMaker().makeMap();
            BrowserRuntime.report("mapmaker.strong.computeIfAbsent",
                    String.valueOf(strong.computeIfAbsent("k", k -> "computed")));

            ResourceKey<?> key = ResourceKey.createRegistryKey(Identifier.withDefaultNamespace("game_event"));
            BrowserRuntime.report("ResourceKey.createRegistryKey", String.valueOf(key));
        } catch (Throwable t) {
            BrowserRuntime.report("error", BrowserRuntime.describe(t));
        }
    }
}
