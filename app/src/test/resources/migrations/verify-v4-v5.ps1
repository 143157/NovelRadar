[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../../..')).Path,
    [string]$PythonPath = 'C:\Users\26530\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
)

$ErrorActionPreference = 'Stop'
$taskSchemaDirectory = Join-Path $RepositoryRoot 'app/schemas/com.novelradar.app.data.db.NovelDatabase'
$taskSourcePath = Join-Path $RepositoryRoot 'app/src/main/java/com/novelradar/app/data/db/NovelDatabase.kt'
$taskSource = Get-Content -LiteralPath $taskSourcePath -Raw
$taskMigrationBlock = [regex]::Match($taskSource, '(?s)val MIGRATION_4_5 =.*?(?=val MIGRATION_|\z)').Value
if ($taskMigrationBlock -notmatch 'Migration\(4, 5\)') { throw 'Actual 4-to-5 migration is missing' }
$taskStatements = @([regex]::Matches($taskMigrationBlock, 'db\.execSQL\("([^"]+)"\)') | ForEach-Object { $_.Groups[1].Value })
if ($taskStatements.Count -ne 2) { throw 'Expected exactly two actual migration statements' }
$taskInput = @{
    before = (Get-Content -LiteralPath (Join-Path $taskSchemaDirectory '4.json') -Raw | ConvertFrom-Json)
    after = (Get-Content -LiteralPath (Join-Path $taskSchemaDirectory '5.json') -Raw | ConvertFrom-Json)
    statements = $taskStatements
} | ConvertTo-Json -Depth 100 -Compress

# Python receives actual schemas/SQL on stdin; all databases remain in memory.
$taskPython = @'
import json, sqlite3, sys

payload = json.load(sys.stdin)
before = payload['before']['database']
after = payload['after']['database']
assert before['version'] == 4 and after['version'] == 5
old_entities = {e['tableName']: e for e in before['entities']}
new_entities = {e['tableName']: e for e in after['entities']}
assert len(old_entities) == 7 and old_entities.keys() == new_entities.keys()
assert payload['statements'] == [
    'ALTER TABLE chapters ADD COLUMN contentFormatVersion INTEGER NOT NULL DEFAULT 0',
    'ALTER TABLE reading_progress ADD COLUMN contentFormatVersion INTEGER NOT NULL DEFAULT 0',
]

def create(schema):
    connection = sqlite3.connect(':memory:')
    connection.execute('PRAGMA foreign_keys = ON')
    for entity in schema['entities']:
        table = entity['tableName']
        connection.execute(entity['createSql'].replace('${TABLE_NAME}', table))
        for index in entity.get('indices', []):
            connection.execute(index['createSql'].replace('${TABLE_NAME}', table))
    return connection

db = create(before)
expected_db = create(after)

def insert(table, row):
    columns = list(row)
    quoted = ','.join('`' + column + '`' for column in columns)
    placeholders = ','.join('?' for column in columns)
    db.execute('INSERT INTO `' + table + '` (' + quoted + ') VALUES (' + placeholders + ')', list(row.values()))

for source in ('a', 'b'):
    insert('source_rules', dict(sourceId=source, rawJson='{}', enabled=1, builtIn=0, allowCleartext=0,
        scheme='HTTPS', healthStatus='HEALTHY', lastLatencyMillis=12, lastHealthAtEpochMillis=41,
        lastSuccessAtEpochMillis=40, lastFailureKind='saved-kind', lastFailureDetail='saved-detail'))
insert('books', dict(bookId='book', normalizedBookKey='key', bookName='Book', author='Author',
    coverUrl='https://example.test/cover', introduction='Saved introduction'))
for source in ('a', 'b'):
    insert('book_sources', dict(bookId='book', sourceId=source, detailUrl='https://example.test/' + source + '/book',
        status='HEALTHY', updatedAtEpochMillis=41))
for source, chapter, index, state, size in [('a', 'cached', 1, 'CACHED', 120), ('b', 'downloaded', 2, 'DOWNLOADED', 240)]:
    insert('chapters', dict(bookId='book', sourceId=source, chapterId=chapter, chapterIndex=index,
        title=chapter + ' title', url='https://example.test/' + source + '/chapter', localContentState=state,
        localFilePath='books/book/' + source + '/' + chapter + '.txt', contentSha256=chapter + '-hash', contentBytes=size))
