package com.anesop.archfixture.m0.impl;

/**
 * 负向夹具：只用于证明 ArchUnit 的 impl→impl 规则会真实报错，不参与业务。
 */
public class FixtureM0Impl {

    public String module() {
        return "m0";
    }
}
