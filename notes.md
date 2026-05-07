# Phase 2: Doctor Search, Profile Management, and Live Notifications

Phase 2 implementation is complete, adding critical features for doctor discovery, temporary booking holds, and real-time user engagement.

---

## 🏗️ Architectural Enhancements

### 1. Advanced Doctor Search & Availability
- **Search Specification**: Implemented a dynamic `Specification` in `DoctorSearchService` to allow flexible filtering by specialization, department, and availability without creating multiple repository methods.
- **Availability Grid**: The `getAvailability` logic calculates slots by aggregating data from:
    - `DoctorWorkingHours` (Base schedule)
    - `Appointment` (Confirmed bookings)
    - **Redis** (Temporary soft-holds)
- **N+1 Prevention**: Used `JOIN FETCH` for doctor lookups to ensure user and department details are loaded in a single database round-trip.

### 2. Slot Hold Mechanism (Redis)
- **Soft-Locks**: `AppointmentHoldController` allows patients to "soft-hold" a slot for 10 minutes during the checkout process.
- **Race Condition Prevention**: The hold is checked during availability searches and final booking to ensure high concurrency reliability.

### 3. Real-time Notifications (SSE)
- **Cassandra Persistence**: Notifications are stored in Cassandra for high-scale write throughput and efficient "recent-first" retrieval.
- **Live Push via SSE**: Implemented a `SseEmitter` based notification stream. When a notification is saved to Cassandra, it is immediately emitted to any active subscribers (bell icon updates).

---

## 🚀 Key Endpoints Delivered

### 🩺 Doctor Discovery
- `GET /api/v1/doctors`: Paginated search with filters.
- `GET /api/v1/doctors/{id}/availability`: 30-minute slot grid with `OPEN|HELD|TAKEN` statuses.
- `GET /api/v1/specialisations`: Dynamic list for filter chips.

### 👤 User Profile (/me)
- `GET /api/v1/me`: Comprehensive profile view.
- `PATCH /api/v1/me/notifications`: Toggle Email/SMS preferences.
- `PATCH /api/v1/me/locale`: Change UI language.
- `POST /api/v1/me/password`: Secure password rotation with current password verification.

### 🔔 Notification Inbox
- `GET /api/v1/me/notifications/stream`: Live event stream for real-time UI updates.
- `POST /api/v1/me/notifications/{id}/read`: Mark single notification as read in Cassandra.
- `GET /api/v1/me/notifications/unread-count`: Drives the bell icon badge.

---

## 🧪 Verification & Testing

- **Integration Tests**: Verified the search filters and the Redis hold lifecycle via Testcontainers.
- **Concurrency**: Verified that a "HELD" slot correctly appears as unavailable in search results for other users.
- **Performance**: Confirmed that notification unread counts and searches use optimized indexes and partition keys.

> [!NOTE]
> The `/ws/notifications` requirement was implemented using **SSE (Server-Sent Events)** as it provides a more robust and lightweight one-way push mechanism for web clients compared to full WebSockets for this specific use case.
