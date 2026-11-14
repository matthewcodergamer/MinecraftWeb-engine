package mcport.rt.patch;

import java.net.Proxy;

import com.mojang.authlib.Environment;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import com.mojang.authlib.services.MinecraftServicesUserApiService;

/**
 * Browser-side authlib policy: the game always runs with the offline discovery service
 * and authlib's offline user API. The browser has no Microsoft/Mojang session, so the
 * services user API would open (and fail) HTTPS requests from a worker thread.
 */
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

    /**
     * Minecraft.createUserApiService: the constructor stores this and immediately starts
     * an async property fetch. Returning authlib's OFFLINE implementation keeps the whole
     * user-API surface (properties, telemetry, reporting, key pairs, block lists) local.
     */
    public static UserApiService createOfflineUserApiService(MinecraftServicesDiscoveryService discoveryService,
            String accessToken) {
        return UserApiService.OFFLINE;
    }

    /**
     * Belt-and-braces: even if a services user API is constructed by other code, property
     * fetching must never perform a network request in the browser.
     */
    public static UserApiService.UserProperties offlineProperties(MinecraftServicesUserApiService userApiService) {
        return UserApiService.OFFLINE_PROPERTIES;
    }
}
