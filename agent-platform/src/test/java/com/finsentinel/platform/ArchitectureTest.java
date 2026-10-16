package com.finsentinel.platform;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Bean;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Architecture rules the compiler cannot see, checked on the compiled classes of the platform and of the shared
 * modules it uses. Each rule names its reason; a rule that must change needs the ADR that changes it.
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.finsentinel");

    @Test
    void everyModelCallGoesThroughTheGateway() {
        noClasses().that().resideOutsideOfPackage("com.finsentinel.platform.llm..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.ai.chat.client..")
                .orShould().dependOnClassesThat().areAssignableTo(ChatModel.class)
                .because("LlmGateway prices every call and falls back to another provider (ADR 0012)")
                .check(CLASSES);
    }

    @Test
    void ourCodeUsesJackson3() {
        noClasses().should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson.databind..")
                .because("Jackson 3 (tools.jackson) everywhere; Jackson 2 is only a transitive dependency of libraries")
                .check(CLASSES);
    }

    /** Seen live: the model sends null for a required number, and a primitive parameter fails the whole call. */
    @Test
    void toolParametersAreBoxed() {
        methods().that().areAnnotatedWith(Tool.class).should(takeNoPrimitiveParameters()).check(CLASSES);
    }

    @Test
    void thePlatformsOwnMcpToolsAreNeverToolCallbackProviders() {
        noMethods().that().areAnnotatedWith(Bean.class)
                .should().haveRawReturnType(JavaClass.Predicates.assignableTo(ToolCallbackProvider.class))
                .because("Spring AI hands every ToolCallbackProvider bean to the agents (ADR 0002)")
                .check(CLASSES);
        noClasses().should().implement(ToolCallbackProvider.class)
                .because("Spring AI hands every ToolCallbackProvider bean to the agents (ADR 0002)")
                .check(CLASSES);
    }

    @Test
    void checkpointsGoOnlyThroughTheFencedSaver() {
        noClasses().should().dependOnClassesThat().resideInAPackage("com.alibaba.cloud.ai.graph.checkpoint.savers..")
                .because("only FencedPostgresCheckpointSaver may store graph state (ADR 0009)")
                .check(CLASSES);
    }

    private static ArchCondition<JavaMethod> takeNoPrimitiveParameters() {
        return new ArchCondition<>("take no primitive parameters") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                method.getRawParameterTypes().stream().filter(JavaClass::isPrimitive).forEach(type ->
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " takes a primitive " + type.getName())));
            }
        };
    }
}
