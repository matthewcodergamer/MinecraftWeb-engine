package com.mojang.text2speech;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Browser replacement for Mojang's native text-to-speech library.
 *
 * <p>The real {@code text2speech} artifact is excluded from the browser classpath: every
 * narrator implementation loads a platform native library through JNA / libffi, which does not
 * exist in a Wasm GC tab. The client only needs the interface to construct {@code GameNarrator};
 * an inert narrator keeps narration silently disabled instead of failing client startup. It
 * reports {@link #active()} as true so the client does not raise the desktop-only
 * "text-to-speech failed to initialize" prompt.</p>
 */
public interface Narrator {
    Logger LOGGER = LoggerFactory.getLogger("Text2Speech");

    Narrator EMPTY = new Narrator() {
        @Override
        public void say(String text, boolean interrupt, float volume) {
        }

        @Override
        public void clear() {
        }

        @Override
        public boolean active() {
            return true;
        }

        @Override
        public void destroy() {
        }
    };

    void say(String text, boolean interrupt, float volume);

    void clear();

    boolean active();

    void destroy();

    static Narrator getNarrator() {
        return EMPTY;
    }
}
