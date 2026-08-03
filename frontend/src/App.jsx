import { useState, useEffect, useCallback } from 'react';
import useSession from './hooks/useSession';
import * as api from './api/windmillApi';
import CreateTripScreen from './components/CreateTripScreen';
import AutoPlanScreen from './components/AutoPlanScreen';
import PinwheelHero from './components/PinwheelHero';
import WeatherBanner from './components/WeatherBanner';
import ItineraryList from './components/ItineraryList';
import RecommendationSearch from './components/RecommendationSearch';
import AlternativesPanel from './components/AlternativesPanel';
import DocentModal from './components/DocentModal';
import TripRecordModal from './components/TripRecordModal';
import './App.css';

const TRIGGER_POLL_MS = 90 * 1000;

export default function App() {
  const { sessionId, itineraryId, setItineraryId } = useSession();

  const [itinerary, setItinerary] = useState(null);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState(null);
  const [showAutoPlan, setShowAutoPlan] = useState(false);

  const [trigger, setTrigger] = useState(null);
  const [weatherItems, setWeatherItems] = useState(null);

  const [recoResults, setRecoResults] = useState(null);
  const [recoLoading, setRecoLoading] = useState(false);
  const [addingContentId, setAddingContentId] = useState(null);

  const [altOpen, setAltOpen] = useState(false);
  const [altCandidates, setAltCandidates] = useState([]);
  const [altLoading, setAltLoading] = useState(false);

  const [docentOpen, setDocentOpen] = useState(false);
  const [docentPlaceName, setDocentPlaceName] = useState('');
  const [docentScript, setDocentScript] = useState('');
  const [docentAudioUrl, setDocentAudioUrl] = useState(null);
  const [docentLoading, setDocentLoading] = useState(false);
  const [docentError, setDocentError] = useState(null);

  const [tripRecordOpen, setTripRecordOpen] = useState(false);
  const [tripSubmitting, setTripSubmitting] = useState(false);
  const [rerouteCount, setRerouteCount] = useState(0);

  const [autoReplacing, setAutoReplacing] = useState(false);
  const [autoReplaceNotice, setAutoReplaceNotice] = useState(null);

  // 새로고침 시 저장된 itineraryId로 일정 복원
  useEffect(() => {
    if (!itineraryId) return;
    api.getItinerary(itineraryId)
      .then(setItinerary)
      .catch(() => setItineraryId(null));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [itineraryId]);

  const refreshTrigger = useCallback(() => {
    if (!itineraryId) return;
    api.getTriggerStatus(itineraryId).then(setTrigger).catch(() => {});
  }, [itineraryId]);

  useEffect(() => {
    if (!itineraryId) return;
    refreshTrigger();
    if (itinerary?.weatherNx && itinerary?.weatherNy) {
      api.getWeather(itinerary.weatherNx, itinerary.weatherNy).then(setWeatherItems).catch(() => setWeatherItems(null));
    }
    const id = setInterval(refreshTrigger, TRIGGER_POLL_MS);
    return () => clearInterval(id);
  }, [itineraryId, itinerary?.weatherNx, itinerary?.weatherNy, refreshTrigger]);

  async function handleCreate(formData) {
    setCreating(true);
    setCreateError(null);
    try {
      const result = await api.createItinerary(sessionId, formData);
      setItinerary(result);
      setItineraryId(result.itineraryId);
      setShowAutoPlan(true);
    } catch (e) {
      setCreateError(e.message);
    } finally {
      setCreating(false);
    }
  }

  async function handleUpdateTime(itemId, scheduledTime) {
    const result = await api.updateItem(itineraryId, itemId, { scheduledTime });
    setItinerary(result);
  }

  async function handleTogglePin(itemId, isPinned) {
    const result = await api.updateItem(itineraryId, itemId, { isPinned });
    setItinerary(result);
  }

  async function handleDeleteItem(itemId) {
    const result = await api.deleteItem(itineraryId, itemId);
    setItinerary(result);
  }

  async function handleSearch({ query, tags }) {
    setRecoLoading(true);
    try {
      const excludeContentIds = itinerary.items.map((i) => i.contentId).filter(Boolean);
      const results = await api.getRecommendations({
        regionCode: itinerary.signguFullCode,
        withPet: itinerary.withPet,
        companionType: itinerary.companionType,
        query,
        tags,
        excludeContentIds,
      });
      setRecoResults(results);
    } catch {
      setRecoResults([]);
    } finally {
      setRecoLoading(false);
    }
  }

  async function addCandidateToItinerary(candidate) {
    const result = await api.addItem(itineraryId, {
      contentId: candidate.contentId,
      placeName: candidate.placeName,
      thumbnailUrl: candidate.thumbnailUrl,
      tags: candidate.matchedTags,
      crowdRate: candidate.crowdRate,
    });
    setItinerary(result);
    return result;
  }

  async function handleAddRecommendation(candidate) {
    setAddingContentId(candidate.contentId);
    try {
      await addCandidateToItinerary(candidate);
    } finally {
      setAddingContentId(null);
    }
  }

  async function handleRequestAlternatives(avoidHint) {
    setAltOpen(true);
    setAltLoading(true);
    try {
      const candidates = await api.getAlternatives(itineraryId, { avoid: avoidHint });
      setAltCandidates(candidates);
    } catch {
      setAltCandidates([]);
    } finally {
      setAltLoading(false);
    }
  }

  async function handleAddAlternative(candidate) {
    setAddingContentId(candidate.contentId);
    try {
      await addCandidateToItinerary(candidate);
      setRerouteCount((n) => n + 1);
    } finally {
      setAddingContentId(null);
    }
  }

  // 자동 재배치: 트리거로 영향받은 첫 항목을 최상위 대안으로 자동 교체 (수동 "새 코스 추천받기"와 별도 액션)
  async function handleAutoReplace(avoidHint) {
    setAutoReplacing(true);
    setAutoReplaceNotice(null);
    try {
      const candidates = await api.getAlternatives(itineraryId, { avoid: avoidHint });
      if (candidates.length === 0) {
        setAutoReplaceNotice('지금은 자동으로 바꿀 대안이 없어요.');
        return;
      }
      const top = candidates[0];
      const affectedId = trigger?.affectedItemIds?.[0];
      const affectedItem = affectedId ? itinerary.items.find((i) => i.itemId === affectedId) : null;

      if (affectedItem) {
        await api.deleteItem(itineraryId, affectedItem.itemId);
        const result = await api.addItem(itineraryId, {
          contentId: top.contentId,
          placeName: top.placeName,
          thumbnailUrl: top.thumbnailUrl,
          scheduledTime: affectedItem.scheduledTime,
          tags: top.matchedTags,
          crowdRate: top.crowdRate,
        });
        setItinerary(result);
        setAutoReplaceNotice(`"${affectedItem.placeName}"을(를) "${top.placeName}"(으)로 자동 교체했어요.`);
      } else {
        await addCandidateToItinerary(top);
        setAutoReplaceNotice(`"${top.placeName}"을(를) 일정에 자동으로 추가했어요.`);
      }
      setRerouteCount((n) => n + 1);
      refreshTrigger();
    } catch (e) {
      setAutoReplaceNotice(`자동 교체 실패: ${e.message}`);
    } finally {
      setAutoReplacing(false);
      setTimeout(() => setAutoReplaceNotice(null), 5000);
    }
  }

  function handleGenerateAutoPlan(tags) {
    return api.getAutoPlan(itineraryId, { tags, placeCount: 5 });
  }

  async function handleConfirmAutoPlan(selected) {
    let result = itinerary;
    for (const candidate of selected) {
      result = await api.addItem(itineraryId, {
        contentId: candidate.contentId,
        placeName: candidate.placeName,
        thumbnailUrl: candidate.thumbnailUrl,
        scheduledTime: candidate.suggestedTime,
        tags: candidate.matchedTags,
        crowdRate: candidate.crowdRate,
      });
    }
    setItinerary(result);
    setShowAutoPlan(false);
  }

  async function handleOpenDocent(item) {
    setDocentOpen(true);
    setDocentPlaceName(item.placeName);
    setDocentScript('');
    setDocentAudioUrl(null);
    setDocentError(null);

    if (!item.contentTypeId) {
      setDocentError('이 장소는 정보가 부족해 도슨트를 준비할 수 없어요.');
      return;
    }
    setDocentLoading(true);
    try {
      const result = await api.getDocent(item.contentId, item.contentTypeId);
      setDocentScript(result.scriptText);
      setDocentAudioUrl(result.audioUrl || null);
    } catch (e) {
      setDocentError(e.message);
    } finally {
      setDocentLoading(false);
    }
  }

  async function handleSubmitTripRecord({ overallNote, visitFeedback }) {
    setTripSubmitting(true);
    try {
      await api.createTripRecord(sessionId, {
        itineraryId,
        overallNote,
        rerouteCount,
        visitFeedback,
      });
      setTripRecordOpen(false);
      setItineraryId(null);
      setItinerary(null);
      setTrigger(null);
      setRecoResults(null);
      setRerouteCount(0);
    } catch (e) {
      alert(e.message);
    } finally {
      setTripSubmitting(false);
    }
  }

  if (!itinerary) {
    return <CreateTripScreen onCreate={handleCreate} loading={creating} error={createError} />;
  }

  if (showAutoPlan) {
    return (
      <AutoPlanScreen
        onGenerate={handleGenerateAutoPlan}
        onConfirm={handleConfirmAutoPlan}
        onSkip={() => setShowAutoPlan(false)}
      />
    );
  }

  return (
    <div className="app">
      <header className="app-header">
        <div className="header-inner">
          <span className="logo">🌬️ 바람따라</span>
          <button className="btn-finish" onClick={() => setTripRecordOpen(true)}>🏁 여행 마무리</button>
        </div>
      </header>

      <main className="app-main">
        <PinwheelHero
          trigger={trigger}
          onRequestAlternatives={handleRequestAlternatives}
          loading={altLoading}
          onAutoReplace={handleAutoReplace}
          autoLoading={autoReplacing}
        />

        {autoReplaceNotice && <div className="auto-replace-notice">⚡ {autoReplaceNotice}</div>}

        <WeatherBanner items={weatherItems} />

        <ItineraryList
          items={itinerary.items}
          onUpdateTime={handleUpdateTime}
          onTogglePin={handleTogglePin}
          onDelete={handleDeleteItem}
          onOpenDocent={handleOpenDocent}
        />

        <RecommendationSearch
          onSearch={handleSearch}
          onAdd={handleAddRecommendation}
          results={recoResults}
          loading={recoLoading}
          addingId={addingContentId}
        />
      </main>

      <footer className="app-footer">
        <p>바람따라 · 바람이 알려주는 실시간 여행</p>
      </footer>

      <AlternativesPanel
        open={altOpen}
        candidates={altCandidates}
        loading={altLoading}
        onAdd={handleAddAlternative}
        addingId={addingContentId}
        onClose={() => setAltOpen(false)}
      />

      <DocentModal
        open={docentOpen}
        placeName={docentPlaceName}
        script={docentScript}
        audioUrl={docentAudioUrl}
        loading={docentLoading}
        error={docentError}
        onClose={() => setDocentOpen(false)}
      />

      <TripRecordModal
        open={tripRecordOpen}
        items={itinerary.items}
        submitting={tripSubmitting}
        onSubmit={handleSubmitTripRecord}
        onClose={() => setTripRecordOpen(false)}
      />
    </div>
  );
}
