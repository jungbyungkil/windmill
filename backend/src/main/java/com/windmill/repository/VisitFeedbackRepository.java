package com.windmill.repository;

import com.windmill.domain.VisitFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitFeedbackRepository extends JpaRepository<VisitFeedback, Long> {
}
