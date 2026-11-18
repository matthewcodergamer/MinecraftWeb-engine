package mcport.rt.patch;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Java Locale compatibility for methods not provided by TeaVM's class library. */
public final class LocalePatches {
    private LocalePatches() {
    }

    /**
     * Locale.getExtensionKeys() returns the singleton keys from a BCP-47 extension
     * sequence. TeaVM 0.16 does not expose this method; recover the keys from the
     * standard Locale.toString() extension suffix (for example, "#u-ca-japanese").
     */
    public static Set<Character> getExtensionKeys(Locale locale) {
        String value = locale.toString();
        int marker = value.lastIndexOf("#");
        if (marker < 0 || marker + 1 >= value.length()) {
            return Collections.emptySet();
        }

        String[] subtags = value.substring(marker + 1).replace('_', '-').split("-");
        Set<Character> keys = new LinkedHashSet<>();
        for (String subtag : subtags) {
            if (subtag.length() == 1) {
                char key = Character.toLowerCase(subtag.charAt(0));
                if (Character.digit(key, 36) >= 0) {
                    keys.add(key);
                }
            }
        }
        return keys.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(keys);
    }
}
