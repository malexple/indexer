#!/usr/bin/env python3
"""Applies an Indexer patch (.sql / .sql.gz) to index.db. Usage: python apply_patch.py index.db patch.sql.gz"""
import gzip, re, sqlite3, sys


def main(db_path, patch_path):
    raw = open(patch_path, "rb").read()
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    sql = raw.decode("utf-8")
    head = sql.split("\nBEGIN;", 1)[0]
    header = dict(re.findall(r"^-- ([a-z_]+): (.*)$", head, re.M))
    con = sqlite3.connect(db_path)
    meta = dict(con.execute("SELECT key, value FROM meta WHERE scope = ''"))
    for key in ("db_id", "schema_version", "seq"):
        want = header["from_seq"] if key == "seq" else header[key]
        if meta.get(key) != want:
            sys.exit(f"REFUSED: database {key}={meta.get(key)!r}, patch expects {want!r}. "
                     f"Apply the missing patches first or upload a fresh index.db.")
    try:
        con.executescript(sql)
    except Exception as e:
        if con.in_transaction:
            con.execute("ROLLBACK")
        sys.exit(f"FAILED, database left unchanged: {e}")
    fk = con.execute("PRAGMA foreign_key_check").fetchall()
    ok = con.execute("PRAGMA integrity_check").fetchone()[0]
    try:
        con.execute("INSERT INTO code_fts(code_fts, rank) VALUES ('integrity-check', 1)")
        fts = "ok"
    except Exception as e:
        fts = f"BROKEN: {e}"
    seq = con.execute("SELECT value FROM meta WHERE scope = '' AND key = 'seq'").fetchone()[0]
    print(f"applied: seq {header['from_seq']} -> {seq}; integrity={ok}, fk_violations={len(fk)}, fts={fts}")
    con.close()


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])