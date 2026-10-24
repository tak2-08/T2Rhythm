// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.base;

import java.util.List;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/** Publishes the Java 10 collector backport also used by independently compiled music libraries.
 * The base retains this bridge so release L8 cannot remove the implementation on Android 8. */
public final class DesugaredSdkContract {
    private DesugaredSdkContract() {}

    public static <T> Collector<T, ?, List<T>> toUnmodifiableList() {
        return Collectors.toUnmodifiableList();
    }
}
