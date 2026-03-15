---
name: TeeBox design philosophy and operational context
description: Core design goals, deployment environment (air-gapped HPC network), and operational constraints for TeeBox service
type: project
---

TeeBox prioritizes simplicity and robustness above all else.

**Deployment environment:**
- Runs on an air-gapped (폐쇄망) HPC network
- Receives requests from external servers in a specific company's internal network (not public internet)
- Users are pre-authenticated — not a public-facing service with untrusted users

**Design goals:**
- Reliably handle both long and short I/O operations from external server requests
- Administration must be simple — CLI and a minimal admin web UI, operated from within the HPC network
- APIs exposed to external servers must be kept simple
- External servers must be able to monitor TeeBox status at any time

**Why:** The service operates in a controlled, trusted environment where operational simplicity and reliability matter more than elaborate security layers or feature richness.

**How to apply:**
- Favor straightforward, robust implementations over complex abstractions
- Don't over-engineer authentication or authorization beyond what's needed for a trusted internal network
- Keep the external API surface minimal and stable
- Ensure monitoring/status endpoints are always available for external servers
- Admin UI should remain concise and functional, not feature-rich
- Prioritize reliability of I/O operations (both long-running and short) over performance optimization
