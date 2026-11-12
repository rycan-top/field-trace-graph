-- 作用：定义索引数据库的全部表、索引与外键。
-- 使用场景：首次建库以及表结构迁移的基线。
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA busy_timeout = 5000;

CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER PRIMARY KEY CHECK (version > 0),
    applied_at TEXT NOT NULL,
    migration_checksum TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS full_index_state (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    active_generation INTEGER NOT NULL DEFAULT 0,
    graph_status TEXT NOT NULL DEFAULT 'EMPTY' CHECK (graph_status IN ('EMPTY', 'READY', 'PARTIAL')),
    published_at TEXT,
    context_hash TEXT,
    parser_version TEXT NOT NULL,
    project_root_fingerprint TEXT NOT NULL,
    last_attempt_id TEXT,
    last_attempt_base_generation INTEGER,
    last_attempt_status TEXT NOT NULL DEFAULT 'IDLE' CHECK (last_attempt_status IN ('IDLE', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'INTERRUPTED')),
    last_attempt_started_at TEXT,
    last_attempt_finished_at TEXT,
    last_error_code TEXT,
    last_error_message TEXT
);

CREATE TABLE IF NOT EXISTS index_file_state (
    attempt_id TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    change_kind TEXT NOT NULL CHECK (change_kind IN ('ADDED', 'MODIFIED', 'DELETED', 'CONTEXT_CHANGED', 'REUSED')),
    attempt_hash TEXT,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'ANALYZING', 'ANALYZED', 'REUSED', 'DELETED', 'FAILED', 'UNSUPPORTED')),
    error_code TEXT,
    error_message TEXT,
    started_at TEXT,
    finished_at TEXT,
    PRIMARY KEY (attempt_id, relative_path)
);

CREATE TABLE IF NOT EXISTS source_file (
    file_id TEXT PRIMARY KEY,
    relative_path TEXT NOT NULL UNIQUE,
    module_id TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    file_size_bytes INTEGER NOT NULL CHECK (file_size_bytes >= 0),
    encoding TEXT NOT NULL,
    language_level TEXT NOT NULL,
    analysis_status TEXT NOT NULL CHECK (analysis_status IN ('READY', 'PARTIAL')),
    analysis_issue_code TEXT
);

CREATE TABLE IF NOT EXISTS source_code_symbol (
    symbol_id TEXT PRIMARY KEY,
    file_id TEXT NOT NULL,
    module_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN (
        'CLASS', 'INTERFACE', 'ENUM', 'ANNOTATION', 'RECORD',
        'METHOD', 'CONSTRUCTOR', 'INITIALIZER'
    )),
    symbol_role TEXT NOT NULL DEFAULT 'BUSINESS'
        CHECK (symbol_role IN ('BUSINESS', 'ACCESSOR_GET', 'ACCESSOR_SET', 'INITIALIZER')),
    qname TEXT NOT NULL,
    simple_name TEXT NOT NULL,
    declaring_type TEXT,
    parameter_types_json TEXT NOT NULL,
    return_type TEXT,
    parent_symbol_id TEXT,
    line_start INTEGER NOT NULL,
    column_start INTEGER NOT NULL,
    line_end INTEGER NOT NULL,
    column_end INTEGER NOT NULL,
    FOREIGN KEY (file_id) REFERENCES source_file(file_id) ON DELETE CASCADE,
    FOREIGN KEY (parent_symbol_id) REFERENCES source_code_symbol(symbol_id) ON DELETE CASCADE,
    UNIQUE (module_id, kind, qname)
);

