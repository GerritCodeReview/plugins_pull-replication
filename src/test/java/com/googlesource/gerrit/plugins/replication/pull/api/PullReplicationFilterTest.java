package com.googlesource.gerrit.plugins.replication.pull.api;

import org.junit.Test;

public class PullReplicationFilterTest {
  @Test
  public void shouldFilterFetchAction() {}

  @Test
  public void shouldFilterApplyObjectAction() {}

  @Test
  public void shouldFilterApplyObjectsAction() {}

  @Test
  public void shouldFilterProjectInitializationAction() {}

  @Test
  public void shouldFilterUpdateHEADAction() {}

  @Test
  public void shouldFilterProjectDeletionAction() {}

  @Test
  public void shouldBe404WhenIsNotPullReplicationURL() {}

  @Test
  public void shouldBe404WhenJsonIsMalformed() {}

  @Test
  public void shouldBe500WhenProjectCannotBeInitiated() {}

  @Test
  public void shouldBe500WhenResourceNotFound() {}

  @Test
  public void shouldBe403WhenUserIsNotAuthorised() {}

  @Test
  public void shouldBe422WhenEntityCannotBeProcessed() {}

  @Test
  public void shouldBe409WhenThereIsResourceConflict() {}

}
