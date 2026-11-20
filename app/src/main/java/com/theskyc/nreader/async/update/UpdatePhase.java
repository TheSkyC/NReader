// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.async.update;

/** Durable phases shared by the update screen, notifications and workers. */
public enum UpdatePhase {
    IDLE,
    CHECKING,
    AVAILABLE,
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    READY_TO_INSTALL,
    INSTALLING,
    ERROR
}
