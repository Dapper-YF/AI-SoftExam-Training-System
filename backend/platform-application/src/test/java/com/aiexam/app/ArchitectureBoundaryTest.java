package com.aiexam.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ArchitectureBoundaryTest {
  private final JavaClasses classes = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("com.aiexam");

  @Test
  void moduleDependenciesFollowDocumentedBoundaries() {
    ArchRule rule = layeredArchitecture().consideringAllDependencies()
        .layer("App").definedBy("com.aiexam.app..")
        .layer("Common").definedBy("com.aiexam.common..")
        .layer("Foundation").definedBy("com.aiexam.foundation..")
        .layer("Ai").definedBy("com.aiexam.ai..")
        .layer("Question").definedBy("com.aiexam.question..")
        .layer("Essay").definedBy("com.aiexam.essay..")
        .whereLayer("Common").mayOnlyBeAccessedByLayers("App", "Foundation", "Ai", "Question", "Essay")
        .whereLayer("Foundation").mayOnlyBeAccessedByLayers("App", "Ai", "Question", "Essay")
        .whereLayer("Ai").mayOnlyBeAccessedByLayers("App", "Question", "Essay")
        .whereLayer("Question").mayOnlyBeAccessedByLayers("App")
        .whereLayer("Essay").mayOnlyBeAccessedByLayers("App");

    rule.check(classes);
  }

  @Test
  void controllersDoNotDependOnRepositories() {
    classes().that().resideInAPackage("..controller..")
        .and().doNotHaveSimpleName("DevAuthChallengeController")
        .should().onlyDependOnClassesThat()
        .resideOutsideOfPackage("..repository..")
        .check(classes);
  }

  @Test
  void aiCapabilityDoesNotDependOnConcreteBusinessModules() {
    classes().that().resideInAPackage("com.aiexam.ai..")
        .should().onlyDependOnClassesThat()
        .resideOutsideOfPackages("com.aiexam.question..", "com.aiexam.essay..")
        .check(classes);
  }
}
