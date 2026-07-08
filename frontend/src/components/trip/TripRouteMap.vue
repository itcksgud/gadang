<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon  from '../ui/AppIcon.vue'
import Money    from '../ui/Money.vue'
import FeeBadge from '../ui/FeeBadge.vue'
import { CATS, CAT_HUE } from '../../data/mock.js'
import { getTourDetail, getPostsByPlace } from '../../api/gadang.js'

// course: 백엔드 CourseResponse (확정 일정 상세의 course)
const props = defineProps({
  course: { type: Object, required: true },
})

const router = useRouter()

const mapEl = ref(null)
const sel   = ref(null)   // 선택된 place item
const mapFailed = ref(false)

const places = computed(() =>
  (props.course?.items || []).filter(i => i.type === 'place' && i.lat != null && i.lng != null))

/* ── Kakao SDK 로드 (CourseView와 같은 방식) ─────────────── */
function loadKakaoSDK() {
  return new Promise((resolve, reject) => {
    const ready = () => window.kakao?.maps?.Map
    if (ready()) { resolve(); return }

    const waitUntilReady = () => {
      const id = setInterval(() => {
        if (ready()) { clearInterval(id); resolve() }
      }, 80)
      setTimeout(() => { clearInterval(id); reject(new Error('Kakao SDK load timed out')) }, 10000)
    }

    const existing = document.querySelector('script[src*="dapi.kakao.com"]')
    if (existing) { waitUntilReady(); return }

    const s = document.createElement('script')
    s.src = `//dapi.kakao.com/v2/maps/sdk.js?appkey=${import.meta.env.VITE_KAKAO_MAP_KEY}&libraries=services&autoload=false`
    s.onload = () => window.kakao.maps.load(() => {
      if (ready()) resolve()
      else reject(new Error('Kakao Map is unavailable'))
    })
    s.onerror = () => reject(new Error('Kakao SDK load failed'))
    document.head.appendChild(s)
  })
}

/* ── 지도 + 번호 마커 + 경로선 ───────────────────────────── */
let _map = null
let _overlays = []   // { overlay, div, place }
let _polyline = null
let _hubOverlay = null
let _hubLines = []

/* 출발·도착 교통 허브 — 코스 응답에 허브 좌표가 없어(기존 저장 일정 포함)
   허브명(도착 역/터미널, 없으면 출발지명)을 카카오 키워드 검색으로 지오코딩 */
const hubName = computed(() =>
  props.course?.selectedTransport?.toHub || props.course?.startPoint || '')

function geocodeKeyword(kw) {
  return new Promise(resolve => {
    if (!window.kakao?.maps?.services) { resolve(null); return }
    new window.kakao.maps.services.Places().keywordSearch(kw, (res, status) => {
      if (status === window.kakao.maps.services.Status.OK && res[0]) {
        resolve({ lat: parseFloat(res[0].y), lng: parseFloat(res[0].x) })
      } else resolve(null)
    })
  })
}

async function renderHub(bounds) {
  if (!hubName.value || !_map) return
  const { kakao } = window
  // 허브명이 다른 지역 동명 장소로 잡히지 않게 지역명을 붙여 검색, 실패 시 이름만으로 재시도
  const coord = await geocodeKeyword(`${props.course?.region || ''} ${hubName.value}`.trim())
    || await geocodeKeyword(hubName.value)
  if (!coord || !_map) return

  const pos = new kakao.maps.LatLng(coord.lat, coord.lng)
  const div = document.createElement('div')
  div.innerHTML =
    `<div style="display:flex;align-items:center;gap:4px;padding:4px 10px;border-radius:999px;`
    + `background:#1a7f37;color:#fff;font-weight:700;font-size:12px;border:2px solid #fff;`
    + `box-shadow:0 1px 4px rgba(0,0,0,.3);white-space:nowrap">🚉 출발·도착 · ${hubName.value}</div>`
  _hubOverlay = new kakao.maps.CustomOverlay({ position: pos, content: div, xAnchor: 0.5, yAnchor: 0.5 })
  _hubOverlay.setMap(_map)

  // 허브 → 첫 방문지 / 마지막 방문지 → 허브 (점선)
  const pts = places.value
  const legs = [
    [pos, new kakao.maps.LatLng(pts[0].lat, pts[0].lng)],
    [new kakao.maps.LatLng(pts[pts.length - 1].lat, pts[pts.length - 1].lng), pos],
  ]
  for (const path of legs) {
    const line = new kakao.maps.Polyline({
      path, strokeWeight: 3, strokeColor: '#1a7f37', strokeOpacity: 0.7, strokeStyle: 'shortdash',
    })
    line.setMap(_map)
    _hubLines.push(line)
  }

  bounds.extend(pos)
  _map.setBounds(bounds)
}

