package com.loan.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScorecardResultVO {

    private Integer totalScore;

    private String scoreLevel;

    private String decision;

    private String decisionDesc;

    private java.util.Map<String, Object> featureScores;
}