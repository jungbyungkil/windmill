package com.windmill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** TourAPI intro 원문 한 줄. 값이 비면 만들지 않는다. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetailFact {
    private String key;
    private String label;
    private String value;
}