function markerHtml(place, index, selected) {
  const hue = CAT_HUE[place.cat] || '#2b6cb0'
  const size = selected ? 32 : 26
  return `<div style="display:flex;align-items:center;justify-content:center;cursor:pointer;`
    + `width:${size}px;height:${size}px;border-radius:50%;`
    + `background:${selected ? hue : '#2b6cb0'};color:#fff;font-weight:700;`
    + `font-size:${selected ? 14 : 13}px;border:2px solid #fff;`
    + `box-shadow:0 1px 4px rgba(0,0,0,.3);transition:all .12s">${index + 1}</div>`
}

function refreshMarkerStyles() {
  _overlays.forEach(({ div, place }, i) => {
    div.innerHTML = markerHtml(place, i, sel.value === place)
    div.style.zIndex = sel.value === place ? 10 : 1
  })
}

async function renderMap() {
  if (!mapEl.value || !places.value.length) return
  try {
    await loadKakaoSDK()
  } catch (e) {
    console.error('[TripRouteMap]', e)
    mapFailed.value = true
    return
  }
  const { kakao } = window

  const path = places.value.map(p => new kakao.maps.LatLng(p.lat, p.lng))
  _map = new kakao.maps.Map(mapEl.value, { center: path[0], level: 5 })

  places.value.forEach((p, i) => {
    const div = document.createElement('div')
    div.innerHTML = markerHtml(p, i, false)
    div.addEventListener('click', () => { sel.value = p })
    const overlay = new kakao.maps.CustomOverlay({ position: path[i], content: div, xAnchor: 0.5, yAnchor: 0.5 })
    overlay.setMap(_map)
    _overlays.push({ overlay, div, place: p })
  })

  _polyline = new kakao.maps.Polyline({
    path, strokeWeight: 4, strokeColor: '#2b6cb0', strokeOpacity: 0.85, strokeStyle: 'solid',
  })
  _polyline.setMap(_map)

  const bounds = new kakao.maps.LatLngBounds()
  path.forEach(ll => bounds.extend(ll))
  _map.setBounds(bounds)

  renderHub(bounds)   // 교통 허브는 지오코딩이 끝나는 대로 표시 (실패 시 조용히 생략)
}

/* ── 상세: TourAPI + 이 장소가 담긴 커뮤니티 코스 ────────── */
const tourDetail  = ref(null)
const tourLoading = ref(false)
const placePosts = ref([])
const placePostsLoading = ref(false)

watch(sel, async (p) => {
  if (!p) return
  if (_map && window.kakao?.maps) {
    _map.panTo(new window.kakao.maps.LatLng(p.lat, p.lng))
    refreshMarkerStyles()
  }
  tourDetail.value = null
  tourLoading.value = true
  getTourDetail(p.name, p.cat).then(d => {
    // 응답 도착 시점에 다른 장소가 선택돼 있으면 무시
    if (sel.value === p) { tourDetail.value = d; tourLoading.value = false }
  })
  placePosts.value = []
  placePostsLoading.value = true
  getPostsByPlace(p.name).then(posts => {
    if (sel.value === p) { placePosts.value = posts; placePostsLoading.value = false }
  })
})

function openKakaoPlace(p) {
  if (p?.pid) window.open(`https://place.map.kakao.com/${p.pid}`, '_blank', 'noopener')
  else window.open(`https://map.kakao.com/link/search/${encodeURIComponent(p.name)}`, '_blank', 'noopener')
}

onMounted(async () => {
  await renderMap()
  // 첫 방문지를 기본 선택 — 패널이 비어 보이지 않게
  if (places.value.length) sel.value = places.value[0]
})

