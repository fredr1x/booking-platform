// Baseline: просмотр каталога. Лента города, поиск с опечаткой, карточка события, заполненность зала.
//   k6 run load/catalog-browse.js
// Каждый VU — свой пользователь: rate limiter gateway считает запросы по X-User-Id (50 в секунду).
import http from 'k6/http';
import {check} from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '60s';
const QUERIES = ['jaz nigth', 'rock', 'mega concert', 'opera', 'stand up'];

export const options = {
    scenarios: {
        browse: {
            executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
            preAllocatedVUs: 50, maxVUs: 300,
        },
    },
    thresholds: {
        'http_req_duration{name:feed}': ['p(99)<500'],
        'http_req_duration{name:search}': ['p(99)<500'],
        'http_req_duration{name:event}': ['p(99)<500'],
        'http_req_duration{name:availability}': ['p(99)<500'],
        http_req_failed: ['rate<0.01'],
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
    const params = name => ({headers: {'X-User-Id': `browser-${__VU}`}, tags: {name}});
    const feed = http.get(`${BASE}/api/v1/events?city=Almaty&size=20`, params('feed'));
    const search = http.get(`${BASE}/api/v1/events/search?q=${encodeURIComponent(QUERIES[__ITER % QUERIES.length])}`,
        params('search'));
    const eventId = 1 + (__ITER % 3);
    const event = http.get(`${BASE}/api/v1/events/${eventId}`, params('event'));
    const availability = http.get(`${BASE}/api/v1/events/${eventId}/availability`, params('availability'));
    check(feed, {'feed 200': r => r.status === 200});
    check(search, {'search 200': r => r.status === 200});
    check(event, {'event 200': r => r.status === 200});
    check(availability, {'availability 200': r => r.status === 200});
}
