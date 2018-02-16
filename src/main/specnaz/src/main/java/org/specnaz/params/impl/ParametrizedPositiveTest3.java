package org.specnaz.params.impl;

import org.specnaz.TestSettings;
import org.specnaz.impl.TestCaseType;
import org.specnaz.params.TestClosureParams3;
import org.specnaz.utils.TestClosure;

import java.util.List;

public final class ParametrizedPositiveTest3<P1, P2, P3> extends
        AbstractParametrizedPositiveTest {
    private final TestClosureParams3<P1, P2, P3> testBody;

    public ParametrizedPositiveTest3(TestSettings testSettings,
            String description, TestClosureParams3<P1, P2, P3> testBody, TestCaseType testCaseType) {
        super(testSettings, description, testCaseType);
        this.testBody = testBody;
    }

    @Override
    protected TestClosure toTestClosure(List<?> params) {
        return Conversions.toTestClosure3(testBody, params);
    }
}
