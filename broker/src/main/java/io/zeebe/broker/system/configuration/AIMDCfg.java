/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.system.configuration;

import java.time.Duration;
import java.util.Objects;

public class AIMDCfg {

  private Duration requestTimeout = Duration.ofSeconds(1);
  private int initialLimit = 100;
  private int minLimit = 1;
  private int maxLimit = 1000;
  private double backoffRatio = 0.9;

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(final Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  public int getInitialLimit() {
    return initialLimit;
  }

  public void setInitialLimit(final int initialLimit) {
    this.initialLimit = initialLimit;
  }

  public int getMinLimit() {
    return minLimit;
  }

  public void setMinLimit(final int minLimit) {
    this.minLimit = minLimit;
  }

  public int getMaxLimit() {
    return maxLimit;
  }

  public void setMaxLimit(final int maxLimit) {
    this.maxLimit = maxLimit;
  }

  public double getBackoffRatio() {
    return backoffRatio;
  }

  public void setBackoffRatio(final double backoffRatio) {
    this.backoffRatio = backoffRatio;
  }

  @Override
  public int hashCode() {
    return Objects.hash(requestTimeout, initialLimit, minLimit, maxLimit, backoffRatio);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final AIMDCfg aimdCfg = (AIMDCfg) o;
    return initialLimit == aimdCfg.initialLimit
        && minLimit == aimdCfg.minLimit
        && maxLimit == aimdCfg.maxLimit
        && Double.compare(aimdCfg.backoffRatio, backoffRatio) == 0
        && Objects.equals(requestTimeout, aimdCfg.requestTimeout);
  }

  @Override
  public String toString() {
    return "AIMDCfg{"
        + "requestTimeout='"
        + requestTimeout
        + ", initialLimit="
        + initialLimit
        + ", minLimit="
        + minLimit
        + ", maxLimit="
        + maxLimit
        + ", backoffRatio="
        + backoffRatio
        + '}';
  }
}
