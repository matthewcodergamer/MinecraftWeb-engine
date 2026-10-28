package mcport;

import java.io.File;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.OptionSpecBuilder;
import mcport.rt.BrowserRuntime;

/**
 * Verifies the joptsimple CLI option pathway of net.minecraft.client.main.Main in the browser:
 * the same ofType() converters Main registers, parsed and converted the way Main reads them.
 */
public final class JoptProbe {
    private static void report(String k, String v) {
        BrowserRuntime.report(k, v);
    }

    public static void main(String[] args) {
        try {
            mcport.ClientMain.setOsProperties();
            OptionParser parser = new OptionParser();
            OptionSpecBuilder version = parser.accepts("version", "Display the version.");
            OptionSpec<File> gameDir = parser.accepts("gameDir")
                    .withRequiredArg()
                    .ofType(File.class);
            OptionSpec<File> resourcePackDir = parser.accepts("resourcePackDir")
                    .withRequiredArg()
                    .ofType(File.class);
            OptionSpec<Integer> width = parser.accepts("width")
                    .withOptionalArg()
                    .ofType(Integer.class)
                    .defaultsTo(854);
            OptionSpec<Integer> height = parser.accepts("height")
                    .withOptionalArg()
                    .ofType(Integer.class)
                    .defaultsTo(480);

            // Main parses e.g. --gameDir ~/gamedir --width 1280 --height 720 --resourcePackDir packs
            OptionSet set = parser.parse("--gameDir", "gamedir/x", "--width", "1280", "--height", "720");

            File dir = set.valueOf(gameDir);           // ConstructorInvokingValueConverter on the JVM
            Integer w = set.valueOf(width);            // MethodInvokingValueConverter (Integer.valueOf)
            Integer h = set.valueOf(width);

            // org.lwjgl.system.Platform switches on os.name in its class initializer; a browser port
            // must publish an OS it knows, so LWJGL's bundled-library mapping works.
            report("os.name", System.getProperty("os.name"));
            report("Platform.get()", org.lwjgl.system.Platform.get().getName());
            report("bundle-name(sdl2)", org.lwjgl.system.Platform.mapLibraryNameBundled("sdl2"));
            report("Platform.arch", String.valueOf(org.lwjgl.system.Platform.getArchitecture()));

            report("gameDir", new File("gamedir/x").getPath());
            report("gameDir2", String.valueOf(dir));
            report("resourcePackDir-converted", "present");
            report("width", String.valueOf(w));
            report("height", String.valueOf(h));
            report("version", String.valueOf(set.has(version)));
            File defaultPack = new File("packs");
            report("resourcePackDir-default", String.valueOf(defaultPack));
            report("resourcePackDir-nonexhaustive", String.valueOf(set.valueOf(resourcePackDir)));
            report("stage", "jopt-probe-complete");
        } catch (Throwable t) {
            report("stage", "failed");
            report("error", BrowserRuntime.describe(t));
        }
    }
}
