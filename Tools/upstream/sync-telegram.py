#!/usr/bin/env python3
"""Prepare a normal upstream merge; leave the release branch and manual edits intact.

Run from the repository root in GitHub Actions. No third-party Python dependencies.
"""
import json
import os
import subprocess
from urllib.parse import urlencode
from urllib.request import Request, urlopen


UPSTREAM = "https://github.com/DrKLO/Telegram.git"
BRANCH = "codex/telegram-upstream"
BASE = "master"
ISSUE_TITLE = "Telegram update needs conflict resolution"
ISSUE_MARKER = "<!-- moagram-telegram-upstream -->"


def git(*args, check=True):
    return subprocess.run(["git", *args], text=True, capture_output=True, check=check)


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
    with urlopen(request, timeout=60) as response:
        return json.load(response)


def summary(message):
    print(message)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as output:
            output.write(message + "\n")


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
Rerun **Check Telegram updates** after pushing. It will open/update a draft PR
and run Android checks. Review privacy, hidden chats, repeating messages,
branding, API configuration, signing, and version information before release.

[Workflow run]({run}). No release or automatic merge was performed.
"""
    if issue:
        issue = api("PATCH", f"issues/{issue['number']}", {"body": body})
    else:
        issue = api("POST", "issues", {"title": ISSUE_TITLE, "body": body})
    summary(f"Manual conflict resolution needed: {issue['html_url']}")


def merge_candidate(starting, base, target):
    """Keep existing maintainer commits and merge normally, never using ours/theirs."""
    git("switch", "--detach", starting)
    for revision in (base, target):
        result = git("merge", "--no-edit", "--no-ff", revision, check=False)
        if result.returncode:
            conflicts = git("diff", "--name-only", "--diff-filter=U").stdout.splitlines()
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
    git("fetch", "--no-tags", "origin", f"+refs/heads/{BASE}:refs/remotes/origin/{BASE}")
    base_sha = git("rev-parse", f"origin/{BASE}").stdout.strip()
    # Do not rely on stale/missing Telegram release tags or fetch arbitrary forks.
    git("fetch", "--no-tags", UPSTREAM, "master")
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
    remote = git("ls-remote", "--heads", "origin", f"refs/heads/{BRANCH}").stdout.strip()
    starting = base_sha
    if remote:
        git("fetch", "--no-tags", "origin", f"+refs/heads/{BRANCH}:refs/remotes/origin/{BRANCH}")
        branch_sha = git("rev-parse", f"origin/{BRANCH}").stdout.strip()
        # A closed, unmerged PR is a deliberate pause. A manually repaired branch
        # with no PR yet is also valid; it can be picked up after conflict repair.
        closed_query = urlencode({"state": "closed", "head": f"{owner}:{BRANCH}", "base": BASE})
        closed = api("GET", f"pulls?{closed_query}") if not pulls else []
        if not pulls and closed and not ancestor(branch_sha, base_sha):
            summary("Update branch has a closed, unmerged PR. Reopen it or delete the branch to resume.")
            return
        starting = branch_sha

    conflicts = merge_candidate(starting, base_sha, target)
    if conflicts:
        report_conflicts(target, version, starting, conflicts, base_sha)
        return
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
            "head": BRANCH, "base": BASE, "draft": True,
            "body": """Merge the official Telegram source into Moagram while retaining local commits.

The **Check Telegram updates** workflow compiles Java, runs the repeat regression
suite, and builds a CI-only standalone APK against the exact candidate commit.
Check the latest workflow run for results. The APK uses public dummy signing and
has no Telegram API credentials; it is a compilation artifact, not a release.

Before marking ready:
- [ ] Review upstream changes and Moagram feature compatibility.
- [ ] Verify repeating messages, hidden chats, and privacy controls on a device.
- [ ] Review branding, credentials, signing, version information, and toolchain changes.
- [ ] Confirm the latest update workflow passed for the current PR head.

Merge this PR with **Create a merge commit** so future checks retain Telegram's
ancestry. The workflow never merges or publishes a release automatically.
""",
        })
    issue = conflict_issue()
    if issue:
        api("PATCH", f"issues/{issue['number']}", {"state": "closed"})
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"candidate={candidate}\n")
    summary(f"Telegram {version}: {pull['html_url']}\n\nCandidate: `{candidate}`. Android checks follow in this run.")


if __name__ == "__main__":
    main()
