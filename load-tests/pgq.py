#!/usr/bin/env python3
"""pgq.py — run a SQL query against Railway Postgres via JDBC driver + jshell.

Usage:
    python pgq.py "<sql>"

Requires a tunnel: `railway connect -s Postgres` running locally.
Env overrides:
    DATABASE_URL  explicit URL (else fetched via `railway run -s Postgres`)
    PGHOST        tunnel host (default: derived from DATABASE_URL)
    PGPORT        tunnel port (default: derived from DATABASE_URL)
"""
import os
import subprocess
import sys
import tempfile

SQL = sys.argv[1] if len(sys.argv) > 1 else "SELECT 1"
PGURL = os.environ.get("DATABASE_URL", "")
if not PGURL:
    out = subprocess.run(
        ["railway", "run", "-s", "Postgres", "--", "sh", "-c", "echo $DATABASE_URL"],
        capture_output=True, text=True, cwd=os.getcwd(),
    )
    PGURL = (out.stdout or "").strip().splitlines()[-1]

if not PGURL.startswith("postgresql://"):
    print("ERROR: no DATABASE_URL found", file=sys.stderr)
    sys.exit(2)

jar = os.path.expanduser("~/.m2/repository/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar")
if not os.path.exists(jar):
    print(f"ERROR: JDBC jar not found at {jar}", file=sys.stderr)
    sys.exit(2)

rest = PGURL[len("postgresql://"):]
user, rest = rest.split(":", 1)
passwd, rest = rest.split("@", 1)
hostport, db = rest.split("/", 1)
db = db.split("?", 1)[0]
host, port = (hostport.split(":", 1) if ":" in hostport else (hostport, "5432"))

host = os.environ.get("PGHOST", host)
port = os.environ.get("PGPORT", port)
if "railway.internal" in host and not os.environ.get("PGHOST"):
    print("ERROR: host is internal; run 'railway connect -s Postgres' and set PGHOST/PGPORT", file=sys.stderr)
    sys.exit(2)

jdbc_url = f"jdbc:postgresql://{host}:{port}/{db}"
print(f":: {user}@{host}:{port}/{db}", file=sys.stderr)

# Write SQL to a temp file; jshell reads it -> no quoting issues.
fd, sql_path = tempfile.mkstemp(suffix=".sql", text=True)
with os.fdopen(fd, "w", encoding="utf-8") as f:
    f.write(SQL)

jsh = f"""
import java.sql.*;
import java.nio.file.*;
String sql = Files.readString(Path.of("{sql_path.replace(os.sep, "/")}"));
try (var c = DriverManager.getConnection("{jdbc_url}", "{user}", "{passwd}");
     var st = c.createStatement();
     var rs = st.executeQuery(sql)) {{
    var md = rs.getMetaData();
    int n = md.getColumnCount();
    for (int i = 1; i <= n; i++) {{
        if (i > 1) System.out.print("\\t");
        System.out.print(md.getColumnLabel(i));
    }}
    System.out.println();
    int rows = 0;
    while (rs.next()) {{
        rows++;
        for (int i = 1; i <= n; i++) {{
            if (i > 1) System.out.print("\\t");
            var v = rs.getString(i);
            System.out.print(v == null ? "NULL" : v);
        }}
        System.out.println();
        if (rows >= 500) {{ System.out.println("... (truncated at 500 rows)"); break; }}
    }}
    System.out.println("[" + rows + " rows]");
}}
/exit
"""

with tempfile.NamedTemporaryFile("w", suffix=".jsh", delete=False, encoding="utf-8") as f:
    f.write(jsh)
    script_path = f.name

r = subprocess.run(["jshell", "--class-path", jar.replace("\\", "/"), script_path],
                   capture_output=True, text=True)
os.unlink(script_path)
os.unlink(sql_path)
if r.returncode != 0:
    err = "\n".join(l for l in (r.stderr or "").splitlines() if l.strip())
    print("JSHELL ERROR:", err[:1500], file=sys.stderr)
    sys.exit(1)
keep = []
for l in (r.stdout or "").splitlines():
    s = l.strip()
    if not s or s.startswith("jshell>") or s.startswith("import ") or s.startswith("String sql"):
        continue
    keep.append(l)
print("\n".join(keep))
