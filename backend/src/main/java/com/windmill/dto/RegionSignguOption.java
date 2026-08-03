package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RegionSignguOption {
    private String signguFullCode;
    private String signguName;
}
