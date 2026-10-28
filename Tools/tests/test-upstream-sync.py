#!/usr/bin/env python3
"""Exercise real Git merges in temporary repos; never contact GitHub or Telegram."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "sync_telegram", Path(__file__).resolve().parents[1] / "upstream/sync-telegram.py")
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class RepositoryCase(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.previous = Path.cwd()
        os.chdir(self.directory.name)
        sync.git("init", "-b", "master")
        sync.git("config", "user.name", "Test")
        sync.git("config", "user.email", "test@example.invalid")
        self.commit("core.txt", "original\n")
        self.common = self.head()

    def tearDown(self):
        os.chdir(self.previous)
        self.directory.cleanup()

    def head(self):
        return sync.git("rev-parse", "HEAD").stdout.strip()

    def commit(self, name, text):
        Path(name).write_text(text)
        sync.git("add", name)
        sync.git("commit", "-m", name)
        return self.head()


class MergeTests(RepositoryCase):
    def merge_properties(self, base, ours, theirs):
        common = self.commit("gradle.properties", base)
        local = self.commit("gradle.properties", ours)
        sync.git("switch", "--detach", common)
        target = self.commit("gradle.properties", theirs)
        return local, target, sync.merge_candidate(local, local, target)

    def test_adjacent_version_and_package_changes_merge_by_key(self):
        base = "APP_VERSION_CODE=1\nAPP_VERSION_NAME=1.0\nAPP_PACKAGE=org.telegram.messenger\n"
        ours = base.replace("org.telegram", "org.moagram")
        theirs = base.replace("CODE=1", "CODE=2").replace("NAME=1.0", "NAME=2.0")
        local, target, conflicts = self.merge_properties(base, ours, theirs)
        self.assertEqual(conflicts, [])
        self.assertEqual(Path("gradle.properties").read_text(), theirs.replace("org.telegram", "org.moagram"))
        self.assertTrue(sync.ancestor(local, "HEAD"))
        self.assertTrue(sync.ancestor(target, "HEAD"))
        self.assertEqual(sync.git("rev-parse", "master").stdout.strip(), local)
        self.assertEqual(sync.git("status", "--porcelain").stdout, "")

    def test_property_resolver_preserves_key_additions_and_deletions(self):
        base = "A=old\nB=keep\nC=remove\n"
        ours = "A=old\nB=local\nLOCAL=yes\n"
        theirs = "A=new\nB=keep\nC=remove\nREMOTE=yes\n"
        _, _, conflicts = self.merge_properties(base, ours, theirs)
        self.assertEqual(conflicts, [])
        self.assertEqual(sync.property_entries(Path("gradle.properties").read_text()),
                         {"A": "new", "B": "local", "LOCAL": "yes", "REMOTE": "yes"})

    def test_property_resolver_refuses_conflicting_values(self):
        local, _, conflicts = self.merge_properties("VERSION=1\n", "VERSION=2\n", "VERSION=3\n")
        self.assertEqual(conflicts, ["gradle.properties"])
        self.assertEqual(self.head(), local)
        self.assertEqual(Path("gradle.properties").read_text(), "VERSION=2\n")
        self.assertEqual(sync.git("status", "--porcelain").stdout, "")

    def test_unsupported_properties_are_left_for_manual_resolution(self):
        for text in ("A=1\nA=2\n", "A=first\\\n next\n", "A:value\n", " A=1\n", "A=escaped\\ value\n"):
            with self.subTest(text=text):
                self.assertIsNone(sync.property_entries(text))
        base = "A=original\nB=original\n"
        ours = "A=original\nA=duplicate\nB=local\n"
        theirs = "A=upstream\nB=original\n"
        local, _, conflicts = self.merge_properties(base, ours, theirs)
        self.assertEqual(conflicts, ["gradle.properties"])
        self.assertEqual(self.head(), local)
        self.assertEqual(Path("gradle.properties").read_text(), ours)

    def test_merge_keeps_mods_and_upstream_ancestry(self):
        local = self.commit("moagram.txt", "repeats and privacy\n")
        sync.git("switch", "--detach", self.common)
        target = self.commit("telegram.txt", "new Telegram source\n")
        self.assertEqual(sync.merge_candidate(local, local, target), [])
        self.assertEqual(Path("moagram.txt").read_text(), "repeats and privacy\n")
        self.assertEqual(Path("telegram.txt").read_text(), "new Telegram source\n")
        self.assertTrue(sync.ancestor(target, "HEAD"))
        self.assertEqual(sync.git("rev-parse", "master").stdout.strip(), local)

    def test_conflict_aborts_without_changing_release_branch(self):
        local = self.commit("core.txt", "Moagram\n")
        sync.git("switch", "--detach", self.common)
        target = self.commit("core.txt", "Telegram\n")
        self.assertEqual(sync.merge_candidate(local, local, target), ["core.txt"])
        self.assertEqual(self.head(), local)
        self.assertEqual(Path("core.txt").read_text(), "Moagram\n")
        self.assertEqual(sync.git("status", "--porcelain").stdout, "")
        self.assertEqual(sync.git("rev-parse", "master").stdout.strip(), local)

    def test_existing_update_branch_keeps_manual_repairs_and_new_master_commits(self):
        local = self.commit("moagram.txt", "mods\n")
        sync.git("switch", "-c", "update")
        manual = self.commit("repair.txt", "maintainer fix\n")
        sync.git("switch", "master")
        base = self.commit("later-mod.txt", "later mod\n")
        sync.git("switch", "--detach", self.common)
        target = self.commit("telegram.txt", "new source\n")
        self.assertEqual(sync.merge_candidate(manual, base, target), [])
        for revision in (manual, base, target, local):
            self.assertTrue(sync.ancestor(revision, "HEAD"))
        self.assertTrue(Path("repair.txt").exists())
        self.assertTrue(Path("later-mod.txt").exists())

    def test_up_to_date_does_not_create_another_commit(self):
        local = self.commit("moagram.txt", "mods\n")
        self.assertEqual(sync.merge_candidate(local, local, self.common), [])
        self.assertEqual(self.head(), local)

    def test_unexpected_merge_failure_is_not_reported_as_a_conflict(self):
        with self.assertRaises(RuntimeError):
            sync.merge_candidate(self.common, self.common, "missing-revision")


class ReportingTests(unittest.TestCase):
    @patch.dict(os.environ, {"GITHUB_REPOSITORY": "test/repo", "GITHUB_RUN_ID": "1"})
    def test_identical_conflicts_do_not_notify_again(self):
        issue = {"body": "<!-- target:target base:base starting:start -->", "html_url": "issue"}
        with patch.object(sync, "conflict_issue", return_value=issue), \
                patch.object(sync, "api") as api, patch.object(sync, "summary"):
            sync.report_conflicts("target", "13", "start", ["core.txt"], "base")
        api.assert_not_called()

    def test_conflict_issue_search_paginates(self):
        issue = {"body": sync.ISSUE_MARKER, "number": 123}
        with patch.object(sync, "api", side_effect=[[{}] * 100, [issue]]) as api:
            self.assertEqual(sync.conflict_issue(), issue)
        self.assertIn("page=2", api.call_args.args[1])


class WorkflowTests(RepositoryCase):
    """Use real local remotes to cover fetch, push, retries, and the release ref."""

    def setUp(self):
        super().setUp()
        self.remotes = tempfile.TemporaryDirectory()
        self.origin = str(Path(self.remotes.name) / "origin.git")
        self.upstream = str(Path(self.remotes.name) / "telegram.git")
        self.common = self.commit("gradle.properties", "APP_VERSION_NAME=1.0\n")
        sync.git("clone", "--bare", ".", self.origin)
        sync.git("clone", "--bare", ".", self.upstream)
        sync.git("remote", "add", "origin", self.origin)
        self.local = self.commit("moagram.txt", "mods\n")
        sync.git("push", "origin", "master")
        sync.git("switch", "--detach", self.common)
        self.target = self.commit("gradle.properties", "APP_VERSION_NAME=2.0\n")
        sync.git("push", self.upstream, "HEAD:master")
        sync.git("switch", "master")
        self.output = str(Path(self.remotes.name) / "output")
        self.environment = patch.dict(os.environ, {
            "GITHUB_REPOSITORY": "test/repo", "GITHUB_RUN_ID": "1",
            "GITHUB_OUTPUT": self.output,
            "GITHUB_STEP_SUMMARY": str(Path(self.remotes.name) / "summary"),
        })
        self.environment.start()
        self.upstream_patch = patch.object(sync, "UPSTREAM", self.upstream)
        self.upstream_patch.start()

    def tearDown(self):
        self.upstream_patch.stop()
        self.environment.stop()
        self.remotes.cleanup()
        super().tearDown()

    def remote_head(self, ref="master"):
        return sync.git("ls-remote", self.origin, f"refs/heads/{ref}").stdout.split()[0]

    def api(self, method, endpoint, data=None):
        if method == "GET":
            return []
        if method == "POST" and endpoint == "pulls":
            return {"html_url": "https://example.invalid/pr/1"}
        raise AssertionError((method, endpoint, data))

    def test_main_publishes_a_candidate_without_changing_master(self):
        with patch.object(sync, "api", side_effect=self.api) as api:
            sync.main()
        candidate = self.head()
        self.assertEqual(self.remote_head(), self.local)
        self.assertEqual(self.remote_head(sync.BRANCH), candidate)
        self.assertTrue(sync.ancestor(self.local, candidate))
        self.assertTrue(sync.ancestor(self.target, candidate))
        self.assertIn(f"candidate={candidate}", Path(self.output).read_text())
        creates = [c for c in api.call_args_list if c.args[:2] == ("POST", "pulls")]
        self.assertEqual(len(creates), 1)
        self.assertTrue(creates[0].args[2]["draft"])

    def test_main_reports_conflicts_without_pushing_or_opening_pr(self):
        # Both Moagram and Telegram edit the same base line.
        self.local = self.commit("core.txt", "Moagram\n")
        sync.git("push", "origin", "master")
        sync.git("switch", "--detach", self.target)
        self.commit("core.txt", "Telegram\n")
        sync.git("push", self.upstream, "HEAD:master")
        sync.git("switch", "master")
        with patch.object(sync, "api", side_effect=self.api) as api, \
                patch.object(sync, "report_conflicts") as report:
            with self.assertRaises(SystemExit) as stopped:
                sync.main()
            self.assertEqual(stopped.exception.code, 1)
        self.assertEqual(self.remote_head(), self.local)
        self.assertEqual(sync.git("ls-remote", self.origin, f"refs/heads/{sync.BRANCH}").stdout, "")
        self.assertFalse(Path(self.output).exists())
        report.assert_called_once()
        self.assertIn("core.txt", report.call_args.args[3])
        self.assertFalse(any(c.args[0] == "POST" for c in api.call_args_list))

    def test_retry_keeps_manual_update_branch_edits_and_reuses_pr(self):
        with patch.object(sync, "api", side_effect=self.api):
            sync.main()
        manual = self.commit("repair.txt", "manual compatibility fix\n")
        sync.git("push", "origin", f"HEAD:refs/heads/{sync.BRANCH}")

        def api(method, endpoint, data=None):
            if method == "GET" and endpoint.startswith("pulls?"):
                return [{"number": 1, "title": "Merge Telegram 2.0 into Moagram",
                         "html_url": "https://example.invalid/pr/1"}]
            return self.api(method, endpoint, data)

        with patch.object(sync, "api", side_effect=api) as calls:
            sync.main()
        self.assertEqual(self.remote_head(sync.BRANCH), manual)
        self.assertTrue(Path("repair.txt").exists())
        self.assertFalse(any(c.args[:2] == ("POST", "pulls") for c in calls.call_args_list))

    def test_integrated_upstream_does_not_publish_or_request_a_build(self):
        sync.git("merge", "--no-edit", self.target)
        sync.git("push", "origin", "master")
        with patch.object(sync, "api", side_effect=self.api) as api:
            sync.main()
        self.assertFalse(Path(self.output).exists())
        self.assertFalse(any(c.args[0] == "POST" for c in api.call_args_list))

    def test_closed_unmerged_pr_pauses_the_existing_branch(self):
        sync.git("push", "origin", f"HEAD:refs/heads/{sync.BRANCH}")
        sync.git("switch", "--detach", self.local)
        self.commit("later.txt", "new master work\n")
        sync.git("push", "origin", f"HEAD:refs/heads/{sync.BRANCH}")
        before = self.remote_head(sync.BRANCH)
        sync.git("switch", "master")

        def api(method, endpoint, data=None):
            if "state=closed" in endpoint:
                return [{"number": 1}]
            return self.api(method, endpoint, data)

        with patch.object(sync, "api", side_effect=api):
            with self.assertRaises(SystemExit) as stopped:
                sync.main()
            self.assertEqual(stopped.exception.code, 1)
        self.assertEqual(self.remote_head(sync.BRANCH), before)
        self.assertFalse(Path(self.output).exists())


if __name__ == "__main__":
    unittest.main()
