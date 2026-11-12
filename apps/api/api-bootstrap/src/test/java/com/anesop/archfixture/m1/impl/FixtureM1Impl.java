package com.anesop.archfixture.m1.impl;

import com.anesop.archfixture.m0.impl.FixtureM0Impl;

/**
 * 负向夹具：故意跨模块依赖 m0 的 impl，ArchUnit 必须拒绝。
 */
public class FixtureM1Impl {

    private final FixtureM0Impl illegalDependency = new FixtureM0Impl();

    public String module() {
        return illegalDependency.module() + "->m1";
    }
}
