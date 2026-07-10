// Copyright (C) 2025 The Android Open Source Project
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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.errors.TransportException;

public class BadObjectTransportException extends TransportException {
  private static final long serialVersionUID = 1L;
  private static final Pattern BAD_OBJECT_REF_PATTERN =
      Pattern.compile(".*fatal: bad object (refs/\\S+).*", Pattern.DOTALL);

  private final String badRef;

  public BadObjectTransportException(String badRef, TransportException cause) {
    super("Local ref " + badRef + " points to a missing object", cause);
    this.badRef = badRef;
  }

  public String getBadRef() {
    return badRef;
  }

  public static Optional<BadObjectTransportException> wrapIfBadObject(TransportException e) {
    Matcher m = BAD_OBJECT_REF_PATTERN.matcher(e.getMessage());
    if (m.matches()) {
      return Optional.of(new BadObjectTransportException(m.group(1), e));
    }
    return Optional.empty();
  }
}
