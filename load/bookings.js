// Baseline: обычные брони без очереди. Бронь одного места события 1, оплата, отмена (место возвращается в продажу).
//   k6 run load/bookings.js
// Платёжка по умолчанию отвечает за 0.5 с, поэтому оплата в цифрах — это в основном её задержка.
import http from 'k6/http';
import {Counter} from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 20);
const DURATION = __ENV.DURATION || '60s';

const confirmed = new Counter('bookings_confirmed');
const conflicts = new Counter('bookings_conflict');

export const options = {
    scenarios: {
        buyers: {
            executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
            preAllocatedVUs: 50, maxVUs: 300,
        },
    },
    thresholds: {
        'http_req_duration{name:book}': ['p(99)<1000'],
        'http_req_duration{name:pay}': ['p(99)<3000'],
        'http_req_duration{name:cancel}': ['p(99)<1000'],
        http_req_failed: ['rate<0.01'],
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
    const res = http.get(`${BASE}/api/v1/events/1/seats?status=AVAILABLE&limit=500`, {headers: {'X-User-Id': 'baseline-setup'}});
    return {seats: res.json().map(s => s.id)};
}

export default function (data) {
    const user = `buyer-${__VU}-${__ITER}`;
    // соседние VU берут разные места; совпадение даёт 409, это тоже нормальный исход
    const seat = data.seats[(__VU * 7 + __ITER) % data.seats.length];
    const headers = {'X-User-Id': user, 'Content-Type': 'application/json'};
    const created = http.post(`${BASE}/api/v1/bookings`, JSON.stringify({eventId: 1, seatIds: [seat]}),
        {headers, tags: {name: 'book'}, responseCallback: http.expectedStatuses(201, 409)});
    if (created.status !== 201) {
        conflicts.add(1);
        return;
    }
    const id = created.json().id;
    const pay = http.post(`${BASE}/api/v1/bookings/${id}/pay`, null, {headers, tags: {name: 'pay'}});
    if (pay.status === 200 && pay.json().status === 'CONFIRMED') {
        confirmed.add(1);
    }
    http.post(`${BASE}/api/v1/bookings/${id}/cancel`, null, {headers, tags: {name: 'cancel'}});
}
