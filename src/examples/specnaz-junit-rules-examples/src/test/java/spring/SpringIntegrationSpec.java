package spring;

import a.ServiceA;
import org.junit.ClassRule;
import org.specnaz.junit.SpecnazJUnit;
import org.specnaz.junit.rules.Rule;
import org.specnaz.utils.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.IfProfileValue;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.rules.SpringClassRule;
import org.springframework.test.context.junit4.rules.SpringMethodRule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * An example of integration testing Spring using its JUnit Rules support.
 * The most interesting part is the {@link #dummy} field.
 *
 * @see #dummy
 */
@ContextConfiguration(classes = TestSpringConfig.class)
public class SpringIntegrationSpec extends SpecnazJUnit {
    @ClassRule
    public static final SpringClassRule springClassRule = new SpringClassRule();

    public Rule<SpringMethodRule> springMethodRule = Rule.of(() -> new SpringMethodRule());

    @Autowired
    ServiceA serviceA;

    {
        describes("Spring integration", it -> {
            it.should("inject ServiceA correctly", () -> {
                assertThat(serviceA).isNotNull();
            });

            it.should("inject the dependencies correctly", () -> {
                assertThat(serviceA.findA()).isEqualTo("ServiceA:TestDaoA");
            });

            it.should("respect Spring annotations when providing a method that has them", () -> {
                fail("this should not have been executed");
            }).usingMethod(Utils.findMethod(this, "ifProfile"));
        });
    }

    @IfProfileValue(name = "does_not_exist")
    public void ifProfile() {
    }

    /**
     * This field needs to be declared because of validation that the
     * {@link SpringClassRule} performs. It will never be used,
     * and so can be set to {@code null} safely.
     */
    @org.junit.Rule
    public SpringMethodRule dummy = null;
}
