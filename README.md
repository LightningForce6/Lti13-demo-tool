# LTI 1.3 Demo Tool

A minimal, dependency-light LTI 1.3 tool in Java and Spring Boot. It implements the parts of
the standard a tool needs before it can do anything useful:

- **OIDC third-party initiated login** — the redirect handshake that starts every launch
- **`id_token` validation** — signature against the platform's keyset, plus issuer, audience,
  expiry, nonce, deployment and message-type checks, each written out explicitly
- **Deep Linking** — an instructor picks content and it is returned to the LMS as a signed JWT
- **Published JWKS** — the tool's own public keys, with rotation designed in
- **Multi-tenant platform registry** — one registration per institution, resolved at launch
- **OAuth 2.0 client credentials** — JWT-bearer client assertions, signed with the same key
  that backs the JWKS, with per-scope token caching
- **AGS** — find or create a gradebook column, post scores, with progress states that let a
  tool record an advisory signal without claiming to have graded anything
- **NRPS** — course rosters, following `Link`-header pagination

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

For AGS and NRPS you also have to enable their scopes on the registration in the LMS — they are
off by default, and the symptom of forgetting is a launch that simply arrives without the
service claims rather than an error anywhere. To see AGS work at all, the instructor has to
attach the tool to a *graded* item; attached to plain content, the platform has no gradebook
column to offer and sends no AGS claim.

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

Service calls come afterwards and run the other way — server to platform, no browser involved:

```
  This tool                                            LMS
      │  POST {token_url}   client_assertion signed by our key
      ├────────────────────────────────────────────────────▶
      │  ◀──────────────────────────────────────────────────┤  access_token, scoped
      │
      │  POST {lineitems}         find or create the column
      │  POST {lineitem}/scores   post the score
      │  GET  {memberships}       roster, page by page
      ├────────────────────────────────────────────────────▶
```

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

## The service layer

AGS and NRPS endpoints are not configured anywhere. They arrive inside the launch, scoped to
that course and resource link, which is the practical reason the launch has to capture them: a
tool that did not store them has to wait for the next launch before it can write a grade.

Service calls invert the trust direction. The launch is browser-mediated and the platform signs
it; service calls are server to server and *we* have to prove who we are. The proof is a JWT
signed with the same key that backs the published JWKS, sent as a `client_assertion` under the
JWT-bearer grant. There is no client secret anywhere in LTI 1.3 — which is why the tool needs a
keypair at all, and why rotation has to work before any of this does.

Things in here that were learned rather than read:

**Tokens are cached per scope set, not per platform.** Platforms issue tokens scoped to exactly
what you asked for, so a token fetched for `score` is refused for a membership call. Caching by
platform produces failures that look random.

**The client assertion's `aud` is configurable.** Most platforms want the token endpoint URL.
Some want the platform issuer and reject an otherwise perfect assertion with a bare
`invalid_client`, which tells you nothing.

**`/scores` is inserted before the query string, not appended.** Platforms hand out line item
URLs that already carry query parameters — Canvas does. Appending naively gives
`.../line_items/7?type_id=1/scores`, which 404s in a way that reads like a permissions problem.
`AgsClient.scoresUrl` and its test cover exactly this.

**Look for an existing column before creating one.** The bound `lineitem` from the claim first,
then a search of the collection by `resourceLinkId`, then create. Skip that and the tool adds a
duplicate column to someone's gradebook on every run.

**`scoreMaximum` must match the column's own maximum.** Post 1 out of 1 against a column of 100
and platforms disagree about whether that is full marks or one per cent.

**`gradingProgress: PendingManual` is the honest state for advisory output.** It records the
number and tells the LMS a human still decides. A tool that produces a signal rather than a
grade should not be claiming `FullyGraded`.

**NRPS pagination lives in an HTTP `Link` header**, not in the JSON body where anyone would
look first. A client that reads only the body gets page one, no error, and a roster that is
quietly wrong for any course above the platform's page size — invisible in a test course of
four, obvious in a cohort of three hundred.

**A roster member's `user_id` is the same value as `sub` in that person's launch.** That is the
whole value of NRPS: it lets a tool reconcile a roster against users it has actually seen, using
the identifier it already stores. Names and emails are frequently absent depending on the
institution's privacy configuration, so code that needs them has to treat absence as normal
rather than as an error.

**Pulling a roster is an instructor action.** A score concerns the launching user, who the
launch identifies. A roster is other people's data, and a learner has no business retrieving
their classmates. The roles claim is signed, so that is a real authorisation decision.

## Platform differences to expect

| Platform | Worth knowing |
| --- | --- |
| Brightspace | Compares redirect URIs by exact string. A trailing slash costs an afternoon. |
| Canvas | Registered by pasting a Developer Key JSON configuration; supports substitution variables for custom parameters. |
| Blackboard Learn Ultra | Registered through the Developer Portal; the application ID is issued there, not by the institution. |
| Moodle | Straightforward, and the easiest to run locally for development. |

## What this deliberately does not do

Dynamic Registration, which automates installing the tool on a new platform rather than
exchanging configuration by hand. The registry would need to accept writes at runtime for it,
which is the same change that turns the YAML registry into a table.

Persistence is also deliberately absent: the platform registry is configuration and the state
and nonce stores are in-memory with a TTL sweep. Both are correct for one instance and wrong
behind a load balancer, where launch and callback can land on different nodes — anything you
do not share is a nonce you are not really checking. Production wants Redis or a table with a
TTL index, and a platform registration table rather than YAML.

## Tests

Nothing is mocked. Two suites run against the real HTTP stack with no LMS involved.

`LaunchFlowTest` exercises the launch path. The test platform's `jwks-url` points at the tool's
own JWKS endpoint and the test signs its tokens with the tool's own key, so the validator
fetches a keyset over HTTP and verifies a signature against it exactly as it would against
Canvas. Covered: a valid resource-link launch reaching the task, a Deep Linking request reaching
the picker, a token carrying the wrong nonce, an unregistered deployment ID, a state that does
not match its cookie, single-use state, single-use nonce, and that the JWKS endpoint never
exposes private key material.

`AdvantageServiceTest` exercises AGS and NRPS against `StubPlatform`, a deliberately strict fake
LMS. Its token endpoint verifies the client assertion's signature against the tool's published
key and checks the assertion's issuer, subject, audience and `jti` before issuing anything, and
every service endpoint requires the resulting bearer token — so a broken OAuth layer fails at
the token endpoint instead of passing on a stubbed happy path. Its line item URLs carry a query
string, and its roster is served in two pages with the second advertised only in a `Link`
header, because those are the two cases implementations get wrong.

Covered: creating a column and posting a score to it, reusing the column on a second run,
`PendingManual` for advisory output, a roster assembled across both pages, members whose name
and email the institution withheld, a learner refused the roster, per-scope token requests, and
unit coverage of the scores-URL and `Link`-header parsing.

## Layout

```
config/    LtiProperties, PlatformRegistration, PlatformRegistry   — multi-tenancy
key/       ToolKeyService                                          — tool keys and rotation
launch/    OidcLoginController, LaunchController, IdTokenValidator  — the launch, and the checks
deeplink/  DeepLinkingController, DeepLinkingResponseBuilder        — content selection
service/   PlatformTokenService, ServiceScopes                     — OAuth for service calls
ags/       AgsClient, LineItem, Score                              — gradebook columns, scores
nrps/      NrpsClient, Member, MembershipContainer                 — rosters, pagination
web/       JwksController, ServiceController, LaunchErrorHandler   — endpoints and errors
task/      Task, TaskRepository                                     — stand-in content model
```

## Licence

MIT.
