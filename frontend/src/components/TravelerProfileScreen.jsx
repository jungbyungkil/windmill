import { useEffect, useState } from 'react';
import { TrashIcon } from './Icons';
import {
  COMPANION_TYPE_OPTIONS,
  AGE_GROUP_OPTIONS,
  CHILD_AGE_OPTIONS,
  FIXED_PARTY_SIZE_BY_COMPANION_TYPE,
  EXTENDED_FAMILY_MIN_SIZE,
  EXTENDED_FAMILY_MAX_SIZE,
} from '../constants';
import { readTravelerProfile, writeTravelerProfile, clearTravelerProfile } from '../utils/travelerProfile';

function defaultsFromProfile(profile) {
  return {
    companionType: profile?.companionType || 'SOLO',
    partySize: profile?.totalCount || 1,
    withPet: Boolean(profile?.accessibility?.pet),
    strollerFriendly: Boolean(profile?.accessibility?.stroller),
    accessibleFriendly: Boolean(profile?.accessibility?.barrierFree),
    adultAgeGroup: profile?.adultAgeGroup || 'THIRTIES',
    childAges: [...(profile?.childrenAges || [])],
  };
}

/**
 * 프로필 > 내 여행 정보 - 홈 화면 "동반 · 접근성"에 저장된 값을 확인·수정하거나 초기화한다
 * (2026-09-12 브리프 5-4절). 여행 지역·날짜는 여기서 다루지 않는다(홈 화면 전용 필드).
 */
