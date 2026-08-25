package com.windmill.service.recommendation;

import com.windmill.domain.CongestionSensitivity;
import com.windmill.domain.InferredSource;
import com.windmill.domain.PlaceSituationalTags;
import com.windmill.domain.RainSensitivity;
import com.windmill.repository.PlaceSituationalTagsRepository;
import com.windmill.util.KoreaClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SituationalTagService {

    private final PlaceSituationalTagsRepository repository;

    @Transactional
    public PlaceSituationalTags ensureInferred(String contentId, Integer contentTypeId, String cat3,
                                               String placeName, String overview) {
        if (contentId == null || contentId.isBlank()) {
            return fromInference(contentId, contentTypeId, cat3, placeName, overview);
        }
        PlaceSituationalTags existing = repository.findById(contentId).orElse(null);
        if (existing != null && existing.getInferredSource() == InferredSource.MANUAL) {
            return existing;
        }
        SituationalInference.Result inferred = SituationalInference.infer(contentTypeId, cat3, placeName, overview);
        if (existing == null) {
            existing = PlaceSituationalTags.builder().contentId(contentId).build();
        }
        existing.setIndoorYn(inferred.indoor());
        existing.setRainSensitivity(inferred.rain());
        existing.setCongestionSensitivity(inferred.congestion());
        existing.setInferredSource(InferredSource.RULE);
        existing.setUpdatedAt(KoreaClock.now());
        return repository.save(existing);
    }

    @Transactional
    public PlaceSituationalTags saveManual(String contentId, Boolean indoorYn, RainSensitivity rain,
                                           CongestionSensitivity congestion) {
        PlaceSituationalTags row = repository.findById(contentId)
                .orElseGet(() -> PlaceSituationalTags.builder().contentId(contentId).build());
        if (indoorYn != null) {
            row.setIndoorYn(indoorYn);
        }
        if (rain != null) {
            row.setRainSensitivity(rain);
        }
        if (congestion != null) {
            row.setCongestionSensitivity(congestion);
        }
        if (row.getRainSensitivity() == null) {
            row.setRainSensitivity(Boolean.TRUE.equals(row.getIndoorYn())
                    ? RainSensitivity.INSENSITIVE : RainSensitivity.SENSITIVE);
        }
        if (row.getCongestionSensitivity() == null) {
            row.setCongestionSensitivity(CongestionSensitivity.SENSITIVE);
        }
        row.setInferredSource(InferredSource.MANUAL);
        row.setUpdatedAt(KoreaClock.now());
        return repository.save(row);
    }

    @Transactional(readOnly = true)
    public Map<String, PlaceSituationalTags> findByContentIds(Collection<String> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Map.of();
        }
        List<PlaceSituationalTags> rows = repository.findByContentIdIn(contentIds);
        Map<String, PlaceSituationalTags> map = new HashMap<>();
        for (PlaceSituationalTags row : rows) {
            map.put(row.getContentId(), row);
        }
        return map;
    }

    private static PlaceSituationalTags fromInference(String contentId, Integer contentTypeId, String cat3,
                                                      String placeName, String overview) {
        SituationalInference.Result inferred = SituationalInference.infer(contentTypeId, cat3, placeName, overview);
        return PlaceSituationalTags.builder()
                .contentId(contentId)
                .indoorYn(inferred.indoor())
                .rainSensitivity(inferred.rain())
                .congestionSensitivity(inferred.congestion())
                .inferredSource(InferredSource.RULE)
                .updatedAt(KoreaClock.now())
                .build();
    }
}
