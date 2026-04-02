package com.propertee.teebox;

import com.propertee.platform.DefaultPlatformProvider;

import java.io.File;

/**
 * TeeBox host binding for ProperTee platform capabilities.
 *
 * This currently exposes the default host-backed behavior and keeps the policy
 * hook in TeeBox so host restrictions can be added later without changing the
 * core interpreter wiring again.
 */
public class TeeBoxPlatformProvider extends DefaultPlatformProvider {
    private final File dataDir;

    public TeeBoxPlatformProvider(File dataDir) {
        this.dataDir = dataDir;
    }

    public File getDataDir() {
        return dataDir;
    }
}
