#!/usr/bin/env python3
"""
한국관광공사 KorService2(ldongCode2, areaBasedList2)를 라이브 호출해
backend/src/main/resources/region-codes.json 을 생성한다.

- legacy areaCd/signguCd는 LDONG 코드에서 파생한다 (검증: 서울 종로구 11/110->11110, 강원 속초 51/210->51210):
    legacyAreaCd   = lDongRegnCd
    legacySignguCd = lDongRegnCd + lDongSignguCd (5자리, zero-pad)
- weatherNx/Ny는 각 시군구의 대표 관광지 좌표(mapx/mapy, KorService2 areaBasedList2)를
  기상청 공식 LCC 격자변환 공식으로 계산한다 (외부 좌표표 불필요, 관광공사 API 좌표만 사용).

재실행 가능 - 행정구역 개편(예: 2026년 확인된 "전남광주통합특별시" 신설) 시 이 스크립트를 다시 돌리면
region-codes.json이 최신 상태로 갱신된다. TOURAPI_KEY 환경변수가 필요하다.
"""
import json
import math
import os
import sys
import time
import urllib.parse
import urllib.request

SERVICE_KEY = os.environ.get("TOURAPI_KEY")
if not SERVICE_KEY:
    sys.exit("TOURAPI_KEY 환경변수가 필요합니다.")

BASE = "https://apis.data.go.kr/B551011/KorService2"
MOBILE_APP = "WindTrail"


def call(path, params, retries=3):
    query = {
        "serviceKey": SERVICE_KEY,
        "MobileOS": "ETC",
        "MobileApp": MOBILE_APP,
        "_type": "json",
        **params,
    }
    url = f"{BASE}/{path}?" + urllib.parse.urlencode(query)
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(url, timeout=15) as res:
                body = json.loads(res.read().decode("utf-8"))
        except Exception as e:
            if attempt == retries - 1:
                print(f"  [WARN] {path} {params} 호출 실패: {e}", file=sys.stderr)
                return []
            time.sleep(1.0)
            continue
        header = body.get("response", {}).get("header", {})
        code = header.get("resultCode", "")
        if code not in ("0000", "00"):
            if attempt == retries - 1:
                print(f"  [WARN] {path} {params} resultCode={code} msg={header.get('resultMsg')}", file=sys.stderr)
                return []
            time.sleep(1.0)
            continue
        items = body.get("response", {}).get("body", {}).get("items", "")
        if items == "" or items is None:
            return []
        item = items.get("item", [])
        if isinstance(item, dict):
            return [item]
        return item
    return []


def ldong_code(regn_cd=None):
    params = {"numOfRows": 100, "pageNo": 1, "lDongListYn": "N"}
    if regn_cd:
        params["lDongRegnCd"] = regn_cd
    return call("ldongCode2", params)


def representative_coord(l_dong_regn_cd, l_dong_signgu_cd):
    items = call("areaBasedList2", {
        "numOfRows": 1, "pageNo": 1, "arrange": "Q",
        "lDongRegnCd": l_dong_regn_cd, "lDongSignguCd": l_dong_signgu_cd,
    })
    if not items:
        return None, None
    mapx = items[0].get("mapx")
    mapy = items[0].get("mapy")
    if not mapx or not mapy:
        return None, None
    return float(mapx), float(mapy)


# 기상청 공식 LCC(Lambert Conformal Conic) 격자변환 공식
_RE = 6371.00877
_GRID = 5.0
_SLAT1 = 30.0
_SLAT2 = 60.0
_OLON = 126.0
_OLAT = 38.0
_XO = 43
_YO = 136
_DEGRAD = math.pi / 180.0


def latlon_to_grid(lon, lat):
    re = _RE / _GRID
    slat1 = _SLAT1 * _DEGRAD
    slat2 = _SLAT2 * _DEGRAD
    olon = _OLON * _DEGRAD
    olat = _OLAT * _DEGRAD

    sn = math.tan(math.pi * 0.25 + slat2 * 0.5) / math.tan(math.pi * 0.25 + slat1 * 0.5)
    sn = math.log(math.cos(slat1) / math.cos(slat2)) / math.log(sn)
    sf = math.tan(math.pi * 0.25 + slat1 * 0.5)
    sf = math.pow(sf, sn) * math.cos(slat1) / sn
    ro = math.tan(math.pi * 0.25 + olat * 0.5)
    ro = re * sf / math.pow(ro, sn)

    ra = math.tan(math.pi * 0.25 + lat * _DEGRAD * 0.5)
    ra = re * sf / math.pow(ra, sn)
    theta = lon * _DEGRAD - olon
    if theta > math.pi:
        theta -= 2.0 * math.pi
    if theta < -math.pi:
        theta += 2.0 * math.pi
    theta *= sn

    x = math.floor(ra * math.sin(theta) + _XO + 0.5)
    y = math.floor(ro - ra * math.cos(theta) + _YO + 0.5)
    return int(x), int(y)


def main():
    print("1) 시도 목록 조회...")
    sidos = ldong_code(None)
    print(f"   {len(sidos)}개 시도")

    regions = []
    for sido in sidos:
        sido_code = sido["code"]
        sido_name = sido["name"]
        print(f"2) {sido_name}({sido_code}) 시군구 조회...")
        signgus = ldong_code(sido_code)
        for signgu in signgus:
            signgu_code3 = signgu["code"]
            signgu_name = signgu["name"]
            signgu_full = f"{sido_code}{signgu_code3.zfill(3)}"
            lon, lat = representative_coord(sido_code, signgu_code3)
            nx = ny = None
            if lon is not None and lat is not None:
                nx, ny = latlon_to_grid(lon, lat)
            else:
                print(f"   [WARN] {sido_name} {signgu_name}: 대표좌표 없음 (관광지 데이터 미등록)", file=sys.stderr)
            regions.append({
                "sidoCode": sido_code,
                "sidoName": sido_name,
                "signguFullCode": signgu_full,
                "signguName": signgu_name,
                "lDongRegnCd": sido_code,
                "lDongSignguCd": signgu_code3,
                "weatherNx": nx,
                "weatherNy": ny,
            })
            time.sleep(0.05)

    # 수원시/성남시처럼 하위 구가 있는 "부모 컨테이너" 코드는 그 자체로는 관광지 콘텐츠가 없어
    # 대표좌표를 못 구한다(예: 수원시 vs 수원시 장안구/권선구/...). 하위 구가 실제로 존재하는
    # 부모 항목은 목록에서 제외한다 - 선택 가능한 지역은 항상 콘텐츠 조회가 되는 리프 단위만 남긴다.
    names_by_sido = {}
    for r in regions:
        names_by_sido.setdefault(r["sidoCode"], set()).add(r["signguName"])

    def has_children(r):
        prefix = r["signguName"] + " "
        return any(name.startswith(prefix) for name in names_by_sido[r["sidoCode"]])

    filtered = [r for r in regions if not (r["weatherNx"] is None and has_children(r))]
    dropped = len(regions) - len(filtered)

    out_path = os.path.join(
        os.path.dirname(__file__), "..", "backend", "src", "main", "resources", "region-codes.json"
    )
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(filtered, f, ensure_ascii=False, indent=2)

    still_missing = sum(1 for r in filtered if r["weatherNx"] is None)
    print(f"완료: 총 {len(filtered)}개 시군구(상위 컨테이너 {dropped}개 제외), "
          f"weatherNx/Ny 누락 {still_missing}건 -> {out_path}")


if __name__ == "__main__":
    main()
