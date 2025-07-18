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
package org.apache.phoenix.hbase.index.scanner;

import java.io.IOException;
import org.apache.hadoop.hbase.Cell;
import org.apache.phoenix.hbase.index.covered.filter.ApplyAndFilterDeletesFilter.DeleteTracker;
import org.apache.phoenix.hbase.index.scanner.ScannerBuilder.CoveredDeleteScanner;

/**
 * {@link Scanner} that has no underlying data
 */
public class EmptyScanner implements CoveredDeleteScanner {
  private final DeleteTracker deleteTracker;

  public EmptyScanner(DeleteTracker deleteTracker) {
    this.deleteTracker = deleteTracker;
  }

  @Override
  public Cell next() throws IOException {
    return null;
  }

  @Override
  public boolean seek(Cell next) throws IOException {
    return false;
  }

  @Override
  public Cell peek() throws IOException {
    return null;
  }

  @Override
  public void close() throws IOException {
    // noop
  }

  @Override
  public DeleteTracker getDeleteTracker() {
    return deleteTracker;
  }
}
