package com.loan.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureTransformationResultDTO {

    private UserProfileDTO userProfile;

    private Map<String, Object> transformationDetails;

    private Boolean isValid;

    private String errorMessage;

    public static FeatureTransformationResultDTO success(UserProfileDTO profile, Map<String, Object> details) {
        return FeatureTransformationResultDTO.builder()
                .userProfile(profile)
                .transformationDetails(details)
                .isValid(true)
                .build();
    }

    public static FeatureTransformationResultDTO failure(String errorMessage) {
        return FeatureTransformationResultDTO.builder()
                .isValid(false)
                .errorMessage(errorMessage)
                .build();
    }
}
