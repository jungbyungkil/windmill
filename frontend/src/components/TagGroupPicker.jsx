import { useState } from 'react';
import { TAG_GROUPS } from '../constants';

/**
 * 28개 태그를 5개 카테고리로 묶어 접기/펴기로 보여준다 - RecommendationSearch/AutoPlanScreen/
 * ItineraryItemCard 3곳에서 동일하게 쓰던 flat 태그 목록을 공용화(2026-08-18).
 */
export default function TagGroupPicker({ selected, onToggle, className = '' }) {
  const [openGroups, setOpenGroups] = useState([]);

  function toggleGroup(label) {
    setOpenGroups((prev) => prev.includes(label) ? prev.filter((l) => l !== label) : [...prev, label]);
  }

  return (
    <div className={`tag-accordion ${className}`}>
      {TAG_GROUPS.map((group) => {
        const selectedCount = group.tags.filter((tag) => selected.includes(tag)).length;
        const isOpen = openGroups.includes(group.label);
        return (
          <div className="tag-group" key={group.label}>
            <button
              type="button"
              className="tag-group-header"
              onClick={() => toggleGroup(group.label)}
              aria-expanded={isOpen}
            >
              <span className="tag-group-title">{group.label}</span>
              {selectedCount > 0 && <span className="tag-group-count">{selectedCount}</span>}
              <span className="tag-group-caret">{isOpen ? '▾' : '▸'}</span>
            </button>
            {isOpen && (
              <div className="reco-tag-row tag-group-body">
                {group.tags.map((tag) => (
                  <button
                    type="button"
                    key={tag}
                    className={`tag ${selected.includes(tag) ? 'selected' : ''}`}
                    onClick={() => onToggle(tag)}
                  >
                    {tag}
                  </button>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
