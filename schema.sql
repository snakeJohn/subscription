-- SubStat D1 表结构（多用户）
-- 应用于：wrangler d1 execute substat --file=./schema.sql
-- 说明：Worker 首次请求时会自动建表并对既有库做兼容迁移（见 worker/index.js ensureSchema），
--       正常无需手动执行本文件；此处仅作全新部署的参考与文档。

-- 用户表
CREATE TABLE IF NOT EXISTS users (
  id            TEXT PRIMARY KEY,
  username      TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  is_admin      INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL
);

-- 订阅（按用户隔离）
CREATE TABLE IF NOT EXISTS subscriptions (
  id          TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL,
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
CREATE INDEX IF NOT EXISTS idx_subs_user    ON subscriptions(user_id);
CREATE INDEX IF NOT EXISTS idx_subs_enabled ON subscriptions(enabled);
CREATE INDEX IF NOT EXISTS idx_subs_cat     ON subscriptions(cat);

-- 每用户配置：cur / warn / theme / notify_* / bark_* / tg_* / webhook_* / webdav_* / rate / rate_mode 等
CREATE TABLE IF NOT EXISTS user_settings (
  user_id    TEXT NOT NULL,
  key        TEXT NOT NULL,
  value      TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, key)
);

-- 全局配置：仅存共享的汇率缓存（rate / rate_at / rate_source）
CREATE TABLE IF NOT EXISTS settings (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

-- 提醒去重：同一订阅同一扣费日只推一次（sub_id 全局唯一，user_id 便于清理与隔离）
CREATE TABLE IF NOT EXISTS notify_log (
  id       INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id  TEXT NOT NULL DEFAULT '',
  sub_id   TEXT NOT NULL,
  due_date TEXT NOT NULL,
  channel  TEXT NOT NULL,
  ok       INTEGER NOT NULL DEFAULT 1,
  detail   TEXT DEFAULT '',
  sent_at  INTEGER NOT NULL,
  UNIQUE(sub_id, due_date, channel)
);
CREATE INDEX IF NOT EXISTS idx_notify_sent ON notify_log(sent_at);
