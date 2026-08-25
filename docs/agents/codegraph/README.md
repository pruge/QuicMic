# CodeGraph notes for QuicMic

Query patterns and per-area symbol maps discovered while working this repo.

## Useful patterns

- `codegraph explore <symbol>` resolves the audio pipeline quickly:
  `decode_into_ring` (src/audio/processor.rs) → `RingBuffer::push`
  (src/audio/ring_buffer.rs) → `write_data` / `ResamplerState`
  (src/audio/output.rs).
- Connection lifecycle: `acquire_connection_slot` + `ConnectionGuard`
  (src/server/state.rs) are called from both `websocket.rs` and
  `webtransport.rs`; `impact acquire_connection_slot` shows both consumers.
- Auth surface: token reads fan out from `StreamState.session_token`
  (state.rs) into api.rs (`handle_pair`, `handle_renew`, `handle_stats`,
  `handle_update_settings`, `handle_client_state`), websocket.rs, and
  webtransport.rs. Token *writes* are only handle_pair/handle_renew.

## Per-area map

- src/main.rs — startup wiring; the three persisted values (TLS identity,
  pairing PIN, session token) are resolved here and persisted via
  src/persist.rs.
- src/server/mod.rs — router + middlewares (`reject_during_shutdown`,
  `reject_cross_origin`, `apply_no_store`) + hermetic router-test fixture
  (`test_state()`).
