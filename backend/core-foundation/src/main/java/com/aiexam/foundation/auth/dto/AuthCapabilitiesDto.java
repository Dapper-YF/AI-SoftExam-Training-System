package com.aiexam.foundation.auth.dto;

public record AuthCapabilitiesDto(
    AuthProviderCapabilityDto usernamePassword,
    AuthProviderCapabilityDto mobile,
    AuthProviderCapabilityDto email,
    AuthProviderCapabilityDto wechat,
    boolean registrationVerificationRequired,
    CredentialPolicyDto credentialPolicy,
    boolean adminRegistrationEnabled
) {
}
