package gag.nails.architecture;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import gag.nails.common.error.InternalException;
import gag.nails.common.error.UserFacingException;

/**
 * Правила из спецификации, раздел 9: только свои исключения; видимые пользователю живут в
 * {@code ..error}, внутренние — в {@code ..internal.error}.
 */
@AnalyzeClasses(packages = "gag.nails", importOptions = ImportOption.DoNotIncludeTests.class)
class ExceptionRulesTest {

    @ArchTest
    static final ArchRule ownExceptionsOnly = classes()
            .that()
            .areAssignableTo(Throwable.class)
            .should()
            .beAssignableTo(UserFacingException.class)
            .orShould()
            .beAssignableTo(InternalException.class)
            .as("каждое объявленное исключение наследует UserFacingException или InternalException");

    @ArchTest
    static final ArchRule noIllegalArgument = noConstructionOf(IllegalArgumentException.class);

    @ArchTest
    static final ArchRule noIllegalState = noConstructionOf(IllegalStateException.class);

    @ArchTest
    static final ArchRule noUnsupportedOperation = noConstructionOf(UnsupportedOperationException.class);

    @ArchTest
    static final ArchRule noRawRuntimeException = noConstructionOf(RuntimeException.class);

    @ArchTest
    static final ArchRule userFacingLiveInErrorPackage = classes()
            .that()
            .resideInAPackage("..error")
            .and()
            .resideOutsideOfPackage("..internal.error")
            .and()
            .areAssignableTo(Throwable.class)
            .and()
            .doNotHaveFullyQualifiedName(InternalException.class.getName())
            .should()
            .beAssignableTo(UserFacingException.class)
            .as("в пакете error только исключения, видимые пользователю");

    // Пакеты internal появляются вместе с доменными модулями; до них правило проверяет пустое множество.
    @ArchTest
    static final ArchRule internalLiveInInternalErrorPackage = classes()
            .that()
            .resideInAPackage("..internal.error")
            .and()
            .areAssignableTo(Throwable.class)
            .should()
            .beAssignableTo(InternalException.class)
            .as("в пакете internal.error только внутренние исключения")
            .allowEmptyShould(true);

    // Пакеты internal появляются вместе с доменными модулями; до них правило проверяет пустое множество.
    @ArchTest
    static final ArchRule userFacingNeverInInternalPackage = noClasses()
            .that()
            .resideInAPackage("..internal..")
            .and()
            .areAssignableTo(Throwable.class)
            .should()
            .beAssignableTo(UserFacingException.class)
            .as("видимые исключения — часть публичного API, им не место в internal")
            .allowEmptyShould(true);

    /**
     * Два базовых исключения исключены из проверки: их {@code super(...)} — наследование от
     * {@code RuntimeException}, а не создание чужого исключения.
     */
    private static ArchRule noConstructionOf(Class<? extends Throwable> type) {
        return noClasses()
                .that()
                .doNotHaveFullyQualifiedName(UserFacingException.class.getName())
                .and()
                .doNotHaveFullyQualifiedName(InternalException.class.getName())
                .should()
                .callConstructorWhere(target(owner(type(type))))
                .as("никто не создаёт " + type.getSimpleName() + ": только свои исключения");
    }
}
