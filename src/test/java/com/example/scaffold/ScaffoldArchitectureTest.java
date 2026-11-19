package com.example.scaffold;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.koikifw.archunit.KoikiArchitectureRules;

class ScaffoldArchitectureTest {

    private static final String APPLICATION = "com.example.scaffold";

    @Test
    void followsBusinessModuleRules() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(APPLICATION);

        KoikiArchitectureRules.businessModuleRules(APPLICATION, Map.of()).check(classes);
    }

    @Test
    void usesOnlyFrameworkIdentityPublicBoundary() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.koikifw.identity", APPLICATION);

        KoikiArchitectureRules.frameworkOwnershipRules("org.koikifw.identity", APPLICATION).check(classes);
    }
}
