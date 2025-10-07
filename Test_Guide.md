# Aggregation System — Manual Test Guide (Human‑Readable)

This guide shows **exact commands** to run each manual test on Windows.  
Use **separate terminals** where noted (Terminal #1, #2, #3).

> **Classpath note (Windows):** The classpath separator is `;` (already correct below).

---

## 0) Pre‑requisites (run once per build)

Open a terminal at the **project root** (where the `pom.xml` lives), then:

```powershell
mvn -q -DskipTests clean package
mvn -q dependency:copy-dependencies -DincludeScope=runtime
```

---

## 1) Start the server (Terminal #1) — keep it running

```powershell
java -cp "target/classes;target/dependency/*" org.example.server.AggregationServer 4567
```

You should see the server startup logs. Leave this window open.

---

## 2) PUT tests with `ContentServer` (Terminal #2)

Open **Terminal #2** at the project root.

### ✅ 201 Created — first non‑empty PUT
```powershell
java -cp "target/classes;target/dependency/*" org.example.client.ContentServer localhost:4567 src/main/resources/weather.txt
```
**Expected:** First write returns **201 Created**. (Later writes to the same data return **200 OK**.)

### ✅ 200 OK — subsequent non‑empty PUT
Run the **same** command again:
```powershell
java -cp "target/classes;target/dependency/*" org.example.client.ContentServer localhost:4567 src/main/resources/weather.txt
```
**Expected:** **200 OK**

### ✅ 204 No Content — empty body
Create an empty file and PUT it:
```powershell
ni src/main/resources/empty.json -Force | Out-Null
java -cp "target/classes;target/dependency/*" org.example.client.ContentServer localhost:4567 src/main/resources/empty.json
```
**Expected:** **204 No Content**

### ✅ 400 Bad Request — wrong endpoint path (optional)
This only applies if your `ContentServer` accepts a full URL (with path) as arg #1:
```powershell
java -cp "target/classes;target/dependency/*" org.example.client.ContentServer http://localhost:4567/not-weather src/main/resources/weather.txt
```
**Expected:** **400 Bad Request**.  
If your client does **not** support a path here, skip this test.

### ✅ 500 Internal Server Error — bad JSON
```powershell
java -cp "target/classes;target/dependency/*" org.example.client.ContentServer localhost:4567 src/main/resources/bad.json
```
**Expected:** **500 Internal Server Error**

---

## 3) GET tests with `GetClient` (Terminal #2)

### ❌ 404 Not Found — when nothing is stored (or after TTL)
```powershell
java -cp "target/classes;target/dependency/*" org.example.client.GetClient localhost:4567 /weather.json
```
**Expected:** **404 Not Found** (if no prior successful PUT or after TTL expiry).

### ✅ 200 OK — after a successful PUT
First, ensure you’ve PUT valid data (e.g., `weather.txt` or `weatherA.json`). Then:
```powershell
java -cp "target/classes;target/dependency/*" org.example.client.GetClient localhost:4567 /weather.json
```
**Expected:** **200 OK** and a readable dump of the weather attributes.

### ❌ 400 Bad Request — wrong GET path
```powershell
java -cp "target/classes;target/dependency/*" org.example.client.GetClient localhost:4567 /nope
```
**Expected:** **400 Bad Request**

---

## 4) Lamport Clock — concurrent PUTs (Terminal #2 and #3)

With the server still running in **Terminal #1**, open **Terminal #2** and **Terminal #3**.

**Terminal #2 (PUT A):**
```powershell
java -cp "target/classes;target/dependency/*" org.example.client.ContentServer localhost:4567 src/main/resources/weatherA.json
```

**Terminal #3 (PUT B):**
Run this **immediately after** starting Terminal #2 (so they overlap):
```powershell
java -cp "target/classes;target/dependency/*" org.example.client.ContentServer localhost:4567 src/main/resources/weatherB.json
```

Now, in **either** terminal:
```powershell
java -cp "target/classes;target/dependency/*" org.example.client.GetClient localhost:4567 /weather.json
```
**Expected:** **200 OK**. The latest visible state should reflect the Lamport-ordered writes.

---

## 5) Ordering consistency (“barrier” acid test)

**Terminal #1 — Server (keep running):**
```powershell
java -cp "target/classes;target/dependency/*" org.example.server.AggregationServer 4567
```

**Terminal #2 — PUT A:**
```powershell
java -cp "target/classes;target/dependency/*" org.example.client.ContentServer localhost:4567 src/main/resources/weatherA.json
```

**Terminal #3 — GET then (slightly delayed) PUT B:**
```powershell
# GET first
java -cp "target/classes;target/dependency/*" org.example.client.GetClient localhost:4567 /weather.json

# Immediately push B (tiny delay to try to “overtake” the GET)
Start-Sleep -Milliseconds 50
java -cp "target/classes;target/dependency/*" org.example.client.ContentServer localhost:4567 src/main/resources/weatherB.json
```

**Expected:**
- The **first GET** prints **IDS60901A** (cannot “see” B yet due to ordering barrier).
- A **subsequent GET** then shows **IDS60901B**.

If the first GET ever shows **B** while B’s PUT started **after** the GET, something’s off.

---

## 6) TTL expiry test (default 30 seconds)

After a successful PUT (e.g., A), wait for the TTL to elapse, then GET:

```powershell
Start-Sleep -Seconds 31
java -cp "target/classes;target/dependency/*" org.example.client.GetClient localhost:4567 /weather.json
```
**Expected:** **404 Not Found** (data expired)

---

## Quick Reference (copy/paste)

**Build:**
```powershell
mvn -q -DskipTests clean package
mvn -q dependency:copy-dependencies -DincludeScope=runtime
```

**Run server:**
```powershell
java -cp "target/classes;target/dependency/*" org.example.server.AggregationServer 4567
```

**PUT (valid / empty / bad):**
```powershell
java -cp "target/classes;target/dependency/*" org.example.client.ContentServer localhost:4567 src/main/resources/weather.txt
java -cp "target/classes;target/dependency/*" org.example.client.ContentServer localhost:4567 src/main/resources/empty.json
java -cp "target/classes;target/dependency/*" org.example.client.ContentServer localhost:4567 src/main/resources/bad.json
```

**GET:**
```powershell
java -cp "target/classes;target/dependency/*" org.example.client.GetClient localhost:4567 /weather.json
java -cp "target/classes;target/dependency/*" org.example.client.GetClient localhost:4567 /nope
```

---

## Troubleshooting

- **`ClassNotFoundException` / cannot find main class**  
  Ensure you ran the **build** steps and that you’re in the project root.

- **Classpath issues**  
  Double‑check the quotes in `-cp "target/classes;target/dependency/*"` and that the `target/dependency` folder exists.

- **404 on GET**  
  You either never PUT valid data, or the TTL expired. PUT again, then GET.

- **Server port in use**  
  Stop any previous server instance or change the port number consistently in both server and clients.

---

Happy testing! 🧪
