package org.specnaz.junit.core;

import org.junit.ClassRule;
import org.junit.internal.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.Statement;
import org.specnaz.Specnaz;
import org.specnaz.core.SpecnazCoreDsl;
import org.specnaz.impl.ExecutableTestCase;
import org.specnaz.impl.SingleTestCase;
import org.specnaz.impl.SpecParser;
import org.specnaz.impl.SpecsRegistryViolation;
import org.specnaz.impl.TestsGroup;
import org.specnaz.impl.TestsGroupNodeExecutor;
import org.specnaz.impl.TreeNode;
import org.specnaz.junit.impl.JUnitDescUtils;
import org.specnaz.junit.impl.JUnitFrameworkMethod;
import org.specnaz.junit.impl.JUnitNotifier;
import org.specnaz.junit.impl.TestCases2DescriptionsMap;
import org.specnaz.junit.rules.Rule;
import org.specnaz.junit.utils.Utils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedList;
import java.util.List;

import static java.lang.String.format;
import static org.junit.runner.Description.createSuiteDescription;

/**
 * The JUnit {@link Runner} for {@link SpecnazCoreDsl}.
 * This is the runner you should use when writing your own custom Specnaz DSL.
 */
public final class SpecnazCoreDslJUnitRunner extends Runner {
    private final SpecParser specParser;
    private final Class<?> classs;
    private final Object specInstance;
    private final List<TestRule> classRules;
    private final List<Rule.Wrapper<?>> instanceRules;

    /**
     * This is JUnit's entry point for {@link Runner}s.
     *
     * @param classs
     *     the class of the tests currently being run.
     *     It must implement and obey the contract of the
     *     {@link SpecnazCoreDsl} interface
     */
    @SuppressWarnings("unused")
    public SpecnazCoreDslJUnitRunner(Class<?> classs) throws IllegalStateException {
        this(classs, Utils.instantiateTestClass(classs, SpecnazCoreDsl.class));
    }

    /**
     * This is the extension point for custom {@link Runner}s that want
     * to leverage existing JUnit infrastructure provided by this class.
     * <p>
     * It's used by the Kotlin Specnaz bindings, for example.
     *
     * @param classs
     *     the {@link Class} instance of the test class
     * @param specInstance
     *     an instance of the test object.
     *     It had to be registered for running, most likely by calling,
     *     directly or indirectly, a method like {@link Specnaz#describes},
     *     {@link SpecnazCoreDsl#specification}, or its equivalent
     * @throws IllegalStateException
     *     if the {@code specInstance} was never registered for running
     */
    public SpecnazCoreDslJUnitRunner(Class<?> classs, Object specInstance)
            throws IllegalStateException {
        try {
            this.specParser = new SpecParser(specInstance);
        } catch (SpecsRegistryViolation e) {
            throw new IllegalStateException("SpecnazCoreDsl.specification() was never called in the " +
                    "no-argument constructor of " + classs.getSimpleName());
        }
        this.classs = classs;
        this.specInstance = specInstance;
        this.classRules = discoverClassRules(classs);
        this.instanceRules = discoverInstanceRules(classs);
    }

    private Description classDescription;
    private TestCases2DescriptionsMap testCases2DescriptionsMap;

    @Override
    public Description getDescription() {
        if (classDescription != null)
            return classDescription;

        classDescription = createSuiteDescription(classs);
        Description rootDescribeDescription = createSuiteDescription(specParser.name());
        classDescription.addChild(rootDescribeDescription);

        TreeNode<TestsGroup> testsPlan = specParser.testsPlan();
        TestCases2DescriptionsMap.Builder testCases2DescriptionsMapBuilder = new TestCases2DescriptionsMap.Builder();
        parseSubGroupDescriptions(testsPlan, rootDescribeDescription, testCases2DescriptionsMapBuilder);

        this.testCases2DescriptionsMap = testCases2DescriptionsMapBuilder.build();

        return classDescription;
    }

