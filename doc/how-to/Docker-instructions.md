# Docker Setup Instructions

## Step 1: Start the Containers

Open PowerShell or Command Prompt in your project directory and run:

```bash
docker-compose up -d
```

This will:
- Download PostgreSQL 16 with PGVector (if not cached)
- Download Ollama (if not cached)
- Start both containers in detached mode
- Create volumes for persistent data

**Expected output:**
```
Creating compliance-postgres ... done
Creating compliance-ollama   ... done
```

Check status:
```bash
docker-compose ps
```

You should see both containers running.

---

## Step 2: Pull Ollama Models

Once containers are running, you need to download the models.

### Option A: Using Docker Exec (Recommended)

**Pull llama3.2:1b (Chat Model):**
```bash
docker exec compliance-ollama ollama pull llama3.2:1b
```

**Pull bge-m3 (Embedding Model):**
```bash
docker exec compliance-ollama ollama pull bge-m3
```

**Wait for downloads to complete.** The models are:
- llama3.2:1b: ~1.3 GB
- bge-m3: ~2.4 GB

**Verify models are installed:**
```bash
docker exec compliance-ollama ollama list
```

Expected output:
```
NAME              ID              SIZE    MODIFIED
llama3.2:1b       abc123def456    1.3 GB  5 minutes ago
bge-m3            xyz789uvw012    2.4 GB  3 minutes ago
```

### Option B: Interactive Shell (Alternative)

```bash
docker exec -it compliance-ollama /bin/sh
ollama pull llama3.2:1b
ollama pull bge-m3
exit
```

---

## Step 3: Verify PostgreSQL

**Connect to PostgreSQL:**
```bash
docker exec -it compliance-postgres psql -U postgres -d compliance_db
```

**Verify PGVector extension:**
```sql
SELECT * FROM pg_available_extensions WHERE name = 'vector';
```

**Exit:**
```sql
\q
```

---

## Step 4: Test Ollama API

**Test from host machine:**
```bash
curl http://localhost:11434/api/tags
```

**Or in PowerShell:**
```powershell
Invoke-RestMethod -Uri http://localhost:11434/api/tags
```

You should see JSON response with your models listed.

---

## Step 5: Update Spring Boot Application

Now that the database is running, remove the database exclusion:

**Open:** `src/main/java/com/insurance/compliance/ComplianceCheckerApplication.java`

**Change FROM:**
```java
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
```

---

## Useful Docker Commands

**View logs:**
```bash
docker-compose logs -f postgres
docker-compose logs -f ollama
```

**Stop containers:**
```bash
docker-compose down
```

**Stop and remove volumes (clean slate):**
```bash
docker-compose down -v
```

**Restart containers:**
```bash
docker-compose restart
```

---

## Troubleshooting

### PostgreSQL won't start
```bash
# Check logs
docker-compose logs postgres

# Common issue: port 5432 already in use
# Solution: Stop any local PostgreSQL instance or change port in docker-compose.yml
```

### Ollama models download slowly
```bash
# This is normal - models are large
# llama3.2:1b: ~1.3 GB
# bge-m3: ~2.4 GB
# Be patient, downloads can take 10-20 minutes depending on connection
```

### Can't connect to database from Spring Boot
```bash
# Verify PostgreSQL is accessible
docker exec -it compliance-postgres psql -U postgres -d compliance_db

# Check application.yml has correct connection string:
# jdbc:postgresql://localhost:5432/compliance_db
```
