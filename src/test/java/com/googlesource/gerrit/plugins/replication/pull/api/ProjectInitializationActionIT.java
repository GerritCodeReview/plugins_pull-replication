// Copyright (C) 2021 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.api;

import static com.googlesource.gerrit.plugins.replication.pull.api.ProjectInitializationAction.getProjectInitializationUrl;

import com.google.common.net.MediaType;
import com.google.gerrit.extensions.restapi.Url;
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

    HttpGet getNewProjectRequest =
        new HttpGet(userRestSession.url() + "/a/projects/" + Url.encode("new/Project"));
    httpClientFactory
        .create(source)
        .execute(getNewProjectRequest, assertHttpResponseCode(200), getContext());
  }

  @Override
  protected String getURL() {
    return userRestSession.url()
        + "/"
        + getProjectInitializationUrl("pull-replication", "new/Project");
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
