// SPDX-License-Identifier: GPL-3.0-or-later
package chromahub.rhythm.runtime.api;

import android.os.Bundle;

/** Blocking verification/storage calls run on the feature's IO dispatcher. No code loads here. */
public interface RuntimeUpdateController {
    String fingerprint();
    Bundle status();
    Bundle stage(String completedDownloadPath, long expectedVersion);
    Bundle verifyCandidate(String archiveHash);
    void requestApproval(String archiveHash);
    void discardUnapproved(String archiveHash);
    AutoCloseable observe(Runnable listener);
}
