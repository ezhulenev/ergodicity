-- Postgres schema

-- table declarations :
create table "SESSION" (
    "sess_id" integer not null,
    "begin" bigint not null,
    "end" bigint not null,
    "opt_sess_id" integer not null,
    "inter_cl_begin" bigint not null,
    "inter_cl_end" bigint not null,
    "eve_on" integer not null,
    "eve_begin" bigint not null,
    "eve_end" bigint not null,
    "mon_on" integer not null,
    "mon_begin" bigint not null,
    "mon_end" bigint not null,
    "pos_transfer_begin" bigint not null,
    "pos_transfer_end" bigint not null
  );

create table "FUT_SESS_CONTENTS" (
    "sess_id" integer not null,
    "isin_id" integer not null,
    "short_isin" varchar(128) not null,
    "isin" varchar(128) not null,
    "name" varchar(128) not null,
    "inst_term" integer not null,
    "code_vcb" varchar(128) not null,
    "is_limited" integer not null,
    "limit_up" numeric(16,5) not null,
    "limit_down" numeric(16,5) not null,
    "old_kotir" numeric(16,5) not null,
    "buy_deposit" numeric(16,2) not null
    "sell_deposit" numeric(16,2) not null,
    "roundto" integer not null,
    "min_step" numeric(16,5) not null,
    "lot_volume" integer not null,
    "step_price" numeric(16,5) not null,
    "d_pg" bigint not null,
    "is_spread" integer not null,
    "coeff" numeric(9,6) not null,
    "d_exp" bigint not null,
    "is_percent" integer not null,
    "percent_rate" numeric(6,2) not null,
    "last_cl_quote" numeric(16,5) not null,
    "signs" integer not null,
    "is_trade_evening" integer not null,
    "ticker" integer not null,
    "price_dir" integer not null,
    "multileg_type" integer not null,
    "legs_qty" integer not null,
    "step_price_clr" numeric(16,5) not null,
    "step_price_interclr" numeric(16,5) not null,
    "step_price_curr" numeric(16,5) not null,
    "d_start" bigint not null
  );

create table "OPT_SESS_CONTENTS" (
    "sess_id" integer not null,
    "isin_id" integer not null,
    "isin_id" integer not null,
    "short_isin" varchar(128) not null,
    "name" varchar(128) not null,
    "code_vcb" varchar(128) not null,
    "fut_isin_id" integer not null,
    "is_limited" integer not null,
    "limit_up" numeric(16,5) not null,
    "limit_down" numeric(16,5) not null,
    "old_kotir" numeric(16,5) not null,
    "bgo_c" numeric(16,2) not null,
    "bgo_nc" numeric(16,2) not null,
    "europe" integer not null,
    "put" integer not null,
    "strike" numeric(16,5) not null,
    "roundto" integer not null,
    "min_step" numeric(16,5) not null,
    "lot_volume" integer not null,
    "step_price" numeric(16,5) not null,
    "d_pg" bigint not null,
    "d_exec_beg" bigint not null,
    "d_exec_end" bigint not null,
    "signs" integer not null,
    "last_cl_quote" numeric(16,5) not null,
    "bgo_buy" numeric(16,2) not null,
    "base_isin_id" integer not null,
    "d_start" bigint not null
  );

create table "REPLICATION_STATE" (
    "state" varchar(128) not null,
    "stream" varchar(5000) not null
  );
-- indexes on REPLICATION_STATE
create unique index "idx_REPLICATION_STATE_STREAM" on "REPLICATION_STATE" ("stream");
-- composite key indexes :
alter table "SESSION" add primary key ("sess_id","opt_sess_id");
alter table "FUT_SESS_CONTENTS" add primary key ("sess_id","isin_id");
alter table "OPT_SESS_CONTENTS" add primary key ("sess_id","isin_id");