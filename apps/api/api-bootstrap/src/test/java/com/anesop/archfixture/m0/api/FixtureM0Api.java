package com.anesop.archfixture.m0.api;

import com.anesop.archfixture.m0.impl.FixtureM0Impl;

/**
 * 负向夹具：api 层直接依赖了 impl，ArchUnit 必须拒绝。
 */
public class FixtureM0Api {

    private final FixtureM0Impl illegalDependency = new FixtureM0Impl();

    public String describe() {
        return "fixture-api->" + illegalDependency.module();
    }
}
