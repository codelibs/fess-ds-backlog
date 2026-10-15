Backlog Data Store for Fess
==========================

[![Java CI with Maven](https://github.com/codelibs/fess-ds-backlog/actions/workflows/maven.yml/badge.svg)](https://github.com/codelibs/fess-ds-backlog/actions/workflows/maven.yml)

> [!NOTE]
> **Experimental.** This data store debuts in Fess 15.9 as an experimental release. It has not yet
> been tested across a wide range of environments, so parameters and behavior may change in
> upcoming releases. Feedback from real-world use will drive its improvements - please share your
> experience in [GitHub Issues](https://github.com/codelibs/fess-ds-backlog/issues).

## Overview

Backlog Data Store is an extension for Fess Data Store Crawling. It authenticates to a Backlog
space with an API key and indexes issues (with their comments), Wiki pages, Documents, the
attachments of all three, and project shared files. Each document is restricted to the members of
its Backlog project.

Every crawl is a full crawl. By default Fess does not delete documents that disappeared from
Backlog at the end of the crawl; it purges them when they expire (3 days). See **Removed content and
revoked access** to change that.

## Download

See [Maven Repository](https://maven.codelibs.org/release/org/codelibs/fess/fess-ds-backlog/).

## Installation

Install the jar as a Fess plugin; the data store handler list in the admin UI only shows plugins
found in `app/WEB-INF/plugin/`:

1. Build or download `fess-ds-backlog-X.X.X.jar`.
2. Either install it from **System > Plugin > Install** (choose *Local* and upload the jar), or
   copy it into `$FESS_HOME/app/WEB-INF/plugin/` (`/usr/share/fess/app/WEB-INF/plugin/` for the
   RPM/DEB packages).
3. Restart Fess.

Then create a crawl config under **Crawler > Data Store** and select `BacklogDataStore` as the
handler.

## Backlog-side setup

1. Create a dedicated Backlog user for crawling, and issue an API key for it
   (**Personal Settings > API**). The crawl sees exactly what this user sees.
2. Add the user to every project to crawl. The data store never sends `all=true`, so everyone,
   space administrators included, only gets the projects the API user has joined.
3. Give the user a role without restrictions (Administrator or Normal User). With a restricted
   role, Backlog answers HTTP 403 for some features:
   - A "View only" user cannot download issue attachments.
   - Only users without restrictions can download shared files.

   On such a 403, the data store logs one WARN per project and feature and skips that feature
   (issue attachments, Wiki attachments, Document attachments or shared files) for that project;
   the rest of the crawl continues.
4. If the space requires two-factor authentication for API access, Backlog answers error code 12
   (RequiredMFAError) and the crawl aborts; see **Error handling**.

The API key is sent only in the `Backlog-API-Key` request header. It is never put in a URL,
never exposed to crawl scripts and never written to a log line by this data store. HTTP redirects
are not followed, so the key never leaves the space host; a redirect is recorded as a failure of
that item. Do not enable
`FINE` logging for the `org.codelibs.curl` java.util.logging logger: curl4j logs request headers
at that level and masks only `Authorization`/`Cookie`-style headers, not `Backlog-API-Key`.

## Parameters

Example:

```
api_key=**********************************************
space_url=https://example.backlog.com
```

| Key | Default | Value |
| --- | --- | --- |
| `api_key` | *(required)* | The Backlog API key. Never copied into the script context or logged. |
| `space_url` | *(required)* | The space URL, e.g. `https://example.backlog.com` (`*.backlog.com`, `*.backlog.jp` or `*.backlogtool.com`). `https` only, scheme and host only; a trailing slash is ignored. A missing, malformed or `http` value aborts the crawl. |
| `project_keys` | *(empty)* | Comma-separated project keys to crawl, case-insensitive. Empty crawls every project the user can see. A key that is not visible logs a WARN. |
| `include_archived` | `false` | Also crawl archived projects. |
| `include_issues` | `true` | Index issues. |
| `include_comments` | `true` | Fold issue comments into the issue document. |
| `include_wikis` | `true` | Index Wiki pages (only in projects with the Wiki enabled). |
| `include_documents` | `true` | Index Documents (only in projects with Documents enabled). |
| `include_attachments` | `true` | Download and index issue, Wiki and Document attachments. |
| `include_shared_files` | `true` | Download and index shared files (only in projects with file sharing enabled). |
| `permission_sync` | `true` | Give every document of a project one `{user}` role per project member (see **Permissions**). |
| `default_permissions` | *(empty)* | Comma-separated `{user}name`, `{group}name` or `{role}name` permissions added to every document. |
| `max_file_size` | `10485760` | Files larger than this many bytes are skipped (not indexed). |
| `include_pattern` | *(empty)* | Regular expression `backlog.url` must match to be indexed. |
| `exclude_pattern` | *(empty)* | Regular expression `backlog.url` must not match. An issue, Wiki page or Document filtered out also skips its attachments. |
| `request_interval` | `200` | Milliseconds to wait before every API request. |
| `search_request_interval` | `1000` | Minimum milliseconds between two Search-type requests (issue list, Wiki list). Backlog asks clients to keep at least one second between them. |
| `max_retry_count` | `5` | Retries for HTTP 429, 5xx and connection failures. |
| `connection_timeout` | `10000` | Connection timeout in milliseconds. |
| `read_timeout` | `30000` | Read timeout in milliseconds. |
| `ignore_error` | `true` | When text extraction of a downloaded file fails, index it with empty content instead of recording a failure. |

An invalid value (a non-number, a negative interval or retry count, a non-positive timeout or
`max_file_size`, or a boolean other than `true`/`false`) logs a WARN and falls back to the
default. `0` is valid for `request_interval`, `search_request_interval` and `max_retry_count`.

Backlog's API rate limits apply per user, not per API key, so anyone using the same account
shares the budget with the crawler; on a Free plan the limits are stricter, so raise
`request_interval`. A crawl makes one comments request per issue, so at the default 200 ms a space
with 100,000 issues takes about 5.5 hours or more.

## Scripts

Fess 15.9 evaluates data store scripts with the `script_type` parameter, and an unset
`script_type` resolves to `groovy`, which is not bundled with Fess 15.9. Either add
`script_type=javascript` to the parameters or install the `fess-script-groovy` plugin; the scripts
below work with both.

Default script:

```
title=backlog.title
content=backlog.content
url=backlog.url
mimetype=backlog.mimetype
created=backlog.created
last_modified=backlog.updated
role=backlog.roles
```

`role=backlog.roles` is required for the project-member restriction to take effect: without it,
only the crawl config's own **Permissions** field reaches the index.

Every document is exposed under the `backlog` root:

| Key | Types | Value |
| --- | --- | --- |
| `backlog.type` | all | `issue`, `wiki`, `document`, `attachment` or `file`. |
| `backlog.id` | all | The Backlog id of the issue, Wiki page, Document, attachment or shared file. |
| `backlog.project_key` / `backlog.project_name` | all | The project. |
| `backlog.title` | all | `[KEY-1] summary` for an issue, the page name, the Document title, or the file name. |
| `backlog.content` | all | Issue: description, attributes, custom fields and comments. Wiki: page content. Document: plain text. File/attachment: extracted text. |
| `backlog.url` | all | See **URLs**. |
| `backlog.mimetype` | all | `text/plain` for issues, Wiki pages and Documents; the detected type for files. |
| `backlog.created` / `backlog.updated` | all | ISO 8601 UTC timestamps, when Backlog returns them. |
| `backlog.created_by` / `backlog.updated_by` | all | User names, when Backlog returns them. |
| `backlog.roles` | all | The search roles (see **Permissions**). |
| `backlog.issue_key`, `backlog.issue_type`, `backlog.status`, `backlog.priority`, `backlog.assignee` | issue | Issue attributes (names). |
| `backlog.categories`, `backlog.milestones`, `backlog.versions` | issue | Lists of names. |
| `backlog.custom_fields` | issue | Map of custom field name to value text. |
| `backlog.comments` | issue | Comment text, `author: content`, blank-line separated. |
| `backlog.tags` | wiki, document | List of tag names. |
| `backlog.parent_type`, `backlog.parent_url`, `backlog.parent_title` | attachment | The issue, Wiki page or Document the attachment belongs to. |
| `backlog.size` | attachment, file | Downloaded size in bytes. |
| `backlog.path` | file | The shared file path, e.g. `/docs/spec.pdf`. |

## URLs

| Type | URL |
| --- | --- |
| issue | `{space_url}/view/{ISSUE-KEY}` |
| wiki | `{space_url}/alias/wiki/{id}` |
| document | `{space_url}/document/{PROJECT}/{id}` (UNVERIFIED) |
| file | `{space_url}/file/{PROJECT}{dir}{name}`, each path segment percent-encoded (UNVERIFIED) |
| attachment | `{parent url}#attachment-{id}` |

The Document and shared-file URL forms were not verified against a real Backlog space. If a link
does not open the right page, the document is still indexed and searchable; only the link target
is wrong.

The Document attachment download endpoint is documented by Backlog but has not been exercised
against a real space, nor have these behaviors: the `type` values of shared-file entries
(`file` / `directory`; an entry of any other type is skipped with a WARN), the listing path
`files/metadata/`, and attachment downloads being answered directly (a redirect is not followed,
so the API key never leaves the space host, and would be recorded as a failure).

## Permissions

With `permission_sync=true` (the default), every document of a project gets these roles:

- one `{user}` role for each project member's `mailAddress` and one for its `userId` (blank
  values skipped), so a member can sign in to Fess with either;
- `default_permissions`;
- the crawl config's **Permissions** field.

If the project members cannot be fetched, or none of them has a usable `mailAddress`/`userId`,
the whole project is skipped and recorded under **Crawler > Failure URL**; it is never indexed
without its member roles.

With `permission_sync=false`, only `default_permissions` and the **Permissions** field apply.

**Clear the Permissions field of the crawl config.** The admin UI pre-fills it with
`{role}guest`, which every user, including anonymous searchers, holds. Left in place, it is merged
into every document's roles and makes all Backlog content searchable by everyone. With
`permission_sync=true`, the data store logs a WARN per project when a guest role ends up among its
roles.

Things to know before relying on the member roles:

- **A member identifier is a Fess login name.** A Backlog `userId` such as `tanaka` grants access
  to whichever Fess login is called `tanaka`, which need not be the same person (an LDAP or AD
  `sAMAccountName`, say). The `mailAddress` role does not have this problem. Use `permission_sync`
  only where the Fess login names are the Backlog user ids or mail addresses.
- **A member whose identifier would map to the guest role or to another login is ignored**, with a
  WARN naming the project and the member id: a `userId` of `guest`, and any identifier containing a
  backslash (Fess drops everything up to a backslash from a user name, so `x\carol` would become the
  login `carol`).
- **Every project member can find every indexed item of the project**, attachments and shared
  files included, even where Backlog itself would refuse that member the download (a "View only"
  user cannot download issue attachments). The roles are per project, not per feature.

## Removed content and revoked access

Fess stamps every document with an expiry (`day.for.cleanup`, 3 days by default) and does not
delete documents that have one when the crawl ends. Documents are removed by the purger once they
expire. So, by default:

- content deleted in Backlog stays searchable for up to about 3 days;
- when a member leaves a project, that member keeps access to the project's already indexed
  documents for up to about 3 days: the next crawl indexes them again under new document ids (the
  id includes the role list), and the old copies, with the old roles, stay until they expire. During
  that time the remaining members see duplicate results.

To remove such documents when the crawl ends instead, add `keep_expires_docs=false` to the
parameters of the crawl config. Fess then deletes every document of that config that the crawl did
not re-index, which is also what happens to everything the crawl did not reach (see **Error
handling**).

## Error handling

- **The crawl aborts only on HTTP 401 or Backlog error code 11 (AuthenticationError) or 12
  (RequiredMFAError)**, and on a missing/malformed `api_key`/`space_url` or a failure to list the
  projects. With `keep_expires_docs=false`, aborting is not free: Fess still deletes every
  document of this data config that was not re-indexed before the abort.
- HTTP 429: the request waits until `X-RateLimit-Reset` (at least 1 second and at most 120
  seconds, 60 seconds when the header is missing) and is retried. HTTP 5xx and connection failures: retried with exponential
  backoff (1 s, 2 s, 4 s, ... up to 60 s). After `max_retry_count` retries, the item fails.
- Any other failure (403, 404, exhausted retries) is recorded for that one item and the crawl
  continues.
- **Content under a failure is not re-indexed.** With `keep_expires_docs=false`, Fess deletes the
  documents of a data config that were not re-indexed in a crawl, so everything under a failed
  listing, a skipped project or a feature skipped on a 403 is removed from the index at the end
  of that crawl, and stays missing until a later crawl completes without that failure. By default
  it stays searchable until it expires.
- **A failed item does not fail the crawl job.** Check **Crawler > Failure URL** and
  `logs/fess-crawler.log` after a crawl.
