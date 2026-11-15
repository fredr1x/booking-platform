// Chaos: платёжка отвечает с ошибками и задержкой, а остальная система должна отвечать как обычно.
// payment-service запускается с PAYMENT_ERROR_RATE=0.5 PAYMENT_DELAY=3s, см. docs/adr/0008-resilience-and-saga.md.
//
//   k6 run load/payment-chaos.js
//
// buyers: бронь и оплата, каждый раз новый пользователь и новое место события 2.
// readers: карточка события и заполненность зала, их задержка не должна расти из-за платёжки.
import http from 'k6/http';
import {check} from 'k6';
import {Counter} from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const DURATION = __ENV.DURATION || '60s';
const BUYERS_RATE = Number(__ENV.BUYERS_RATE || 10);

const paid = new Counter('chaos_paid');
const cancelled = new Counter('chaos_cancelled');
const unavailable = new Counter('chaos_payment_unavailable');
const rateLimited = new Counter('chaos_rate_limited');

export const options = {
    scenarios: {
        buyers: {
            executor: 'constant-arrival-rate', rate: BUYERS_RATE, timeUnit: '1s', duration: DURATION,
            preAllocatedVUs: 100, maxVUs: 400, exec: 'buyer',
        },
        readers: {
            executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: DURATION,
            preAllocatedVUs: 20, maxVUs: 100, exec: 'reader',
        },
    },
    // чтение не должно страдать от платёжки; пороги заодно включают в итогах разбивку по типам запросов
    thresholds: {
        'http_req_duration{name:event}': ['p(99)<500'],
        'http_req_duration{name:availability}': ['p(99)<500'],
        'http_req_duration{name:book}': ['p(99)<2000'],
        'http_req_duration{name:pay}': ['p(99)<10000'],
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
    const res = http.get(`${BASE}/api/v1/events/2/seats?status=AVAILABLE&limit=500`, {headers: {'X-User-Id': 'chaos-setup'}});
    return {seats: res.json().map(s => s.id)};
}

export function buyer(data) {
    const user = `chaos-${__VU}-${__ITER}-${Date.now()}`;
    const seat = data.seats[(__VU * 1000 + __ITER) % data.seats.length];
    const headers = {'X-User-Id': user, 'Content-Type': 'application/json'};
    const created = http.post(`${BASE}/api/v1/bookings`, JSON.stringify({eventId: 2, seatIds: [seat]}),
        {headers, tags: {name: 'book'}, responseCallback: http.expectedStatuses(201, 409)});
    if (created.status !== 201) {
        return;
    }
    const pay = http.post(`${BASE}/api/v1/bookings/${created.json().id}/pay`, null,
        {headers, tags: {name: 'pay'}, responseCallback: http.expectedStatuses(200, 503)});
    if (pay.status === 200) {
        pay.json().status === 'CONFIRMED' ? paid.add(1) : cancelled.add(1);
        // оплаченное место возвращаем, чтобы следующий прогон нашёл свободные
        http.post(`${BASE}/api/v1/bookings/${created.json().id}/cancel`, null, {headers, tags: {name: 'cancel'}});
    } else if (pay.status === 503) {
        unavailable.add(1);
        http.post(`${BASE}/api/v1/bookings/${created.json().id}/cancel`, null, {headers, tags: {name: 'cancel'}});
    } else if (pay.status === 429) {
        rateLimited.add(1);
    }
}

export function reader() {
    const headers = {'X-User-Id': `reader-${__VU}`};
    const event = http.get(`${BASE}/api/v1/events/2`, {headers, tags: {name: 'event'}});
    const availability = http.get(`${BASE}/api/v1/events/2/availability`, {headers, tags: {name: 'availability'}});
    check(event, {'event 200': r => r.status === 200});
    check(availability, {'availability 200': r => r.status === 200});
}
