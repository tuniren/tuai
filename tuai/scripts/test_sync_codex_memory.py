import copy
import json
from pathlib import Path
import tempfile
import tomllib
import unittest

from install_codex_sync import merge_mcp
from sync_codex_memory import SyncError, collect, redact, split_text, synchronize


class FakeMcp:
    def __init__(self):
        self.memories, self.documents, self.calls = {}, {}, []
        self.revision = 0
        self.lose_next_write_response = False

    def initialize(self):
        self.calls.append(("initialize", {}))

    def call(self, name, args):
        self.calls.append((name, copy.deepcopy(args)))
        address = (args["namespace"], args.get("key"))
        if name == "memory_read":
            if address not in self.memories:
                raise SyncError("Memory not found in this namespace")
            return copy.deepcopy(self.memories[address])
        if name == "memory_upsert":
            previous = self.memories.get(address)
            if args["expectedRevision"] != (previous["summary"]["revision"] if previous else 0):
                raise SyncError("Memory revision conflict")
            self.revision += 1
            self.memories[address] = {"summary": {key: args[key] for key in ("namespace", "key", "title", "kind", "source")}, "content": args["content"]}
            self.memories[address]["summary"]["revision"] = self.revision
            if self.lose_next_write_response:
                self.lose_next_write_response = False
                raise TimeoutError("response lost after commit")
            return copy.deepcopy(self.memories[address])
        if name == "memory_delete":
            if self.memories[address]["summary"]["revision"] != args["expectedRevision"]:
                raise SyncError("Memory revision conflict")
            del self.memories[address]
            return {"deleted": True}
        if name == "kb_upsert_document":
            self.documents[(args["namespace"], args["source"])] = copy.deepcopy(args)
            return {"documentId": str(len(self.documents)), "chunks": 1, "unchanged": False}
        raise AssertionError(name)


class CodexSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.memory = root / "memories_v2"
        (self.memory / "rollout_summaries").mkdir(parents=True)
        self.summary = self.memory / "memory_summary.md"
        self.summary.write_text("# Profile\n\n## Preferences\n\nPrefer concise answers.\n\n## Projects\n\nUse Scala.\n", encoding="utf-8")
        (self.memory / "rollout_summaries/session.md").write_text("# Prior knowledge\n\nA confirmed project fact.\n", encoding="utf-8")
        (self.memory / "auth.json").write_text('{"not_authorized":"must never be uploaded"}')
        self.config = {"memory_dir": str(self.memory), "state_dir": str(root / "state"), "owner": "test-owner",
                       "memory_namespace": "user-test", "knowledge_namespace": "knowledge-test", "settle_seconds": 0}
        self.client = FakeMcp()

    def tearDown(self):
        self.temp.cleanup()

    def test_allowlist_and_incremental_sync_never_upload_auth_or_empty_headings(self):
        documents, memories, _ = collect(self.config)
        self.assertEqual(2, len(documents))
        self.assertEqual(2, len(memories))
        self.assertNotIn("must never be uploaded", json.dumps(documents))
        result = synchronize(self.config, self.client)
        self.assertEqual("success", result["status"])
        self.assertEqual(2, result["memoriesUploaded"])
        before = len(self.client.calls)
        repeated = synchronize(self.config, self.client)
        self.assertEqual(0, repeated["memoriesUploaded"])
        self.assertEqual(0, repeated["documentsUploaded"])
        self.assertEqual(before, len(self.client.calls))

    def test_remote_edits_are_preserved_instead_of_being_overwritten(self):
        synchronize(self.config, self.client)
        address = next(key for key, value in self.client.memories.items() if "Preferences" in value["summary"]["title"])
        self.client.memories[address]["content"] = "A remote correction."
        self.client.memories[address]["summary"]["revision"] += 100
        self.summary.write_text(self.summary.read_text().replace("concise", "detailed"))
        result = synchronize(self.config, self.client)
        self.assertEqual(1, result["conflicts"])
        self.assertEqual("A remote correction.", self.client.memories[address]["content"])

    def test_lost_mutation_response_is_read_back_without_a_second_upsert(self):
        self.client.lose_next_write_response = True
        result = synchronize(self.config, self.client)
        self.assertEqual("success", result["status"])
        self.assertEqual(2, sum(name == "memory_upsert" for name, _ in self.client.calls))

    def test_superseded_sections_retire_but_knowledge_archives_remain(self):
        synchronize(self.config, self.client)
        self.summary.write_text("# Profile\n\n## Preferences\n\nPrefer concise answers.\n", encoding="utf-8")
        (self.memory / "rollout_summaries/session.md").unlink()
        result = synchronize(self.config, self.client)
        self.assertEqual(1, result["retired"])
        self.assertEqual(1, len(self.client.memories))
        self.assertEqual(2, len(self.client.documents))

    def test_utf16_chunks_and_credential_redaction(self):
        text = "A" * 5999 + "😀" + "B" * 8000
        parts = split_text(text)
        self.assertEqual(text, "".join(parts))
        self.assertTrue(all(len(part.encode("utf-16-le")) // 2 <= 6000 for part in parts))
        sanitized = redact("DB_PASSWORD='example-private-value'\nAPI_KEY=sk-abcdefghijklmnopqrstuv\nDB_PASSWORD=$DB_PASSWORD\nserver=192.168.6.162")
        self.assertNotIn("example-private-value", sanitized)
        self.assertNotIn("sk-abcdefghijklmnopqrstuv", sanitized)
        self.assertIn("$DB_PASSWORD", sanitized)
        self.assertIn("192.168.6.162", sanitized)

    def test_mcp_merge_keeps_notification_provider_and_other_servers(self):
        text = 'model="existing-model"\nnotify=["existing-notifier","notify"]\n[mcp_servers.other]\ncommand="existing-tool"\n[model_providers.example]\nbase_url="https://example.test"\n'
        merged = merge_mcp(text, "http://192.168.6.162:30881/mcp")
        parsed = tomllib.loads(merged)
        original = tomllib.loads(text)
        self.assertEqual(original["notify"], parsed["notify"])
        self.assertEqual(original["model_providers"], parsed["model_providers"])
        self.assertEqual(original["mcp_servers"]["other"], parsed["mcp_servers"]["other"])
        self.assertEqual(parsed, tomllib.loads(merge_mcp(merged, "http://192.168.6.162:30881/mcp")))


if __name__ == "__main__":
    unittest.main()
