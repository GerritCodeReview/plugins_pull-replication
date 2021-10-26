package com.googlesource.gerrit.plugins.replication.pull.api;

import static com.googlesource.gerrit.plugins.replication.pull.api.ProjectInitializationAction.getProjectInitializationUrl;

import com.google.common.net.MediaType;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.message.BasicHeader;
import org.junit.Test;

public class ProjectInitializationActionIT extends ActionITBase {

  @Test
  public void shouldReturnUnauthorizedForUserWithoutPermissions() throws Exception {
    httpClientFactory
        .create(source)
        .execute(createPutRequestWithHeaders(), assertHttpResponseCode(401), getAnonymousContext());
  }

  @Test
  public void shouldReturnBadRequestItContentNotSet() throws Exception {
    httpClientFactory
        .create(source)
        .execute(createPutRequestWithoutHeaders(), assertHttpResponseCode(400), getContext());
  }

  @Test
  public void shouldCreateRepository() throws Exception {
    httpClientFactory
        .create(source)
        .execute(createPutRequestWithHeaders(), assertHttpResponseCode(200), getContext());

    HttpGet getNewProjectRequest = new HttpGet(userRestSession.url() + "/a/projects/newProject");
    httpClientFactory
        .create(source)
        .execute(getNewProjectRequest, assertHttpResponseCode(200), getContext());
  }

  @Override
  protected String getURL() {
    return userRestSession.url() + getProjectInitializationUrl("pull-replication", "newProject");
  }

  protected HttpPut createPutRequestWithHeaders() {
    HttpPut put = createPutRequestWithoutHeaders();
    put.addHeader(new BasicHeader("Accept", MediaType.ANY_TEXT_TYPE.toString()));
    put.addHeader(new BasicHeader("Content-Type", MediaType.PLAIN_TEXT_UTF_8.toString()));
    return put;
  }

  protected HttpPut createPutRequestWithoutHeaders() {
    HttpPut put = new HttpPut(url);
    return put;
  }
}
