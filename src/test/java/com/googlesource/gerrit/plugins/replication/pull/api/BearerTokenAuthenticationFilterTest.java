package com.googlesource.gerrit.plugins.replication.pull.api;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class BearerTokenAuthenticationFilterTest {

  @Test
  public void shouldAuthenticateWithBearerTokenWhenFetch(){}

  @Test
  public void shouldAuthenticateWithBearerTokenWhenApplyObject(){}

  @Test
  public void shouldAuthenticateWithBearerTokenWhenApplyObjects(){}

  @Test
  public void shouldAuthenticateWithBearerTokenWhenDeleteProject(){}

  @Test
  public void shouldAuthenticateWithBearerTokenWhenUpdateHead(){}

  @Test
  public void shouldAuthenticateWithBearerTokenWhenInitProject(){}

  @Test
  public void shouldBe401WhenBearerTokenIsNotCorrect(){}

  @Test
  public void shouldBe401WhenBearerTokenIsNotPresentInRequest(){}

  @Test
  public void shouldBe500WhenBearerTokenIsNotPresentInServer(){}

  @Test
  public void shouldGoNextInChainWhenUriDoesNotMatch(){}

  @Test
  public void shouldGoNextInChainWhenBasicAuthenticationRequired(){}

}
