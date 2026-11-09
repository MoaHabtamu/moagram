#!/usr/bin/env python3
"""Prepare upstream merges and promote only the exact candidate validated by CI.

Run from the repository root in GitHub Actions. No third-party Python dependencies.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


UPSTREAM = "https://github.com/DrKLO/Telegram.git"
BRANCH = "codex/telegram-upstream"
BASE = "master"
ISSUE_TITLE = "Telegram update needs conflict resolution"
ISSUE_MARKER = "<!-- moagram-telegram-upstream -->"


def git(*args, check=True):
    return subprocess.run(["git", *args], text=True, capture_output=True, check=check)


def fetch(*args):
    # Only source/branch metadata is needed here; native submodules belong in CI.
    for attempt in range(3):
        result = git("-c", "fetch.recurseSubmodules=false", "fetch", "--no-tags", *args, check=False)
        if not result.returncode:
            return result
        if attempt == 2:
            raise RuntimeError(result.stderr)
        time.sleep(2 ** attempt)


def ancestor(older, newer):
    result = git("merge-base", "--is-ancestor", older, newer, check=False)
    if result.returncode not in (0, 1):
        raise RuntimeError(result.stderr)
    return result.returncode == 0


def api(method, endpoint, data=None):
    request = Request(
        f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}/{endpoint}",
        data=json.dumps(data).encode() if data is not None else None,
        headers={
            "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
        method=method,
    )
    for attempt in range(3):
        try:
            with urlopen(request, timeout=60) as response:
                return json.load(response)
        except (HTTPError, URLError, TimeoutError) as error:
            transient = not isinstance(error, HTTPError) or error.code in (429, 500, 502, 503, 504)
            # A lost POST response may already have created a PR/issue. A later
            # run discovers it; blindly repeating POST could create duplicates.
            if method == "POST" or not transient or attempt == 2:
                raise
            time.sleep(2 ** attempt)


def summary(message):
    print(message)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as output:
            output.write(message + "\n")


def stop_update(message):
    summary(message)
    print(f"::error::{message}")
    raise SystemExit(1)


def property_entries(text):
    """Accept only unambiguous, single-line key=value properties."""
    entries = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith(("#", "!")):
            continue
        match = re.fullmatch(r"([A-Za-z0-9_.-]+)=(.*)", line)
        if not match or "\\" in line or match[1] in entries:
            return None
        entries[match[1]] = match[2]
    return entries


def resolve_properties():
    """Merge independent keys, refusing any key changed differently on both sides.

    Git treats adjacent version/package edits as one conflicting text block.
    Use its three index stages rather than special-casing version or package values.
    Unsupported property syntax is left for a maintainer to resolve.
    """
    stages = [git("show", f":{stage}:gradle.properties", check=False) for stage in (1, 2, 3)]
    if any(stage.returncode for stage in stages):
        return False
    parsed = [property_entries(stage.stdout) for stage in stages]
    if any(entries is None for entries in parsed):
        return False
    base, ours, theirs = parsed
    missing = object()
    merged = {}
    for key in base.keys() | ours.keys() | theirs.keys():
        before, local, remote = (entries.get(key, missing) for entries in parsed)
        if local == remote or remote == before:
            merged[key] = local
        elif local == before:
            merged[key] = remote
        else:
            return False
    # Keep upstream's current comments/order and append any Moagram-only keys.
    lines = []
    for line in stages[2].stdout.splitlines():
        match = re.fullmatch(r"([A-Za-z0-9_.-]+)=(.*)", line)
        if not match or line.lstrip().startswith(("#", "!")):
            lines.append(line)
        elif merged[match[1]] is not missing:
            lines.append(f"{match[1]}={merged[match[1]]}")
    lines.extend(f"{key}={merged[key]}" for key in ours
                 if key not in theirs and merged[key] is not missing)
    Path("gradle.properties").write_text("\n".join(lines) + "\n")
    git("add", "gradle.properties")
    return True


def conflict_issue():
    # Paginate: a busy repository may have more than 100 open issues/PRs.
    page = 1
    while True:
        issues = api("GET", f"issues?state=open&per_page=100&page={page}")
        for issue in issues:
            if "pull_request" not in issue and ISSUE_MARKER in (issue.get("body") or ""):
                return issue
        if len(issues) < 100:
            return None
        page += 1


def report_conflicts(target, version, starting, conflicts, base_sha):
    marker = f"<!-- target:{target} base:{base_sha} starting:{starting} -->"
    issue = conflict_issue()
    if issue and marker in (issue.get("body") or ""):
        summary(f"The same merge conflicts still need attention: {issue['html_url']}")
        return
    run = f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    # Names come from Git's output, not from shell evaluation.
    files = "\n".join(f"- `{name}`" for name in conflicts)
    body = f"""{ISSUE_MARKER}
{marker}
Telegram **{version}** (`{target}`) could not be merged without conflicts.
Moagram's `master` and any existing update branch were left unchanged.

