package com.aiexam.common.error;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends BusinessException {
  public ResourceNotFoundException(String message) {
    super(ErrorCode.NOT_FOUND, message, HttpStatus.NOT_FOUND);
  }
}
