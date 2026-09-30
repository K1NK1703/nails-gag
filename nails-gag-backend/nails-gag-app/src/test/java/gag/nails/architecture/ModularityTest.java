package gag.nails.architecture;

import gag.nails.NailsGagApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Границы модулей: нет циклов, никто не лезет в чужой {@code internal}. */
class ModularityTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(NailsGagApplication.class);

    @Test
    void modulesRespectBoundaries() {
        MODULES.verify();
    }

    @Test
    void documentationIsGenerated() {
        // build/spring-modulith-docs: диаграммы модулей и их зависимостей
        new Documenter(MODULES).writeDocumentation();
    }
}
