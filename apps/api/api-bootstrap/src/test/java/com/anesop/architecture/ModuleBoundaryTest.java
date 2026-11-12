package com.anesop.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anesop.archfixture.m0.api.FixtureM0Api;
import com.anesop.archfixture.m0.impl.FixtureM0Impl;
import com.anesop.archfixture.m1.impl.FixtureM1Impl;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 模块边界铁律（CONTEXT_CORE 第 4 节 / AGENTS.md「模块边界与依赖禁令」）。
 *
 * <p>三条规则对真实模块生效：
 * <ol>
 *   <li>api 层不得依赖任何模块的 impl；</li>
 *   <li>一个模块的 impl 不得依赖另一个模块的 impl（跨模块只用 api）；</li>
 *   <li>模块之间不得存在依赖环。</li>
 * </ol>
 *
 * <p>S0-02 阶段 impl 尚无实现类、也不在 bootstrap 测试类路径上，因此规则对真实模块暂时
 * 空跑（allowEmptyShould(true) 是过渡设置，S0-07 装配 impl 后由真实代码覆盖）。
 * 为避免恒真断言，下面两个负向用例用测试夹具（archfixture）构造真实违规，
 * 断言规则确实会报错——规则本身可执行，不是摆设。
 */
class ModuleBoundaryTest {

    private static final String API_PACKAGE = "com.anesop.m*.api..";
    private static final String IMPL_PACKAGE = "com.anesop.m*.impl..";

    private static JavaClasses projectClasses;

    @BeforeAll
    static void importProjectClasses() {
        projectClasses = new ClassFileImporter().importPackages("com.anesop");
    }

    @Test
    void apiLayerMustNotDependOnImpl() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(API_PACKAGE)
                .should().dependOnClassesThat().resideInAPackage(IMPL_PACKAGE)
                .allowEmptyShould(true);

        rule.check(projectClasses);
    }

    @Test
    void implModulesMustNotDependOnEachOther() {
        ArchRule rule = slices()
                .matching("com.anesop.(m*).impl..")
                .should().notDependOnEachOther()
                .allowEmptyShould(true);

        rule.check(projectClasses);
    }

    @Test
    void modulesMustBeFreeOfCycles() {
        ArchRule rule = slices()
                .matching("com.anesop.(m*)..")
                .should().beFreeOfCycles()
                .allowEmptyShould(true);

        rule.check(projectClasses);
    }

    @Test
    void apiToImplRuleRejectsKnownViolation() {
        JavaClasses fixtures = new ClassFileImporter()
                .importClasses(FixtureM0Api.class, FixtureM0Impl.class);

        ArchRule fixtureRule = noClasses()
                .that().resideInAPackage("com.anesop.archfixture.m*.api..")
                .should().dependOnClassesThat().resideInAPackage("com.anesop.archfixture.m*.impl..");

        assertThatThrownBy(() -> fixtureRule.check(fixtures))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("com.anesop.archfixture.m0.api.FixtureM0Api");
    }

    @Test
    void implToImplRuleRejectsKnownViolation() {
        JavaClasses fixtures = new ClassFileImporter()
                .importClasses(FixtureM0Impl.class, FixtureM1Impl.class);

        ArchRule fixtureRule = slices()
                .matching("com.anesop.archfixture.(m*).impl..")
                .should().notDependOnEachOther();

        assertThatThrownBy(() -> fixtureRule.check(fixtures))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("com.anesop.archfixture.m1.impl");
    }
}