onUnmounted(() => {
  _overlays.forEach(o => o.overlay.setMap(null))
  _overlays = []
  if (_polyline) { _polyline.setMap(null); _polyline = null }
  if (_hubOverlay) { _hubOverlay.setMap(null); _hubOverlay = null }
  _hubLines.forEach(l => l.setMap(null))
  _hubLines = []
  _map = null
})
</script>

<template>
  <div v-if="places.length" style="margin-top:34px">
    <div class="rhead">
      <div class="rt">
        <span class="n">ROUTE</span>
        <h2>경로 지도</h2>
        <span style="font-size:13px;color:var(--ink-faint);align-self:center">
          마커를 누르면 옆에 상세 정보가 나와요
        </span>
      </div>
    </div>

    <div style="display:grid;grid-template-columns:minmax(0,1.6fr) minmax(0,1fr);gap:22px;align-items:start" class="trip-grid">

      <!-- ── 지도 ─────────────────────────────────────────── -->
      <div style="display:flex;flex-direction:column;gap:10px">
        <div class="card" style="overflow:hidden;position:relative;padding:0;border-radius:14px">
          <div ref="mapEl" style="width:100%;height:440px" />
          <div v-if="mapFailed"
               style="position:absolute;inset:0;display:flex;align-items:center;justify-content:center;background:var(--card);color:var(--ink-faint);font-size:13px">
            지도를 불러오지 못했습니다. Kakao JavaScript 키와 도메인 설정을 확인해 주세요.
          </div>
        </div>

        <!-- 방문 순서 칩 — 지도 아래에서 바로 장소 전환 -->
        <div style="display:flex;gap:6px;overflow-x:auto;padding-bottom:2px">
          <button v-for="(p, i) in places" :key="p.pid || p.name"
                  class="chip"
                  style="flex-shrink:0;display:inline-flex;align-items:center;gap:5px"
                  :style="sel === p ? {
                    background: `color-mix(in oklch, ${CAT_HUE[p.cat] ?? 'var(--accent)'} 14%, var(--card))`,
                    borderColor: CAT_HUE[p.cat] ?? 'var(--accent)',
                    color: CAT_HUE[p.cat] ?? 'var(--accent)',
                    fontWeight: '600',
                  } : {}"
                  @click="sel = p">
            <span class="mono" style="font-size:11px">{{ i + 1 }}</span>
            {{ p.name }}
          </button>
        </div>
      </div>

      <!-- ── 상세 패널 (지도 탭과 같은 구성) ──────────────── -->
      <div style="position:sticky;top:18px">
        <div v-if="!sel" class="card"
             style="padding:48px 32px;text-align:center;color:var(--ink-faint);font-size:13px">
          지도 마커 또는 방문 순서에서 장소를 선택하세요.
        </div>

        <div v-if="sel" class="card" :key="sel.pid || sel.name" style="overflow:hidden">
          <!-- 사진 (TourAPI) 또는 기본 이모지 -->
          <div style="aspect-ratio:16/9;position:relative;overflow:hidden;background:linear-gradient(135deg,var(--accent-wash) 0%,var(--transit-wash) 100%);display:flex;align-items:center;justify-content:center">
            <img v-if="tourDetail?.img" :src="tourDetail.img" alt=""
                 style="position:absolute;inset:0;width:100%;height:100%;object-fit:cover" />
            <span v-if="!tourDetail?.img"
                  style="font-size:54px;line-height:1;position:relative;z-index:1;filter:drop-shadow(0 2px 8px rgba(0,0,0,.18))">
              {{ CATS[sel.cat]?.emoji }}
            </span>
          </div>
          <div style="padding:16px 18px">
            <div style="display:flex;align-items:flex-start;justify-content:space-between;gap:10px">
              <div style="min-width:0">
                <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">
                  <span class="mono" style="font-size:12px;color:var(--accent-deep);font-weight:700">
                    {{ places.indexOf(sel) + 1 }}번째
                  </span>
                  <span style="font-size:18px;font-weight:700">{{ sel.name }}</span>
                  <span v-if="sel.meal" class="badge badge-accent">{{ sel.meal }}</span>
                  <span class="badge"
                        :style="{ background: `color-mix(in oklch,${CAT_HUE[sel.cat]} 12%, var(--card))`, color: CAT_HUE[sel.cat] }">
                    {{ CATS[sel.cat]?.emoji }} {{ CATS[sel.cat]?.ko ?? sel.cat }}
                  </span>
                </div>
                <div v-if="sel.note" style="font-size:12.5px;color:var(--ink-soft);margin-top:6px">
                  {{ sel.note }}
                </div>
              </div>
              <FeeBadge :type="sel.feeType" style="flex-shrink:0" />
            </div>

            <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:10px;margin-top:14px;padding:13px 0;border-top:1px solid var(--line);border-bottom:1px solid var(--line)">
              <div>
                <div style="font-size:11px;color:var(--ink-faint);font-weight:600;margin-bottom:6px">방문 시간</div>
                <span class="mono" style="font-weight:700;font-size:14px">{{ sel.arr }}<template v-if="sel.dep">~{{ sel.dep }}</template></span>
              </div>
              <div>
                <div style="font-size:11px;color:var(--ink-faint);font-weight:600;margin-bottom:6px">체류</div>
                <span class="mono" style="font-weight:700;font-size:14px">{{ sel.stay }}분</span>
              </div>
              <div>
                <div style="font-size:11px;color:var(--ink-faint);font-weight:600;margin-bottom:6px">비용</div>
                <Money :amount="sel.fee || 0" :type="sel.feeType" :size="14" />
              </div>
            </div>

            <!-- 관광공사 상세 -->
            <div v-if="tourLoading" style="margin-top:12px;font-size:12px;color:var(--ink-faint);text-align:center;padding:8px 0">
              상세 정보 불러오는 중…
            </div>
            <div v-else-if="tourDetail" style="margin-top:12px;display:flex;flex-direction:column;gap:10px">
              <p v-if="tourDetail.overview"
                 style="font-size:12.5px;color:var(--ink-soft);line-height:1.65;margin:0">
                {{ tourDetail.overview.length > 200 ? tourDetail.overview.slice(0, 200) + '…' : tourDetail.overview }}
              </p>

              <div v-if="tourDetail.usetime||tourDetail.usefee||tourDetail.restdate||tourDetail.parking||tourDetail.useseason"
                   style="display:flex;flex-direction:column;gap:4px;padding:10px 12px;background:var(--bg);border-radius:10px;font-size:12px">
                <div v-if="tourDetail.usetime" style="display:flex;gap:8px;align-items:flex-start">
                  <span style="flex-shrink:0;width:52px;color:var(--ink-faint);font-weight:600">운영시간</span>
                  <span style="color:var(--ink);line-height:1.5">{{ tourDetail.usetime }}</span>
                </div>
                <div v-if="tourDetail.restdate" style="display:flex;gap:8px;align-items:flex-start">
                  <span style="flex-shrink:0;width:52px;color:var(--ink-faint);font-weight:600">휴무일</span>
                  <span style="color:var(--ink);line-height:1.5">{{ tourDetail.restdate }}</span>
                </div>
                <div v-if="tourDetail.usefee" style="display:flex;gap:8px;align-items:flex-start">
                  <span style="flex-shrink:0;width:52px;color:var(--ink-faint);font-weight:600">입장료</span>
                  <span style="color:var(--ink);line-height:1.5">{{ tourDetail.usefee }}</span>
                </div>
                <div v-if="tourDetail.parking" style="display:flex;gap:8px;align-items:flex-start">
                  <span style="flex-shrink:0;width:52px;color:var(--ink-faint);font-weight:600">주차</span>
                  <span style="color:var(--ink);line-height:1.5">{{ tourDetail.parking }}</span>
                </div>
                <div v-if="tourDetail.useseason" style="display:flex;gap:8px;align-items:flex-start">
                  <span style="flex-shrink:0;width:52px;color:var(--ink-faint);font-weight:600">이용시기</span>
                  <span style="color:var(--ink);line-height:1.5">{{ tourDetail.useseason }}</span>
                </div>
              </div>

              <div v-if="tourDetail.menu||tourDetail.seat"
                   style="padding:10px 12px;background:var(--bg);border-radius:10px;font-size:12px;display:flex;flex-direction:column;gap:4px">
                <div v-if="tourDetail.menu" style="display:flex;gap:8px;align-items:flex-start">
                  <span style="flex-shrink:0;width:52px;color:var(--ink-faint);font-weight:600">대표메뉴</span>
                  <span style="color:var(--ink);line-height:1.5">{{ tourDetail.menu }}</span>
                </div>
                <div v-if="tourDetail.seat" style="display:flex;gap:8px">
                  <span style="flex-shrink:0;width:52px;color:var(--ink-faint);font-weight:600">좌석수</span>
                  <span style="color:var(--ink)">{{ tourDetail.seat }}석</span>
                </div>
              </div>

              <div v-if="tourDetail.pet||tourDetail.babycarriage||tourDetail.creditcard||tourDetail.infocenter"
                   style="display:flex;flex-wrap:wrap;gap:6px">
                <span v-if="tourDetail.pet" class="badge"
                      :style="{ background: tourDetail.pet.includes('가능') ? 'var(--free-wash,#e8f5e9)' : 'var(--card)', color: tourDetail.pet.includes('가능') ? '#388e3c' : 'var(--ink-faint)' }">
                  🐾 반려동물 {{ tourDetail.pet.includes('가능') ? '가능' : '불가' }}
                </span>
                <span v-if="tourDetail.babycarriage" class="badge"
                      :style="{ background: tourDetail.babycarriage.includes('가능')||tourDetail.babycarriage.includes('대여') ? 'var(--free-wash,#e8f5e9)' : 'var(--card)', color: tourDetail.babycarriage.includes('가능')||tourDetail.babycarriage.includes('대여') ? '#388e3c' : 'var(--ink-faint)' }">
                  🛒 유모차 {{ tourDetail.babycarriage.includes('가능')||tourDetail.babycarriage.includes('대여') ? '대여가능' : tourDetail.babycarriage }}
                </span>
                <span v-if="tourDetail.creditcard" class="badge"
                      :style="{ background: tourDetail.creditcard.includes('가능') ? 'var(--free-wash,#e8f5e9)' : 'var(--card)', color: tourDetail.creditcard.includes('가능') ? '#388e3c' : 'var(--ink-faint)' }">
                  💳 카드결제 {{ tourDetail.creditcard.includes('가능') ? '가능' : '불가' }}
                </span>
                <span v-if="tourDetail.infocenter" class="badge" style="background:var(--card);color:var(--ink-soft)">
                  📞 {{ tourDetail.infocenter }}
                </span>
              </div>

              <a v-if="tourDetail.homepage" :href="tourDetail.homepage" target="_blank" rel="noopener"
                 style="font-size:12px;color:var(--accent);text-decoration:none;display:inline-flex;align-items:center;gap:4px">
                🔗 공식 홈페이지
              </a>
            </div>

            <div style="display:flex;gap:8px;margin-top:14px">
              <button class="btn btn-ghost btn-sm" style="flex:1" @click="openKakaoPlace(sel)">
                카카오맵에서 보기
              </button>
            </div>
          </div>
        </div>

        <!-- 이 장소가 포함된 커뮤니티 코스 -->
        <div v-if="sel" style="margin-top:14px">
          <div style="font-size:13px;font-weight:700;color:var(--ink);margin-bottom:8px;display:flex;align-items:center;gap:6px">
            <span>📍 이 장소가 담긴 코스</span>
            <span v-if="placePosts.length" class="badge" style="background:var(--accent-wash);color:var(--accent)">{{ placePosts.length }}</span>
          </div>
          <div v-if="placePostsLoading" style="font-size:12px;color:var(--ink-faint);text-align:center;padding:10px 0">불러오는 중…</div>
          <div v-else-if="!placePosts.length" style="font-size:12px;color:var(--ink-faint);padding:10px 0">
            이 장소가 담긴 공유 코스가 아직 없어요.
          </div>
          <div v-else style="display:flex;flex-direction:column;gap:7px">
            <button v-for="post in placePosts" :key="post.id"
                    class="card"
                    style="padding:10px 13px;text-align:left;cursor:pointer;width:100%;box-sizing:border-box"
                    @click="router.push(`/community/${post.postId ?? post.id}`)">
              <div style="font-weight:600;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">
                {{ post.title }}
              </div>
              <div style="display:flex;gap:10px;margin-top:4px;font-size:11.5px;color:var(--ink-faint)">
                <span>{{ post.author }}</span>
                <span>💰 {{ (post.cost || 0).toLocaleString() }}원</span>
                <span>⏱ {{ post.min }}분</span>
                <span>💬 {{ post.comments }}</span>
              </div>
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
