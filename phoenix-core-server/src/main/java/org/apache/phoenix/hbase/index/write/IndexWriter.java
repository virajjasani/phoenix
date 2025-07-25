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
package org.apache.phoenix.hbase.index.write;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Stoppable;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.hbase.index.exception.IndexWriteException;
import org.apache.phoenix.hbase.index.table.HTableInterfaceReference;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.index.PhoenixIndexFailurePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.phoenix.thirdparty.com.google.common.collect.ArrayListMultimap;
import org.apache.phoenix.thirdparty.com.google.common.collect.Multimap;

/**
 * Do the actual work of writing to the index tables.
 * <p>
 * We attempt to do the index updates in parallel using a backing threadpool. All threads are daemon
 * threads, so it will not block the region from shutting down.
 */
public class IndexWriter implements Stoppable {

  private static final Logger LOGGER = LoggerFactory.getLogger(IndexWriter.class);
  public static final String INDEX_COMMITTER_CONF_KEY = "phoenix.index.writer.commiter.class";
  public static final String INDEX_FAILURE_POLICY_CONF_KEY =
    "phoenix.index.writer.failurepolicy.class";
  private AtomicBoolean stopped = new AtomicBoolean(false);
  private IndexCommitter writer;
  private IndexFailurePolicy failurePolicy;

  // This relies on Hadoop Configuration to handle warning about deprecated configs and
  // to set the correct non-deprecated configs when an old one shows up.
  static {
    Configuration.addDeprecation("index.writer.commiter.class", INDEX_COMMITTER_CONF_KEY);
    Configuration.addDeprecation("index.writer.failurepolicy.class", INDEX_FAILURE_POLICY_CONF_KEY);
  }

  /**
   * @throws IOException if the {@link IndexWriter} or {@link IndexFailurePolicy} cannot be
   *                     instantiated
   */
  public IndexWriter(RegionCoprocessorEnvironment env, String name) throws IOException {
    this(getCommitter(env), getFailurePolicy(env), env, name, true);
  }

  public IndexWriter(RegionCoprocessorEnvironment env, String name, boolean disableIndexOnFailure)
    throws IOException {
    this(getCommitter(env), getFailurePolicy(env), env, name, disableIndexOnFailure);
  }

  public IndexWriter(RegionCoprocessorEnvironment env, IndexCommitter indexCommitter, String name,
    boolean disableIndexOnFailure) throws IOException {
    this(indexCommitter, getFailurePolicy(env), env, name, disableIndexOnFailure);
  }

  public static IndexCommitter getCommitter(RegionCoprocessorEnvironment env) throws IOException {
    return getCommitter(env, TrackingParallelWriterIndexCommitter.class);
  }

  public static IndexCommitter getCommitter(RegionCoprocessorEnvironment env,
    Class<? extends IndexCommitter> defaultClass) throws IOException {
    Configuration conf = env.getConfiguration();
    try {
      IndexCommitter committer =
        conf.getClass(INDEX_COMMITTER_CONF_KEY, defaultClass, IndexCommitter.class).newInstance();
      return committer;
    } catch (InstantiationException e) {
      throw new IOException(e);
    } catch (IllegalAccessException e) {
      throw new IOException(e);
    }
  }

  public static IndexFailurePolicy getFailurePolicy(RegionCoprocessorEnvironment env)
    throws IOException {
    Configuration conf = env.getConfiguration();
    try {
      IndexFailurePolicy committer = conf.getClass(INDEX_FAILURE_POLICY_CONF_KEY,
        PhoenixIndexFailurePolicy.class, IndexFailurePolicy.class).newInstance();
      return committer;
    } catch (InstantiationException e) {
      throw new IOException(e);
    } catch (IllegalAccessException e) {
      throw new IOException(e);
    }
  }

  public IndexWriter(IndexCommitter committer, IndexFailurePolicy policy,
    RegionCoprocessorEnvironment env, String name, boolean disableIndexOnFailure) {
    this(committer, policy);
    this.writer.setup(this, env, name, disableIndexOnFailure);
    this.failurePolicy.setup(this, env);
  }

  /**
   * Directly specify the {@link IndexCommitter} and {@link IndexFailurePolicy}. Both are expected
   * to be fully setup before calling.
   */
  public IndexWriter(IndexCommitter committer, IndexFailurePolicy policy,
    RegionCoprocessorEnvironment env, String name) {
    this(committer, policy);
    this.writer.setup(this, env, name, true);
    this.failurePolicy.setup(this, env);
  }

