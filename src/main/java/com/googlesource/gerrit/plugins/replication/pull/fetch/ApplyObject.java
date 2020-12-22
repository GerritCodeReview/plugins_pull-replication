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

package com.googlesource.gerrit.plugins.replication.pull.fetch;

import java.io.IOException;

import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.LocalDiskRepositoryManager;
import com.google.inject.Inject;

public class ApplyObject {
	
	private final GitRepositoryManager gitManager;
	
	@Inject
	public ApplyObject(LocalDiskRepositoryManager gitManager) {
		this.gitManager = gitManager;
	}


	public RefUpdateState apply(Project.NameKey name, RefSpec refSpec, String object) throws IOException {
		try (Repository git = gitManager.openRepository(name)) {
		
		      ObjectId newObjectID = null;
		      try (ObjectInserter oi = git.newObjectInserter();
		          RevWalk rw = new RevWalk(git)) {
		        RevCommit commit = RevCommit.parse(rw, object.getBytes());
		        CommitBuilder cb = new CommitBuilder();
		        cb.setAuthor(commit.getAuthorIdent());
		        cb.setCommitter(commit.getCommitterIdent());
		        cb.setMessage(commit.getFullMessage());
		        cb.setParentIds(commit.getParents());
		        cb.setTreeId(commit.getTree());
		        cb.setEncoding(commit.getEncoding());

		        newObjectID = oi.insert(cb);
		        oi.flush();
		      }
		      RefUpdate ru = git.updateRef(refSpec.getSource());
		      ru.setNewObjectId(newObjectID);
		      RefUpdate.Result result = ru.update();
		     	
		      return new RefUpdateState(refSpec.getSource(), result);
		}

}
}
