/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.system.configuration;

public final class BackpressureCfg implements ConfigurationEntry {

  private boolean enabled = true;
  private boolean useWindowed = true;
  private String algorithm = "vegas";
  private final AIMDCfg aimd = new AIMDCfg();
  private final FixedLimitCfg fixedLimit = new FixedLimitCfg();
  private final VegasCfg vegas = new VegasCfg();
  private final GradientCfg gradient = new GradientCfg();
  private final Gradient2Cfg gradient2 = new Gradient2Cfg();

  public boolean isEnabled() {
    return enabled;
  }

  public BackpressureCfg setEnabled(final boolean enabled) {
    this.enabled = enabled;
    return this;
  }

  public boolean useWindowed() {
    return useWindowed;
  }

  public BackpressureCfg setUseWindowed(final boolean useWindowed) {
    this.useWindowed = useWindowed;
    return this;
  }

  public LimitAlgorithm getAlgorithm() {
    return LimitAlgorithm.valueOf(algorithm.toUpperCase());
  }

  public BackpressureCfg setAlgorithm(final String algorithm) {
    this.algorithm = algorithm;
    return this;
  }

  public AIMDCfg getAimd() {
    return aimd;
  }

  public FixedLimitCfg getFixedLimit() {
    return fixedLimit;
  }

  public VegasCfg getVegas() {
    return vegas;
  }

  public GradientCfg getGradient() {
    return gradient;
  }

  public Gradient2Cfg getGradient2() {
    return gradient2;
  }

  @Override
  public String toString() {
    return "BackpressureCfg{"
        + "enabled="
        + enabled
        + ", useWindowed="
        + useWindowed
        + ", algorithm='"
        + algorithm
        + '\''
        + ", aimd="
        + aimd
        + ", fixedLimit="
        + fixedLimit
        + ", vegas="
        + vegas
        + ", gradient="
        + gradient
        + ", gradient2="
        + gradient2
        + '}';
  }

  public enum LimitAlgorithm {
    VEGAS,
    GRADIENT,
    GRADIENT2,
    FIXED,
    AIMD
  }
}
