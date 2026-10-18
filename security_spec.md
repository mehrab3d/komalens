# Security Specification — KomaLens Firestore Rules

## 1. Data Invariants
1. **Strict Multi-Tenant Ownership**: Every document in `/users/{userId}/glossary/{entryId}` and `/users/{userId}/history/{historyId}` belongs exclusively to `userId`. Only an authenticated user where `request.auth.uid == userId` and `incoming().userId == userId` may read, list, create, update, or delete their own documents.
2. **Path Variable Hardening**: `userId`, `entryId`, and `historyId` must match `^[a-zA-Z0-9_\-]+$` with length `<= 128` on single-document operations (`get`, `create`, `update`, `delete`).
3. **Immutable Audit Fields**: `userId` and `createdAt` cannot be modified after creation (`incoming().userId == existing().userId && incoming().createdAt == existing().createdAt`).
4. **Temporal Integrity**: `createdAt` and `updatedAt` must be valid server timestamps (`is timestamp && <= request.time`).

## 2. The "Dirty Dozen" Payloads
1. **Unauthenticated Read/List**: Caller with `request.auth == null` attempts `get` or `list` on `/users/alice_123/glossary` -> REJECTED.
2. **Cross-User Read (`bob_456` reading `alice_123`)**: Authenticated Bob attempts `get` or `list` on `/users/alice_123/history` -> REJECTED.
3. **Identity Spoofing on Create**: Alice creates `/users/alice_123/glossary/term1` with `userId: "bob_456"` -> REJECTED.
4. **Shadow/Ghost Field Injection**: Alice creates or updates a glossary entry including `"isAdmin": true` -> REJECTED via `.keys().hasOnly(...)` / `.affectedKeys().hasOnly(...)`.
5. **Oversized String ("Denial of Wallet")**: Alice sends a `sourceTerm` of 5,000 characters or `bubblesPayload` > 100,000 characters -> REJECTED via `.size()` bounds.
6. **Invalid Language Enum**: Alice writes `sourceLanguage: "FR"` (not in `['JA', 'KO', 'ZH']`) -> REJECTED.
7. **Invalid Category Enum**: Alice writes `category: "InvalidCat"` (not in `['Character', 'Skill', 'Location', 'Honorific']`) -> REJECTED.
8. **Immutable Field Tampering**: Alice updates `createdAt` or `userId` on an existing glossary or history document -> REJECTED.
9. **Future Timestamp Spoofing**: Alice sends a client-crafted timestamp in the future (`> request.time`) -> REJECTED.
10. **Null Required Field**: Alice updates `targetTerm` to `null` or empty string `""` -> REJECTED.
11. **Negative Bubble Count**: Alice creates a history item with `bubbleCount: -5` or `bubbleCount: "ten"` -> REJECTED.
12. **Path ID Poisoning**: Caller attempts to create a document with malicious ID containing spaces or special characters -> REJECTED by `isValidId()`.