Conflicting files:
{files}

Resolve on the update branch, preserving Moagram's features:

```bash
git fetch origin
git fetch https://github.com/DrKLO/Telegram.git master
git switch -c telegram-update-repair {starting}
git merge origin/master
git merge {target}
# Resolve conflicts, git add the resolved files, and git commit.
git push origin HEAD:{BRANCH}
```

If the first merge conflicts, finish it before running the second merge.
Rerun **Check Telegram updates** after pushing. It will open/update a PR,
run Android checks, and merge the validated candidate automatically.
Review privacy, hidden chats, repeating messages,
branding, API configuration, signing, and version information before release.

[Workflow run]({run}). No release or automatic merge was performed.
"""
    if issue:
        issue = api("PATCH", f"issues/{issue['number']}", {"body": body})
    else:
        issue = api("POST", "issues", {"title": ISSUE_TITLE, "body": body})
    summary(f"Manual conflict resolution needed: {issue['html_url']}")


def merge_candidate(starting, base, target):
    """Keep maintainer commits, stopping on conflicts except independent properties."""
    git("switch", "--detach", starting)
    for revision in (base, target):
        result = git("merge", "--no-edit", "--no-ff", revision, check=False)
        if result.returncode:
            conflicts = git("diff", "--name-only", "--diff-filter=U").stdout.splitlines()
            if "gradle.properties" in conflicts and resolve_properties():
                conflicts.remove("gradle.properties")
                if not conflicts:
                    git("commit", "--no-edit")
                    continue
            git("merge", "--abort", check=False)
            if not conflicts:
                raise RuntimeError(result.stderr or result.stdout)
            return conflicts
    return []


def main():
    if git("status", "--porcelain").stdout:
        raise RuntimeError("Start with a clean working tree.")
    repo = os.environ["GITHUB_REPOSITORY"]
    owner = repo.split("/")[0]
    git("config", "user.name", "github-actions[bot]")
    git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    fetch("origin", f"+refs/heads/{BASE}:refs/remotes/origin/{BASE}")
    base_sha = git("rev-parse", f"origin/{BASE}").stdout.strip()
    # Do not rely on stale/missing Telegram release tags or fetch arbitrary forks.
    fetch(UPSTREAM, "master")
    target = git("rev-parse", "FETCH_HEAD").stdout.strip()
    properties = git("show", f"{target}:gradle.properties").stdout
    version = next(line.split("=", 1)[1] for line in properties.splitlines()
                   if line.startswith("APP_VERSION_NAME="))
    if ancestor(target, base_sha):
        issue = conflict_issue()
        if issue:
            api("PATCH", f"issues/{issue['number']}", {"state": "closed"})
        summary(f"Moagram already contains Telegram {version} (`{target[:12]}`).")
        return

    query = urlencode({"state": "open", "head": f"{owner}:{BRANCH}", "base": BASE})
    pulls = api("GET", f"pulls?{query}")
    if pulls and pulls[0].get("draft"):
        stop_update("Update PR is draft. Mark it ready and rerun to resume automatic merging.")
    remote = git("ls-remote", "--heads", "origin", f"refs/heads/{BRANCH}").stdout.strip()
    starting = base_sha
    if remote:
        fetch("origin", f"+refs/heads/{BRANCH}:refs/remotes/origin/{BRANCH}")
        branch_sha = git("rev-parse", f"origin/{BRANCH}").stdout.strip()
        # A closed, unmerged PR is a deliberate pause. A manually repaired branch
        # with no PR yet is also valid; it can be picked up after conflict repair.
        closed_query = urlencode({"state": "closed", "head": f"{owner}:{BRANCH}", "base": BASE})
        closed = api("GET", f"pulls?{closed_query}") if not pulls else []
        if not pulls and closed and not closed[0].get("merged_at") and not ancestor(branch_sha, base_sha):
            stop_update("Update branch has a closed, unmerged PR. Reopen it or delete the branch to resume.")
        starting = branch_sha

    conflicts = merge_candidate(starting, base_sha, target)
    if conflicts:
        report_conflicts(target, version, starting, conflicts, base_sha)
        stop_update("Telegram update blocked by merge conflicts. See the conflict issue linked in this run's summary.")
    candidate = git("rev-parse", "HEAD").stdout.strip()
    # A regular push rejects concurrent/manual changes instead of overwriting them.
    git("push", "origin", f"HEAD:refs/heads/{BRANCH}")
    if pulls:
        pull = pulls[0]
        title = f"Merge Telegram {version} into Moagram"
        if pull["title"] != title:
            api("PATCH", f"pulls/{pull['number']}", {"title": title})
    else:
        pull = api("POST", "pulls", {
            "title": f"Merge Telegram {version} into Moagram",
            "head": BRANCH, "base": BASE, "draft": False,
            "body": """Merge the official Telegram source into Moagram while retaining local commits.

