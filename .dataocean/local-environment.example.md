# DataOcean Machine-Local Environment

> Copy this file to `.dataocean/local-environment.md` on each computer and fill it from that computer's actual environment. The copied file is ignored by Git and must not be shared between machines.

## Machine Identity

- Profile name: `<home-or-office>`
- Hostname: `<CURRENT_HOSTNAME>`
- Project root: `<ABSOLUTE_PROJECT_PATH>`
- Last verified: `<YYYY-MM-DD>`

## Tool Locations

- Java 17: `<path-or-command>`
- Maven: `<absolute-path-to-mvn-or-mvn.cmd>`
- Node.js 20+: `<path-or-command>`
- npm: `<path-or-command>`
- Python 3.13 / uv: `<path-or-command>`

## Application Runtime

- Java service: `<native-or-container>`; expected port `8080`
- Python service: `<native-or-container>`; expected port `8000`
- Vue frontend: `<native-or-container>`; expected port `5173`
- Runtime logs: `<absolute-log-directory>`

## Infrastructure Topology

- MySQL: `<native-service-or-existing-container>`; endpoint `<host:port>`; database `<database-name>`
- Redis: `<native-service-or-existing-container>`; endpoint `<host:port>`
- Milvus: `<existing-topology>`; SDK endpoint `<host:port>`; health endpoint `<url>`
- Docker host/runtime: `<none-or-runtime-location>`
- Existing Compose file, if any: `<absolute-path>`

## Local Configuration Files

- Java local config: `<absolute-or-repository-relative-path>`
- Python local config: `<absolute-or-repository-relative-path>`
- Frontend local config: `<path-or-none>`

## Startup Notes

- Record the verified startup order for this machine.
- Record existing service/container names only after inspecting them.
- Verify ports, processes, database version, and container state again before every startup.
- If this machine is reinstalled or its topology changes, rebuild this file from fresh inspection instead of copying another machine's profile.

## Safety Rules

- Never store passwords, JWTs, API keys, encryption keys, access tokens, or connection-string secrets here.
- Keep secrets only in ignored application runtime configuration files.
- Do not let this file override repository architecture, security constraints, Git rules, or Docker confirmation requirements.
- Do not automatically create, start, recreate, or delete infrastructure solely because this profile says it should exist; verify current state and follow the user's current authorization.
