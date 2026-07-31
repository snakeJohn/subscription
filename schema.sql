-- SubStat D1 表结构
-- 应用于：wrangler d1 execute substat --file=./schema.sql

CREATE TABLE IF NOT EXISTS subscriptions (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  domain      TEXT DEFAULT '',
  cat         TEXT NOT NULL DEFAULT 'ai',
  plan        TEXT DEFAULT '',
  price       REAL NOT NULL DEFAULT 0,
  cur         TEXT NOT NULL DEFAULT 'CNY',   -- CNY | USD
  cycle       TEXT NOT NULL DEFAULT 'month', -- once|day|week|month|quarter|half|year
  qty         INTEGER NOT NULL DEFAULT 1,
  start       TEXT NOT NULL,                 -- YYYY-MM-DD 首次付费日（锚点）
  note        TEXT DEFAULT '',
  nsfw        INTEGER NOT NULL DEFAULT 0,
  enabled     INTEGER NOT NULL DEFAULT 1,
  remind      INTEGER NOT NULL DEFAULT 1,    -- 是否参与到期提醒
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_subs_enabled ON subscriptions(enabled);
CREATE INDEX IF NOT EXISTS idx_subs_cat     ON subscriptions(cat);

-- 键值配置：rate / cur / warn_days / theme / notify_* / password_hash 等
CREATE TABLE IF NOT EXISTS settings (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

-- 提醒去重：同一订阅同一扣费日只推一次
CREATE TABLE IF NOT EXISTS notify_log (
  id       INTEGER PRIMARY KEY AUTOINCREMENT,
  sub_id   TEXT NOT NULL,
  due_date TEXT NOT NULL,
  channel  TEXT NOT NULL,
  ok       INTEGER NOT NULL DEFAULT 1,
  detail   TEXT DEFAULT '',
  sent_at  INTEGER NOT NULL,
  UNIQUE(sub_id, due_date, channel)
);
CREATE INDEX IF NOT EXISTS idx_notify_sent ON notify_log(sent_at);
