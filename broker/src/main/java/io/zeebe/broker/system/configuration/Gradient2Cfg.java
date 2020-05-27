/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.system.configuration;

import java.util.Objects;

public class Gradient2Cfg {

  private int minLimit = 10;
  private int initialLimit = 20;
  private double rttTolerance = 2.0;
  private int longWindow = 600;

  public int getMinLimit() {
    return this.minLimit;
  }

  public void setMinLimit(final int minLimit) {
    this.minLimit = minLimit;
  }

  public int getInitialLimit() {
    return this.initialLimit;
  }

  public void setInitialLimit(final int initialLimit) {
    this.initialLimit = initialLimit;
  }

  public double getRttTolerance() {
    return rttTolerance;
  }

  public void setRttTolerance(final double rttTolerance) {
    this.rttTolerance = rttTolerance;
  }

  public int getLongWindow() {
    return longWindow;
  }

  public void setLongWindow(final int longWindow) {
    this.longWindow = longWindow;
  }

  @Override
  public int hashCode() {
    return Objects.hash(minLimit, initialLimit, rttTolerance, longWindow);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final Gradient2Cfg that = (Gradient2Cfg) o;
    return minLimit == that.minLimit
        && initialLimit == that.initialLimit
        && Double.compare(that.rttTolerance, rttTolerance) == 0
        && longWindow == that.longWindow;
  }

  @Override
  public String toString() {
    return "Gradient2Cfg{"
        + "minLimit="
        + minLimit
        + ", initialLimit="
        + initialLimit
        + ", rttTolerance="
        + rttTolerance
        + ", longWindow="
        + longWindow
        + '}';
  }
}
