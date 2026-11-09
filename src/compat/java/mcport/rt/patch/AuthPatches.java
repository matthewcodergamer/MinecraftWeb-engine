package mcport.rt.patch;

import java.net.Proxy;

import com.mojang.authlib.Environment;
import com.mojang.authlib.services.MinecraftServicesDiscoveryService;

/** Browser-side authlib policy: the game always runs with the offline discovery service. */
public final class AuthPatches {
    private AuthPatches() {
    }

    public static MinecraftServicesDiscoveryService createOffline(Proxy proxy) {
        return MinecraftServicesDiscoveryService.createOffline(proxy);
    }

    public static MinecraftServicesDiscoveryService createOffline(Proxy proxy, boolean servicesKeySetEnabled) {
        return MinecraftServicesDiscoveryService.createOffline(proxy);
    }

    public static MinecraftServicesDiscoveryService createOfflineWithEnv(Proxy proxy, boolean servicesKeySetEnabled,
            Environment environment) {
        return MinecraftServicesDiscoveryService.createOffline(proxy);
    }
}
