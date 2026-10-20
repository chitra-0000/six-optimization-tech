package com.bnpp.regliss.service.six;

import java.nio.file.Path;
import java.time.LocalDateTime;

/** Test helper: builds SixXmlGenerationService.HeldFile (final class, package-private constructor) for other packages. */
public final class SixHeldFileFixtures {

    private SixHeldFileFixtures() {
    }

    public static SixXmlGenerationService.HeldFile held(String group, String listFolder, Path path, LocalDateTime lastModified) {
        return new SixXmlGenerationService.HeldFile(group, listFolder, path, lastModified);
    }
}