    @Override
    public void run(RunNotifier runNotifier) {
        Statement entireClassStmt = new Statement() {
            @Override
            public void evaluate() throws Throwable {
                run(new JUnitNotifier(runNotifier, testCases2DescriptionsMap));
            }
        };

        Statement embellishedStmt = stmtWithClassRules(entireClassStmt);

        try {
            embellishedStmt.evaluate();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private Statement stmtWithClassRules(Statement entireClassStmt) {
        Statement ret = entireClassStmt;
        for (TestRule classRule : classRules) {
            ret = classRule.apply(ret, classDescription);
        }
        return ret;
    }

    private List<TestRule> discoverClassRules(Class<?> classs) {
        List<TestRule> ret = new LinkedList<>();

        for (Field field : classs.getFields()) {
            if (field.isAnnotationPresent(ClassRule.class)) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    throw new RuntimeException(format(
                            "The field '%s' of the class '%s' is annotated with @ClassRule, " +
                                    "but is not static. Only static fields can be annotated " +
                                    "with @ClassRule", field.getName(), classs.getSimpleName()));
                }
                if (!TestRule.class.isAssignableFrom(field.getType())) {
                    throw new RuntimeException(format(
                            "The field '%s' of the class '%s' is annotated with @ClassRule, " +
                                    "but is not of the type org.junit.rules.TestRule. " +
                                    "Only fields of that type are allowed to be annotated " +
                                    "with @ClassRule (org.junit.rules.MethodRule is also not allowed)",
                            field.getName(), classs.getSimpleName()));
                }
                try {
                    ret.add((TestRule) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        return ret;
    }

    private void run(JUnitNotifier junitNotifier) {
        for (TestsGroupNodeExecutor testsGroupNodeExecutor : specParser.testsGroupNodeExecutors()) {
            runTestsGroup(testsGroupNodeExecutor, junitNotifier);
        }
    }

    private void runTestsGroup(TestsGroupNodeExecutor testsGroupNodeExecutor, JUnitNotifier junitNotifier) {
        Throwable beforeAllsError = testsGroupNodeExecutor.beforeAllsExecutable().execute();

        SingleTestCase lastTestCase = null;
        for (ExecutableTestCase executableTestCase : testsGroupNodeExecutor.executableTestCases(beforeAllsError)) {
            Statement stmt = singleCaseStmtWithInstanceRules(executableTestCase);

            if (stmt == null) {
                junitNotifier.ignored(executableTestCase.testCase);
            } else {
                junitNotifier.started(executableTestCase.testCase);
                try {
                    stmt.evaluate();
                    junitNotifier.passed(executableTestCase.testCase);
                } catch (AssumptionViolatedException e) {
                    junitNotifier.skipped(executableTestCase.testCase, e);
                } catch (Throwable e) {
                    junitNotifier.failed(executableTestCase.testCase, e);
                }
            }

            lastTestCase = executableTestCase.testCase;
        }

        junitNotifier.teardownStarted(lastTestCase);
        Throwable afterAllsError = testsGroupNodeExecutor.afterAllsExecutable().execute();
        Throwable effectiveError = beforeAllsError == null ? afterAllsError : beforeAllsError;
        if (effectiveError == null) {
            junitNotifier.teardownSucceeded(lastTestCase);
        } else {
            junitNotifier.teardownFailed(lastTestCase, effectiveError);
        }
    }

    private Statement singleCaseStmtWithInstanceRules(ExecutableTestCase executableTestCase) {
        Statement ret = singleTestCaseStmt(executableTestCase);

        if (ret == null)
            return null;

        for (Rule.Wrapper<?> instanceRule : instanceRules) {
            instanceRule.reset();
            ret = instanceRule.apply(ret,
                    testCases2DescriptionsMap.findDesc(executableTestCase.testCase),
                    JUnitFrameworkMethod.frameworkMethod(executableTestCase), specInstance);
        }
        return ret;
    }

    private Statement singleTestCaseStmt(ExecutableTestCase executableTestCase) {
        return executableTestCase.isIgnored()
                ? null
                : new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Throwable throwable = executableTestCase.execute();
                if (throwable != null)
                    throw throwable;
            }
        };
    }

    private List<Rule.Wrapper<?>> discoverInstanceRules(Class<?> classs) {
        List<Rule.Wrapper<?>> ret = new LinkedList<>();

        for (Field field : classs.getFields()) {
            if (Rule.class.isAssignableFrom(field.getType())) {
                if (Modifier.isStatic(field.getModifiers())) {
                    throw new RuntimeException(format(
                            "The field '%s' of class '%s' is of type org.specnaz.junit.rules.Rule, " +
                                    "but is static. Rule fields must be instance fields",
                            field.getName(), classs.getSimpleName()));
                }

                Rule<?> instanceRule;
                try {
                    instanceRule = (Rule) field.get(specInstance);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }

                ret.add(new Rule.Wrapper<>(instanceRule));
            }
        }

        return ret;
    }

    private void parseSubGroupDescriptions(TreeNode<TestsGroup> testsGroupNode, Description parentDescription,
            TestCases2DescriptionsMap.Builder testCases2DescriptionsMapBuilder) {
        List<SingleTestCase> testCases = testsGroupNode.value.testCases;
        for (SingleTestCase testCase : testCases) {
            Description description = JUnitDescUtils.makeTestDesc(testCase.description, parentDescription,
                    testCase.testSettings.annotations());
            parentDescription.addChild(description);
            testCases2DescriptionsMapBuilder.addDescMapping(testCase, description);
        }
        if (!testCases.isEmpty() && testsGroupNode.value.afterAllsCount() > 0) {
            Description description = JUnitDescUtils.makeTestDesc("teardown", parentDescription);
            parentDescription.addChild(description);
            for (SingleTestCase testCase : testCases) {
                testCases2DescriptionsMapBuilder.addTeardownMapping(testCase, description);
            }
        }

        for (TreeNode<TestsGroup> child : testsGroupNode.children()) {
            if (child.value.testsInTree > 0) {
                Description suiteDescription = createSuiteDescription(child.value.description);
                parentDescription.addChild(suiteDescription);
                parseSubGroupDescriptions(child, suiteDescription, testCases2DescriptionsMapBuilder);
            }
        }
    }
}
