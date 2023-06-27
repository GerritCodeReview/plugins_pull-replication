// Copyright (C) 2022 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.client;

import static com.google.common.truth.Truth.assertThat;

import java.util.Arrays;
import java.util.Optional;
import javax.servlet.http.HttpServletResponse;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class HttpResultTest {

  @Parameterized.Parameters(name = "HTTP Status = {0} is successful: {1}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(
        new Object[][] {
          {HttpServletResponse.SC_OK, true},
          {HttpServletResponse.SC_CREATED, true},
          {HttpServletResponse.SC_ACCEPTED, true},
          {HttpServletResponse.SC_NO_CONTENT, true},
          {HttpServletResponse.SC_BAD_REQUEST, false},
          {HttpServletResponse.SC_NOT_FOUND, false},
          {HttpServletResponse.SC_CONFLICT, false}
        });
  }

  private Integer httpStatus;
  private boolean isSuccessful;

  public HttpResultTest(Integer httpStatus, Boolean isSuccessful) {
    this.httpStatus = httpStatus;
    this.isSuccessful = isSuccessful;
  }

  @Test
  public void httpResultIsSuccessful() {
    HttpResult httpResult = new HttpResult(httpStatus, Optional.empty());
    assertThat(httpResult.isSuccessful()).isEqualTo(isSuccessful);
  }
}
