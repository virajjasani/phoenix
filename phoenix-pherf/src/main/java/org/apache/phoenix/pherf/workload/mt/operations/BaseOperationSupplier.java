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
package org.apache.phoenix.pherf.workload.mt.operations;

import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.LoadProfile;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.rules.RulesApplier;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.workload.mt.generators.TenantOperationInfo;

import org.apache.phoenix.thirdparty.com.google.common.base.Function;
import org.apache.phoenix.thirdparty.com.google.common.base.Supplier;

/**
 * An abstract base class for all OperationSuppliers
 */
public abstract class BaseOperationSupplier
  implements Supplier<Function<TenantOperationInfo, OperationStats>> {

  final PhoenixUtil phoenixUtil;
  final DataModel model;
  final Scenario scenario;
  final RulesApplier rulesApplier;
  final LoadProfile loadProfile;

  public BaseOperationSupplier(PhoenixUtil phoenixUtil, DataModel model, Scenario scenario) {
    this.phoenixUtil = phoenixUtil;
    this.model = model;
    this.scenario = scenario;
    this.rulesApplier = new RulesApplier(model);
    this.loadProfile = this.scenario.getLoadProfile();
  }
}
