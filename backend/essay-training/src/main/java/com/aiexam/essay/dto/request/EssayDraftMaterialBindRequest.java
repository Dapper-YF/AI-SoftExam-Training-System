package com.aiexam.essay.dto.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record EssayDraftMaterialBindRequest(
    @NotEmpty List<Long> materialIds
) {
}
