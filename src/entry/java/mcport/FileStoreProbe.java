package mcport;

import com.google.common.reflect.TypeToken;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Paths;
import mcport.rt.fs.BrowserFileStore;

/** Small build-time probe for the browser JDK/nio compatibility shims. */
public final class FileStoreProbe {
    public static void main(String[] args) throws Exception {
        FileStore direct = BrowserFileStore.getFileStore(Paths.get("/minecraft"));
        System.out.println("direct=" + direct.name() + " usable=" + direct.getUsableSpace());
        FileStore viaFiles = Files.getFileStore(Paths.get("/minecraft"));
        System.out.println("files=" + viaFiles.name() + " usable=" + viaFiles.getUsableSpace());
        FileStore link = net.minecraft.server.packs.linkfs.LinkFSProbe.make();
        System.out.println("link=" + link.name() + " readonly=" + link.isReadOnly());
        TypeToken<?> token = new TypeToken<com.mojang.datafixers.optics.profunctors.Cocartesian.Mu>() {
        };
        System.out.println("typeToken=" + token.getType() + " raw=" + token.getRawType().getName());
        System.out.println("nestedTypeToken=" + Mu.TOKEN.getType() + " raw=" + Mu.TOKEN.getRawType().getName());
        System.out.println("PROBE_OK");
    }

    /** Mirrors DFU's {@code Cocartesian.Mu}: a TypeToken anonymous class declared inside its own type. */
    static final class Mu {
        static final TypeToken<Mu> TOKEN = new TypeToken<Mu>() {
        };
    }
}
