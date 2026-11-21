package mcport.rt.patch;

import java.util.List;

import net.minecraft.client.gui.Gui;

/**
 * Browser-specific GUI bootstrap behaviour.
 *
 * <p>A browser smoke run always starts with a fresh profile, so
 * {@code Options.onboardAccessibility} is still at its first-launch default. Vanilla then
 * puts {@code AccessibilityOnboardingScreen} in front of the title screen and waits for a
 * narrator prompt that a browser cannot answer. The smoke world is only allowed to start
 * from {@code TitleScreen}, so the client would otherwise sit on the onboarding screen
 * forever. Browsers have no narrator; skip the onboarding stack.</p>
 */
public final class GuiPatches {
    private GuiPatches() {
    }

    /**
     * Body for {@code Gui.addInitialScreens(List)}. BODY instance replacements receive the
     * original {@code Gui} receiver first. Returning {@code false} also skips offline-mode
     * ban notices, which cannot apply without a Mojang account.
     */
    public static boolean addInitialScreens(Gui gui, List<?> screens) {
        return false;
    }
}