CREATE TABLE IF NOT EXISTS parsed_invoke_edge (
    edge_id TEXT PRIMARY KEY,
    call_site_id TEXT NOT NULL,
    caller_symbol_id TEXT NOT NULL,
    callee_symbol_id TEXT,
    target_qname TEXT NOT NULL,
    dispatch_kind TEXT NOT NULL CHECK (dispatch_kind IN ('STATIC', 'VIRTUAL', 'CONSTRUCTOR', 'METHOD_REFERENCE')),
    resolution TEXT NOT NULL CHECK (resolution IN ('EXACT_STATIC', 'COMPILE_TIME_TARGET', 'VIRTUAL_UNIQUE', 'CANDIDATE', 'UNRESOLVED')),
    receiver_value_id TEXT,
    arguments_json TEXT NOT NULL,
    result_value_id TEXT,
    file_id TEXT NOT NULL,
    line_start INTEGER NOT NULL,
    column_start INTEGER NOT NULL,
    line_end INTEGER NOT NULL,
    column_end INTEGER NOT NULL,
    FOREIGN KEY (caller_symbol_id) REFERENCES source_code_symbol(symbol_id) ON DELETE CASCADE,
    FOREIGN KEY (callee_symbol_id) REFERENCES source_code_symbol(symbol_id) ON DELETE CASCADE,
    FOREIGN KEY (file_id) REFERENCES source_file(file_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS pending_parse_invoke_ref (
    pending_id TEXT PRIMARY KEY,
    call_site_id TEXT NOT NULL,
    caller_symbol_id TEXT NOT NULL,
    reference_kind TEXT NOT NULL CHECK (reference_kind IN ('METHOD', 'CONSTRUCTOR', 'METHOD_REFERENCE')),
    reference_name TEXT NOT NULL,
    reason TEXT NOT NULL,
    candidates_json TEXT NOT NULL,
    file_id TEXT NOT NULL,
    line_start INTEGER NOT NULL,
    column_start INTEGER NOT NULL,
    line_end INTEGER NOT NULL,
    column_end INTEGER NOT NULL,
    dispatch_kind TEXT NOT NULL DEFAULT 'VIRTUAL'
        CHECK (dispatch_kind IN ('STATIC', 'VIRTUAL', 'CONSTRUCTOR', 'METHOD_REFERENCE')),
    receiver_value_id TEXT,
    arguments_json TEXT NOT NULL DEFAULT '[]',
    result_value_id TEXT,
    FOREIGN KEY (caller_symbol_id) REFERENCES source_code_symbol(symbol_id) ON DELETE CASCADE,
    FOREIGN KEY (file_id) REFERENCES source_file(file_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS value_node (
    value_id TEXT PRIMARY KEY,
    scope_symbol_id TEXT NOT NULL,
    file_id TEXT NOT NULL,
    value_kind TEXT NOT NULL CHECK (value_kind IN ('PARAMETER', 'LAMBDA_PARAMETER', 'LOCAL', 'FIELD', 'CONSTANT', 'CALL_RESULT', 'PHI', 'RETURN', 'CONTAINER_ELEMENT', 'EXPRESSION', 'EXTERNAL_BOUNDARY')),
    canonical_value TEXT NOT NULL,
    root_name TEXT,
    access_path_json TEXT NOT NULL,
    declaration_offset INTEGER NOT NULL DEFAULT 0,
    definition_ordinal INTEGER NOT NULL DEFAULT 0,
    literal_kind TEXT,
    line_start INTEGER NOT NULL,
    column_start INTEGER NOT NULL,
    line_end INTEGER NOT NULL,
    column_end INTEGER NOT NULL,
    origin_file TEXT,
    origin_line_start INTEGER NOT NULL DEFAULT 0,
    origin_column_start INTEGER NOT NULL DEFAULT 0,
    origin_line_end INTEGER NOT NULL DEFAULT 0,
    origin_column_end INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (scope_symbol_id) REFERENCES source_code_symbol(symbol_id) ON DELETE CASCADE,
    FOREIGN KEY (file_id) REFERENCES source_file(file_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS value_data_edge (
    edge_id TEXT PRIMARY KEY,
    scope_symbol_id TEXT NOT NULL,
    src_value_id TEXT NOT NULL,
    dst_value_id TEXT NOT NULL,
    edge_type TEXT NOT NULL CHECK (edge_type IN (
        'ASSIGNED_FROM', 'DERIVED_FROM', 'FIELD_READ', 'FIELD_WRITE', 'RETURN_FROM',
        'PHI_INPUT', 'CONDITION_USES', 'CONTAINER_FLOW',
        'COPY_ALL', 'WRITES_ELEMENT', 'READS_ELEMENT', 'COPIES_ELEMENTS', 'MERGED_FROM',
        'BUILDER_RESULT', 'SQL_PARAMETER', 'MUTATES_PARAMETER'
    )),
    operation TEXT,
    resolution TEXT NOT NULL CHECK (resolution IN (
        'EXACT_STATIC', 'COMPILE_TIME_TARGET', 'CANDIDATE', 'UNRESOLVED', 'RUNTIME_OBSERVED'
    )),
    provenance TEXT NOT NULL DEFAULT 'SOURCE' CHECK (provenance IN (
        'SOURCE', 'GENERATED_SOURCE', 'BYTECODE',
        'FRAMEWORK_ADAPTER', 'RUNTIME_TRACE', 'MANUAL_CONTRACT'
    )),
    evidence_id TEXT NOT NULL,
    branch_id TEXT,
    branch_condition TEXT,
    file_id TEXT NOT NULL,
    line_start INTEGER NOT NULL,
    column_start INTEGER NOT NULL,
    line_end INTEGER NOT NULL,
    column_end INTEGER NOT NULL,
    statement_ordinal INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (scope_symbol_id) REFERENCES source_code_symbol(symbol_id) ON DELETE CASCADE,
    FOREIGN KEY (src_value_id) REFERENCES value_node(value_id) ON DELETE CASCADE,
    FOREIGN KEY (dst_value_id) REFERENCES value_node(value_id) ON DELETE CASCADE,
    FOREIGN KEY (file_id) REFERENCES source_file(file_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS edge_evidence (
    evidence_id TEXT PRIMARY KEY,
    logical_edge_key TEXT NOT NULL,
    scope_symbol_id TEXT,
    source_edge_id TEXT,
    resolution TEXT NOT NULL CHECK (resolution IN (
        'EXACT_STATIC', 'CANDIDATE', 'UNRESOLVED', 'RUNTIME_OBSERVED'
    )),
    provenance TEXT NOT NULL CHECK (provenance IN (
        'SOURCE', 'GENERATED_SOURCE', 'BYTECODE',
        'FRAMEWORK_ADAPTER', 'RUNTIME_TRACE', 'MANUAL_CONTRACT'
    )),
    evidence_kind TEXT,
    adapter TEXT,
    rule TEXT,
    file_id TEXT,
    line_start INTEGER,
    column_start INTEGER,
    line_end INTEGER,
    column_end INTEGER,
    details_json TEXT NOT NULL DEFAULT '{}',
    coverage TEXT,
    observed_at TEXT,
    FOREIGN KEY (source_edge_id) REFERENCES value_data_edge(edge_id) ON DELETE CASCADE,
    FOREIGN KEY (file_id) REFERENCES source_file(file_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS method_summary (
    summary_id TEXT PRIMARY KEY,
    scope_symbol_id TEXT NOT NULL,
    file_id TEXT NOT NULL,
    parameters_json TEXT NOT NULL,
    return_value_id TEXT,
    return_order INTEGER NOT NULL,
    branch_condition TEXT,
    field_writes_json TEXT NOT NULL,
    parameter_mutations_json TEXT NOT NULL DEFAULT '[]',
    line_start INTEGER NOT NULL,
    column_start INTEGER NOT NULL,
    line_end INTEGER NOT NULL,
    column_end INTEGER NOT NULL,
    FOREIGN KEY (scope_symbol_id) REFERENCES source_code_symbol(symbol_id) ON DELETE CASCADE,
    FOREIGN KEY (file_id) REFERENCES source_file(file_id) ON DELETE CASCADE,
    UNIQUE (scope_symbol_id, return_order)
);

CREATE INDEX IF NOT EXISTS idx_symbol_simple_name_kind ON source_code_symbol(simple_name, kind);
CREATE INDEX IF NOT EXISTS idx_symbol_role ON source_code_symbol(symbol_role);
CREATE INDEX IF NOT EXISTS idx_symbol_declaring_type ON source_code_symbol(declaring_type);
CREATE INDEX IF NOT EXISTS idx_symbol_file ON source_code_symbol(file_id);
CREATE INDEX IF NOT EXISTS idx_source_file_module ON source_file(module_id);
CREATE INDEX IF NOT EXISTS idx_value_scope ON value_node(scope_symbol_id);
CREATE INDEX IF NOT EXISTS idx_value_kind ON value_node(scope_symbol_id, value_kind);
CREATE INDEX IF NOT EXISTS idx_data_scope_dst ON value_data_edge(scope_symbol_id, dst_value_id);
CREATE INDEX IF NOT EXISTS idx_data_scope_src ON value_data_edge(scope_symbol_id, src_value_id);
CREATE INDEX IF NOT EXISTS idx_data_logical_key ON value_data_edge(scope_symbol_id, src_value_id, dst_value_id, edge_type);
CREATE INDEX IF NOT EXISTS idx_edge_evidence_logical ON edge_evidence(logical_edge_key);
CREATE INDEX IF NOT EXISTS idx_edge_evidence_source ON edge_evidence(source_edge_id);
CREATE INDEX IF NOT EXISTS idx_edge_evidence_scope ON edge_evidence(scope_symbol_id);
CREATE INDEX IF NOT EXISTS idx_call_caller_site ON parsed_invoke_edge(caller_symbol_id, call_site_id);
CREATE INDEX IF NOT EXISTS idx_call_callee_caller ON parsed_invoke_edge(callee_symbol_id, caller_symbol_id);
CREATE INDEX IF NOT EXISTS idx_pending_caller ON pending_parse_invoke_ref(caller_symbol_id);
CREATE INDEX IF NOT EXISTS idx_pending_reference ON pending_parse_invoke_ref(reference_name, reason);
CREATE INDEX IF NOT EXISTS idx_attempt_file_status ON index_file_state(attempt_id, status);
