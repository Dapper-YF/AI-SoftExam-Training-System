package com.aiexam.foundation.auth.domain;

import com.aiexam.foundation.auth.dto.ChallengeDto;

public record IssuedChallenge(
    ChallengeDto challenge,
    boolean deliverable,
    String deliveryChannel,
    String deliveryMobile
) {
  public IssuedChallenge(ChallengeDto challenge, boolean deliverable, String deliveryMobile) {
    this(challenge, deliverable, "MOBILE", deliveryMobile);
  }
}
