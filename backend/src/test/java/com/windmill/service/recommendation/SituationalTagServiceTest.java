package com.windmill.service.recommendation;

import com.windmill.domain.CongestionSensitivity;
import com.windmill.domain.InferredSource;
import com.windmill.domain.PlaceSituationalTags;
import com.windmill.domain.RainSensitivity;
import com.windmill.repository.PlaceSituationalTagsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SituationalTagServiceTest {

    private PlaceSituationalTagsRepository repository;
    private SituationalTagService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlaceSituationalTagsRepository.class);
        service = new SituationalTagService(repository);
        when(repository.save(any(PlaceSituationalTags.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void manualRowIsNotOverwrittenByReInference() {
        PlaceSituationalTags manual = PlaceSituationalTags.builder()
                .contentId("100")
                .indoorYn(true)
                .rainSensitivity(RainSensitivity.INSENSITIVE)
                .congestionSensitivity(CongestionSensitivity.INSENSITIVE)
                .inferredSource(InferredSource.MANUAL)
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();
        when(repository.findById("100")).thenReturn(Optional.of(manual));

        PlaceSituationalTags out = service.ensureInferred(
                "100", 12, "A01010100", "국립공원", "야외 정상에서 보는 전망");

        assertEquals(InferredSource.MANUAL, out.getInferredSource());
        assertTrue(out.getIndoorYn());
        assertEquals(RainSensitivity.INSENSITIVE, out.getRainSensitivity());
        verify(repository, never()).save(any());
    }

    @Test
    void ruleRowIsRecomputed() {
        PlaceSituationalTags existing = PlaceSituationalTags.builder()
                .contentId("200")
                .indoorYn(false)
                .rainSensitivity(RainSensitivity.SENSITIVE)
                .congestionSensitivity(CongestionSensitivity.SENSITIVE)
                .inferredSource(InferredSource.RULE)
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();
        when(repository.findById("200")).thenReturn(Optional.of(existing));

        PlaceSituationalTags out = service.ensureInferred(
                "200", 14, "A02060100", "시립박물관", "실내 상설 전시");

        assertEquals(InferredSource.RULE, out.getInferredSource());
        assertTrue(out.getIndoorYn());
        assertEquals(RainSensitivity.INSENSITIVE, out.getRainSensitivity());
        verify(repository).save(existing);
    }

    @Test
    void saveManualMarksSourceAndFillsRainDefault() {
        when(repository.findById("300")).thenReturn(Optional.empty());

        PlaceSituationalTags out = service.saveManual("300", false, null, null);

        assertFalse(out.getIndoorYn());
        assertEquals(RainSensitivity.SENSITIVE, out.getRainSensitivity());
        assertEquals(CongestionSensitivity.SENSITIVE, out.getCongestionSensitivity());
        assertEquals(InferredSource.MANUAL, out.getInferredSource());
    }
}
