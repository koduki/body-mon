"""Execute production migration SQL against SQLite and verify retention/revision behavior."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
DB_FILE = ROOT / 'app/src/main/java/com/master/healthcoach/data/db/HealthCoachDatabase.kt'
DAO_FILE = ROOT / 'app/src/main/java/com/master/healthcoach/data/db/HealthCoachDao.kt'

class McpMigrationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        source = DB_FILE.read_text()
        helper = source.split('private fun installRevisionTriggers', 1)[1].split('private val MIGRATION_1_2', 1)[0]
        self.tables = re.findall(r'"([a-z_]+)"', re.search(r'listOf\((.*?)\)\.forEach \{ table', helper, re.S).group(1))
        for table in self.tables:
            if table != 'report_archive':
                self.db.execute(f'CREATE TABLE {table} (id INTEGER PRIMARY KEY, content TEXT)')
        self.db.execute("INSERT INTO chat_messages VALUES (1, 'existing conversation')")
        migration = source.split('internal val MIGRATION_9_10', 1)[1].split('private fun installRevisionTriggers', 1)[0]
        for sql in re.findall(r'db.execSQL\("([^"\n]+)"\)', migration):
            self.db.execute(sql)
        for sql in re.findall(r'db.execSQL\("([^"\n]+)"\)', helper):
            if '$table' in sql:
                for table in self.tables:
                    for operation in ('INSERT', 'UPDATE', 'DELETE'):
                        expanded = sql.replace('${table}', table).replace('$table', table).replace('$operation', operation)
                        self.db.execute(expanded)
            else:
                self.db.execute(sql)
        self.db.commit()

    def tearDown(self):
        self.db.close()

    def test_existing_chat_is_preserved_and_revision_rolls_back_with_failed_transaction(self):
        self.assertEqual(self.db.execute('SELECT content FROM chat_messages').fetchone()[0], 'existing conversation')
        self.db.execute("UPDATE chat_messages SET content='changed' WHERE id=1")
        self.assertEqual(self.db.execute("SELECT value FROM app_metadata WHERE key='revision'").fetchone()[0], '1')
        self.db.rollback()
        self.assertEqual(self.db.execute("SELECT value FROM app_metadata WHERE key='revision'").fetchone()[0], '0')
        self.assertEqual(self.db.execute('SELECT content FROM chat_messages').fetchone()[0], 'existing conversation')

    def test_evidence_is_removed_when_source_message_is_deleted(self):
        self.db.execute("INSERT INTO response_evidence VALUES (1, 1000, 'encrypted')")
        self.db.execute('DELETE FROM chat_messages')
        sql = re.search(r'@Query\("(DELETE FROM response_evidence[^"\n]+)"\)', DAO_FILE.read_text()).group(1)
        self.db.execute(sql, {'cutoff': 0})
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM response_evidence').fetchone()[0], 0)

    def test_telemetry_cannot_invalidate_health_snapshot_cursor(self):
        self.db.execute("INSERT INTO app_events VALUES (1, 1000, 'mcp', 'success', 0, '', '{}', 'v1', 'sha')")
        self.assertEqual(self.db.execute("SELECT value FROM app_metadata WHERE key='revision'").fetchone()[0], '0')

if __name__ == '__main__':
    unittest.main()
