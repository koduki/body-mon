"""Exercise range regression tests execute the production DAO SQL."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DAO = ROOT / "app/src/main/java/com/master/healthcoach/data/db/HealthCoachDao.kt"

def query_for(method):
    text = DAO.read_text()
    pattern = r'@Query\(([\s\S]*?)\)\s+suspend fun ' + method + r'\('
    match = re.search(pattern, text)
    # Start at the nearest @Query rather than swallowing preceding declarations.
    block = match.group(1).split("@Query(")[-1]
    return "".join(re.findall(r'"([^"]*)"', block))

class ExerciseRangeTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("CREATE TABLE exercise_sessions(recordId TEXT, startEpochMillis INTEGER, endEpochMillis INTEGER)")
        self.db.executemany("INSERT INTO exercise_sessions VALUES(?,?,?)", [
            ("overnight", 500, 1500), ("ended_at_start", 500, 1000),
            ("inside", 1200, 1800), ("outside", 2100, 2400)])
    def tearDown(self):
        self.db.close()
    def test_read_includes_overlap_and_excludes_end_boundary(self):
        rows = self.db.execute(query_for("getExerciseSessions"), {"from":1000, "to":1999}).fetchall()
        self.assertEqual(["overnight", "inside"], [r[0] for r in rows])
    def test_sync_replaces_overlapping_stale_sessions(self):
        self.db.execute(query_for("deleteExerciseSessionsInRange"), {"from":1000, "toExclusive":2000})
        self.assertEqual({"ended_at_start","outside"}, {r[0] for r in self.db.execute("SELECT * FROM exercise_sessions")})

if __name__ == "__main__":
    unittest.main()
