package io.github.x0b.safdav.file;

public class SafConstants {
    public static final String SAF_REMOTE_NAME = "local-saf";
    /** Marker URL persisted in rclone.conf so SAF remotes can be identified; runtime connections override this via env vars with an ephemeral loopback port. */
    public static final String SAF_REMOTE_URL = "http://localhost:40404/";
}
