package com.aiexam.app.migration.v9;

import com.aiexam.app.migration.deploymentcontrol.AttemptState;

public interface V9ExecutionFence {
  void verifyBoundary(String boundary, AttemptState expectedState);

  void transition(AttemptState from, AttemptState to, String evidenceDigest);
}
