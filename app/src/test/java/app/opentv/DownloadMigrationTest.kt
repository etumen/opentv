package app.opentv

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import app.opentv.data.db.OpenTvDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadMigrationTest {
    @Test fun upgradesV14WithoutHeadersAndPreservesDownload() = verifyUpgrade(false)

    @Test fun upgradesV14WithExistingHeadersAndPreservesDownload() = verifyUpgrade(true)

    @Test fun upgradesToshibaV13WithExistingDownloadsAndHeaders() = verifyUpgrade(true, 13)

    private fun verifyUpgrade(hasHeaders: Boolean, oldVersion: Int = 14) {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("opentv.db")
        // Start with all the real Room tables so opening after migration also validates v15.
        OpenTvDatabase.build(context).also { it.openHelper.writableDatabase }.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("opentv.db").path, null, 0).use { db ->
            if (!hasHeaders) {
                db.execSQL("DROP TABLE downloads")
                db.execSQL("CREATE TABLE downloads (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, mediaKey TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, posterUrl TEXT, tag TEXT, seriesTitle TEXT, season INTEGER, episode INTEGER, sourceUrl TEXT NOT NULL, systemId INTEGER NOT NULL, createdMillis INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX index_downloads_mediaKey ON downloads (mediaKey)")
            }
            db.execSQL("INSERT INTO downloads (mediaKey, kind, title, sourceUrl, systemId, createdMillis) VALUES ('movie:42', 'MOVIE', 'Saved movie', 'https://example.test/movie', 42, 123)")
            if (hasHeaders) {
                db.execSQL("UPDATE downloads SET requestHeaders = ?", arrayOf("{\"Referer\":\"https://example.test\"}"))
            }
            db.version = oldVersion
        }
        val upgraded = OpenTvDatabase.build(context)
        try {
            val db = upgraded.openHelper.writableDatabase
            assertEquals(15, db.version)
            db.query("SELECT mediaKey, title, systemId, requestHeaders FROM downloads").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("movie:42", cursor.getString(0))
                assertEquals("Saved movie", cursor.getString(1))
                assertEquals(42L, cursor.getLong(2))
                assertEquals(if (hasHeaders) "{\"Referer\":\"https://example.test\"}" else null, cursor.getString(3))
                assertEquals(false, cursor.moveToNext())
            }
        } finally {
            upgraded.close()
            context.deleteDatabase("opentv.db")
        }
    }
}
