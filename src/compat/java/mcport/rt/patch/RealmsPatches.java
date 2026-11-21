package mcport.rt.patch;

import java.util.concurrent.CompletableFuture;

import com.mojang.realmsclient.RealmsAvailability;

/**
 * Browser-specific Realms availability.
 *
 * <p>Vanilla probes the Realms HTTPS API as soon as the title screen is built.
 * {@code RealmsClient} opens the connection through {@code URL.openConnection(Proxy)},
 * an overload TeaVM's class library does not provide. The availability future therefore
 * completes exceptionally with {@link NoSuchMethodError}; {@code RealmsNotificationsScreen}
 * later rethrows it from {@code getNow(false)} as a {@link java.util.concurrent.CompletionException}
 * during {@code TitleScreen.tick}/{@code extractRenderState}, which crashes the render thread.</p>
 *
 * <p>The browser port has no Mojang session or Realms account, so report the same state as
 * an unauthenticated client without touching the network.</p>
 */
public final class RealmsPatches {
    private RealmsPatches() {
    }

    /** Body for {@code RealmsAvailability.check()}. */
    public static CompletableFuture<RealmsAvailability.Result> check() {
        return CompletableFuture.completedFuture(
                new RealmsAvailability.Result(RealmsAvailability.Type.AUTHENTICATION_ERROR));
    }
}
