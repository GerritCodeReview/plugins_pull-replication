// Copyright (C) 2020 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.project.ProjectResource;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionInput;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionObjectData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import java.io.IOException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ApplyObjectActionTest {
  ApplyObjectAction applyObjectAction;
  String label = "instance-2-label";
  String url = "file:///gerrit-host/instance-1/git/${name}.git";
  String refName = "refs/heads/master";
  String location = "http://gerrit-host/a/config/server/tasks/08d173e9";
  int taskId = 1234;

  private String sampleCommitContent =
      "tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904\n"
          + "parent 20eb48d28be86dc88fb4bef747f08de0fbefe12d\n"
          + "author Gerrit User 1000000 <1000000@69ec38f0-350e-4d9c-96d4-bc956f2faaac> 1610471611 +0100\n"
          + "committer Gerrit Code Review <root@maczech-XPS-15> 1610471611 +0100\n"
          + "\n"
          + "Update patch set 1\n"
          + "\n"
          + "Change has been successfully merged by Administrator\n"
          + "\n"
          + "Patch-set: 1\n"
          + "Status: merged\n"
          + "Tag: autogenerated:gerrit:merged\n"
          + "Reviewer: Gerrit User 1000000 <1000000@69ec38f0-350e-4d9c-96d4-bc956f2faaac>\n"
          + "Label: SUBM=+1\n"
          + "Submission-id: 1904-1610471611558-783c0a2f\n"
          + "Submitted-with: OK\n"
          + "Submitted-with: OK: Code-Review: Gerrit User 1000000 <1000000@69ec38f0-350e-4d9c-96d4-bc956f2faaac>";

  @Mock ApplyObjectCommand applyObjectCommand;
  @Mock ProjectResource projectResource;
  @Mock FetchPreconditions preConditions;

  @Before
  public void setup() {
    when(preConditions.canCallFetchApi()).thenReturn(true);

    applyObjectAction = new ApplyObjectAction(applyObjectCommand, preConditions);
  }

  @Test
  public void shouldReturnCreatedResponseCode() throws RestApiException {
    RevisionInput inputParams = new RevisionInput(label, refName, createSampleRevisionData());

    Response<?> response = applyObjectAction.apply(projectResource, inputParams);

    assertThat(response.statusCode()).isEqualTo(SC_CREATED);
  }

  @SuppressWarnings("cast")
  @Test
  public void shouldReturnSourceUrlAndrefNameAsAResponseBody() throws Exception {
    RevisionInput inputParams = new RevisionInput(label, refName, createSampleRevisionData());
    Response<?> response = applyObjectAction.apply(projectResource, inputParams);

    assertThat((RevisionInput) response.value()).isEqualTo(inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenMissingLabel() throws Exception {
    RevisionInput inputParams = new RevisionInput(null, refName, createSampleRevisionData());

    applyObjectAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenEmptyLabel() throws Exception {
    RevisionInput inputParams = new RevisionInput("", refName, createSampleRevisionData());

    applyObjectAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenMissingRefName() throws Exception {
    RevisionInput inputParams = new RevisionInput(label, null, createSampleRevisionData());

    applyObjectAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenEmptyRefName() throws Exception {
    RevisionInput inputParams = new RevisionInput(label, "", createSampleRevisionData());

    applyObjectAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenMissingRevisionData() throws Exception {
    RevisionInput inputParams = new RevisionInput(label, refName, null);

    applyObjectAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenMissingCommitObjectData() throws Exception {
    RevisionObjectData commitData = new RevisionObjectData(Constants.OBJ_COMMIT, null);
    RevisionObjectData treeData = new RevisionObjectData(Constants.OBJ_TREE, new byte[] {});
    RevisionInput inputParams =
        new RevisionInput(label, refName, createSampleRevisionData(commitData, treeData));

    applyObjectAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenMissingTreeObject() throws Exception {
    RevisionObjectData commitData =
        new RevisionObjectData(Constants.OBJ_COMMIT, sampleCommitContent.getBytes());
    RevisionInput inputParams =
        new RevisionInput(label, refName, createSampleRevisionData(commitData, null));

    applyObjectAction.apply(projectResource, inputParams);
  }

  @Test(expected = AuthException.class)
  public void shouldThrowAuthExceptionWhenCallFetchActionCapabilityNotAssigned()
      throws RestApiException {
    RevisionInput inputParams = new RevisionInput(label, refName, createSampleRevisionData());

    when(preConditions.canCallFetchApi()).thenReturn(false);

    applyObjectAction.apply(projectResource, inputParams);
  }

  @Test(expected = ResourceConflictException.class)
  public void shouldThrowResourceConflictExceptionWhenMissingParentObject()
      throws RestApiException, IOException, RefUpdateException, MissingParentObjectException {
    RevisionInput inputParams = new RevisionInput(label, refName, createSampleRevisionData());

    doThrow(
            new MissingParentObjectException(
                Project.nameKey("test_projects"), refName, ObjectId.zeroId()))
        .when(applyObjectCommand)
        .applyObject(any(), anyString(), any(), anyString());

    applyObjectAction.apply(projectResource, inputParams);
  }

  private RevisionData createSampleRevisionData() {
    RevisionObjectData commitData =
        new RevisionObjectData(Constants.OBJ_COMMIT, sampleCommitContent.getBytes());
    RevisionObjectData treeData = new RevisionObjectData(Constants.OBJ_TREE, new byte[] {});
    return createSampleRevisionData(commitData, treeData);
  }

  private RevisionData createSampleRevisionData(
      RevisionObjectData commitData, RevisionObjectData treeData) {
    return new RevisionData(commitData, treeData, Lists.newArrayList());
  }
}
