/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.monitoring;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Version of {@link Metric} that can be used when the metric is being concurrently accessed or
 * modified by multiple threads.
 */
public class AtomicMetric implements Metric {

  private final MetricType type;
  private final AtomicLong value = new AtomicLong();

  public AtomicMetric(MetricType type) {
    this.type = type;
  }

  @Override
  public MetricType getMetricType() {
    return type;
  }

  @Override
  public long getValue() {
    return value.get();
  }

  @Override
  public void change(long delta) {
    value.addAndGet(delta);
  }

  @Override
  public void increment() {
    value.incrementAndGet();
  }

  @Override
  public String getCurrentMetricState() {
    return getMetricType().shortName() + ": " + value.get();
  }

  @Override
  public void reset() {
    value.set(0);
  }

  /**
   * Set the Metric value as current value
   */
  @Override
  public void set(long value) {
    this.value.set(value);
  }

  @Override
  public void decrement() {
    value.decrementAndGet();
  }

}
