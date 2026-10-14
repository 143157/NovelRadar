[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../../..')).Path,
    [string]$PythonPath = 'C:\Users\26530\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
)
$ErrorActionPreference = 'Stop'
$taskSource = Get-Content -LiteralPath (Join-Path $RepositoryRoot 'app/src/main/java/com/novelradar/app/data/db/BookDao.kt') -Raw
$taskQuery = [regex]::Match($taskSource, '(?s)@Query\("""(.*?)"""\)\s*fun observeShelfDetails').Groups[1].Value
if ([string]::IsNullOrWhiteSpace($taskQuery)) { throw 'Actual shelf SELECT not found' }
$taskInput = @{
    query = $taskQuery
    schema = (Get-Content -LiteralPath (Join-Path $RepositoryRoot 'app/schemas/com.novelradar.app.data.db.NovelDatabase/5.json') -Raw | ConvertFrom-Json)
} | ConvertTo-Json -Depth 100 -Compress
# Read files in PowerShell; Python receives only JSON and uses SQLite in memory.
$taskPython = @'
import json, sqlite3, sys
p = json.load(sys.stdin)
db = sqlite3.connect(':memory:')
db.row_factory = sqlite3.Row
db.execute('PRAGMA foreign_keys = ON')
for e in p['schema']['database']['entities']:
    db.execute(e['createSql'].replace('${TABLE_NAME}', e['tableName']))
def insert(table, **values):
    db.execute('INSERT INTO ' + table + ' (' + ','.join(values) + ') VALUES (' + ','.join('?' for _ in values) + ')', list(values.values()))
insert('books', bookId='book', normalizedBookKey='key', bookName='Book', author='Author', coverUrl=None, introduction=None)
for s in ('a', 'b'):
    insert('book_sources', bookId='book', sourceId=s, detailUrl='https://example.test/'+s, status='HEALTHY', updatedAtEpochMillis=41)
for s, cid, idx in [('a','first',10), ('a','tie-a',30), ('a','tie-b',30), ('a','last',90), ('b','tie-b',2)]:
    insert('chapters', bookId='book', sourceId=s, chapterId=cid, chapterIndex=idx, title=cid, url='https://example.test/'+cid,
        localContentState='CACHED', localFilePath='saved/'+cid, contentSha256='known-'+cid, contentBytes=120, contentFormatVersion=0)
insert('shelf_entries', bookId='book', addedAtEpochMillis=10, lastReadAtEpochMillis=60, hasUpdate=1, preferredSourceId='a')
insert('reading_progress', bookId='book', sourceId='a', chapterId='tie-b', chapterIndex=999, offset=77, updatedAtEpochMillis=50, contentFormatVersion=0)
def snapshot():
    return {t: [tuple(r) for r in db.execute('SELECT * FROM '+t+' ORDER BY rowid')] for t in ('chapters','reading_progress','shelf_entries','books','book_sources')}
before = snapshot()
def row():
    saved = snapshot()
    rows = db.execute(p['query']).fetchall()
    assert snapshot() == saved, 'SELECT changed persisted history'
    assert len(rows) == 1
    return dict(rows[0])
r = row()
assert 'readingChapterPosition' in r, 'Missing actual catalog ordinal; raw saved index must not be used as position'
assert r['readingChapterPosition'] == 3, r
assert (r['chapterCount'], r['unreadCount'], r['selectedSourceId']) == (4, 0, 'a'), r
assert r['progress_chapterIndex'] == 999 and r['progress_offset'] == 77 and r['progress_contentFormatVersion'] == 0
assert snapshot() == before, 'SELECT changed persisted history'
db.execute("UPDATE reading_progress SET chapterId='tie-a'")
assert row()['readingChapterPosition'] == 2
db.execute("UPDATE reading_progress SET chapterId='removed'")
assert row()['readingChapterPosition'] is None
db.execute("UPDATE reading_progress SET chapterId='tie-b', sourceId='b'")
r = row()
assert r['readingChapterPosition'] is None and r['chapterCount'] == 4 and r['unreadCount'] == 4
db.execute("UPDATE shelf_entries SET preferredSourceId='missing'")
r = row()
assert r['selectedSourceId'] == 'b' and r['readingChapterPosition'] == 1 and r['chapterCount'] == 1
db.execute("UPDATE reading_progress SET sourceId='foreign'")
r = row()
assert r['selectedSourceId'] == 'a' and r['readingChapterPosition'] is None and r['chapterCount'] == 4
assert [tuple(x) for x in db.execute('SELECT * FROM chapters ORDER BY rowid')] == before['chapters']
assert row()['progress_offset'] == 77 and row()['progress_updatedAtEpochMillis'] == 50
db.execute('DELETE FROM reading_progress')
r = row()
assert r['readingChapterPosition'] is None and r['chapterCount'] == 4 and r['unreadCount'] == 4
db.execute("UPDATE shelf_entries SET preferredSourceId='b'")
db.execute("DELETE FROM chapters WHERE sourceId='b'")
insert('reading_progress', bookId='book', sourceId='b', chapterId='tie-b', chapterIndex=999,
    offset=77, updatedAtEpochMillis=50, contentFormatVersion=0)
r = row()
assert r['readingChapterPosition'] is None and r['chapterCount'] == 0 and r['unreadCount'] == 0 and r['selectedSourceId'] == 'b'
assert not db.execute('PRAGMA foreign_key_check').fetchall()
print('PASS actual BookDao shelf SELECT + generated schema 5: gapped/tied ordinal, stale index, removed/foreign position, fallback/counts, read-only raw offset/hash/provenance/history')
'@
$taskInput | & $PythonPath -c $taskPython
if ($LASTEXITCODE -ne 0) { throw "Shelf SQLite verification failed (exit $LASTEXITCODE)" }