insert('shelf_entries', dict(bookId='book', addedAtEpochMillis=10, lastReadAtEpochMillis=60, hasUpdate=1, preferredSourceId='b'))
insert('reading_progress', dict(bookId='book', sourceId='a', chapterId='cached', chapterIndex=1, offset=77, updatedAtEpochMillis=50))
for state in ('PAUSED', 'COMPLETED'):
    insert('download_tasks', dict(taskId=state, bookId='book', sourceId='b', state=state, totalCount=100,
        completedCount=100 if state == 'COMPLETED' else 20, failedCount=0 if state == 'COMPLETED' else 2,
        createdAtEpochMillis=1, updatedAtEpochMillis=50, workId='saved-work', lastError='saved-error', generation=8,
        allowMetered=1, retryFailedOnly=1, networkRetryOnly=1, networkAttempts=3, failedChapterIds='c0\nc1',
        networkFailedChapterIds='c1', attemptedNonNetworkChapterIds='c0', bytesDownloaded=360,
        currentChapterId='downloaded', bytesPerSecond=25))

def rows(table, columns):
    return db.execute('SELECT ' + ','.join('`' + c + '`' for c in columns) + ' FROM `' + table + '` ORDER BY rowid').fetchall()

def table_info(connection, table):
    return connection.execute('PRAGMA table_info(`' + table + '`)').fetchall()

def foreign_keys(connection, table):
    return connection.execute('PRAGMA foreign_key_list(`' + table + '`)').fetchall()

def indices(connection, table):
    return {row[1]: (row[2:], connection.execute('PRAGMA index_info(`' + row[1] + '`)').fetchall())
        for row in connection.execute('PRAGMA index_list(`' + table + '`)')}

def tables(connection):
    return {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}

old_columns = {table: [field['columnName'] for field in entity['fields']] for table, entity in old_entities.items()}
old_rows = {table: rows(table, columns) for table, columns in old_columns.items()}
old_info = {table: table_info(db, table) for table in old_entities}
old_fks = {table: foreign_keys(db, table) for table in old_entities}
old_indices = {table: indices(db, table) for table in old_entities}
old_tables = tables(db)
counts = {table: len(data) for table, data in old_rows.items()}
assert counts == dict(source_rules=2, books=1, book_sources=2, chapters=2, shelf_entries=1, reading_progress=1, download_tasks=2)
assert not db.execute('PRAGMA foreign_key_check').fetchall()

for statement in payload['statements']:
    db.execute(statement)

assert tables(db) == old_tables == tables(expected_db)
for table, old in old_entities.items():
    current = new_entities[table]
    assert rows(table, old_columns[table]) == old_rows[table], table + ': old row changed'
    assert table_info(db, table) == table_info(expected_db, table), table + ': generated DDL differs'
    assert foreign_keys(db, table) == old_fks[table] == foreign_keys(expected_db, table)
    assert indices(db, table) == old_indices[table] == indices(expected_db, table)
    for attribute in ('primaryKey', 'foreignKeys', 'indices'):
        assert current.get(attribute) == old.get(attribute), table + ': schema metadata changed'
    if table in ('chapters', 'reading_progress'):
        assert current['fields'][:-1] == old['fields']
        assert current['fields'][-1] == dict(fieldPath='contentFormatVersion', columnName='contentFormatVersion',
            affinity='INTEGER', notNull=True, defaultValue='0')
        assert table_info(db, table)[:-1] == old_info[table]
        assert table_info(db, table)[-1][1:] == ('contentFormatVersion', 'INTEGER', 1, '0', 0)
        assert db.execute('SELECT COUNT(*) FROM `' + table + '` WHERE contentFormatVersion != 0 OR contentFormatVersion IS NULL').fetchone()[0] == 0
    else:
        assert current == old, table + ': unrelated schema changed'
assert not db.execute('PRAGMA foreign_key_check').fetchall()
print('PASS actual v4 DDL + actual MIGRATION_4_5 + generated v5 DDL')
print('PASS preserved 7 tables / 11 rows / primary keys / foreign keys / indices / all old columns')
print('PASS two INTEGER NOT NULL DEFAULT 0 columns; 3 legacy row markers = 0')
print('PASS cached/downloaded paths+hashes+bytes, offset=77, source selection, timestamps, PAUSED/COMPLETED checkpoints')
db.close()
expected_db.close()
'@

$taskInput | & $PythonPath -c $taskPython
if ($LASTEXITCODE -ne 0) { throw "In-memory SQLite verification failed (exit $LASTEXITCODE)" }
