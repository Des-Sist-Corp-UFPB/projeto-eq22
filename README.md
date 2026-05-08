# IWrite Backend

Backend Spring Boot do MVP 1.

## Execucao local com Docker Compose

Suba o projeto inteiro:

```bash
docker compose up -d --build
```

Servicos:

- Frontend: `http://localhost:3000`
- Backend: `http://localhost:8085`
- PostgreSQL host local: `localhost:5435`
- PostgreSQL host interno Docker: `db:5432`
- Database: `iwrite`
- User: `postgres`
- Password: `postgres`

Para parar:

```bash
docker compose down
```

## Execucao local sem Docker

Suba apenas o PostgreSQL local:

```bash
docker compose up -d db
```

Configuracao padrao:

- API: `http://localhost:8085`
- PostgreSQL host: `localhost:5435`
- Database: `iwrite`
- User: `postgres`
- Password: `postgres`

Compile o backend no Windows:

```powershell
mvnw.cmd -DskipTests compile
```

Execute o backend no Windows:

```powershell
mvnw.cmd spring-boot:run
```

Compile o backend no Linux/macOS:

```bash
./mvnw -DskipTests compile
```

Execute o backend no Linux/macOS:

```bash
./mvnw spring-boot:run
```

## Frontend local

O app Next.js fica em `web/`.

Configuracao padrao:

- Frontend: `http://localhost:3000`
- API consumida pelo frontend: `NEXT_PUBLIC_API_URL=http://localhost:8085`

Instale dependencias e rode:

```bash
cd web
npm install
npm run dev
```
