// Copyright (C) 2023 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.replication.pull;

import com.google.gerrit.metrics.Counter0;
import com.google.gerrit.metrics.Counter1;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.metrics.Field;
import com.google.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Exports no metrics but counters kept in memory for testing purposes */
@Singleton
public class InMemoryMetricMaker extends DisabledMetricMaker {

  public static class InMemoryCounter0 extends Counter0 {
    private AtomicLong counter = new AtomicLong();

    @Override
    public void remove() {}

    @Override
    public void incrementBy(long delta) {
      counter.addAndGet(delta);
    }

    public long getValue() {
      return counter.get();
    }

    @Override
    public String toString() {
      return counter.toString();
    }
  }

  public static class InMemoryCounter1<F> extends Counter1<F> {
    private ConcurrentHashMap<F, Long> countersMap = new ConcurrentHashMap<>();

    @Override
    public void remove() {}

    @Override
    public void incrementBy(F field, long delta) {
      countersMap.compute(field, (f, counter) -> counter == null ? delta : counter + delta);
    }

    public Optional<Long> getValue(Object fieldValue) {
      return Optional.ofNullable(countersMap.get(fieldValue));
    }

    @Override
    public String toString() {
      return countersMap.toString();
    }
  }

  private ConcurrentHashMap<String, InMemoryCounter0> counters0Map = new ConcurrentHashMap<>();
  private ConcurrentHashMap<String, InMemoryCounter1<?>> counters1Map = new ConcurrentHashMap<>();

  @Override
  public Counter0 newCounter(String name, Description desc) {
    InMemoryCounter0 counter = new InMemoryCounter0();
    counters0Map.put(name, counter);
    return counter;
  }

  @Override
  public <F> Counter1<F> newCounter(String name, Description desc, Field<F> field) {
    InMemoryCounter1<F> counter = new InMemoryCounter1<>();
    counters1Map.put(name, counter);
    return counter;
  }

  public Optional<Long> counterValue(String name) {
    return Optional.ofNullable(counters0Map.get(name)).map(InMemoryCounter0::getValue);
  }

  public <F> Optional<Long> counterValue(String name, F fieldValue) {
    return Optional.ofNullable(counters1Map.get(name))
        .flatMap(counter -> counter.getValue(fieldValue));
  }

  @Override
  public String toString() {
    return counters0Map.toString() + "\n" + counters1Map.toString();
  }
}
