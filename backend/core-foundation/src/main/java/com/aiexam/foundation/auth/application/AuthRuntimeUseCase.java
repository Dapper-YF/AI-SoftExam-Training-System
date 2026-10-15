package com.aiexam.foundation.auth.application;

import com.aiexam.common.security.PlatformPrincipal;
import com.aiexam.foundation.auth.domain.AuthClientEvidence;
import com.aiexam.foundation.auth.dto.AuthCapabilitiesDto;
import com.aiexam.foundation.auth.dto.AuthContext;
import com.aiexam.foundation.auth.dto.ChallengeDto;
import com.aiexam.foundation.auth.dto.CommittedAuthSession;
import com.aiexam.foundation.auth.dto.RegistrationResultDto;
import com.aiexam.foundation.auth.dto.RotatedAuthSession;
import com.aiexam.foundation.auth.dto.SessionExpiryDto;
import com.aiexam.foundation.auth.dto.request.LoginRequest;
import com.aiexam.foundation.auth.dto.request.PasswordResetChallengeRequest;
import com.aiexam.foundation.auth.dto.request.PasswordResetRequest;
import com.aiexam.foundation.auth.dto.request.RegisterRequest;
import com.aiexam.foundation.auth.dto.request.RegistrationChallengeRequest;
import com.aiexam.foundation.auth.dto.request.SmsLoginChallengeRequest;
import com.aiexam.foundation.auth.dto.request.SmsLoginRequest;

public interface AuthRuntimeUseCase {
  AuthCapabilitiesDto capabilities();

  ChallengeDto createRegistrationChallenge(RegistrationChallengeRequest request);

  RegistrationResultDto register(RegisterRequest request);

  CommittedAuthSession login(LoginRequest request, AuthClientEvidence clientEvidence);

  default ChallengeDto createSmsLoginChallenge(SmsLoginChallengeRequest request) {
    throw new UnsupportedOperationException("SMS login is not configured");
  }

  default CommittedAuthSession smsLogin(SmsLoginRequest request, AuthClientEvidence clientEvidence) {
    throw new UnsupportedOperationException("SMS login is not configured");
  }

  AuthContext me(PlatformPrincipal principal);

  RotatedAuthSession refresh(String refreshToken);

  void logout(PlatformPrincipal principal, String refreshToken);

  ChallengeDto createPasswordResetChallenge(PasswordResetChallengeRequest request);

  void resetPassword(PasswordResetRequest request);
}