The **Check Telegram updates** workflow compiles Java, runs the repeat regression
suite, and builds a CI-only standalone APK against the exact candidate commit.
Check the latest workflow run for results. The APK uses public dummy signing and
has no Telegram API credentials; it is a compilation artifact, not a release.

After all checks pass, the workflow fast-forwards master to that exact tested
merge commit, preserving Telegram's ancestry. Concurrent changes to master stop
promotion and are included and tested on the next run. Convert this PR to draft
or close it to pause automatic merging. Publishing a signed release is separate;
review feature compatibility and perform device testing before release.
""",
        })
    issue = conflict_issue()
    if issue:
        api("PATCH", f"issues/{issue['number']}", {"state": "closed"})
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"candidate={candidate}\npull_request={pull['number']}\n")
    summary(f"Telegram {version}: {pull['html_url']}\n\nCandidate: `{candidate}`. Android checks follow in this run.")


def complete():
    """Called only after successful validation, from the trusted workflow checkout.

    A normal fast-forward push is atomic: it cannot include untested concurrent
    master changes or overwrite them. GitHub marks the containing PR merged.
    No candidate code runs in this job, which has repository write permission.
    """
    candidate = os.environ["CANDIDATE"]
    number = os.environ["PULL_REQUEST"]
    if not re.fullmatch(r"[0-9a-f]{40}", candidate) or not number.isdecimal():
        stop_update("Invalid candidate or pull request number.")
    pull = api("GET", f"pulls/{number}")
    repo = os.environ["GITHUB_REPOSITORY"]
    if (pull["head"]["repo"]["full_name"] != repo or pull["base"]["repo"]["full_name"] != repo
            or pull["head"]["ref"] != BRANCH or pull["base"]["ref"] != BASE):
        stop_update("Pull request no longer targets the expected update and master branches.")
    if pull["head"]["sha"] != candidate:
        stop_update("Update branch changed after validation. Rerun Check Telegram updates.")
    if pull.get("merged_at"):
        summary(f"Candidate `{candidate}` was already merged: {pull['html_url']}")
        return
    if pull["state"] != "open" or pull.get("draft"):
        stop_update("Update PR is closed or draft. Reopen/mark ready and rerun to resume.")
    fetch("origin", f"+refs/heads/{BASE}:refs/remotes/origin/{BASE}",
          f"+refs/heads/{BRANCH}:refs/remotes/origin/{BRANCH}")
    if git("rev-parse", f"origin/{BRANCH}").stdout.strip() != candidate:
        stop_update("Update branch changed after validation. Rerun Check Telegram updates.")
    base = git("rev-parse", f"origin/{BASE}").stdout.strip()
    if ancestor(candidate, base):
        summary(f"Master already contains validated candidate `{candidate}`.")
        return
    if not ancestor(base, candidate):
        stop_update("Master changed after preparation. Rerun to merge and validate the new base.")
    # Do not force: the server rechecks ancestry atomically, including changes
    # arriving after our fetch. Branch protection is enforced by GitHub as usual.
    result = git("push", "origin", f"{candidate}:refs/heads/{BASE}", check=False)
    if result.returncode:
        stop_update(f"Promotion was rejected; no changes overwritten. Rerun the checker.\n{result.stderr}")
    summary(f"Merged validated candidate `{candidate}`: {pull['html_url']}. No release published.")


if __name__ == "__main__":
    if sys.argv[1:] == ["--complete"]:
        complete()
    elif len(sys.argv) == 1:
        main()
    else:
        raise SystemExit("Usage: sync-telegram.py [--complete]")