export default function TravelerProfileScreen() {
  const [savedProfile, setSavedProfile] = useState(readTravelerProfile);
  const [form, setForm] = useState(() => defaultsFromProfile(savedProfile));
  const [partySizeTouched, setPartySizeTouched] = useState(false);
  const [resetDone, setResetDone] = useState(false);

  const isExtendedFamily = form.companionType === 'EXTENDED_FAMILY';
  const partySizeError = isExtendedFamily
    && (form.partySize < EXTENDED_FAMILY_MIN_SIZE || form.partySize > EXTENDED_FAMILY_MAX_SIZE)
    ? `대가족 여행은 ${EXTENDED_FAMILY_MIN_SIZE}명 이상 ${EXTENDED_FAMILY_MAX_SIZE}명 이하만 가능해요`
    : null;
  const maxChildren = Math.max(0, (form.partySize || 0) - 1);
  const canAddChild = form.childAges.length < maxChildren;

  useEffect(() => {
    if (form.childAges.length > maxChildren) {
      persist({ childAges: form.childAges.slice(0, maxChildren) });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [maxChildren]);

  function persist(next) {
    const merged = { ...form, ...next };
    setForm(merged);
    setResetDone(false);
    const written = writeTravelerProfile({
      companionType: merged.companionType,
      totalCount: merged.partySize,
      accessibility: { pet: merged.withPet, stroller: merged.strollerFriendly, barrierFree: merged.accessibleFriendly },
      adultAgeGroup: merged.adultAgeGroup,
      childrenAges: merged.childAges,
      lastRegion: savedProfile?.lastRegion,
    });
    setSavedProfile(written);
  }

  function handleCompanionTypeChange(value) {
    const fixedSize = FIXED_PARTY_SIZE_BY_COMPANION_TYPE[value];
    const nextPartySize = fixedSize != null
      ? fixedSize
      : (form.partySize < EXTENDED_FAMILY_MIN_SIZE || form.partySize > EXTENDED_FAMILY_MAX_SIZE
        ? EXTENDED_FAMILY_MIN_SIZE
        : form.partySize);
    setPartySizeTouched(false);
    persist({ companionType: value, partySize: nextPartySize });
  }

  function handleAddChild() {
    if (!canAddChild) return;
    persist({ childAges: [...form.childAges, 10] });
  }

  function handleChangeChildAge(index, age) {
    persist({ childAges: form.childAges.map((a, i) => (i === index ? age : a)) });
  }

  function handleRemoveChild(index) {
    persist({ childAges: form.childAges.filter((_, i) => i !== index) });
  }

  function handleReset() {
    if (!window.confirm('저장된 동반·접근성 정보를 초기화할까요? 다음 방문 때 다시 입력하게 돼요.')) return;
    clearTravelerProfile();
    setSavedProfile(null);
    setForm(defaultsFromProfile(null));
    setPartySizeTouched(false);
    setResetDone(true);
  }

  return (
    <div className="traveler-profile-screen">
      <p className="settings-section-hint">
        홈 화면 &quot;동반 · 접근성&quot;에 저장된 값이에요. 여기서 바꾸면 다음 홈 화면에도 그대로 반영돼요.
      </p>

      <div className="trip-form-row">
        <label className="trip-form-label">누구와 함께하나요?</label>
        <div className="reco-tag-row">
          {COMPANION_TYPE_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              className={`tag ${form.companionType === opt.value ? 'selected' : ''}`}
              onClick={() => handleCompanionTypeChange(opt.value)}
            >
              {opt.label}
            </button>
          ))}
        </div>
        <label className="trip-form-party-size">
          총 인원수
          <input
            type="number"
            min={isExtendedFamily ? EXTENDED_FAMILY_MIN_SIZE : form.partySize}
            max={isExtendedFamily ? EXTENDED_FAMILY_MAX_SIZE : form.partySize}
            value={form.partySize}
            readOnly={!isExtendedFamily}
            disabled={!isExtendedFamily}
            aria-invalid={partySizeTouched && Boolean(partySizeError)}
            onChange={(e) => {
              if (!isExtendedFamily) return;
              const n = parseInt(e.target.value, 10);
              if (!Number.isNaN(n)) persist({ partySize: n });
            }}
            onBlur={() => setPartySizeTouched(true)}
          />
          <span className="trip-form-hint-inline">
            {isExtendedFamily ? '명 · 5~9명까지 적을 수 있어요' : '명 · 동반 유형에 맞춰 표시돼요'}
          </span>
        </label>
        {isExtendedFamily && partySizeTouched && partySizeError && (
          <div className="error-msg">❌ {partySizeError}</div>
        )}
        <div className="trip-form-checkbox-row">
          <label className="trip-form-checkbox">
            <input type="checkbox" checked={form.withPet} onChange={(e) => persist({ withPet: e.target.checked })} />
            반려동물
          </label>
          <label className="trip-form-checkbox" title="유모차 이용 가능한 곳을 우선 추천해요">
            <input
              type="checkbox"
              checked={form.strollerFriendly}
              onChange={(e) => persist({ strollerFriendly: e.target.checked })}
            />
            유모차
          </label>
          <label className="trip-form-checkbox" title="장애인 동반 - 무장애 시설을 우선 추천해요">
            <input
              type="checkbox"
              checked={form.accessibleFriendly}
              onChange={(e) => persist({ accessibleFriendly: e.target.checked })}
            />
            무장애
          </label>
        </div>
      </div>

      <div className="trip-form-row">
        <label className="trip-form-label">성인 연령대</label>
        <div className="reco-tag-row">
          {AGE_GROUP_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              className={`tag ${form.adultAgeGroup === opt.value ? 'selected' : ''}`}
              onClick={() => persist({ adultAgeGroup: opt.value })}
            >
              {opt.label}
            </button>
          ))}
        </div>

        <div className="trip-form-child-ages">
          <div className="trip-form-child-ages-head">
            <span className="trip-form-child-ages-label">동반 자녀 나이 (선택)</span>
            <button
              type="button"
              className="btn-child-add"
              onClick={handleAddChild}
              disabled={!canAddChild}
              title={!canAddChild ? '성인 최소 1명을 위해 더 이상 자녀를 추가할 수 없어요' : undefined}
            >
              + 자녀 추가
            </button>
          </div>
          {form.childAges.map((age, index) => (
            <div key={index} className="trip-form-child-row">
              <span className="trip-form-child-index">자녀 {index + 1}</span>
              <select value={age} onChange={(e) => handleChangeChildAge(index, Number(e.target.value))}>
                {CHILD_AGE_OPTIONS.map((opt) => (
                  <option key={opt.value} value={opt.value}>{opt.label}</option>
                ))}
              </select>
              <button
                type="button"
                className="icon-btn danger"
                aria-label="자녀 삭제"
                onClick={() => handleRemoveChild(index)}
              >
                <TrashIcon size={16} />
              </button>
            </div>
          ))}
        </div>
      </div>

      <button type="button" className="btn-secondary settings-push-btn" onClick={handleReset}>
        초기화
      </button>
      {resetDone && <p className="settings-section-hint">초기화했어요. 다음 방문 때 다시 입력하게 돼요.</p>}
    </div>
  );
}
