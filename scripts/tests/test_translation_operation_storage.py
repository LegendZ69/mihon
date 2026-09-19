"""Exercise the actual translator schema and migrations with durable SQLite files."""
import pathlib
import sqlite3
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SQL = ROOT / "data/src/main/sqldelight/tachiyomi"


class TranslationOperationStorageTest(unittest.TestCase):
    def test_usage_survives_translation_deletion_and_operation_survives_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "translation.db"
            connection = sqlite3.connect(path)
            connection.execute("PRAGMA foreign_keys=ON")
            connection.executescript((SQL / "data/translation.sq").read_text().split("\njobs:\n")[0])
            connection.execute("INSERT INTO translation_jobs VALUES ('job',1,2,0,1,'original settings')")
            connection.execute("INSERT INTO translation_results VALUES ('job','page',123,'manual correction')")
            connection.execute("INSERT INTO translation_operations VALUES ('ocr','job',NULL,NULL,'page','LOCAL_OCR','COMPLETED',10,'saved OCR')")
            connection.execute("INSERT INTO translation_usage VALUES ('request','job',10,'VERTEX_SERVICE_ACCOUNT','model','usage')")
            connection.commit()
            connection.close()
            connection = sqlite3.connect(path)
            connection.execute("PRAGMA foreign_keys=ON")
            self.assertEqual(connection.execute("SELECT payload FROM translation_operations WHERE image_id='page'").fetchall(), [('saved OCR',)])
            connection.execute("DELETE FROM translation_jobs WHERE id='job'")
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM translation_results").fetchone()[0], 0)
            self.assertEqual(connection.execute("SELECT payload FROM translation_usage").fetchall(), [('usage',)])
            connection.close()

    def test_page_log_includes_its_batch_and_review_transport_but_not_neighbors(self):
        connection = sqlite3.connect(":memory:")
        text = (SQL / "data/translation.sq").read_text()
        connection.executescript(text.split("\njobs:\n")[0])
        for ident, parent, image in [('review', None, 'page'), ('attempt', 'review', None)]:
            connection.execute("INSERT INTO translation_operations VALUES (?,?,?,NULL,?,'REVIEW','COMPLETED',10,'{}')", (ident, 'job', parent, image))
        for ident, batch, image, operation, time in [('page', None, 'page', None, 10), ('batch', 'shared', None, None, 11), ('review-http', None, None, 'attempt', 12), ('neighbor', 'other', 'neighbor', None, 13), ('old', 'shared', None, None, 1)]:
            connection.execute("INSERT INTO translation_events VALUES (?,?,?,?,?,?,?,'REQUEST','INFO')", (ident, 'job', time, ident, operation, batch, image))
        query = text.split('\neventPage:\n')[1].split(';', 1)[0].replace('IN :relatedBatchIds', 'IN (:relatedBatchIds)')
        args = dict(jobId='job', operationId=None, batchId=None, imageId='page', relatedBatchIds='shared', level=None, search='', since=10, until=13, provider=None, model=None, pageLimit=100, pageOffset=0)
        self.assertEqual([row[0] for row in connection.execute(query, args)], ['review-http', 'batch', 'page'])
        connection.close()

    def test_provider_model_log_drilldown_excludes_other_and_unattributed_operations(self):
        connection = sqlite3.connect(":memory:")
        text = (SQL / "data/translation.sq").read_text()
        connection.executescript(text.split("\njobs:\n")[0])
        for ident, payload in [('groq', '{"provider":"GROQ","model":"model"}'), ('openai', '{"provider":"OPENAI","model":"model"}'), ('legacy', '{}')]:
            connection.execute("INSERT INTO translation_operations VALUES (?,'job',NULL,NULL,NULL,'REQUEST','COMPLETED',10,?)", (ident, payload))
            connection.execute("INSERT INTO translation_events VALUES (?,'job',10,?,?,NULL,NULL,'REQUEST','INFO')", (ident, ident, ident))
        query = text.split('\neventPage:\n')[1].split(';', 1)[0].replace('IN :relatedBatchIds', 'IN (:relatedBatchIds)')
        args = dict(jobId=None, operationId=None, batchId=None, imageId=None, relatedBatchIds='', level=None, search='', since=None, until=None, provider='GROQ', model='model', pageLimit=100, pageOffset=0)
        self.assertEqual([row[0] for row in connection.execute(query, args)], ['groq'])
        connection.close()

    def test_upgrade_preserves_raw_results_events_and_legacy_stage_is_unknown(self):
        connection = sqlite3.connect(":memory:")
        for version in (15, 16):
            connection.executescript((SQL / f"migrations/{version}.sqm").read_text())
        connection.execute("INSERT INTO translation_jobs VALUES ('job',1,2,0,1,'immutable settings')")
        connection.execute("INSERT INTO translation_results VALUES ('job','page',123,'manual correction with raw OCR')")
        connection.execute("INSERT INTO translation_events VALUES ('event','job',10,'historical event')")
        connection.executescript((SQL / "migrations/17.sqm").read_text())
        self.assertEqual(connection.execute("SELECT payload,committed_at FROM translation_results").fetchall(), [('manual correction with raw OCR',123)])
        self.assertEqual(connection.execute("SELECT payload,operation_id,stage FROM translation_events").fetchall(), [('historical event',None,'legacy')])
        fresh = sqlite3.connect(":memory:")
        fresh.executescript((SQL / "data/translation.sq").read_text().split("\njobs:\n")[0])
        tables = connection.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
        for (name,) in tables:
            self.assertEqual(connection.execute(f"PRAGMA table_info({name})").fetchall(), fresh.execute(f"PRAGMA table_info({name})").fetchall())
        connection.close()
        fresh.close()


if __name__ == "__main__":
    unittest.main()
