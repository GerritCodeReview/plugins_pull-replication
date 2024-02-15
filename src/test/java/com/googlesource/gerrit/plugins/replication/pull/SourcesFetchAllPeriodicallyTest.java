// Copyright (C) 2024 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.util.Providers;
import com.googlesource.gerrit.plugins.replication.ReplicationFilter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class SourcesFetchAllPeriodicallyTest {
  private static final NameKey RANDOM_PROJECT_NAME = Project.nameKey("random_project_name");

  @Mock WorkQueue workQueueMock;
  @Mock SourcesCollection sourcesMock;
  @Mock SourceFetchAllPeriodically.Factory factoryMock;

  private SourcesFetchAllPeriodically objectUnderTest;

  @Before
  public void setup() {
    objectUnderTest =
        new SourcesFetchAllPeriodically(
            workQueueMock, Providers.of(sourcesMock), Providers.of(factoryMock));
  }

  @Test
  public void shouldMatchAnyProjectWhenNoProjectsToFetchPeriodicallyAreConfigured() {
    // given
    when(sourcesMock.getAll()).thenReturn(Collections.emptyList());

    // when
    ReplicationFilter filter = objectUnderTest.skipFromReplicateAllOnPluginStart();

    // then
    assertThat(filter.matches(RANDOM_PROJECT_NAME)).isTrue();
  }

  @Test
  public void shouldNotMatchProjectConfiguredToFetchPeriodically() {
    // given
    Project.NameKey projectToFetch = Project.nameKey("to_be_fetched");
    SourceFetchAllPeriodically fetchProjectForSource = mock(SourceFetchAllPeriodically.class);
    when(fetchProjectForSource.projectsToFetch()).thenReturn(Stream.of(projectToFetch));

    Source sourceWithPeriodicFetch = mock(Source.class);
    when(sourceWithPeriodicFetch.fetchAllEvery()).thenReturn(1L);
    when(sourcesMock.getAll()).thenReturn(List.of(sourceWithPeriodicFetch));
    when(factoryMock.create(any())).thenReturn(fetchProjectForSource);

    // when
    ReplicationFilter filter = objectUnderTest.skipFromReplicateAllOnPluginStart();

    // then
    assertThat(filter.matches(projectToFetch)).isFalse();
    assertThat(filter.matches(RANDOM_PROJECT_NAME)).isTrue();
  }
}
