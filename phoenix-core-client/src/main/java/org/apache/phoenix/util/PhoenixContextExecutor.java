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
package org.apache.phoenix.util;

import java.util.concurrent.Callable;
import org.apache.phoenix.call.CallWrapper;

/**
 * Executes {@code Callable}s using a context classloader that is set up to load classes from
 * Phoenix. Loading HBase configuration settings and endpoint coprocessor classes is done via the
 * context classloader of the calling thread. When Phoenix is being run via a JDBC-enabled GUI, the
 * driver is often loaded dynamically and executed via multiple threads, which makes it difficult or
 * impossible to predict the state of the classloader hierarchy in the current thread. This class is
 * intended to get around that, to ensure that the same classloader used to load Phoenix classes is
 * set as the context classloader for specific calls.
 */
public class PhoenixContextExecutor {

  // We cache the class loader because calls to Class.getClassLoader are relatively expensive
  private static final ClassLoader CACHED_CLASSLOADER =
    PhoenixContextExecutor.class.getClassLoader();

  private static class CurrentContextWrapper implements CallWrapper {
    private ClassLoader saveCcl;

    @Override
    public void before() {
      saveCcl = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(CACHED_CLASSLOADER);
    }

    @Override
    public void after() {
      Thread.currentThread().setContextClassLoader(saveCcl);

    };
  }

  public static CallWrapper inContext() {
    return new CurrentContextWrapper();
  }

  /**
   * Execute an operation (synchronously) using the context classloader used to load this class,
   * instead of the currently-set context classloader of the current thread. This allows loading
   * dynamically-loaded classes and configuration files using the same classloader used to load the
   * rest of the JDBC driver. The context classloader of the current thread is reset to its original
   * value after the callable has been executed.
   * @param target the callable to be executed
   * @return the return value from the callable
   */
  public static <T> T call(Callable<T> target) throws Exception {
    ClassLoader saveCcl = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(CACHED_CLASSLOADER);
      return target.call();
    } finally {
      Thread.currentThread().setContextClassLoader(saveCcl);
    }
  }

  /**
   * Same as {@link #call(java.util.concurrent.Callable)}, but doesn't throw checked exceptions.
   * @param target the callable to be executed
   * @return the return value from the callable
   */
  public static <T> T callWithoutPropagation(Callable<T> target) {
    try {
      return call(target);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

}
