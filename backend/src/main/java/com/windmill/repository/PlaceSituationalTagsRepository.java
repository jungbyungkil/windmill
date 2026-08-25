package com.windmill.repository;

import com.windmill.domain.PlaceSituationalTags;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PlaceSituationalTagsRepository extends JpaRepository<PlaceSituationalTags, String> {
    List<PlaceSituationalTags> findByContentIdIn(Collection<String> contentIds);
}
