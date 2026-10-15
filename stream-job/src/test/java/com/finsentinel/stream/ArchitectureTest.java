package com.finsentinel.stream;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Rules from CLAUDE.md the compiler cannot see; Spring is kept out by the enforcer in the POM. */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.finsentinel.stream");

    @Test
    void ruleLogicIsPureJava() {
        noClasses().that().resideInAPackage("com.finsentinel.stream.rules..")
                .should().dependOnClassesThat().resideInAPackage("org.apache.flink..")
                .because("rules are pure Java and unit-tested directly; Flink functions stay thin wrappers")
                .check(CLASSES);
    }

    @Test
    void ourCodeUsesJackson3() {
        noClasses().should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson.databind..")
                .because("Jackson 3 (tools.jackson) everywhere; Jackson 2 is only a transitive dependency of Flink")
                .check(CLASSES);
    }
}
