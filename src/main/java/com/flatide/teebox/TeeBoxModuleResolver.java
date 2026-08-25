package com.flatide.teebox;

import com.flatide.propertee2.module.ModuleRequest;
import com.flatide.propertee2.module.ModuleResolutionException;
import com.flatide.propertee2.module.ModuleResolver;
import com.flatide.propertee2.module.ModuleSource;

import java.util.function.Consumer;

/** Maps a ProperTee module id directly to a TeeBox scriptId and pins its resolved version. */
final class TeeBoxModuleResolver implements ModuleResolver {
    private final ScriptRegistry registry;
    private final Consumer<ResolvedModuleInfo> observer;

    TeeBoxModuleResolver(ScriptRegistry registry, Consumer<ResolvedModuleInfo> observer) {
        this.registry = registry;
        this.observer = observer;
    }

    @Override public ModuleSource resolve(ModuleRequest request) {
        try {
            ScriptRegistry.ResolvedScript resolved = registry.resolve(request.moduleId(),
                    request.version() == null ? null : String.valueOf(request.version()));
            int version;
            try {
                version = Integer.parseInt(resolved.version);
            } catch (NumberFormatException e) {
                throw new ModuleResolutionException("TeeBox module version is not numeric: "
                        + resolved.displayPath);
            }
            if (observer != null) {
                ResolvedModuleInfo info = new ResolvedModuleInfo();
                info.scriptId = resolved.scriptId;
                info.version = resolved.version;
                info.sha256 = resolved.sha256;
                observer.accept(info);
            }
            return new ModuleSource(resolved.scriptId, version, resolved.displayPath, resolved.source);
        } catch (ModuleResolutionException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ModuleResolutionException(e.getMessage(), e);
        }
    }
}
