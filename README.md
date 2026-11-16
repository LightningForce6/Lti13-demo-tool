# LTI 1.3 Demo Tool

A minimal, dependency-light LTI 1.3 tool in Java and Spring Boot. It implements the parts of
the standard a tool needs before it can do anything useful:

- **OIDC third-party initiated login** — the redirect handshake that starts every launch
- **`id_token` validation** — signature against the platform's keyset, plus issuer, audience,
  expiry, nonce, deployment and message-type checks, each written out explicitly
- **Deep Linking** — an instructor picks content and it is returned to the LMS as a signed JWT
- **Published JWKS** — the tool's own public keys, with rotation designed in
- **Multi-tenant platform registry** — one registration per institution, resolved at launch

There is no hosted LTI service in the middle. The protocol work is in this repository.

## Why this exists

Reading the LTI specification tells you what the messages contain. It does not tell you which
checks actually matter, where platforms differ, or which shortcuts quietly break in production.
This is a working reference built to make those decisions visible — so the interesting code is
`IdTokenValidator`, not the controllers.

## Running it

```bash
mvn test        # proves the launch path works, with no LMS involved
mvn spring-boot:run
```

Then open <http://localhost:8080> for the values an LMS administrator needs when registering
the tool.

### Pointing a real LMS at it

An LMS cannot reach `localhost`, so expose the tool over HTTPS first — any tunnel will do:

```bash
TOOL_BASE_URL=https://your-tunnel.example.dev mvn spring-boot:run
```

Register these four URLs in the LMS:

| Field | Value |
| --- | --- |
| OIDC login initiation URL | `{base}/lti/login` |
| Redirect / target link URI | `{base}/lti/launch` |
| Public JWKS URL | `{base}/.well-known/jwks.json` |
| Deep Linking URL | `{base}/lti/launch` |

Then put the platform's issuer, client ID, deployment ID and endpoint URLs into
`lti.platforms` in `application.yml`.

Free test instances: Canvas offers a free-for-teacher account, and Moodle can be run locally
in Docker. Blackboard and Brightspace generally require a developer-program request, which is
worth starting early — availability of test environments, not the code, is usually what sets
the schedule on a multi-LMS integration.

## How a launch flows

```
  LMS                          This tool
   │
   │  1. POST /lti/login  (iss, login_hint, lti_message_hint)
   ├──────────────────────────────▶  mint state + nonce, set state cookie
   │  ◀──────────────────────────┤  302 to the platform's auth endpoint
   │
   │  2. POST /lti/launch (id_token, state)
   ├──────────────────────────────▶  validate: state cookie, signature (platform JWKS),
   │                                 iss, aud/azp, exp, iat, nonce, deployment_id, version
   │                                 │
   │                                 ├─ LtiDeepLinkingRequest  → content picker
   │                                 └─ LtiResourceLinkRequest → the task
   │
   │  3. POST deep_link_return_url (JWT signed by this tool)
   ◀──────────────────────────────┤  browser auto-submits the content items
```

The round trip in step 1 exists so that the *tool* chooses the nonce. A nonce the tool did not
pick is a nonce the tool cannot use to prove the token is fresh.

## The decisions worth reviewing

**User identity is `(issuer, client_id, deployment_id, sub)` — never email.**
See `LtiLaunch.stableUserKey()`. Institutions routinely withhold email under privacy settings,
and Moodle and Brightspace will send it blank. Matching on email is how an integration ends up
with two accounts for one student, or one account shared by two students whose institution
recycled an address. A real integration stores this tuple against its own user record on first
launch, and links a pre-existing account with one explicit confirmation rather than by
inference.

**`deployment_ids` is a list.** One institution can install the same tool several times — per
department, per campus — and each installation has its own deployment ID. Modelling it as a
single value works until the second install.

**The JWKS carries several keys; only the first signs.**
See `ToolKeyService`. That is what makes key rotation a deploy rather than a coordinated outage
with every LMS vendor: prepend a new key, keep the old one published until platform caches
expire, then drop it.

**The platform JWKS cache refreshes on an unknown `kid`.**
When a platform rotates its keys, the first token signed with the new key triggers a refetch
instead of a failed launch.

**`state` and `nonce` do different jobs.** `state` is CSRF protection for the redirect, checked
against a cookie. `nonce` is replay protection inside the signed token, spent on first use. A
captured launch with a valid signature is still rejected on the second attempt.

**Cookies need `SameSite=None; Secure`.** A tool renders inside an LMS iframe, so every cookie
it sets is a third-party cookie. Default (`Lax`) cookies are not returned and the launch fails
with a state mismatch that looks exactly like a code bug. This also means the tool must be on
HTTPS; Chrome's exemption for `http://localhost` is the only reason local development works.

**Service endpoints are captured at launch even though nothing uses them yet.**
The AGS and NRPS claims are only offered at launch time. Storing them from the first launch is
what keeps grade passback and roster sync a later addition rather than a schema migration.

**Error messages name the failed check.** "Unrecognised deployment_id" rather than "invalid
launch". When a university's IT department reports a broken integration, that difference is
the whole investigation.

## Platform differences to expect

| Platform | Worth knowing |
| --- | --- |
| Brightspace | Compares redirect URIs by exact string. A trailing slash costs an afternoon. |
| Canvas | Registered by pasting a Developer Key JSON configuration; supports substitution variables for custom parameters. |
| Blackboard Learn Ultra | Registered through the Developer Portal; the application ID is issued there, not by the institution. |
| Moodle | Straightforward, and the easiest to run locally for development. |

## What this deliberately does not do

AGS (grade passback), NRPS (roster sync) and Dynamic Registration. All three need an OAuth 2.0
client-credentials flow against the platform's token endpoint, which is a layer above the
launch. The registry already stores the token URL and the launch already captures the service
endpoints, so they attach without rework — but implementing them is not the point of a
reference this size.

Persistence is also deliberately absent: the platform registry is configuration and the state
and nonce stores are in-memory with a TTL sweep. Both are correct for one instance and wrong
behind a load balancer, where launch and callback can land on different nodes — anything you
do not share is a nonce you are not really checking. Production wants Redis or a table with a
TTL index, and a platform registration table rather than YAML.

## Tests

`LaunchFlowTest` exercises the full launch path with no LMS involved. The test platform's
`jwks-url` points at the tool's own JWKS endpoint and the test signs its tokens with the tool's
own key, so the validator fetches a keyset over HTTP and verifies a signature against it
exactly as it would against Canvas. Every claim check runs for real.

Covered: a valid resource-link launch reaching the task, a Deep Linking request reaching the
picker, a token carrying the wrong nonce, an unregistered deployment ID, a state that does not
match its cookie, single-use state, single-use nonce, and that the JWKS endpoint never exposes
private key material.

## Layout

```
config/    LtiProperties, PlatformRegistration, PlatformRegistry   — multi-tenancy
key/       ToolKeyService                                          — tool keys and rotation
launch/    OidcLoginController, LaunchController, IdTokenValidator  — the launch, and the checks
deeplink/  DeepLinkingController, DeepLinkingResponseBuilder        — content selection
web/       JwksController, LaunchErrorHandler                       — public endpoints, errors
task/      Task, TaskRepository                                     — stand-in content model
```

## Licence

MIT.