  /**
   * Create an {@link IndexWriter} with an already setup {@link IndexCommitter} and
   * {@link IndexFailurePolicy}.
   * @param committer to write updates
   * @param policy    to handle failures
   */
  IndexWriter(IndexCommitter committer, IndexFailurePolicy policy) {
    this.writer = committer;
    this.failurePolicy = policy;
  }

  /**
   * see #writeAndHandleFailure(Collection).
   */
  public void writeAndHandleFailure(Multimap<HTableInterfaceReference, Mutation> toWrite,
    boolean allowLocalUpdates, int clientVersion) throws IOException {
    try {
      write(toWrite, allowLocalUpdates, clientVersion);
      if (LOGGER.isTraceEnabled()) {
        LOGGER.trace("Done writing all index updates!\n\t" + toWrite);
      }
    } catch (Exception e) {
      this.failurePolicy.handleFailure(toWrite, e);
    }
  }

  /**
   * Write the mutations to their respective table.
   * <p>
   * This method is blocking and could potentially cause the writer to block for a long time as we
   * write the index updates. When we return depends on the specified {@link IndexCommitter}.
   * <p>
   * If update fails, we pass along the failure to the installed {@link IndexFailurePolicy}, which
   * then decides how to handle the failure. By default, we use a {@link KillServerOnFailurePolicy},
   * which ensures that the server crashes when an index write fails, ensuring that we get WAL
   * replay of the index edits.
   * @param indexUpdates  Updates to write
   * @param clientVersion version of the client
   */
  public void writeAndHandleFailure(Collection<Pair<Mutation, byte[]>> indexUpdates,
    boolean allowLocalUpdates, int clientVersion) throws IOException {
    // convert the strings to htableinterfaces to which we can talk and group by TABLE
    Multimap<HTableInterfaceReference, Mutation> toWrite = resolveTableReferences(indexUpdates);
    writeAndHandleFailure(toWrite, allowLocalUpdates, clientVersion);
  }

  /**
   * Write the mutations to their respective table.
   * <p>
   * This method is blocking and could potentially cause the writer to block for a long time as we
   * write the index updates. We only return when either:
   * <ol>
   * <li>All index writes have returned, OR</li>
   * <li>Any single index write has failed</li>
   * </ol>
   * We attempt to quickly determine if any write has failed and not write to the remaining indexes
   * to ensure a timely recovery of the failed index writes.
   * @param toWrite Updates to write
   * @throws IndexWriteException if we cannot successfully write to the index. Whether or not we
   *                             stop early depends on the {@link IndexCommitter}.
   */
  public void write(Collection<Pair<Mutation, byte[]>> toWrite, int clientVersion)
    throws IOException {
    write(resolveTableReferences(toWrite), false, clientVersion);
  }

  public void write(Collection<Pair<Mutation, byte[]>> toWrite, boolean allowLocalUpdates,
    int clientVersion) throws IOException {
    write(resolveTableReferences(toWrite), allowLocalUpdates, clientVersion);
  }

  /**
   * see #write(Collection)
   */
  public void write(Multimap<HTableInterfaceReference, Mutation> toWrite, boolean allowLocalUpdates,
    int clientVersion) throws IOException {
    this.writer.write(toWrite, allowLocalUpdates, clientVersion);
  }

  /**
   * Convert the passed index updates to {@link HTableInterfaceReference}s.
   * @param indexUpdates from the index builder
   * @return pairs that can then be written by an {@link IndexWriter}.
   */
  protected Multimap<HTableInterfaceReference, Mutation>
    resolveTableReferences(Collection<Pair<Mutation, byte[]>> indexUpdates) {
    Multimap<HTableInterfaceReference, Mutation> updates =
      ArrayListMultimap.<HTableInterfaceReference, Mutation> create();
    // simple map to make lookups easy while we build the map of tables to create
    Map<ImmutableBytesPtr, HTableInterfaceReference> tables =
      new HashMap<ImmutableBytesPtr, HTableInterfaceReference>(updates.size());
    for (Pair<Mutation, byte[]> entry : indexUpdates) {
      byte[] tableName = entry.getSecond();
      ImmutableBytesPtr ptr = new ImmutableBytesPtr(tableName);
      HTableInterfaceReference table = tables.get(ptr);
      if (table == null) {
        table = new HTableInterfaceReference(ptr);
        tables.put(ptr, table);
      }
      updates.put(table, entry.getFirst());
    }

    return updates;
  }

  @Override
  public void stop(String why) {
    if (!this.stopped.compareAndSet(false, true)) {
      // already stopped
      return;
    }
    LOGGER.debug("Stopping because " + why);
    this.writer.stop(why);
    this.failurePolicy.stop(why);
  }

  @Override
  public boolean isStopped() {
    return this.stopped.get();
  }
}
