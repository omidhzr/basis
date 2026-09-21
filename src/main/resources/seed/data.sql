-- Demonstration data for the dev profile: five requests, each with the history
-- its life implies, so a reviewer has something to decide, something already
-- decided, a request that has moved since it was submitted, and one the
-- reviewer raised themselves.
--
-- Inserted as SQL rather than replayed through the API. The rows are a fixture
-- stated directly, not a workflow run at every start whose four-eyes and
-- version rules could turn a mistake here into what looks like a broken
-- service. The cost is that nothing stops this file contradicting the
-- invariants the service enforces, which is why a test asserts what it claims.
--
-- Identifiers and times are literal, so a seeded request can be linked to and a
-- restart produces the same database rather than one that differs by clock.
-- Times are UTC and hours apart, so a history trail reads as a sequence of
-- events and newest-first is visible rather than a tie. The 5eed prefix marks
-- a seeded row.
--
-- History payloads mirror what the service writes: CREATED carries both
-- values, AMENDED only the fields that changed, a decision only its note.

INSERT INTO exception_request
    (id, application_id, discount_bps, reason, status, version, requested_by,
     decided_by, decided_at, created_at, updated_at)
VALUES
    -- Approved: the request the mortgage process would consume for APP-2001.
    ('5eed0000-0000-4000-8000-000000000001', 'APP-2001', 30,
     'Retention case: client of eleven years holding a competing offer 0.30 points lower.',
     'APPROVED', 1, 'rm-1',
     'reviewer-1', TIMESTAMP WITH TIME ZONE '2026-09-17 14:40:00+00:00',
     TIMESTAMP WITH TIME ZONE '2026-09-17 09:05:00+00:00',
     TIMESTAMP WITH TIME ZONE '2026-09-17 14:40:00+00:00'),

    -- Declined: terminal, so it stays in the requester's queue and out of the reviewer's.
    ('5eed0000-0000-4000-8000-000000000002', 'APP-2002', 75,
     'First-time buyer referred by a partner broker; asking for a launch-week discount.',
     'DECLINED', 1, 'rm-2',
     'reviewer-2', TIMESTAMP WITH TIME ZONE '2026-09-19 08:45:00+00:00',
     TIMESTAMP WITH TIME ZONE '2026-09-18 10:20:00+00:00',
     TIMESTAMP WITH TIME ZONE '2026-09-19 08:45:00+00:00'),

    -- Pending at version 2: submitted at 60 and amended down to 45, so a
    -- decision must name version 2 and a stale version 1 is refused.
    ('5eed0000-0000-4000-8000-000000000003', 'APP-2003', 45,
     'Remortgage of an existing customer whose fixed rate is about to end.',
     'PENDING', 2, 'rm-2',
     NULL, NULL,
     TIMESTAMP WITH TIME ZONE '2026-09-19 13:10:00+00:00',
     TIMESTAMP WITH TIME ZONE '2026-09-20 08:30:00+00:00'),

    -- Pending at version 1: untouched since it was raised.
    ('5eed0000-0000-4000-8000-000000000004', 'APP-2004', 25,
     'Customer consolidating two mortgages with us and asking for the loyalty discount.',
     'PENDING', 1, 'rm-1',
     NULL, NULL,
     TIMESTAMP WITH TIME ZONE '2026-09-20 15:20:00+00:00',
     TIMESTAMP WITH TIME ZONE '2026-09-20 15:20:00+00:00'),

    -- Pending, raised by a reviewer. Nothing restricts raising a request to a
    -- relationship manager, and four-eyes is what stops the raiser deciding it.
    -- That is only visible if one exists: reviewer-1's queue leaves it out,
    -- reviewer-2's holds it, and reviewer-1 reaching it by identifier is
    -- refused if they try to decide it.
    ('5eed0000-0000-4000-8000-000000000005', 'APP-2005', 20,
     'Existing customer moving to a shorter fixed term and asking to keep the loyalty rate.',
     'PENDING', 1, 'reviewer-1',
     NULL, NULL,
     TIMESTAMP WITH TIME ZONE '2026-09-20 17:45:00+00:00',
     TIMESTAMP WITH TIME ZONE '2026-09-20 17:45:00+00:00');

INSERT INTO request_history
    (id, request_id, entry_type, version, actor, payload, occurred_at)
VALUES
    -- APP-2001
    ('5eed0000-0000-4000-8000-000000000101', '5eed0000-0000-4000-8000-000000000001',
     'CREATED', 1, 'rm-1',
     '{"discountBps":30,"reason":"Retention case: client of eleven years holding a competing offer 0.30 points lower."}',
     TIMESTAMP WITH TIME ZONE '2026-09-17 09:05:00+00:00'),
    ('5eed0000-0000-4000-8000-000000000102', '5eed0000-0000-4000-8000-000000000001',
     'APPROVED', 1, 'reviewer-1',
     '{"note":"Within delegated authority for a client of this tenure."}',
     TIMESTAMP WITH TIME ZONE '2026-09-17 14:40:00+00:00'),

    -- APP-2002
    ('5eed0000-0000-4000-8000-000000000103', '5eed0000-0000-4000-8000-000000000002',
     'CREATED', 1, 'rm-2',
     '{"discountBps":75,"reason":"First-time buyer referred by a partner broker; asking for a launch-week discount."}',
     TIMESTAMP WITH TIME ZONE '2026-09-18 10:20:00+00:00'),
    ('5eed0000-0000-4000-8000-000000000104', '5eed0000-0000-4000-8000-000000000002',
     'DECLINED', 1, 'reviewer-2',
     '{"note":"Outside delegated authority at this discount. Resubmit at a lower rate if the case still holds."}',
     TIMESTAMP WITH TIME ZONE '2026-09-19 08:45:00+00:00'),

    -- APP-2003
    ('5eed0000-0000-4000-8000-000000000105', '5eed0000-0000-4000-8000-000000000003',
     'CREATED', 1, 'rm-2',
     '{"discountBps":60,"reason":"Remortgage of an existing customer whose fixed rate is about to end."}',
     TIMESTAMP WITH TIME ZONE '2026-09-19 13:10:00+00:00'),
    ('5eed0000-0000-4000-8000-000000000106', '5eed0000-0000-4000-8000-000000000003',
     'AMENDED', 2, 'rm-2',
     '{"discountBps":45}',
     TIMESTAMP WITH TIME ZONE '2026-09-20 08:30:00+00:00'),

    -- APP-2004
    ('5eed0000-0000-4000-8000-000000000107', '5eed0000-0000-4000-8000-000000000004',
     'CREATED', 1, 'rm-1',
     '{"discountBps":25,"reason":"Customer consolidating two mortgages with us and asking for the loyalty discount."}',
     TIMESTAMP WITH TIME ZONE '2026-09-20 15:20:00+00:00'),

    -- APP-2005
    ('5eed0000-0000-4000-8000-000000000108', '5eed0000-0000-4000-8000-000000000005',
     'CREATED', 1, 'reviewer-1',
     '{"discountBps":20,"reason":"Existing customer moving to a shorter fixed term and asking to keep the loyalty rate."}',
     TIMESTAMP WITH TIME ZONE '2026-09-20 17:45:00+00:00');
