// Старт продаж: USERS покупателей разом приходят на одно событие.
//   k6 run -e USERS=10000 -e USERS_PER_VU=50 load/flash-sale.js
// USERS_PER_VU > 1: один VU ведёт несколько покупателей по очереди. Ждущему покупателю не нужен свой JS-рантайм,
// а Windows не успевает принять тысячи соединений разом (backlog listen-сокета).
import http from 'k6/http';
import {sleep} from 'k6';
import {Counter} from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const EVENT = Number(__ENV.EVENT_ID || 2);
const USERS = Number(__ENV.USERS || 10000);
const USERS_PER_VU = Number(__ENV.USERS_PER_VU || 1);
const MAX_ATTEMPTS = 3;

const booked = new Counter('flash_booked');
const conflicts = new Counter('flash_conflict');
const overloaded = new Counter('flash_overloaded');
const forbidden = new Counter('flash_forbidden');
const gaveUp = new Counter('flash_gave_up');

http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export const options = {
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    scenarios: {
        flash: {
            executor: 'per-vu-iterations',
            vus: Math.ceil(USERS / USERS_PER_VU),
            iterations: 1,
            maxDuration: '15m',
        },
    },
    thresholds: {
        'http_req_duration{name:join}': ['p(99)<1000'],
        'http_req_duration{name:status}': ['p(99)<1000'],
        'http_req_duration{name:book}': ['p(99)<2000'],
        flash_forbidden: ['count==0'],
    },
};

export function setup() {
    const seats = http.get(`${BASE}/api/v1/events/${EVENT}/seats?status=AVAILABLE&limit=1`).json();
    if (!seats.length) {
        throw new Error('no available seats');
    }
    return {firstSeat: seats[0].id, run: Date.now()};
}

function headers(user, extra) {
    return {headers: Object.assign({'X-User-Id': user.id, 'Content-Type': 'application/json'}, extra)};
}

function waitSeconds(res, fallback) {
    return Number(res.headers['Retry-After'] || fallback);
}

function onQueueResponse(user, res) {
    if (res.status !== 200) {
        user.nextAt = Date.now() + waitSeconds(res, 1) * 1000;
        return;
    }
    const body = res.json();
    if (body.state === 'ADMITTED') {
        user.token = body.admissionToken;
        user.state = 'book';
    } else {
        user.state = 'wait';
        user.nextAt = Date.now() + body.retryAfterSeconds * 1000;
    }
}

function seatFor(user, data) {
    // каждый десятый идёт на одни и те же 50 мест: там честные конфликты, остальные на свои места
    const offset = user.index % 10 === 0 && user.attempt === 0 ? user.index % 50 : user.index + user.attempt * 3000;
    return data.firstSeat + offset % 19000;
}

function book(user, data) {
    const extra = {'Idempotency-Key': `${data.run}-${user.id}-${user.attempt}`};
    if (user.token) {
        extra['X-Admission-Token'] = user.token;
    }
    const res = http.post(`${BASE}/api/v1/bookings`, JSON.stringify({eventId: EVENT, seatIds: [seatFor(user, data)]}),
        Object.assign(headers(user, extra), {tags: {name: 'book'}}));
    if (res.status === 201) {
        booked.add(1);
        user.state = 'done';
    } else if (res.status === 409) {
        conflicts.add(1);
        user.attempt++;
        if (user.attempt >= MAX_ATTEMPTS) {
            gaveUp.add(1);
            user.state = 'done';
        }
    } else if (res.status === 403) {
        forbidden.add(1);
        user.state = 'done';
    } else {
        // 503 и таймауты: повтор с тем же ключом не создаст вторую бронь. Пауза растёт: иначе повторы добивают сервис
        overloaded.add(1);
        user.retries = (user.retries || 0) + 1;
        const backoff = Math.min(30, waitSeconds(res, 1) * 2 ** (user.retries - 1));
        user.nextAt = Date.now() + backoff * 1000 * (0.5 + Math.random() / 2);
    }
}

function step(user, data) {
    if (user.state === 'join') {
        onQueueResponse(user, http.post(`${BASE}/api/v1/queue/${EVENT}/join`, null,
            Object.assign(headers(user), {tags: {name: 'join'}})));
    } else if (user.state === 'wait') {
        onQueueResponse(user, http.get(`${BASE}/api/v1/queue/${EVENT}/status`,
            Object.assign(headers(user), {tags: {name: 'status'}})));
    } else if (user.state === 'book') {
        book(user, data);
    }
}

export default function (data) {
    const users = [];
    for (let i = 0; i < USERS_PER_VU; i++) {
        const index = (__VU - 1) * USERS_PER_VU + i;
        users.push({id: `flash-${data.run}-${index}`, index, state: 'join', attempt: 0, nextAt: 0});
    }
    while (users.some((u) => u.state !== 'done')) {
        const now = Date.now();
        users.filter((u) => u.state !== 'done' && u.nextAt <= now).forEach((u) => step(u, data));
        sleep(0.05);
    }
}
