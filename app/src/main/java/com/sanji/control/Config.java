package com.sanji.control;

/**
 * Single place for the relay URL. The parent replaces RELAY_URL with the
 * real Railway relay URL (wss://...) before the final build.
 * The WebSocket must use wss:// (TLS). Do not hardcode any other URL.
 */
public final class Config {
    /** Relay WebSocket URL (wss). Replaced with the real URL before the final build. */
    public static final String RELAY_URL = "wss://sanji-control-relay-production.up.railway.app";

    private Config() {}
}
