// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.async.update;

/** Durable phases shared by the update screen, notifications and workers. */
public enum UpdatePhase {
    IDLE,
    CHECKING,
    AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    READY_TO_INSTALL,
    INSTALLING,
    ERROR
}
